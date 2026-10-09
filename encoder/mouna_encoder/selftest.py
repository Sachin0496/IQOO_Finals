"""Self-test assets for the app (finale-plan §6) and the E2 parity check: one real mouth clip, its encoder input and
its embedding, so the phone can verify crop -> tensor -> NPU at start-up and fall back if anything disagrees.

    python -m mouna_encoder.selftest   # writes android/core/src/main/resources/mouna/selftest/{clip.u8, selftest.json}

clip.u8: the clip's 96x96 grey crops, frame after frame (uint8). selftest.json: timestamps, the expected embedding
(fp32 and int8 ONNX on the laptop), and 2,000 sampled values of the (48, 5, 88, 88) encoder input with their
flat indices, which the Kotlin preprocessing (Preprocess.kt) must reproduce.
Clip from the Kannada multi-speaker lip-reading dataset (Divya P, 2026, CC BY 4.0, doi:10.17632/zbzrbs89pz.1).
"""

from __future__ import annotations

import json
import os
import sys

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")

import numpy as np
import onnxruntime as ort
import torch

from .aihub import STATIC, WEIGHTS
from .model import stack_frames
from .preprocess import to_encoder_input

ROOT = WEIGHTS.parents[1]
OUT = ROOT / "android" / "core" / "src" / "main" / "resources" / "mouna" / "selftest"


def main() -> None:
    sys.path.insert(0, str(ROOT / "harness"))
    from mouna_harness.io import load_export

    clips = load_export(ROOT / "data" / "kannada-lab" / "k01_s1.mouna.json", with_crops=True)
    c = next(c for c in clips if c.kind == "protocol" and c.clean and c.phrase == "neeru" and c.crops is not None)
    x = stack_frames(torch.from_numpy(to_encoder_input(c.crops, c.t, frames=STATIC))).numpy().astype(np.float32)
    emb = {}
    for name, f in (("fp32", "encoder_static48.onnx"), ("int8", "encoder_static48.int8.onnx")):
        e = ort.InferenceSession(str(WEIGHTS / f), providers=["CPUExecutionProvider"]).run(None, {"frames": x})[0][0]
        emb[name] = [float(v) for v in e]
    rng = np.random.default_rng(0)
    idx = np.sort(rng.choice(x.size, 2000, replace=False))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "clip.u8").write_bytes(np.ascontiguousarray(c.crops, dtype=np.uint8).tobytes())
    doc = {
        "source": "Kannada multi-speaker lip-reading dataset (CC BY 4.0), speaker k01, 'neeru' (water)",
        "frames": int(c.crops.shape[0]),
        "size": int(c.crops.shape[1]),
        "t_ms": [float(v) for v in c.t],
        "input_shape": list(x.shape),
        "input_sample_index": [int(i) for i in idx],
        "input_sample_value": [float(v) for v in x.reshape(-1)[idx]],
        "input_mean": float(x.mean()),
        "embedding_fp32": emb["fp32"],
        "embedding_int8": emb["int8"],
        "pass_cosine": 0.99,
    }
    (OUT / "selftest.json").write_text(json.dumps(doc), encoding="utf-8")
    a, b = np.array(emb["fp32"]), np.array(emb["int8"])
    print(f"{c.crops.shape[0]} frames; fp32 vs int8 cosine {a @ b / np.linalg.norm(a) / np.linalg.norm(b):.6f}; -> {OUT}")


if __name__ == "__main__":
    main()
