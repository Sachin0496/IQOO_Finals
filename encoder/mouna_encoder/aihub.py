"""Compile and profile the encoder on a real Snapdragon phone in Qualcomm AI Hub.

The Galaxy S26 carries the same SM8850 (Snapdragon 8 Elite Gen 5) as the iQOO 15, so its numbers are the closest
we can get before the event. Needs `qai-hub configure --api_token ...` once; the token never enters this repo.

    python -m mouna_encoder.aihub [target ...]   # default: all targets; writes weights/aihub-report.json
    python -m mouna_encoder.aihub static_npu_int8 --from-quantize JOB   # reuse a finished quantize job
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")

import qai_hub as hub

WEIGHTS = Path(__file__).resolve().parents[1] / "weights"
DEVICE = "Samsung Galaxy S26"  # SM8850, as in the iQOO 15
FRAMES = 64  # 2.56 s at 25 fps: covers a ward phrase
STATIC = 48  # fixed window for the static encoder (clips are stretched to 48 frames; best Kannada accuracy)


def _summary(profile: dict) -> dict:
    s = profile["execution_summary"]
    units = {}
    for layer in profile.get("execution_detail", []):
        units[layer["compute_unit"]] = units.get(layer["compute_unit"], 0) + 1
    return {
        "median_ms": round(s["estimated_inference_time"] / 1000, 2),
        "peak_memory_mb": round(s["estimated_inference_peak_memory"] / 1e6, 1),
        "layers_by_unit": units,
    }


def calibration(n: int = 12) -> dict:
    """Real mouth-crop windows from the Kannada import, for int8 calibration (n windows, 1.7 MB each)."""
    import sys

    import numpy as np
    import torch

    from .model import stack_frames
    from .preprocess import to_encoder_input

    sys.path.insert(0, str(WEIGHTS.parents[1] / "harness"))
    from mouna_harness.io import load_dir

    clips = [c for c in load_dir(WEIGHTS.parents[1] / "data" / "kannada-lab", with_crops=True) if c.crops is not None and c.clean]
    pick = np.random.default_rng(0).choice(len(clips), n, replace=False)
    windows = [stack_frames(torch.from_numpy(to_encoder_input(clips[i].crops, clips[i].t, frames=STATIC))).numpy() for i in pick]
    return {"frames": windows}


def _random(spec: dict) -> dict:
    import numpy as np

    rng = np.random.default_rng(0)
    return {k: [rng.random(shape, dtype=np.float32)] for k, (shape, _) in spec.items()}


# name -> (onnx file, input spec, compile options, profile options)
TARGETS = {
    # whole encoder: compiles for QNN but the HTP cannot compose the GRU + Conv3D graph
    "npu_qnn_fp16": ("lip_encoder.onnx", {"v": ((1, 1, FRAMES, 88, 88), "float32")}, "--target_runtime qnn_dlc", "--compute_unit npu"),
    "gpu_tflite_fp16": ("lip_encoder.onnx", {"v": ((1, 1, FRAMES, 88, 88), "float32")}, "--target_runtime tflite", "--compute_unit gpu"),
    "cpu_tflite_fp32": ("lip_encoder.onnx", {"v": ((1, 1, FRAMES, 88, 88), "float32")}, "--target_runtime tflite", "--compute_unit cpu"),
    # split for the NPU: Conv3D rewritten as Conv2D over 5 stacked frames (exact), GRU separate
    "frontend_npu": ("frontend2d.onnx", {"frames": ((FRAMES, 5, 88, 88), "float32")}, "--target_runtime qnn_dlc", "--compute_unit npu"),
    "frontend_cpu": ("frontend2d.onnx", {"frames": ((FRAMES, 5, 88, 88), "float32")}, "--target_runtime tflite", "--compute_unit cpu"),
    "temporal_cpu": ("temporal.onnx", {"features": ((1, FRAMES, 512), "float32")}, "--target_runtime tflite", "--compute_unit cpu"),
    # whole encoder, fixed 48-frame window, GRU unrolled into matrix multiplies (no GRU op left)
    "static_npu": ("encoder_static48.onnx", {"frames": ((STATIC, 5, 88, 88), "float32")}, "--target_runtime qnn_dlc", "--compute_unit npu"),
    "static_npu_int8": ("encoder_static48.onnx", {"frames": ((STATIC, 5, 88, 88), "float32")}, "--target_runtime qnn_dlc --quantize_io", "--compute_unit npu"),
    "static_cpu": ("encoder_static48.onnx", {"frames": ((STATIC, 5, 88, 88), "float32")}, "--target_runtime tflite", "--compute_unit cpu"),
}


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")  # the hub client prints emoji; Windows consoles default to cp1252
    args = sys.argv[1:]
    resume = args[args.index("--from-quantize") + 1] if "--from-quantize" in args else None
    only = {a for a in args if a in TARGETS} or set(TARGETS)
    device = hub.Device(DEVICE)
    out = WEIGHTS / "aihub-report.json"
    report: dict = json.loads(out.read_text()) if out.exists() else {"device": DEVICE, "frames": FRAMES, "runs": {}}
    uploaded: dict[str, object] = {}
    for name, (file, spec, compile_opts, profile_opts) in TARGETS.items():
        if name not in only:
            continue
        try:
            model = None if (resume and name.endswith("_int8")) else uploaded.setdefault(file, hub.upload_model(str(WEIGHTS / file)))
            if name.endswith("_int8"):
                q = hub.get_job(resume) if resume else hub.submit_quantize_job(
                    model=model, calibration_data=calibration(), weights_dtype=hub.QuantizeDtype.INT8, activations_dtype=hub.QuantizeDtype.INT8, name=f"mouna-{name}"
                )
                model = q.get_target_model()
                if model is None:
                    report["runs"][name] = {"quantize_job": q.job_id, "error": q.get_status().message}
                    continue
                model.download(str(WEIGHTS / "encoder_static48.qnn-int8.onnx"))  # not the browser model
            compiled = hub.submit_compile_job(model=model, device=device, input_specs=spec, options=compile_opts, name=f"mouna-{name}")
            target = compiled.get_target_model()
            if target is None:
                report["runs"][name] = {"compile_job": compiled.job_id, "error": compiled.get_status().message}
                continue
            prof = hub.submit_inference_job(model=target, device=device, inputs=_random(spec), options=profile_opts, profile=True, name=f"mouna-{name}")
            status = prof.wait()
            if not status.success:
                report["runs"][name] = {"compile_job": compiled.job_id, "profile_job": prof.job_id, "error": status.message}
                continue
            report["runs"][name] = {"compile_job": compiled.job_id, "profile_job": prof.job_id, **_summary(prof.download_profile())}
        except Exception as e:  # keep the other targets going; the report says what failed
            report["runs"][name] = {"error": str(e)[:300]}
        finally:
            out.write_text(json.dumps(report, indent=2))
            (WEIGHTS.parents[1] / "deck" / "data" / "device-report.json").write_text(json.dumps(report, indent=2))
        print(name, json.dumps(report["runs"][name]))


if __name__ == "__main__":
    main()
