"""Finale app models (E1, E2): the static 48-frame encoder as LiteRT (.tflite) for the dev phone and the judged phone,
compiled, profiled and checked for parity in Qualcomm AI Hub on phones with the same chips.

    OnePlus 13R = SM8650 -> Samsung Galaxy S24 (Family)
    iQOO 15     = SM8850 -> Samsung Galaxy S26 (Family)

For every device and target: compile to .tflite, profile (median ms, layers per compute unit), then run one real
Kannada mouth window on the phone and compare the embedding with ONNX Runtime fp32 on the laptop (cosine).

    python -m mouna_encoder.litert [sm8650|sm8850 ...] [target ...]

Writes weights/litert/<chip>/<target>.tflite (gitignored), weights/litert-report.json and deck/data/device-litert.json.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")

import numpy as np
import qai_hub as hub

from .aihub import STATIC, WEIGHTS, calibration

DEVICES = {"sm8650": "Samsung Galaxy S24 (Family)", "sm8850": "Samsung Galaxy S26 (Family)"}
QUANTIZE_JOB = "jp4elxv1g"  # int8 weights + activations, calibrated on 12 real Kannada windows (aihub.py)
SOURCE_MODEL = "mmrgxgx6q"  # encoder_static48.onnx as uploaded for the S26 NPU run (compile job jg9ol3jwg)
SPEC = {"frames": ((STATIC, 5, 88, 88), "float32")}

# name -> (source, compile options, profile options)
TARGETS = {
    "fp16_npu": ("fp32", "--target_runtime tflite", "--compute_unit npu"),
    "int8_npu": ("int8", "--target_runtime tflite", "--compute_unit npu"),
    "fp16_gpu": ("fp32", "--target_runtime tflite", "--compute_unit gpu"),
    "fp32_cpu": ("fp32", "--target_runtime tflite", "--compute_unit cpu"),
    # ONNX Runtime QNN EP with the context binary precompiled for this chip (the TFLite conversion of the unrolled
    # GRU overflows a 2 GB size field; QNN DLC compiled fine for the S26 run)
    "qnn_ctx_fp16": ("fp32", "--target_runtime precompiled_qnn_onnx", "--compute_unit npu"),
    "qnn_ctx_int8": ("int8", "--target_runtime precompiled_qnn_onnx", "--compute_unit npu"),
}

OUT = WEIGHTS / "litert"
# --report NAME keeps parallel runs from overwriting each other; the deck file merges every report
REPORT_NAME = sys.argv[sys.argv.index("--report") + 1] if "--report" in sys.argv else "litert"
REPORT = WEIGHTS / f"{REPORT_NAME}-report.json"
DECK = WEIGHTS.parents[1] / "deck" / "data" / "device-litert.json"


def _merged() -> dict:
    out: dict = {"model": "encoder_static48", "devices": {}}
    for f in sorted(WEIGHTS.glob("*-report.json")):
        if f.name in ("aihub-report.json",):
            continue
        for chip, d in json.loads(f.read_text()).get("devices", {}).items():
            out["devices"].setdefault(chip, {"device": d["device"], "runs": {}})["runs"].update(d["runs"])
    return out


def _summary(profile: dict) -> dict:
    s = profile["execution_summary"]
    units: dict[str, int] = {}
    for layer in profile.get("execution_detail", []):
        units[layer["compute_unit"]] = units.get(layer["compute_unit"], 0) + 1
    return {
        "median_ms": round(s["estimated_inference_time"] / 1000, 2),
        "peak_memory_mb": round(s["estimated_inference_peak_memory"] / 1e6, 1),
        "layers_by_unit": units,
    }


def _reference() -> tuple[np.ndarray, np.ndarray]:
    """One real mouth window and its fp32 embedding from ONNX Runtime on the laptop."""
    import onnxruntime as ort

    window = calibration(1)["frames"][0].astype(np.float32)
    sess = ort.InferenceSession(str(WEIGHTS / "encoder_static48.onnx"), providers=["CPUExecutionProvider"])
    return window, sess.run(None, {"frames": window})[0].reshape(-1)


def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    a, b = a.reshape(-1).astype(np.float64), b.reshape(-1).astype(np.float64)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


def _run(chip: str, name: str, source, window: np.ndarray, ref: np.ndarray) -> dict:
    device = hub.Device(DEVICES[chip])
    _, compile_opts, profile_opts = TARGETS[name]
    row: dict = {}
    try:
        cj = hub.submit_compile_job(model=source, device=device, input_specs=SPEC, options=compile_opts, name=f"mouna-litert-{chip}-{name}")
        row["compile_job"] = cj.job_id
        target = cj.get_target_model()
        if target is None:
            row["error"] = hub.get_job(cj.job_id).get_status().message  # the cached status can be stale
            return row
        (OUT / chip).mkdir(parents=True, exist_ok=True)
        row["file"] = Path(target.download(str(OUT / chip / (f"{name}.tflite" if "tflite" in compile_opts else name)))).name
        pj = hub.submit_profile_job(model=target, device=device, options=profile_opts, name=f"mouna-litert-{chip}-{name}")
        ij = hub.submit_inference_job(model=target, device=device, inputs={"frames": [window]}, options=profile_opts, name=f"mouna-litert-{chip}-{name}-parity")
        row["profile_job"], row["inference_job"] = pj.job_id, ij.job_id
        if pj.wait().success:
            row.update(_summary(pj.download_profile()))
        else:
            row["profile_error"] = pj.get_status().message
        if ij.wait().success:
            out = next(iter(ij.download_output_data().values()))[0]
            row["parity_cosine_vs_onnx_fp32"] = round(_cosine(np.asarray(out), ref), 6)
        else:
            row["parity_error"] = ij.get_status().message
    except Exception as e:  # keep the other targets going; the report says what failed
        row["error"] = str(e)[:300]
    return row


def main() -> None:
    from concurrent.futures import ThreadPoolExecutor, as_completed

    sys.stdout.reconfigure(encoding="utf-8")
    hub.set_verbose(False)
    args = sys.argv[1:]
    chips = [c for c in args if c in DEVICES] or list(DEVICES)
    names = [t for t in args if t in TARGETS] or list(TARGETS)
    report: dict = json.loads(REPORT.read_text()) if REPORT.exists() else {"model": "encoder_static48", "devices": {}}
    window, ref = _reference()
    sources = {"fp32": hub.get_model(SOURCE_MODEL)}  # re-uploading 230 MB races the compile jobs
    if any(TARGETS[n][0] == "int8" for n in names):
        sources["int8"] = hub.get_job(QUANTIZE_JOB).get_target_model()
    with ThreadPoolExecutor(len(chips) * len(names)) as pool:
        futures = {pool.submit(_run, c, n, sources[TARGETS[n][0]], window, ref): (c, n) for c in chips for n in names}
        for f in as_completed(futures):
            chip, name = futures[f]
            report["devices"].setdefault(chip, {"device": DEVICES[chip], "runs": {}})["runs"][name] = f.result()
            REPORT.write_text(json.dumps(report, indent=2))
            DECK.write_text(json.dumps(_merged(), indent=2))
            print(chip, name, json.dumps(f.result()), flush=True)


if __name__ == "__main__":
    main()
