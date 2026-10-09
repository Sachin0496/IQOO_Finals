"""E23: half a face is enough? After a stroke one side of the mouth droops. Mirror the working half of every mouth
crop onto the other half (crops are already aligned on the eye line, mouth centred) and run the same few-shot
protocol, taught and tested on mirrored crops. Pass line (PLAN.md): within 5 top-1 points of the full face.

Healthy speakers stand in for patients here: this shows how much information one half of the mouth carries for
the encoder, not how a drooping mouth behaves.

    python -m mouna_encoder.halfface   # writes data/kannada-emb-int8-half.npz and deck/data/kannada-halfface.json
"""

from __future__ import annotations

import json
import os
import sys
from collections import defaultdict

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")

import numpy as np
import onnxruntime as ort
import torch

from .augment import ROOT, TEACH_REPS, _input
from .aihub import WEIGHTS
from .model import stack_frames


def halves(crops: np.ndarray) -> dict[str, np.ndarray]:
    w = crops.shape[2] // 2
    left, right = crops[:, :, :w], crops[:, :, w:]
    return {"image_left_half": np.concatenate([left, left[:, :, ::-1]], axis=2), "image_right_half": np.concatenate([right[:, :, ::-1], right], axis=2)}


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.path.insert(0, str(ROOT / "harness"))
    from mouna_harness.io import load_dir

    path = ROOT / "data" / "kannada-emb-int8-half.npz"
    clips = [c for c in load_dir(ROOT / "data" / "kannada-lab", with_crops=True) if c.kind == "protocol" and c.clean and c.crops is not None and len(c.crops) >= 5]
    if path.exists():
        z = np.load(path)
        emb = dict(zip(z["ids"].tolist(), z["embeddings"]))
    else:
        so = ort.SessionOptions()
        so.intra_op_num_threads = os.cpu_count() or 4
        sess = ort.InferenceSession(str(WEIGHTS / "encoder_static48.int8.onnx"), so, providers=["CPUExecutionProvider"])
        emb = {}
        for i, c in enumerate(clips):
            for name, cr in halves(c.crops).items():
                e = sess.run(None, {"frames": stack_frames(torch.from_numpy(_input(np.ascontiguousarray(cr), c.t))).numpy()})[0][0]
                emb[f"{c.id}#{name}"] = e / (np.linalg.norm(e) + 1e-9)
            if i % 200 == 0:
                print(f"  {i}/{len(clips)}", flush=True)
        np.savez(path, ids=np.array(list(emb)), embeddings=np.stack(list(emb.values())).astype(np.float32))
    z = np.load(ROOT / "data" / "kannada-emb-int8.npz")
    full = dict(zip(z["ids"].tolist(), z["embeddings"]))
    by_person: dict[str, list] = defaultdict(list)
    for c in clips:
        by_person[c.participant].append(c)
    unit = lambda v: v / (np.linalg.norm(v) + 1e-9)  # noqa: E731
    rows = {}
    for name in ("full_face", "image_left_half", "image_right_half"):
        get = (lambda c: full.get(c.id)) if name == "full_face" else (lambda c, n=name: emb.get(f"{c.id}#{n}"))
        n = top1 = top3 = 0
        for cs in by_person.values():
            protos = defaultdict(list)
            for c in cs:
                if c.rep <= TEACH_REPS and get(c) is not None:
                    protos[c.phrase].append(unit(get(c)))
            keys = sorted(protos)
            P = np.stack([unit(np.mean(protos[k], axis=0)) for k in keys])
            for c in cs:
                if c.rep > TEACH_REPS and get(c) is not None:
                    ranked = [keys[i] for i in np.argsort(-(P @ unit(get(c))))[:3]]
                    n += 1
                    top1 += ranked[0] == c.phrase
                    top3 += c.phrase in ranked
        rows[name] = {"n": n, "top1": round(top1 / n, 4), "top3": round(top3 / n, 4)}
    for r in rows.values():
        r["top1_vs_full_points"] = round(100 * (r["top1"] - rows["full_face"]["top1"]), 2)
    doc = {
        "experiment": "E23 half-face mirror (PLAN.md): pass line within 5 top-1 points of the full face",
        "source": "Kannada multi-speaker dataset, deck split (teach reps 1-3, test 4+), int8 static encoder; taught and tested on mirrored crops",
        "caveat": "healthy speakers: measures what one half of the mouth carries, not a drooping mouth",
        **rows,
    }
    (ROOT / "deck" / "data" / "kannada-halfface.json").write_text(json.dumps(doc, indent=2), encoding="utf-8")
    print(json.dumps(doc, indent=2))


if __name__ == "__main__":
    main()
