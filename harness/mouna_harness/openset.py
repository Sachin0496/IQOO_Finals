"""Two questions the closed-set protocol cannot answer.

1. Untaught words (open set): when the person mouths something they never taught, how often does Mouna speak a
   taught phrase anyway, offer choices, or say it does not know?
2. Chained blocks: when the person mouths two or three taught words one after another (one clip each), how often is
   the whole sentence right, and how much does a fixed list of sensible combinations help?

Both reuse the heads, thresholds and teach/test split of protocol.py unchanged; only which classes are taught and
how test clips are grouped differ. Plan B distances (DTW) are computed once per participant and cached.
"""

from __future__ import annotations

import itertools
import random
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

from .heads import UNSURE_FACTOR, DTWHead, PrototypeHead, dtw
from .io import Clip
from .protocol import TEACH_REPS, TEACH_SESSION, geometry

# Two- and three-block sentences a patient on a ward might build from the dataset's vocabulary. Written before any
# chain was scored and not changed after. "manne" is left out: its meaning is unclear from the dataset.
FOOD = ["neeru", "oota", "tindi"]  # water, meal, snack
WANT = ["beku", "beda", "saku"]  # want, don't want, enough
PEOPLE = ["amma", "akka"]  # mother, elder sister
SENTENCES: list[tuple[str, ...]] = (
    [(f, w) for f in FOOD for w in WANT]  # "neeru beku": I want water
    + [(p, "banni") for p in PEOPLE]  # "amma banni": mother, come
    + [(p, f, w) for p in PEOPLE for f in FOOD for w in ("beku", "beda")]  # "amma, neeru beku": mother, I want water
)


# ---------- heads over cached distances ----------


class CachedDTWHead(DTWHead):
    """DTWHead with templates stored as clip ids and distances looked up from a precomputed matrix."""

    def __init__(self, dist: dict[tuple[str, str], float]) -> None:
        super().__init__()
        self.dist = dist

    def _phrase_distance(self, seq, ts, skip):  # seq, ts and skip are clip ids here
        d = sorted(self.dist[(seq, t)] for t in ts if t is not skip)
        if not d:
            return float("inf")
        return d[0] if len(d) == 1 else (d[0] + d[1]) / 2


def _dtw_rows(args: tuple[list[str], list[np.ndarray], int]) -> list[tuple[str, str, float]]:
    ids, seqs, i = args
    return [(ids[i], ids[j], dtw(seqs[i], seqs[j])) for j in range(len(ids)) if j != i]


def dtw_matrix(clips: list[Clip], workers: int = 8) -> dict[tuple[str, str], float]:
    ids = [c.id for c in clips]
    seqs = [geometry(c) for c in clips]
    out: dict[tuple[str, str], float] = {}
    with ProcessPoolExecutor(workers) as pool:
        for rows in pool.map(_dtw_rows, [(ids, seqs, i) for i in range(len(ids))], chunksize=4):
            out.update({(a, b): d for a, b, d in rows})
    return out


def load_or_build_dtw(clips: list[Clip], cache: Path) -> dict[tuple[str, str], float]:
    if cache.exists():
        z = np.load(cache, allow_pickle=False)
        return dict(zip(map(tuple, z["pairs"].tolist()), z["dist"].tolist()))
    by_person: dict[str, list[Clip]] = defaultdict(list)
    for c in clips:
        by_person[c.participant].append(c)
    dist: dict[tuple[str, str], float] = {}
    for p, cs in sorted(by_person.items()):
        dist.update(dtw_matrix(cs))
        print(f"  dtw {p}: {len(cs)} clips", flush=True)
    pairs = np.array(list(dist.keys()))
    np.savez_compressed(cache, pairs=pairs, dist=np.array(list(dist.values())))
    return dist


class Backend:
    """Builds a taught head for one participant and returns (kind, ranked) for a test clip."""

    def __init__(self, embeddings: dict[str, np.ndarray] | None, dist: dict[tuple[str, str], float] | None, strictness: float = 1.0) -> None:
        self.emb, self.dist, self.strictness = embeddings, dist, strictness
        self.name = PrototypeHead.name if embeddings is not None else DTWHead.name

    def usable(self, c: Clip) -> bool:
        return self.emb.get(c.id) is not None if self.emb is not None else len(c.features) > 1

    def teach(self, teach: list[Clip]):
        head = PrototypeHead() if self.emb is not None else CachedDTWHead(self.dist)
        for c in teach:
            head.add(c.phrase, self.emb[c.id] if self.emb is not None else c.id)
        head.calibrate()
        head.threshold *= self.strictness  # < 1: speak less often, on taught and untaught words alike
        return head

    def classify(self, head, c: Clip):
        return head.classify(self.emb[c.id] if self.emb is not None else c.id)


def _protocol_clips(clips: list[Clip], backend: Backend) -> dict[str, list[Clip]]:
    by_person: dict[str, list[Clip]] = defaultdict(list)
    for c in clips:
        if c.kind == "protocol" and c.clean and c.phrase and c.session == TEACH_SESSION and backend.usable(c):
            by_person[c.participant].append(c)
    return by_person


# ---------- 1. untaught words ----------


def open_set(clips: list[Clip], backend: Backend, vocab: int | None, draws: int, seed: int = 7) -> dict:
    """Teach `vocab` random classes (None = all but one, each class left out in turn); test taught classes on reps
    after the teaching reps, and every clip of the untaught classes."""
    rng = random.Random(seed)
    known = Counter()
    unknown = Counter()
    spoken_as: Counter = Counter()
    for person, cs in sorted(_protocol_clips(clips, backend).items()):
        classes = sorted({c.phrase for c in cs})
        if vocab is None:
            splits = [[k for k in classes if k != out] for out in classes]
        else:
            splits = [sorted(rng.sample(classes, vocab)) for _ in range(draws)]
        for taught in splits:
            t = set(taught)
            head = backend.teach([c for c in cs if c.phrase in t and c.rep <= TEACH_REPS])
            for c in cs:
                if c.phrase in t and c.rep > TEACH_REPS:
                    d = backend.classify(head, c)
                    known["n"] += 1
                    known[d.kind] += 1
                    known["top1"] += d.ranked[0][0] == c.phrase
                    known["spoke_right"] += d.kind == "accept" and d.ranked[0][0] == c.phrase
                elif c.phrase not in t:
                    d = backend.classify(head, c)
                    unknown["n"] += 1
                    unknown[d.kind] += 1
                    if d.kind == "accept":
                        spoken_as[(c.phrase, d.ranked[0][0])] += 1
    r = lambda a, b: round(a / b, 4) if b else None  # noqa: E731
    return {
        "taught_classes": vocab if vocab is not None else "all but one (each class left out in turn)",
        "draws_per_participant": draws if vocab is not None else None,
        "taught_words": {
            "n": known["n"],
            "top1": r(known["top1"], known["n"]),
            "spoke": r(known["accept"], known["n"]),
            "spoke_precision": r(known["spoke_right"], known["accept"]),
        },
        "untaught_words": {
            "n": unknown["n"],
            "spoken_as_a_taught_phrase": r(unknown["accept"], unknown["n"]),
            "offered_choices": r(unknown["unsure"], unknown["n"]),
            "said_not_taught": r(unknown["reject"], unknown["n"]),
            "most_often_spoken_as": [
                {"said": a, "spoken_as": b, "share_of_wrong_speaks": r(k, unknown["accept"])}
                for (a, b), k in spoken_as.most_common(5)
            ],
        },
    }


# ---------- 2. chained blocks ----------


def chains(clips: list[Clip], backend: Backend) -> dict:
    """Every sentence in SENTENCES, built from every combination of held-out clips of its words (one participant at
    a time; all 27 classes taught, as in the protocol)."""
    tot = Counter()
    by_len: dict[int, Counter] = defaultdict(Counter)
    errors: Counter = Counter()
    for person, cs in sorted(_protocol_clips(clips, backend).items()):
        head = backend.teach([c for c in cs if c.rep <= TEACH_REPS])
        tests: dict[str, list] = defaultdict(list)
        for c in cs:
            if c.rep > TEACH_REPS:
                d = backend.classify(head, c)
                tests[c.phrase].append((d.kind, dict(d.ranked), [p for p, _ in d.ranked]))
        threshold = head.threshold
        taught = set(head.samples if hasattr(head, "samples") else head.templates)
        listed = [x for x in SENTENCES if set(x) <= taught]
        for sent in listed:
            if any(not tests.get(w) for w in sent):
                continue
            for combo in itertools.product(*(tests[w] for w in sent)):
                s = by_len[len(sent)]
                s["n"] += 1
                # (a) each block on its own: right only if every block's best guess is right
                raw = all(ranked[0] == w for w, (_, _, ranked) in zip(sent, combo))
                s["each_block"] += raw
                # (b) the ceiling for any re-ranking: every block's truth is among its top three
                s["top3_ceiling"] += all(w in ranked[:3] for w, (_, _, ranked) in zip(sent, combo))
                # (c) the sentence list: the listed sentence of this length with the smallest summed distance
                cands = [x for x in listed if len(x) == len(sent)]
                cost = [sum(dist[w] for w, (_, dist, _) in zip(x, combo)) for x in cands]
                best = cands[int(np.argmin(cost))]
                s["sentence_list"] += best == sent
                if best != sent:
                    errors[(" ".join(sent), " ".join(best))] += 1
                # speak only if every block of the chosen sentence is within the head's own threshold
                sure = all(dist[w] <= threshold for w, (_, dist, _) in zip(best, combo))
                s["spoke"] += sure
                s["spoke_right"] += sure and best == sent
    r = lambda a, b: round(a / b, 4) if b else None  # noqa: E731
    out = {}
    for n, s in sorted(by_len.items()):
        out[f"{n}_blocks"] = {
            "n": s["n"],
            "each_block_alone": r(s["each_block"], s["n"]),
            "top3_ceiling": r(s["top3_ceiling"], s["n"]),
            "with_sentence_list": r(s["sentence_list"], s["n"]),
            "spoke": r(s["spoke"], s["n"]),
            "spoke_precision": r(s["spoke_right"], s["spoke"]),
        }
    wrong = sum(errors.values())
    out["most_common_mistakes"] = [{"meant": a, "got": b, "share": r(k, wrong)} for (a, b), k in errors.most_common(5)]
    out["sentences"] = [" ".join(x) for x in SENTENCES]
    return out


def chains_off_list(clips: list[Clip], backend: Backend, per_person: int = 200, seed: int = 11) -> dict:
    """Two-block chains that are NOT on the list: decoding against the list must not speak them."""
    rng = random.Random(seed)
    s = Counter()
    for person, cs in sorted(_protocol_clips(clips, backend).items()):
        head = backend.teach([c for c in cs if c.rep <= TEACH_REPS])
        tests: dict[str, list] = defaultdict(list)
        for c in cs:
            if c.rep > TEACH_REPS:
                tests[c.phrase].append(dict(backend.classify(head, c).ranked))
        taught = set(head.samples if hasattr(head, "samples") else head.templates)
        words = sorted(w for w in tests if w in taught)
        listed = {x for x in SENTENCES if len(x) == 2 and set(x) <= taught}
        cands = sorted(listed)
        for _ in range(per_person):
            a, b = rng.choice(words), rng.choice(words)
            if (a, b) in listed:
                continue
            da, db = rng.choice(tests[a]), rng.choice(tests[b])
            cost = [da[x] + db[y] for x, y in cands]
            x, y = cands[int(np.argmin(cost))]
            s["n"] += 1
            s["spoke"] += da[x] <= head.threshold and db[y] <= head.threshold
            s["offered"] += da[x] <= head.threshold * UNSURE_FACTOR and db[y] <= head.threshold * UNSURE_FACTOR
    return {
        "n": s["n"],
        "spoken_as_a_listed_sentence": round(s["spoke"] / s["n"], 4) if s["n"] else None,
        "within_choice_range": round(s["offered"] / s["n"], 4) if s["n"] else None,
    }


def strictness_curve(clips: list[Clip], embeddings, dist, vocab: int, draws: int, levels=(1.0, 0.9, 0.8, 0.7, 0.6)) -> list[dict]:
    """Lowering the speak threshold trades untaught words spoken by mistake for taught words sent to the choices."""
    rows = []
    for s in levels:
        r = open_set(clips, Backend(embeddings, dist, s), vocab, draws)
        rows.append(
            {
                "strictness": s,
                "taught_spoken": r["taught_words"]["spoke"],
                "taught_spoken_precision": r["taught_words"]["spoke_precision"],
                "untaught_spoken": r["untaught_words"]["spoken_as_a_taught_phrase"],
                "untaught_said_not_taught": r["untaught_words"]["said_not_taught"],
            }
        )
    return rows
