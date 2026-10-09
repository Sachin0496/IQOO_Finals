"""python -m mouna_encoder fetch | convert | bench | embed

fetch    download the Core ML package from rkmtlab/LipLearner (MIT) into encoder/weights
convert  rebuild in PyTorch, check parity against the Core ML graph, export ONNX
static   export the app's static 48-frame encoder (encoder_static48.onnx) and check it against the app's self-test clip
bench    CPU latency per window with ONNX Runtime
embed    embed the mouth crops of Lab exports, for the harness's plan A head
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")  # torch + onnxruntime both ship OpenMP on Windows

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS = ROOT / "weights"
SRC = "https://raw.githubusercontent.com/rkmtlab/LipLearner/main/LipLearner_iOS/LipLearner/LipEncoder.mlpackage/Data/com.apple.CoreML"
ONNX_FP32 = WEIGHTS / "lip_encoder.onnx"


def fetch() -> None:
    WEIGHTS.mkdir(exist_ok=True)
    for name in ("model.mlmodel", "weights/weight.bin.tar.gz"):
        dst = WEIGHTS / Path(name).name
        if not dst.exists():
            urllib.request.urlretrieve(f"{SRC}/{name}", dst)
    if not (WEIGHTS / "weight.bin").exists():
        with tarfile.open(WEIGHTS / "weight.bin.tar.gz") as t:
            t.extractall(WEIGHTS, filter="data")
    print(f"fetched into {WEIGHTS}")


def convert() -> dict:
    import torch

    from .coreml import load_program, run
    from .load import from_coreml

    model = from_coreml(WEIGHTS)
    x = np.random.default_rng(0).random((1, 1, 29, 88, 88)).astype(np.float32)
    block, consts = load_program(WEIGHTS)
    ref = np.asarray(next(iter(run(block, consts, {"v": x}).values())))
    with torch.no_grad():
        y = model(torch.from_numpy(x)).numpy()
    report = {"params_m": round(sum(p.numel() for p in model.parameters()) / 1e6, 2), "torch_vs_coreml_max_abs": float(np.abs(y - ref).max())}

    torch.onnx.export(
        model,
        (torch.from_numpy(x),),
        str(ONNX_FP32),
        input_names=["v"],
        output_names=["embedding"],
        dynamic_axes={"v": {2: "frames"}},
        opset_version=17,
        dynamo=False,
    )
    import onnxruntime as ort

    out = ort.InferenceSession(str(ONNX_FP32), providers=["CPUExecutionProvider"]).run(None, {"v": x})[0]
    report["onnx_fp32_mb"] = round(ONNX_FP32.stat().st_size / 1e6, 1)
    report["onnx_cosine_vs_torch"] = round(float((out @ y.T).item() / np.linalg.norm(out) / np.linalg.norm(y)), 5)
    (WEIGHTS / "convert-report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    return report


def static() -> dict:
    """The app's encoder: (48, 5, 88, 88) -> (1, 500), checked against android/core's self-test clip (cosine)."""
    import torch
    from .load import from_coreml
    from .model import StaticEncoder, stack_frames
    from .preprocess import to_encoder_input
    enc = StaticEncoder(from_coreml(WEIGHTS)).eval()
    st = ROOT.parent / "android/core/src/main/resources/mouna/selftest"
    meta = json.loads((st / "selftest.json").read_text())
    crops = np.frombuffer((st / "clip.u8").read_bytes(), np.uint8).reshape(meta["frames"], meta["size"], meta["size"])
    x = stack_frames(torch.from_numpy(to_encoder_input(crops, np.asarray(meta["t_ms"], np.float64), frames=48)))
    out = WEIGHTS / STATIC_MODELS["static"]
    torch.onnx.export(enc, (x,), str(out), input_names=["frames"], output_names=["embedding"], opset_version=17, dynamo=False)
    import onnxruntime as ort
    y = ort.InferenceSession(str(out), providers=["CPUExecutionProvider"]).run(None, {"frames": x.numpy()})[0][0]
    e = np.asarray(meta["embedding_fp32"], np.float32)
    report = {"onnx_mb": round(out.stat().st_size / 1e6, 1), "selftest_cosine": round(float(y @ e / np.linalg.norm(y) / np.linalg.norm(e)), 6)}
    print(json.dumps(report, indent=2))
    return report


def bench(frames: list[int], runs: int = 10) -> None:
    import onnxruntime as ort

    rows = []
    sess = ort.InferenceSession(str(ONNX_FP32), providers=["CPUExecutionProvider"])
    for t in frames:
        x = np.random.default_rng(t).random((1, 1, t, 88, 88)).astype(np.float32)
        sess.run(None, {"v": x})
        ms = []
        for _ in range(runs):
            t0 = time.perf_counter()
            sess.run(None, {"v": x})
            ms.append((time.perf_counter() - t0) * 1000)
        rows.append({"frames": t, "seconds": round(t / 25, 2), "median_ms": round(float(np.median(ms)), 1)})
    print(json.dumps(rows, indent=2))
    (WEIGHTS / "bench-cpu.json").write_text(json.dumps({"host": "laptop CPU (not the phone)", "rows": rows}, indent=2))


STATIC_MODELS = {"static": "encoder_static48.onnx", "static-int8": "encoder_static48.int8.onnx"}


def embed(data: Path, out: Path, frames: int | None = None, model: str = "dynamic") -> None:
    import onnxruntime as ort

    sys.path.insert(0, str(ROOT.parent / "harness"))
    from mouna_harness.io import load_dir

    from .preprocess import to_encoder_input

    static = model in STATIC_MODELS
    sess = ort.InferenceSession(str(WEIGHTS / STATIC_MODELS[model] if static else ONNX_FP32), providers=["CPUExecutionProvider"])
    if static:
        import torch

        from .model import stack_frames

        frames = 48
    ids, embs = [], []
    for clip in load_dir(data, with_crops=True):
        if clip.crops is None or len(clip.crops) < 5:
            continue
        v = to_encoder_input(clip.crops, clip.t, frames=frames)
        feed = {"frames": stack_frames(torch.from_numpy(v)).numpy()} if static else {"v": v}
        e = sess.run(None, feed)[0][0]
        ids.append(clip.id)
        embs.append(e / (np.linalg.norm(e) + 1e-9))
    np.savez(out, ids=np.array(ids), embeddings=np.stack(embs).astype(np.float32))
    print(f"{len(ids)} clips -> {out}")


def main() -> None:
    ap = argparse.ArgumentParser(prog="mouna_encoder")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fetch")
    sub.add_parser("convert")
    sub.add_parser("static")
    b = sub.add_parser("bench")
    b.add_argument("--frames", type=int, nargs="+", default=[25, 50, 68])
    e = sub.add_parser("embed")
    e.add_argument("data", type=Path)
    e.add_argument("--out", type=Path, default=Path("data/embeddings.npz"))
    e.add_argument("--frames", type=int, help="stretch every clip to this many frames (static shape)")
    e.add_argument("--model", choices=["dynamic", *STATIC_MODELS], default="dynamic", help="static models use 48 frames")
    ex = sub.add_parser("avsr-export", help="free talk: Auto-AVSR ONNX per bucket + phone files (docs/open-vocab-plan.md)")
    ex.add_argument("--lora", type=Path, help="fold a person's adapter (mouna_encoder.adapt) into the weights first")
    ex.add_argument("--out", type=Path, help="output folder (default encoder/weights/avsr)")
    r = sub.add_parser("avsr-read", help="free talk: read a video (joint CTC/attention and CTC beam)")
    r.add_argument("video", type=Path, nargs="+")
    sub.add_parser("avsr-vectors", help="free talk: harness/vectors/freetalk.json for android/core")
    a = ap.parse_args()
    if a.cmd.startswith("avsr"):
        from . import avsr

        if a.cmd == "avsr-export":
            out = a.out or avsr.OUT
            if a.lora:
                from .adapt import merged

                m = merged(a.lora)
            else:
                m = avsr.load()
            for t in avsr.BUCKETS:
                avsr.export(t, m, out)
                avsr.export_decoder(t, m, out)
                avsr.export_scorer(t, m, out)
            avsr.phone_files(m, out)
        elif a.cmd == "avsr-read":
            avsr.read_cli(a.video)
        else:
            avsr.vectors(ROOT.parent / "harness" / "vectors" / "freetalk.json")
        return
    if a.cmd == "fetch":
        fetch()
    elif a.cmd == "convert":
        convert()
    elif a.cmd == "static":
        static()
    elif a.cmd == "bench":
        bench(a.frames)
    else:
        embed(a.data, a.out, a.frames, a.model)


if __name__ == "__main__":
    main()
