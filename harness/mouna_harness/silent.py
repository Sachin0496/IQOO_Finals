"""E4, E5 and E17 on our own silent recordings (Finale protocol, docs/protocol.md).

The decision constants in core.py were chosen on the Kannada (voiced) dataset and are applied here unchanged, so
this also tests whether they transfer to silent mouthing by new people.

    python -m mouna_encoder embed data/finale-silent data/finale-silent-emb.npz --model static-int8
    python -m mouna_harness finale-silent data/finale-silent --embeddings data/finale-silent-emb.npz --out deck/data/finale-silent.json

Per person: teach on s1 reps 1-3 (24 examples); test on s1 reps 4-5 (same session) and every s2 clip (next
session). Untaught words (protocol step 2): half are the person's "none of these" examples (their s1 clip), the
other half are tested (s1 and s2 clips); then the halves swap and the two folds are pooled.
"""

from __future__ import annotations

from collections import Counter, defaultdict

import numpy as np

from . import core
from .heads import PrototypeHead
from .io import Clip
from .protocol import NEXT_SESSION, TEACH_REPS, TEACH_SESSION

PASS_SAME, PASS_NEXT, PASS_UNTAUGHT = 0.90, 0.85, 0.10  # PLAN.md E4, E5
VOICED_SESSION = "voiced"  # optional step 3: the 8 phrases said aloud, reps 1-3 (E12, the pre-surgery session)
PASS_VOICED_GAP = 0.06  # E12: silent top-1 within 6 points of silent teaching, with one silent top-up


def _usable(c: Clip, emb: dict) -> bool:
    return c.clean and c.phrase is not None and emb.get(c.id) is not None


def evaluate(clips: list[Clip], emb: dict[str, np.ndarray]) -> dict:
    by_person: dict[str, list[Clip]] = defaultdict(list)
    for c in clips:
        by_person[c.participant].append(c)
    pooled: dict[str, Counter] = defaultdict(Counter)
    people = []
    for person, cs in sorted(by_person.items()):
        proto = [c for c in cs if c.kind == "protocol" and _usable(c, emb)]
        teach = [c for c in proto if c.session == TEACH_SESSION and c.rep <= TEACH_REPS]
        if not teach:
            continue
        tests = {
            "same_session": [c for c in proto if c.session == TEACH_SESSION and c.rep > TEACH_REPS],
            "next_session": [c for c in proto if c.session == NEXT_SESSION],
        }
        untaught = [c for c in cs if c.kind == "untaught" and _usable(c, emb)]
        words = sorted({c.phrase for c in untaught if c.session == TEACH_SESSION})
        halves = [words[: len(words) // 2], words[len(words) // 2 :]] if len(words) >= 2 else [[], words]
        idle = [c for c in cs if c.kind == "idle" and emb.get(c.id) is not None]
        idle_min = sum(c.duration_ms for c in cs if c.kind == "idle_span") / 60000
        mine: dict[str, Counter] = defaultdict(Counter)
        for fold in (0, 1):
            negs, tested = halves[fold], halves[1 - fold]
            L = core.Learner()
            base = PrototypeHead()
            for c in teach:
                L.add_sample(c.phrase, emb[c.id])
                base.add(c.phrase, emb[c.id])
            base.calibrate()
            careful = PrototypeHead()
            careful.samples, careful.threshold = base.samples, base.threshold * 0.7
            for c in untaught:
                if c.phrase in negs and c.session == TEACH_SESSION:
                    L.add_negative(emb[c.id])
            for split, test in tests.items():
                for c in test:
                    r, x = L.predict(emb[c.id]), emb[c.id]
                    d, b, k = core.decide(r), base.classify(x), careful.classify(x)
                    s = mine[split]
                    s["n"] += 1
                    s["top1"] += r.intents[0] == c.phrase
                    s["top3"] += c.phrase in r.intents[:3]
                    s["core_spoke"] += d.kind == "speak"
                    s["core_spoke_right"] += d.kind == "speak" and d.options[0] == c.phrase
                    s["core_truth_on_screen_when_not_spoken"] += d.kind != "speak" and c.phrase in d.options
                    s["base_spoke"] += b.kind == "accept"
                    s["base_spoke_right"] += b.kind == "accept" and b.ranked[0][0] == c.phrase
                    s["careful_spoke"] += k.kind == "accept"
                    s["careful_spoke_right"] += k.kind == "accept" and k.ranked[0][0] == c.phrase
            for c in untaught:
                if c.phrase not in tested:
                    continue
                r, x = L.predict(emb[c.id]), emb[c.id]
                d = core.decide(r)
                s = mine["untaught"]
                s["n"] += 1
                s["core_spoke"] += d.kind == "speak"
                s["core_said_not_taught"] += d.kind == "not_taught"
                s["base_spoke"] += base.classify(x).kind == "accept"
                s["careful_spoke"] += careful.classify(x).kind == "accept"
            if fold == 0:  # idle does not depend on the fold's negatives split beyond noise; count once
                for c in idle:
                    s = mine["idle"]
                    s["n"] += 1
                    s["core_spoke"] += core.decide(L.predict(emb[c.id])).kind == "speak"
                    s["base_spoke"] += base.classify(emb[c.id]).kind == "accept"
        mine["idle"]["minutes_x1000"] += int(idle_min * 1000)
        voiced = [c for c in proto if c.session == VOICED_SESSION and c.rep <= TEACH_REPS]
        if voiced:
            same = tests["same_session"]
            for label, extra in (("voiced_teach", []), ("voiced_teach_plus_1_silent", [c for c in teach if c.rep == 1])):
                L = core.Learner()
                for c in voiced + extra:
                    L.add_sample(c.phrase, emb[c.id])
                s = mine[label]
                for c in same:
                    d = core.decide(L.predict(emb[c.id]))
                    s["n"] += 1
                    s["top1"] += L.predict(emb[c.id]).intents[0] == c.phrase
                    s["core_spoke"] += d.kind == "speak"
                    s["core_spoke_right"] += d.kind == "speak" and d.options[0] == c.phrase
        for k, v in mine.items():
            pooled[k].update(v)
        people.append({"participant": person, **{k: _summary(k, v) for k, v in mine.items()}})
    out = {k: _summary(k, v) for k, v in pooled.items()}
    same, nxt, unt = out.get("same_session", {}), out.get("next_session", {}), out.get("untaught", {})
    out["verdict"] = {
        "E4_same_session_top1": _pass(same.get("top1"), PASS_SAME),
        "E4_next_session_top1": _pass(nxt.get("top1"), PASS_NEXT),
        "E5_untaught_spoken_core": _pass(None if unt.get("core_spoke") is None else 1 - unt["core_spoke"], 1 - PASS_UNTAUGHT),
    }
    if "voiced_teach_plus_1_silent" in out and same.get("top1") is not None:
        # compared on the same test clips (s1 reps 4-5); same_session pools both folds, which share these clips
        gap = same["top1"] - out["voiced_teach_plus_1_silent"]["top1"]
        out["verdict"]["E12_voiced_gap_with_top_up"] = f"{100 * gap:.1f} points: " + _pass(-gap, -PASS_VOICED_GAP)
    return {"pooled": out, "participants": people, "constants": "core.py as shipped (chosen on Kannada), unchanged"}


def _pass(x: float | None, line: float) -> str:
    return "no data" if x is None else ("pass" if x >= line else "fail")


def _summary(kind: str, c: Counter) -> dict:
    n = c["n"]
    r = lambda a, b: round(a / b, 4) if b else None  # noqa: E731
    if kind == "untaught":
        return {"n": n, "core_spoke": r(c["core_spoke"], n), "core_said_not_taught": r(c["core_said_not_taught"], n), "base_spoke": r(c["base_spoke"], n), "careful_spoke": r(c["careful_spoke"], n)}
    if kind.startswith("voiced"):
        return {"n": n, "top1": r(c["top1"], n), "core_spoke": r(c["core_spoke"], n), "core_spoke_precision": r(c["core_spoke_right"], c["core_spoke"])}
    if kind == "idle":
        mins = c["minutes_x1000"] / 1000
        return {"n": n, "minutes": round(mins, 2), "core_false_speaks_per_min": r(c["core_spoke"], mins), "base_false_speaks_per_min": r(c["base_spoke"], mins)}
    return {
        "n": n,
        "top1": r(c["top1"], n),
        "top3": r(c["top3"], n),
        "core_spoke": r(c["core_spoke"], n),
        "core_spoke_precision": r(c["core_spoke_right"], c["core_spoke"]),
        "core_truth_on_screen_when_not_spoken": r(c["core_truth_on_screen_when_not_spoken"], n - c["core_spoke"]),
        "base_spoke": r(c["base_spoke"], n),
        "base_spoke_precision": r(c["base_spoke_right"], c["base_spoke"]),
        "careful_spoke": r(c["careful_spoke"], n),
        "careful_spoke_precision": r(c["careful_spoke_right"], c["careful_spoke"]),
    }
