"""FAR/FRR curve and equal-error rate for Mouna's open-set rejection (Kannada), following arXiv:2306.02161.

    python -m mouna_harness far-frr data/kannada-lab --embeddings data/kannada-emb-int8.npz --out deck/data/far-frr.json

Sweeps the speak threshold q of core.decide (finale.speak_core, with the shipped margin and negative ratio). Per q:
FAR = untaught words spoken, FRR = taught words not spoken correctly, and taught words spoken as a different word.
The equal-error rate is where FAR and FRR meet. Per-speaker EER is reported beside the pooled one, because speaker
variance is the main error source.

The split is the one in finale.py, reused rather than copied: 8 taught classes, 10 candidate negatives (the first 0, 5
or 10 are used), 10 untaught words. Teaching uses reps 1-3; taught test clips are reps 6 and later, untaught test
clips are every clip.
"""

from __future__ import annotations

import numpy as np

from . import core, finale
from .io import Clip

PROTOCOL = (
    "FAR and FRR over a sweep of the speak threshold q, equal-error rate by linear interpolation between the two q "
    "values where FAR - FRR changes sign. Follows arXiv:2306.02161 (few-shot open-set learning for on-device KWS "
    "customization). Speaker split, teaching and test clips: finale.py."
)


def _share(hit: np.ndarray, base: np.ndarray) -> float | None:
    n = int(base.sum())
    return round(float(hit.sum()) / n, 4) if n else None


def _row(a: dict, q: float, m: float, rho: float) -> dict:
    speak = finale.speak_core(a, q, m, rho)
    known, unknown = a["kind"] == 0, a["kind"] == 1
    right = speak & known & (a["tr"] == 0)
    return {
        "q": round(float(q), 4),
        "far": _share(speak & unknown, unknown),
        "frr": None if not known.any() else round(1 - float(right.sum()) / int(known.sum()), 4),
        "taught_spoken_wrong": _share(speak & known & (a["tr"] != 0), known),
    }


def _subset(a: dict, m: np.ndarray) -> dict:
    return {k: np.asarray(v)[m] for k, v in a.items()}


def curve(a: dict, m: float = core.MARGIN, rho: float = core.NEG_RATIO, qs=None) -> list[dict]:
    qs = np.round(np.arange(0.5, 3.01, 0.05), 2) if qs is None else qs
    return [_row(a, q, m, rho) for q in qs]


def eer(rows: list[dict]) -> dict:
    """Equal-error rate. Rows with a None rate are skipped. Without a sign change, the row where |FAR - FRR| is
    smallest is returned, with eer = its mean of FAR and FRR and interpolated False."""
    ok = [r for r in rows if r["far"] is not None and r["frr"] is not None]
    if not ok:
        return {"eer": None, "q": None, "interpolated": False}
    d = np.array([r["far"] - r["frr"] for r in ok])
    for i in range(len(ok) - 1):
        if d[i] * d[i + 1] <= 0:
            t = 0.0 if d[i] == d[i + 1] else d[i] / (d[i] - d[i + 1])
            lo, hi = ok[i], ok[i + 1]
            return {
                "eer": round(lo["far"] + t * (hi["far"] - lo["far"]), 4),
                "q": round(lo["q"] + t * (hi["q"] - lo["q"]), 4),
                "interpolated": True,
            }
    j = int(np.argmin(np.abs(d)))
    return {"eer": round((ok[j]["far"] + ok[j]["frr"]) / 2, 4), "q": ok[j]["q"], "interpolated": False}


def operating_point(a: dict, q: float = core.Q_SPEAK["A"]) -> dict:
    """FAR and FRR at one threshold, with the margin and negative ratio shipped in core.py."""
    return _row(a, q, core.MARGIN, core.NEG_RATIO)


def run(clips: list[Clip], emb: dict[str, np.ndarray]) -> dict:
    p = finale.pools(clips, emb)
    doc: dict = {
        "source": "Kannada multi-speaker dataset (voiced, 7 speakers, 28 classes), int8 static encoder embeddings",
        "protocol": PROTOCOL,
        "split": finale.__doc__.split("Split, per speaker")[1].split("Every number")[0].strip().replace("\n", " "),
        "speak_constants": {"Q_SPEAK_A": core.Q_SPEAK["A"], "MARGIN": core.MARGIN, "NEG_RATIO": core.NEG_RATIO},
        "participants": sorted(p),
    }
    for n in (0, 5, 10):
        a, info = finale.collect(p, n, active=False)
        rows = curve(a)
        per = [eer(curve(_subset(a, a["sp"] == s)))["eer"] for s in np.unique(a["sp"])]
        vals = [e for e in per if e is not None]
        doc[f"negatives_{n}"] = {
            "examples_per_pack": info["mean_examples"],
            "curve": rows,
            "eer": eer(rows),
            "operating_point": operating_point(a),
            "per_speaker_eer": {
                "values": per,
                "min": round(min(vals), 4) if vals else None,
                "median": round(float(np.median(vals)), 4) if vals else None,
                "max": round(max(vals), 4) if vals else None,
            },
        }
    return doc
