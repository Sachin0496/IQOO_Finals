"""The spike protocol (docs/protocol.md): teach on s1 reps 1-3, test on s1 reps 4-5 and all of s2."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

from collections.abc import Callable

import numpy as np

from .heads import DTWHead, PrototypeHead, to_sequence
from .io import Clip

TEACH_SESSION = "s1"
NEXT_SESSION = "s2"
TEACH_REPS = 3
PASS_SAME = 0.95
PASS_NEXT = 0.90
FLOOR = 0.90


@dataclass
class Split:
    n: int = 0
    top1: int = 0
    top3: int = 0
    accepted: int = 0
    accepted_correct: int = 0
    confusion: dict[str, dict[str, int]] = field(default_factory=lambda: defaultdict(lambda: defaultdict(int)))

    def add(self, truth: str, ranked: list[str], accepted: bool) -> None:
        self.n += 1
        self.top1 += ranked[0] == truth
        self.top3 += truth in ranked[:3]
        if accepted:
            self.accepted += 1
            self.accepted_correct += ranked[0] == truth
        self.confusion[truth][ranked[0]] += 1

    def merge(self, other: "Split") -> None:
        for k in ("n", "top1", "top3", "accepted", "accepted_correct"):
            setattr(self, k, getattr(self, k) + getattr(other, k))
        for t, row in other.confusion.items():
            for p, c in row.items():
                self.confusion[t][p] += c

    def summary(self) -> dict:
        r = lambda a, b: round(a / b, 4) if b else None  # noqa: E731
        return {
            "n": self.n,
            "top1": r(self.top1, self.n),
            "top3": r(self.top3, self.n),
            "spoke": r(self.accepted, self.n),
            "spoke_precision": r(self.accepted_correct, self.accepted),
            "confusion": {t: dict(row) for t, row in self.confusion.items()},
        }


@dataclass
class ParticipantResult:
    participant: str
    threshold: float
    same: Split
    next: Split
    idle_minutes: float
    gate_triggers: int
    false_speaks: int


Featurize = Callable[[Clip], np.ndarray | None]


def geometry(c: Clip) -> np.ndarray | None:
    return to_sequence(c.t, c.features) if len(c.features) > 1 else None


def evaluate_participant(
    clips: list[Clip], featurize: Featurize = geometry, head_cls: type = DTWHead, teach_reps: int = TEACH_REPS, test_after: int | None = None
) -> ParticipantResult | None:
    """Plan B by default (lip geometry + DTW); pass encoder embeddings and PrototypeHead for plan A."""
    proto = [c for c in clips if c.kind == "protocol" and c.clean and c.phrase and featurize(c) is not None]
    test_after = teach_reps if test_after is None else test_after
    teach = [c for c in proto if c.session == TEACH_SESSION and c.rep <= teach_reps]
    if not teach:
        return None
    head = head_cls()
    for c in teach:
        head.add(c.phrase, featurize(c))
    threshold = head.calibrate()

    def run(subset: list[Clip]) -> Split:
        s = Split()
        for c in subset:
            d = head.classify(featurize(c))
            s.add(c.phrase, [p for p, _ in d.ranked], d.kind == "accept")
        return s

    same = run([c for c in proto if c.session == TEACH_SESSION and c.rep > test_after])
    nxt = run([c for c in proto if c.session == NEXT_SESSION])

    idle = [c for c in clips if c.kind == "idle"]
    minutes = sum(c.duration_ms for c in clips if c.kind == "idle_span") / 60000
    false_speaks = sum(head.classify(f).kind == "accept" for c in idle if (f := featurize(c)) is not None)
    return ParticipantResult(clips[0].participant, threshold, same, nxt, minutes, len(idle), false_speaks)


def verdict(same_top1: float | None, next_top1: float | None) -> str:
    if same_top1 is None:
        return "no data"
    if same_top1 < FLOOR:
        return "no-go: below 90% same-session; move to the backup idea"
    if same_top1 >= PASS_SAME and (next_top1 is None or next_top1 >= PASS_NEXT):
        return "go" if next_top1 is not None else "go so far: record s2 next morning"
    return "go with 5 repetitions per phrase and the top-3 fallback; say so in the deck"


def evaluate(clips: list[Clip], embeddings: dict[str, np.ndarray] | None = None, teach_reps: int = TEACH_REPS, test_after: int | None = None) -> dict:
    featurize: Featurize = geometry
    head_cls: type = DTWHead
    if embeddings is not None:
        featurize, head_cls = (lambda c: embeddings.get(c.id)), PrototypeHead  # clips without crops drop out
    by_person: dict[str, list[Clip]] = defaultdict(list)
    for c in clips:
        by_person[c.participant].append(c)

    people = [r for r in (evaluate_participant(cs, featurize, head_cls, teach_reps, test_after) for cs in by_person.values()) if r]
    pooled_same, pooled_next = Split(), Split()
    for r in people:
        pooled_same.merge(r.same)
        pooled_next.merge(r.next)
    minutes = sum(r.idle_minutes for r in people)
    same, nxt = pooled_same.summary(), pooled_next.summary()
    return {
        "head": head_cls.name,
        "participants": [
            {
                "participant": r.participant,
                "threshold": round(r.threshold, 4),
                "same_session": r.same.summary(),
                "next_session": r.next.summary(),
                "idle_minutes": round(r.idle_minutes, 2),
                "gate_triggers": r.gate_triggers,
                "false_speaks": r.false_speaks,
            }
            for r in people
        ],
        "pooled": {
            "same_session": same,
            "next_session": nxt,
            "idle_minutes": round(minutes, 2),
            "gate_triggers_per_min": round(sum(r.gate_triggers for r in people) / minutes, 3) if minutes else None,
            "false_speaks_per_min": round(sum(r.false_speaks for r in people) / minutes, 3) if minutes else None,
        },
        "verdict": verdict(same["top1"], nxt["top1"]),
        "phrases": sorted({c.phrase for c in clips if c.kind == "protocol" and c.phrase}),
    }

