"""Parity vectors for ports of core.py (Kotlin: android/core CoreParityTest). Random clusters in 16-D: these test
the logic, they are not results.

    python -m mouna_harness vectors [--out harness/vectors/core.json]
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

from . import core

DIM = 16
WIDE_Q_SET = 9.0


def _f32(x: np.ndarray) -> list[float]:
    return [float(v) for v in np.asarray(x, dtype=np.float32)]


def _num(x: float):
    return x if math.isfinite(x) else ("inf" if x > 0 else "-inf")


def _ranked(r: core.Ranked) -> dict:
    return {"intents": r.intents, "scores": r.scores, "distances": r.distances, "negative": _num(r.negative)}


def _decision(d: core.Decision) -> dict:
    return {"kind": d.kind, "options": d.options, "why": d.why, "maybe_none": d.maybe_none}


def build(seed: int = 3) -> dict:
    rng = np.random.default_rng(seed)
    names = ["water", "pain", "nurse", "toilet", "fan_off", "thanks"]
    centers = {k: rng.normal(size=DIM) for k in names}
    centers["toilet"] = centers["water"] + rng.normal(scale=0.35, size=DIM)  # a pair that looks alike
    draw = lambda k, s=0.45: (centers[k] + rng.normal(scale=s, size=DIM)).astype(np.float32)  # noqa: E731

    L = core.Learner()
    samples = []
    for k, n in zip(names, (3, 3, 2, 4, 1, 3)):
        for _ in range(n):
            x = draw(k)
            L.add_sample(k, x)
            samples.append({"intent": k, "x": _f32(x)})
    negatives = []
    for _ in range(3):
        x = rng.normal(size=DIM).astype(np.float32)
        L.add_negative(x)
        negatives.append(_f32(x))

    tiers = {"pain": "B", "fan_off": "C"}
    prior = {"water": 0.5, "toilet": 0.3, "pain": 0.1, "nurse": 0.1}
    queries = []
    sources = [(k, 0.45) for k in names] * 4 + [(k, 0.9) for k in names] * 2 + [(None, 0)] * 6
    sources += [("mix", 0)] * 8  # between three intents: sets of 3+ (choose, ask)
    for k, s in sources:
        if k == "mix":
            a, b, c = rng.choice(names, 3, replace=False)
            x = ((centers[a] + centers[b] + centers[c]) / 3 + rng.normal(scale=0.1, size=DIM)).astype(np.float32)
        else:
            x = draw(k, s) if k else rng.normal(size=DIM).astype(np.float32)
        r = L.predict(x)
        queries.append(
            {
                "x": _f32(x),
                "ranked": _ranked(r),
                "default": _decision(core.decide(r)),
                "careful": _decision(core.decide(r, careful=True)),
                "tiers": _decision(core.decide(r, tiers=tiers)),
                "prior": _decision(core.decide(r, prior=prior)),
                "wide": _decision(core.decide(r, q_set=WIDE_Q_SET)),  # wider sets: choose, ask, context order
                "wide_prior": _decision(core.decide(r, prior=prior, q_set=WIDE_Q_SET)),
            }
        )

    # an active teaching session: requests and checks, replayed step by step
    A = core.Learner()
    steps = []
    pack = names[:5]
    while (req := A.next_to_teach(pack, min_shots=2)) is not None and len(steps) < 40:
        x = draw(req.intent, 0.6)
        ok = A.teach(req.intent, x)
        steps.append({"intent": req.intent, "reason": req.reason, "x": _f32(x), "check": ok})
    return {
        "dim": DIM,
        "constants": {k: getattr(core, k) for k in ("PRIOR_TAU", "Q_SET", "Q_SPEAK", "MARGIN", "NEG_RATIO", "CAREFUL", "RED_PAIR", "YELLOW_PAIR")},
        "samples": samples,
        "negatives": negatives,
        "tau": L.tau(),
        "separability": [{"a": p.a, "b": p.b, "gap": p.gap, "level": p.level} for p in L.separability()],
        "tiers": tiers,
        "wide_q_set": WIDE_Q_SET,
        "prior": prior,
        "queries": queries,
        "active": {"pack": pack, "min_shots": 2, "steps": steps},
    }


def write(out: Path) -> Path:
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(build(), indent=1), encoding="utf-8")
    return out
