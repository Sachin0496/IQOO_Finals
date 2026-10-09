"""Mouna's decision core (PLAN.md §5): the reference the app's Kotlin port follows.

Lip reading is ambiguous, so every layer corrects the one before it:

- ``Learner``: few-shot prototypes over any fixed-size embedding, plus "none of my phrases" negatives.
- Scores are distances in units of *this person's* own spread (``tau``), so one set of constants, calibrated across
  people, works for a new person. A score of about 1 is a typical example of that intent.
- ``separability``: which taught intents look alike for this person (green / yellow / red).
- ``teach`` + ``next_to_teach``: active teaching. Every new example is first recognised, then learned; Mouna asks
  for more examples only of the intents it still gets wrong or that sit in a red pair.
- ``decide``: a prediction *set* instead of a threshold. One intent -> speak (or confirm, by risk tier); two -> one-bit
  rescue (two pictures); three or four -> choices; more -> Ask mode; none, or a negative is closer -> not taught.
- Context may order the choices; it never creates, removes or changes a spoken answer.

Constants below are measured on the Kannada dataset by ``python -m mouna_harness finale-core`` and published in
``deck/data/finale-core.json``; change them only from a new measurement.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from itertools import combinations

import numpy as np

# ---------- constants (measured; see the module docstring) ----------

PRIOR_TAU = 0.14  # typical within-intent pairwise cosine distance (Kannada median, int8 encoder)
TAU_PSEUDO = 3  # the prior counts as this many measured pairs
Q_SET = 1.95  # an intent joins the prediction set at or below this (truth in the set: 91.1% held out, target 90%)
Q_SPEAK = {"A": 1.7, "B": 1.4}  # spoken directly only when the best score is at most this (B: design choice, stricter)
MARGIN = 1.3  # speaking also needs the runner-up at least MARGIN times further
NEG_RATIO = 1.2  # and no "none of these" example closer than NEG_RATIO times the best score
CAREFUL = 0.8  # Careful mode scales Q_SPEAK
MAX_CHOICES = 4  # pictures shown at once
SAFE_MARGIN = 1.25  # an example is recognised safely when runner-up score / best score is at least this
RED_PAIR, YELLOW_PAIR = 1.0, 1.8  # prototype gap / tau. Kannada test confusion: < 1.0 2-3.5%, 1.0-1.8 ~1%, > 1.8 <= 0.3%
MAX_SHOTS = 5
RED_SHOTS = 3  # examples each member of a red pair gets
CONTEXT_CAP = 0.15  # the most a context prior can change a choice's score (15%); only inside the set


def _unit(x) -> np.ndarray:
    v = np.asarray(x, dtype=np.float64).reshape(-1)
    return v / (np.linalg.norm(v) + 1e-12)


@dataclass
class Ranked:
    intents: list[str]  # best first
    scores: list[float]  # distance in units of this person's spread; ~1 = a typical example
    distances: list[float]  # raw cosine distance to each prototype
    negative: float = math.inf  # score of the nearest "none of my phrases" example


@dataclass
class PairScore:
    a: str
    b: str
    gap: float  # prototype distance / tau
    level: str  # green | yellow | red


@dataclass
class TeachRequest:
    intent: str
    reason: str  # first | check | missed | close_to:<other>


@dataclass
class Decision:
    kind: str  # speak | confirm | rescue | choose | ask | not_taught
    options: list[str] = field(default_factory=list)  # what to say or show, best first
    why: str = ""
    maybe_none: bool = False  # a "none of these" example is closer: show "None of these" first


class Learner:
    """Few-shot learner for one person. Works for any fixed-size embedding (lip encoder, pooled blendshapes...)."""

    def __init__(self, prior_tau: float = PRIOR_TAU) -> None:
        self.samples: dict[str, list[np.ndarray]] = {}
        self.negatives: list[np.ndarray] = []
        self.checks: dict[str, list[bool]] = {}  # per intent: was each new example recognised safely before learning
        self.prior_tau = prior_tau

    # ----- teaching -----

    def add_sample(self, intent: str, x) -> None:
        self.samples.setdefault(intent, []).append(_unit(x))

    def add_negative(self, x) -> None:
        self.negatives.append(_unit(x))

    def forget(self, intent: str) -> None:
        self.samples.pop(intent, None)
        self.checks.pop(intent, None)

    def teach(self, intent: str, x) -> bool | None:
        """Recognise the example first (if the intent already has one), then learn it. Returns the check result."""
        ok = None
        if self.samples.get(intent):
            r = self.predict(x)
            second = r.scores[1] if len(r.scores) > 1 else math.inf
            ok = r.intents[0] == intent and r.scores[0] <= Q_SET and second >= SAFE_MARGIN * r.scores[0]
            self.checks.setdefault(intent, []).append(ok)
        self.add_sample(intent, x)
        return ok

    # ----- geometry -----

    def tau(self) -> float:
        """This person's typical within-intent distance, shrunk towards the prior while there are few pairs."""
        d = [1 - float(a @ b) for xs in self.samples.values() for i, a in enumerate(xs) for b in xs[i + 1 :]]
        if not d:
            return self.prior_tau
        return float((np.median(d) * len(d) + self.prior_tau * TAU_PSEUDO) / (len(d) + TAU_PSEUDO))

    def _protos(self, skip: tuple[str, int] | None = None) -> dict[str, tuple[np.ndarray, int]]:
        out = {}
        for k, xs in self.samples.items():
            keep = [x for i, x in enumerate(xs) if skip != (k, i)]
            if keep:
                out[k] = (_unit(np.mean(keep, axis=0)), len(keep))
        return out

    @staticmethod
    def _score(d: float, n: int, tau: float) -> float:
        # a test example sits about tau/2 * (1 + 1/n) from a prototype of n examples (isotropic spread)
        return d / (0.5 * tau * (1 + 1 / n))

    def predict(self, x, skip: tuple[str, int] | None = None) -> Ranked:
        v = _unit(x)
        tau = self.tau()
        rows = [(k, 1 - float(v @ p), n) for k, (p, n) in self._protos(skip).items()]
        rows.sort(key=lambda r: self._score(r[1], r[2], tau))
        neg = min((1 - float(v @ u) for u in self.negatives), default=math.inf)
        return Ranked(
            [k for k, _, _ in rows],
            [self._score(d, n, tau) for _, d, n in rows],
            [d for _, d, _ in rows],
            self._score(neg, 1, tau) if math.isfinite(neg) else math.inf,
        )

    def separability(self) -> list[PairScore]:
        """Every pair of taught intents, closest first."""
        tau = self.tau()
        protos = self._protos()
        out = []
        for a, b in combinations(sorted(protos), 2):
            gap = (1 - float(protos[a][0] @ protos[b][0])) / tau
            out.append(PairScore(a, b, gap, "red" if gap < RED_PAIR else "yellow" if gap < YELLOW_PAIR else "green"))
        return sorted(out, key=lambda p: p.gap)

    def next_to_teach(self, intents: list[str] | None = None, min_shots: int = 2, max_shots: int = MAX_SHOTS) -> TeachRequest | None:
        """The one example Mouna needs next, or None when the pack is safe (or every intent is at max_shots).
        min_shots 2 = quick (a tired patient), 3 = thorough."""
        intents = intents if intents is not None else list(self.samples)
        n = {k: len(self.samples.get(k, [])) for k in intents}
        for k in intents:
            if n[k] == 0:
                return TeachRequest(k, "first")
        for want in range(2, min_shots + 1):
            for k in intents:
                if n[k] < want:
                    return TeachRequest(k, "check")
        missed = [k for k in intents if self.checks.get(k) and not self.checks[k][-1] and n[k] < max_shots]
        if missed:
            return TeachRequest(min(missed, key=lambda k: n[k]), "missed")
        for p in self.separability():
            if p.level != "red":
                break
            if p.a not in n or p.b not in n:
                continue
            k = min((p.a, p.b), key=lambda k: n[k])
            if n[k] < min(RED_SHOTS, max_shots):  # a close pair gets the full three examples, no more
                return TeachRequest(k, f"close_to:{p.b if k == p.a else p.a}")
        return None


def decide(
    r: Ranked,
    tiers: dict[str, str] | None = None,
    careful: bool = False,
    prior: dict[str, float] | None = None,
    q_set: float = Q_SET,
    q_speak: dict[str, float] | None = None,
) -> Decision:
    """One decision from a ranking. tiers: intent -> A (low consequence) | B (care request) | C (action).

    Speak needs all three: a close best match (Q_SPEAK by tier), a clear runner-up gap (MARGIN), and no closer
    "none of these" example (NEG_RATIO). Tier C (actions) is never spoken without a confirm. Everything else shows
    the prediction set, which context may reorder but never extend."""
    q_speak = q_speak or Q_SPEAK
    if not r.intents:
        return Decision("not_taught", why="nothing taught")
    best = r.scores[0]
    second = r.scores[1] if len(r.scores) > 1 else math.inf
    tier = (tiers or {}).get(r.intents[0], "A")
    limit = q_speak.get(tier, -math.inf) * (CAREFUL if careful else 1.0)
    if best <= limit and second >= MARGIN * best and r.negative >= NEG_RATIO * best:
        return Decision("speak", [r.intents[0]], "clear")
    s = [k for k, sc in zip(r.intents, r.scores) if sc <= q_set]
    maybe_none = r.negative < best
    if not s:
        return Decision("not_taught", r.intents[:2], "not close to anything taught", maybe_none)
    if prior and len(s) > 1:
        score = dict(zip(r.intents, r.scores))
        mean = sum(prior.get(k, 0.0) for k in s) / len(s)
        s.sort(key=lambda k: score[k] * (1 - CONTEXT_CAP * max(-1.0, min(1.0, (prior.get(k, 0.0) - mean) / (mean or 1)))))
    if len(s) == 1:
        why = "action: always confirmed" if tier == "C" and s[0] == r.intents[0] else "fairly sure: confirm first"
        return Decision("confirm", s, why, maybe_none)
    if len(s) == 2:
        return Decision("rescue", s, "two look alike: pick one", maybe_none)
    if len(s) <= MAX_CHOICES:
        return Decision("choose", s, "a few are possible", maybe_none)
    return Decision("ask", s[:MAX_CHOICES], "many are possible: ask yes/no", maybe_none)
