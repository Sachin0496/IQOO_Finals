"""Synthetic Lab exports, for tests and for exercising the pipeline before real data exists.
Every file it writes is marked ``"synthetic": true`` and the deck refuses to print its numbers."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

PHRASES = ["water", "pain", "nurse", "breathe", "toilet", "medicine", "sit_up", "thank_you"]
DIM = 80


def _phrase_curve(rng: np.random.Generator, frames: int) -> np.ndarray:
    """A smooth random trajectory in feature space standing in for one phrase's lip motion."""
    knots = rng.normal(0, 0.08, size=(6, DIM))
    x = np.linspace(0, 5, frames)
    return np.stack([np.interp(x, np.arange(6), knots[:, d]) for d in range(DIM)], axis=1)


def write_synthetic(out: Path, participants: int = 3, noise: float = 0.012, drift: float = 0.01, seed: int = 7) -> list[Path]:
    rng = np.random.default_rng(seed)
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for p in range(1, participants + 1):
        shapes = {ph: _phrase_curve(rng, 200) for ph in PHRASES}
        for session, extra in (("s1", 0.0), ("s2", drift)):
            clips = []
            offset = rng.normal(0, extra, size=DIM)
            for rep in range(1, 6):
                for ph in PHRASES:
                    frames = int(rng.integers(55, 80))
                    idx = np.linspace(0, 199, frames).astype(int)
                    f = shapes[ph][idx] + offset + rng.normal(0, noise, size=(frames, DIM))
                    clips.append(_clip(f"{p}{session}{ph}{rep}", "protocol", ph, rep, f))
            for k in range(1, 4):
                f = rng.normal(0, 0.03, size=(int(rng.integers(20, 40)), DIM))
                clips.append(_clip(f"{p}{session}idle{k}", "idle", None, k, f))
            span = _clip(f"{p}{session}span", "idle_span", None, 0, np.zeros((0, DIM)))
            span["stats"]["durationMs"] = 600_000
            clips.append(span)
            doc = {
                "format": "mouna-lab/1",
                "synthetic": True,
                "participant": f"p{p}",
                "session": session,
                "featureDim": DIM,
                "crop": None,
                "clips": clips,
            }
            path = out / f"p{p}_{session}.mouna.json"
            path.write_text(json.dumps(doc), encoding="utf-8")
            written.append(path)
    return written


def _clip(cid: str, kind: str, phrase: str | None, rep: int, f: np.ndarray) -> dict:
    t = (np.arange(len(f)) * 1000 / 30).round(1)
    return {
        "id": cid,
        "kind": kind,
        "phrase": phrase,
        "rep": rep,
        "spokenLang": "en",
        "t": t.tolist(),
        "features": np.round(f, 4).tolist(),
        "stats": {"durationMs": float(t[-1]) if len(t) else 0.0},
        "issues": [],
        "cropOffset": None,
        "cropFrames": 0,
    }
