"""Few-shot heads. ``DTWHead`` mirrors lab/src/core (sequence.ts, dtw.ts, recognizer.ts) exactly;
``PrototypeHead`` is the plan A head for fixed-size encoder embeddings."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

TARGET_FPS = 25


# ---------- sequence preparation (sequence.ts) ----------


def resample(t: np.ndarray, frames: np.ndarray, fps: int = TARGET_FPS) -> np.ndarray:
    if len(frames) < 2:
        return frames.copy()
    grid = np.arange(t[0], t[-1] + 1e-9, 1000.0 / fps)
    return np.stack([np.interp(grid, t, frames[:, d]) for d in range(frames.shape[1])], axis=1).astype(np.float32)


VELOCITY_WEIGHT = 6.0  # Kannada: 71.0% -> 75.3% top-1; weights 6-10 plateau at 75.3-75.7%


def with_velocity(seq: np.ndarray, weight: float = VELOCITY_WEIGHT) -> np.ndarray:
    """Append frame-to-frame lip velocity: how the mouth moves, not only where it is."""
    if len(seq) == 0:
        return seq
    d = np.vstack([np.zeros((1, seq.shape[1]), np.float32), np.diff(seq, axis=0)])
    return np.hstack([seq, weight * d]).astype(np.float32)


def to_sequence(t: np.ndarray, frames: np.ndarray) -> np.ndarray:
    r = resample(t, frames)
    return with_velocity(r - r.mean(axis=0, keepdims=True))


# ---------- DTW (dtw.ts) ----------


def dtw(a: np.ndarray, b: np.ndarray, band_fraction: float = 0.25) -> float:
    n, m = len(a), len(b)
    if n == 0 or m == 0:
        return float("inf")
    band = max(abs(n - m), int(np.ceil(band_fraction * max(n, m))))
    cost = np.sqrt(((a[:, None, :] - b[None, :, :]) ** 2).sum(-1))
    prev = np.full(m + 1, np.inf)
    prev[0] = 0.0
    for i in range(1, n + 1):
        cur = np.full(m + 1, np.inf)
        lo, hi = max(1, i - band), min(m, i + band)
        row = cost[i - 1]
        for j in range(lo, hi + 1):
            cur[j] = row[j - 1] + min(prev[j], cur[j - 1], prev[j - 1])
        prev = cur
    return float(prev[m] / (n + m))


# ---------- decisions (recognizer.ts) ----------


@dataclass
class Decision:
    kind: str  # accept | unsure | reject
    ranked: list[tuple[str, float]]  # (phrase, distance), best first
    margin: float
    threshold: float


MIN_MARGIN = 1.15
UNSURE_FACTOR = 1.6
DEFAULT_THRESHOLD = 0.35


def _decide(ranked: list[tuple[str, float]], threshold: float) -> Decision:
    if not ranked or not np.isfinite(ranked[0][1]):
        return Decision("reject", ranked, 0.0, threshold)
    best = ranked[0][1]
    second = ranked[1][1] if len(ranked) > 1 else float("inf")
    margin = second / max(best, 1e-9) if np.isfinite(second) else float("inf")
    if best <= threshold and margin >= MIN_MARGIN:
        kind = "accept"
    elif best <= threshold * UNSURE_FACTOR:
        kind = "unsure"
    else:
        kind = "reject"
    return Decision(kind, ranked, margin, threshold)


class DTWHead:
    """Plan B: lip-geometry sequences, per-phrase distance = mean of the two closest templates."""

    name = "plan-b-dtw"

    def __init__(self) -> None:
        self.templates: dict[str, list[np.ndarray]] = {}
        self.threshold = DEFAULT_THRESHOLD

    def add(self, phrase: str, seq: np.ndarray) -> None:
        self.templates.setdefault(phrase, []).append(seq)

    def _phrase_distance(self, seq: np.ndarray, ts: list[np.ndarray], skip: np.ndarray | None) -> float:
        d = sorted(dtw(seq, t) for t in ts if t is not skip)
        if not d:
            return float("inf")
        return d[0] if len(d) == 1 else (d[0] + d[1]) / 2

    def rank(self, seq: np.ndarray, skip: np.ndarray | None = None) -> list[tuple[str, float]]:
        rows = [(p, self._phrase_distance(seq, ts, skip)) for p, ts in self.templates.items()]
        return sorted(rows, key=lambda r: r[1])

    def calibrate(self) -> float:
        inside, outside = [], []
        for phrase, ts in self.templates.items():
            if len(ts) < 2:
                continue
            for t in ts:
                r = self.rank(t, skip=t)
                own = next((d for p, d in r if p == phrase), np.inf)
                other = next((d for p, d in r if p != phrase), np.inf)
                if np.isfinite(own):
                    inside.append(own)
                if np.isfinite(other):
                    outside.append(other)
        if len(inside) >= 3:
            p90 = _percentile(inside, 0.9)
            mid = (p90 + _percentile(outside, 0.5)) / 2 if outside else np.inf
            self.threshold = float(min(p90 * 1.25, mid))
        return self.threshold

    def classify(self, seq: np.ndarray) -> Decision:
        return _decide(self.rank(seq), self.threshold)


class PrototypeHead:
    """Plan A: mean embedding per phrase, cosine distance. Threshold calibrated the same way."""

    name = "plan-a-prototype"

    def __init__(self) -> None:
        self.samples: dict[str, list[np.ndarray]] = {}
        self.threshold = 0.5

    def add(self, phrase: str, emb: np.ndarray) -> None:
        self.samples.setdefault(phrase, []).append(emb / (np.linalg.norm(emb) + 1e-9))

    def _protos(self, skip_phrase: str | None = None, skip_idx: int = -1) -> dict[str, np.ndarray]:
        out = {}
        for p, xs in self.samples.items():
            keep = [x for i, x in enumerate(xs) if not (p == skip_phrase and i == skip_idx)]
            if keep:
                v = np.mean(keep, axis=0)
                out[p] = v / (np.linalg.norm(v) + 1e-9)
        return out

    @staticmethod
    def _rank(emb: np.ndarray, protos: dict[str, np.ndarray]) -> list[tuple[str, float]]:
        e = emb / (np.linalg.norm(emb) + 1e-9)
        return sorted(((p, float(1 - e @ v)) for p, v in protos.items()), key=lambda r: r[1])

    def calibrate(self) -> float:
        inside, outside = [], []
        for p, xs in self.samples.items():
            if len(xs) < 2:
                continue
            for i, x in enumerate(xs):
                r = self._rank(x, self._protos(p, i))
                inside.append(next(d for q, d in r if q == p))
                outside.extend(d for q, d in r[:2] if q != p)
        if len(inside) >= 3:
            p90 = _percentile(inside, 0.9)
            mid = (p90 + _percentile(outside, 0.5)) / 2 if outside else np.inf
            self.threshold = float(min(p90 * 1.25, mid))
        return self.threshold

    def classify(self, emb: np.ndarray) -> Decision:
        return _decide(self._rank(emb, self._protos()), self.threshold)


def _percentile(xs: list[float], p: float) -> float:
    s = sorted(xs)
    k = min(len(s) - 1, max(0, int(p * (len(s) - 1) + 0.5)))  # Math.round, as in recognizer.ts
    return float(s[k])
