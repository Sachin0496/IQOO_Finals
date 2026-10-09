"""Finale core experiments on the Kannada dataset (PLAN.md §6): E16 set-valued fallback, E17 negatives, E19 rescue,
E15 active teaching, plus the calibration of the separability colours.

    python -m mouna_harness finale-core data/kannada-lab --embeddings data/kannada-emb-int8.npz --out deck/data/finale-core.json

Split, per speaker and per draw (20 draws): 8 taught classes; 10 other classes are candidates for "none of my
phrases" examples (one mouthing each, the first 0, 5 or 10 of them are used); the remaining 10 classes are the
untaught words, never seen in any form. Fixed teaching uses reps 1-3; active teaching may use reps 1-5; every
condition is tested on the same clips (taught classes: reps 6 and later; untaught: every clip).

Every number is held out by speaker: the decision constants are chosen on six speakers and scored on the seventh,
for each of the seven. The constants shipped in core.py are then chosen on all seven.
"""

from __future__ import annotations

import random
from collections import defaultdict
from itertools import product

import numpy as np

from . import core
from .heads import PrototypeHead
from .io import Clip

VOCAB, CANDIDATES, DRAWS, SEED = 8, 10, 20, 7
FIXED_SHOTS, POOL, TEST_FROM = 3, 5, 5  # rep indices are 0-based here: teach [0, 3), active pool [0, 5), test [5, ...)
UNTAUGHT_TARGET = 0.05  # untaught words spoken by mistake, at most (operating point for every family)
COVERAGE_TARGET = 0.90  # truth inside the prediction set


def pools(clips: list[Clip], emb: dict[str, np.ndarray]) -> dict[str, dict[str, list[np.ndarray]]]:
    out: dict[str, dict[str, list[np.ndarray]]] = defaultdict(lambda: defaultdict(list))
    for c in sorted(clips, key=lambda c: (c.participant, c.phrase or "", c.rep)):
        if c.kind == "protocol" and c.clean and c.phrase and emb.get(c.id) is not None:
            out[c.participant][c.phrase].append(emb[c.id])
    return out


def splits(cls: dict[str, list[np.ndarray]], rng: random.Random):
    eligible = sorted(k for k, xs in cls.items() if len(xs) > TEST_FROM)
    every = sorted(cls)
    for _ in range(DRAWS):
        taught = sorted(rng.sample(eligible, VOCAB))
        rest = [k for k in every if k not in taught]
        cand = rng.sample(rest, CANDIDATES)
        yield taught, cand, sorted(k for k in rest if k not in cand)


class Rows:
    """Per test clip: speaker, kind (0 taught, 1 untaught, 2 same word as a negative), rank of the truth, the
    top distances and scores, the negative score, the baseline head's own threshold."""

    def __init__(self) -> None:
        self.cols: dict[str, list] = defaultdict(list)

    def add(self, speaker: int, kind: int, truth: str, r: core.Ranked, thr: float) -> None:
        self.cols["sp"].append(speaker)
        self.cols["kind"].append(kind)
        self.cols["tr"].append(r.intents.index(truth) if truth in r.intents else -1)
        self.cols["D"].append(r.distances[:VOCAB])
        self.cols["S"].append(r.scores[:VOCAB])
        self.cols["NEG"].append(r.negative)
        self.cols["THR"].append(thr)

    def arrays(self) -> dict[str, np.ndarray]:
        return {k: np.asarray(v) for k, v in self.cols.items()}


def _test(rows: Rows, si: int, L: core.Learner, thr: float, cls, taught, untaught, negs) -> None:
    for k in taught:
        for x in cls[k][TEST_FROM:]:
            rows.add(si, 0, k, L.predict(x), thr)
    for k in untaught:
        for x in cls[k]:
            rows.add(si, 1, k, L.predict(x), thr)
    for k in negs:
        for x in cls[k][1:]:
            rows.add(si, 2, k, L.predict(x), thr)


def collect(p: dict, n_neg: int, active: bool, min_shots: int = 2) -> tuple[dict[str, np.ndarray], dict]:
    rows = Rows()
    shots: list[int] = []
    rng = random.Random(SEED)
    for si, (_, cls) in enumerate(sorted(p.items())):
        for taught, cand, untaught in splits(cls, rng):
            L = core.Learner()
            base = PrototypeHead()
            if active:
                used = {k: 0 for k in taught}
                while (req := L.next_to_teach(taught, min_shots=min_shots)) is not None:
                    k = req.intent
                    if used[k] >= POOL:  # this class has no more teaching reps; treat as done
                        L.checks.setdefault(k, []).append(True)
                        continue
                    L.teach(k, cls[k][used[k]])
                    base.add(k, cls[k][used[k]])
                    used[k] += 1
                shots.append(sum(used.values()))
            else:
                for k in taught:
                    for x in cls[k][:FIXED_SHOTS]:
                        L.add_sample(k, x)
                        base.add(k, x)
                shots.append(FIXED_SHOTS * len(taught))
            thr = base.calibrate()
            negs = cand[:n_neg]
            for k in negs:
                L.add_negative(cls[k][0])
            _test(rows, si, L, thr, cls, taught, untaught, negs)
    return rows.arrays(), {"mean_examples": round(float(np.mean(shots)), 2), "per_intent": round(float(np.mean(shots)) / VOCAB, 2)}


# ---------- decision families over stored rows (vectorised) ----------

BASE_GRID = [{"st": st} for st in np.round(np.arange(0.3, 1.31, 0.05), 2)]
CORE_GRID = [
    {"q": q, "m": m, "rho": rho}
    for q, m, rho in product(np.round(np.arange(0.8, 2.61, 0.1), 2), (1.15, 1.3, 1.5, 1.75, 2.0), (0.8, 1.0, 1.2, 1.5))
]


def speak_baseline(a: dict, st: float) -> np.ndarray:
    """Today's head (heads.py): calibrated threshold x strictness and margin 1.15 on raw distances."""
    return (a["D"][:, 0] <= a["THR"] * st) & (a["D"][:, 1] / a["D"][:, 0] >= 1.15)


def speak_core(a: dict, q: float, m: float, rho: float) -> np.ndarray:
    """core.decide's speak rule: normalised score, margin, and no closer "none of these" example."""
    s = a["S"]
    return (s[:, 0] <= q) & (s[:, 1] / s[:, 0] >= m) & (a["NEG"] >= s[:, 0] * rho)


def _rates(a: dict, speak: np.ndarray, mask: np.ndarray | None = None) -> dict:
    m = np.ones(len(speak), bool) if mask is None else mask
    known, unknown, negword = (a["kind"] == 0) & m, (a["kind"] == 1) & m, (a["kind"] == 2) & m
    right = speak & known & (a["tr"] == 0)
    f = lambda x, n: float(x.sum() / n.sum()) if n.sum() else None  # noqa: E731
    return {
        "taught_spoken_right": f(right, known),
        "taught_spoken_wrong": f(speak & known & (a["tr"] != 0), known),
        "spoke_precision": float(right.sum() / (speak & known).sum()) if (speak & known).sum() else None,
        "untaught_spoken": f(speak & unknown, unknown),
        "negative_word_spoken": f(speak & negword, negword) if negword.sum() else None,
    }


def _pick(a: dict, mask: np.ndarray, family: str) -> dict:
    """Best taught_spoken_right with untaught_spoken <= target, on the rows in mask."""
    best, best_p = -1.0, None
    for p in BASE_GRID if family == "baseline" else CORE_GRID:
        r = _rates(a, speak_baseline(a, **p) if family == "baseline" else speak_core(a, **p), mask)
        if r["untaught_spoken"] is not None and r["untaught_spoken"] <= UNTAUGHT_TARGET and r["taught_spoken_right"] > best:
            best, best_p = r["taught_spoken_right"], p
    return best_p or (BASE_GRID[0] if family == "baseline" else CORE_GRID[0])


def held_out(a: dict, family: str) -> dict:
    """Constants chosen on six speakers, scored on the seventh; pooled over the seven held-out speakers."""
    speak = np.zeros(len(a["sp"]), bool)
    chosen = []
    for s in np.unique(a["sp"]):
        p = _pick(a, a["sp"] != s, family)
        chosen.append(p)
        test = a["sp"] == s
        sp = speak_baseline(a, **p) if family == "baseline" else speak_core(a, **p)
        speak[test] = sp[test]
    out = _rates(a, speak)
    out["constants_per_held_out_speaker"] = [{k: float(v) for k, v in p.items()} for p in chosen]
    return out


def fallback(a: dict, speak: np.ndarray, q_set: float) -> dict:
    """What happens to taught words that are not spoken: the prediction set shown instead (E16, E19)."""
    s, tr = a["S"], a["tr"]
    size = (s <= q_set).sum(axis=1)
    known = (a["kind"] == 0) & ~speak
    n = known.sum()
    shown = np.where(size == 0, 2, np.minimum(size, core.MAX_CHOICES))  # an empty set still offers the best two as "maybe"
    inset = (tr >= 0) & (tr < shown)
    rows: dict = {"unspoken_taught_share": round(float(n / (a["kind"] == 0).sum()), 4)}
    for name, sel in (
        ("confirm_one", size == 1),
        ("rescue_two", size == 2),
        ("choose_three_or_four", (size >= 3) & (size <= 4)),
        ("ask_more_than_four", size > 4),
        ("not_taught_with_two_maybes", size == 0),
    ):
        m = known & sel
        rows[name] = {"share": round(float(m.sum() / n), 4) if n else None, "truth_shown": round(float((m & inset).sum() / m.sum()), 4) if m.sum() else None}
    rows["truth_shown_overall"] = round(float((known & inset).sum() / n), 4)
    unknown = (a["kind"] == 1) & ~speak
    rows["untaught_said_not_taught"] = round(float((unknown & (size == 0)).sum() / unknown.sum()), 4)
    rows["untaught_none_of_these_first"] = round(float((unknown & (a["NEG"] < s[:, 0])).sum() / unknown.sum()), 4)
    return rows


def coverage_q(a: dict, mask: np.ndarray) -> float:
    known = (a["kind"] == 0) & mask & (a["tr"] >= 0)
    true_scores = a["S"][known, a["tr"][known]]
    return float(np.quantile(true_scores, COVERAGE_TARGET))


def coverage(a: dict) -> dict:
    cov, sizes = [], []
    for s in np.unique(a["sp"]):
        q = coverage_q(a, a["sp"] != s)
        k = (a["kind"] == 0) & (a["sp"] == s)
        size = (a["S"][k] <= q).sum(axis=1)
        tr = a["tr"][k]
        cov.append(float(((tr >= 0) & (tr < size)).mean()))
        sizes.append(size)
    sizes = np.concatenate(sizes)
    return {
        "target": COVERAGE_TARGET,
        "held_out_coverage_per_speaker": [round(c, 4) for c in cov],
        "held_out_coverage_mean": round(float(np.mean(cov)), 4),
        "set_size_mean": round(float(sizes.mean()), 3),
        "set_size_share": {str(k): round(float((sizes == k).mean()), 4) for k in range(0, 6)},
    }


def pair_calibration(p: dict) -> list[dict]:
    """How often a pair of taught intents is confused at test, by the colour its prototype gap gets at teach time."""
    rng = random.Random(SEED)
    buckets: dict[str, list[float]] = defaultdict(list)
    gaps: list[tuple[float, float]] = []
    for _, cls in sorted(p.items()):
        for taught, _, _ in splits(cls, rng):
            L = core.Learner()
            for k in taught:
                for x in cls[k][:FIXED_SHOTS]:
                    L.add_sample(k, x)
            protos = L._protos()
            top1 = {k: [L.predict(x).intents[0] for x in cls[k][TEST_FROM:]] for k in taught}
            for ps in L.separability():
                n = len(top1[ps.a]) + len(top1[ps.b])
                conf = (sum(t == ps.b for t in top1[ps.a]) + sum(t == ps.a for t in top1[ps.b])) / n
                buckets[ps.level].append(conf)
                gaps.append((ps.gap, conf))
            del protos
    g = np.array(gaps)
    edges = [0, 0.8, 1.0, 1.2, 1.4, 1.6, 1.8, 2.2, 3.0, 99]
    table = []
    for lo, hi in zip(edges, edges[1:]):
        m = (g[:, 0] >= lo) & (g[:, 0] < hi)
        if m.sum():
            table.append({"gap_from": lo, "gap_to": hi, "pairs": int(m.sum()), "mean_confusion": round(float(g[m, 1].mean()), 4)})
    colours = {k: {"pairs": len(v), "mean_confusion": round(float(np.mean(v)), 4)} for k, v in buckets.items()}
    return [{"by_colour": colours}, {"by_gap": table}]


def run(clips: list[Clip], emb: dict[str, np.ndarray]) -> dict:
    p = pools(clips, emb)
    doc: dict = {
        "source": "Kannada multi-speaker dataset (voiced, 7 speakers, 28 classes), int8 static encoder embeddings",
        "split": __doc__.split("Split, per speaker")[1].split("Every number")[0].strip().replace("\n", " "),
        "held_out": "decision constants chosen on six speakers, scored on the seventh, for each speaker; pooled",
        "operating_point": f"untaught words spoken by mistake <= {UNTAUGHT_TARGET:.0%} on the six calibration speakers",
    }
    fixed = {n: collect(p, n, active=False)[0] for n in (0, 5, 10)}
    a5 = fixed[5]
    # E17: speak rules at the same operating point, negatives 0 / 5 / 10
    doc["E17_speak_rule"] = {
        "baseline_today_default": _rates(a5, speak_baseline(a5, 1.0)),
        "baseline_today_careful_0.7": _rates(a5, speak_baseline(a5, 0.7)),
        "baseline_at_operating_point": held_out(a5, "baseline"),
        **{f"core_{n}_negatives": held_out(fixed[n], "core") for n in (0, 5, 10)},
    }
    # constants for core.py: chosen on all seven speakers, 5 negatives
    all_rows = np.ones(len(a5["sp"]), bool)
    ship = _pick(a5, all_rows, "core")
    q_set = coverage_q(a5, all_rows)
    doc["shipped_constants"] = {"Q_SPEAK_A": float(ship["q"]), "MARGIN": float(ship["m"]), "NEG_RATIO": float(ship["rho"]), "Q_SET": round(q_set, 3)}
    # E16 + E19: the set shown when a taught word is not spoken
    doc["E16_prediction_set"] = coverage(a5)
    doc["E19_fallback_for_unspoken_taught_words"] = fallback(a5, speak_core(a5, **ship), q_set)
    # E15: active teaching vs fixed three examples, same constants, same test clips
    core.Q_SET = q_set
    top1 = lambda a: float((a["tr"][a["kind"] == 0] == 0).mean())  # noqa: E731
    doc["E15_active_teaching"] = {"fixed_3": {"examples_per_pack": FIXED_SHOTS * VOCAB, **_rates(a5, speak_core(a5, **ship)), "top1": top1(a5)}}
    for label, k in (("active_quick_min2", 2), ("active_thorough_min3", 3)):
        act, cost = collect(p, 5, active=True, min_shots=k)
        doc["E15_active_teaching"][label] = {"examples_per_pack": cost["mean_examples"], **_rates(act, speak_core(act, **ship)), "top1": top1(act)}
    doc["separability_calibration"] = pair_calibration(p)
    return doc
