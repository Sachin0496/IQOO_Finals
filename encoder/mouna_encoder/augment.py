"""E3: does augmenting each taught example (no retraining) raise few-shot accuracy?

Each teaching clip is also embedded as: mirrored, crop shifted 4 px, zoomed 6%, and trimmed 5% at both ends; the
phrase prototype is the mean of the original and its variants. Same split as the deck (teach reps 1-3, or rep 1 for
the 1-shot row; test reps 4 and later, all 28 classes per speaker), int8 static encoder.

    python -m mouna_encoder.augment   # writes data/kannada-emb-int8-aug.npz and deck/data/kannada-augment.json
"""

from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from pathlib import Path

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")

import numpy as np
import onnxruntime as ort
import torch
import torch.nn.functional as F

from .aihub import WEIGHTS
from .model import stack_frames
from .preprocess import INPUT, LRW_ROI

ROOT = WEIGHTS.parents[1]
FRAMES = 48
TEACH_REPS = 3


def _input(crops: np.ndarray, t: np.ndarray, roi: int = LRW_ROI, dx: int = 0, dy: int = 0) -> np.ndarray:
    grid = np.linspace(t[0], t[-1], FRAMES)
    idx = np.clip(np.searchsorted(t, grid), 0, len(t) - 1)
    x = torch.from_numpy(crops[idx].astype(np.float32) / 255.0)[:, None]
    x = F.interpolate(x, size=(roi, roi), mode="bilinear", align_corners=False)
    o = (roi - INPUT) // 2
    x = x[:, :, o + dy : o + dy + INPUT, o + dx : o + dx + INPUT]
    return x.permute(1, 0, 2, 3)[None].numpy().astype(np.float32)


def variants(crops: np.ndarray, t: np.ndarray) -> dict[str, np.ndarray]:
    n = len(t)
    k = max(1, int(0.05 * n))
    return {
        "mirror": _input(crops[:, :, ::-1].copy(), t),
        "shift": _input(crops, t, dx=4, dy=4),
        "zoom": _input(crops, t, roi=136),
        "trim": _input(crops[k : n - k], t[k : n - k]) if n - 2 * k >= 5 else _input(crops, t),
    }


def embed_variants() -> dict[str, np.ndarray]:
    sys.path.insert(0, str(ROOT / "harness"))
    from mouna_harness.io import load_dir

    out_path = ROOT / "data" / "kannada-emb-int8-aug.npz"
    if out_path.exists():
        z = np.load(out_path)
        return dict(zip(z["ids"].tolist(), z["embeddings"]))
    so = ort.SessionOptions()
    so.intra_op_num_threads = os.cpu_count() or 4
    sess = ort.InferenceSession(str(WEIGHTS / "encoder_static48.int8.onnx"), so, providers=["CPUExecutionProvider"])
    ids, embs = [], []
    clips = [c for c in load_dir(ROOT / "data" / "kannada-lab", with_crops=True) if c.kind == "protocol" and c.clean and c.rep <= TEACH_REPS and c.crops is not None and len(c.crops) >= 5]
    for i, c in enumerate(clips):
        for name, v in variants(c.crops, c.t).items():
            e = sess.run(None, {"frames": stack_frames(torch.from_numpy(v)).numpy()})[0][0]
            ids.append(f"{c.id}#{name}")
            embs.append(e / (np.linalg.norm(e) + 1e-9))
        if i % 100 == 0:
            print(f"  {i}/{len(clips)}", flush=True)
    np.savez(out_path, ids=np.array(ids), embeddings=np.stack(embs).astype(np.float32))
    return dict(zip(ids, embs))


def evaluate(base: dict[str, np.ndarray], aug: dict[str, np.ndarray]) -> dict:
    sys.path.insert(0, str(ROOT / "harness"))
    from mouna_harness.io import load_dir

    clips = [c for c in load_dir(ROOT / "data" / "kannada-lab") if c.kind == "protocol" and c.clean and base.get(c.id) is not None]
    by_person: dict[str, list] = defaultdict(list)
    for c in clips:
        by_person[c.participant].append(c)
    unit = lambda v: v / (np.linalg.norm(v) + 1e-9)  # noqa: E731
    configs = {"none": [], "mirror": ["mirror"], "shift": ["shift"], "zoom": ["zoom"], "trim": ["trim"], "all_four": ["mirror", "shift", "zoom", "trim"]}
    out = {}
    for shots in (1, 3):
        rows = {}
        for name, augs in configs.items():
            n = top1 = top3 = 0
            for cs in by_person.values():
                protos = defaultdict(list)
                for c in cs:
                    if c.rep <= shots:
                        protos[c.phrase].append(unit(base[c.id]))
                        protos[c.phrase].extend(unit(aug[f"{c.id}#{a}"]) for a in augs if f"{c.id}#{a}" in aug)
                keys = sorted(protos)
                P = np.stack([unit(np.mean(protos[k], axis=0)) for k in keys])
                for c in cs:
                    if c.rep > TEACH_REPS:
                        order = np.argsort(-(P @ unit(base[c.id])))
                        ranked = [keys[i] for i in order[:3]]
                        n += 1
                        top1 += ranked[0] == c.phrase
                        top3 += c.phrase in ranked
            rows[name] = {"n": n, "top1": round(top1 / n, 4), "top3": round(top3 / n, 4)}
        base_top1 = rows["none"]["top1"]
        for r in rows.values():
            r["top1_gain_points"] = round(100 * (r["top1"] - base_top1), 2)
        out[f"{shots}_shot"] = rows
    return out


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    z = np.load(ROOT / "data" / "kannada-emb-int8.npz")
    base = dict(zip(z["ids"].tolist(), z["embeddings"]))
    doc = {
        "experiment": "E3 shot augmentation (PLAN.md): pass line +1.5 top-1 points at 3 shots",
        "source": "Kannada multi-speaker dataset, deck split (teach reps 1-3, test reps 4+), int8 static encoder, all 28 classes",
        "variants": "mirror; crop shifted 4 px; zoom 6% (ROI 136); 5% trimmed at both ends",
        **evaluate(base, embed_variants()),
    }
    (ROOT / "deck" / "data" / "kannada-augment.json").write_text(json.dumps(doc, indent=2), encoding="utf-8")
    print(json.dumps(doc, indent=2))


if __name__ == "__main__":
    main()
