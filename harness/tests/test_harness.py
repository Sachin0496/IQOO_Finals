import json

import numpy as np
import pytest

from mouna_harness.heads import DTWHead, PrototypeHead, dtw, to_sequence
from mouna_harness.io import load_dir
from mouna_harness.protocol import evaluate, verdict
from mouna_harness.report import to_markdown
from mouna_harness.synth import write_synthetic

# Same case is asserted in lab/src/core/core.test.ts, so the two implementations stay identical.
PARITY_A = np.array([[0.0, 0.0], [1.0, 0.5], [2.0, 1.0], [1.5, 0.2]], dtype=np.float32)
PARITY_B = np.array([[0.0, 0.1], [2.0, 0.9], [1.4, 0.3]], dtype=np.float32)
PARITY_DTW = 0.202636
SEQUENCE_PARITY = 27.750000


def test_dtw_parity_with_lab():
    assert dtw(PARITY_A, PARITY_B) == pytest.approx(PARITY_DTW, abs=1e-4)
    assert dtw(PARITY_A, PARITY_A) == 0


def test_sequence_resamples_to_25fps_and_centres():
    t = np.arange(61) * 1000 / 30
    f = (t / 1000 + 5)[:, None].astype(np.float32)
    s = to_sequence(t, f)
    assert len(s) == 51
    assert s.shape[1] == 2  # position + velocity
    assert abs(s[:, 0].mean()) < 1e-5  # positions are centred


def test_verdict_rules():
    assert verdict(0.97, 0.93) == "go"
    assert verdict(0.92, 0.85).startswith("go with 5")
    assert verdict(0.85, None).startswith("no-go")
    assert verdict(None, None) == "no data"


def test_prototype_head_separates_clusters():
    rng = np.random.default_rng(0)
    centres = {p: rng.normal(size=64) for p in "abc"}
    head = PrototypeHead()
    for p, c in centres.items():
        for _ in range(3):
            head.add(p, c + rng.normal(0, 0.1, 64))
    head.calibrate()
    d = head.classify(centres["b"] + rng.normal(0, 0.1, 64))
    assert d.ranked[0][0] == "b" and d.kind == "accept"


def test_end_to_end_on_synthetic(tmp_path):
    write_synthetic(tmp_path, participants=2)
    clips = load_dir(tmp_path)
    r = evaluate(clips)
    assert len(r["participants"]) == 2
    same = r["pooled"]["same_session"]
    assert same["n"] == 2 * 8 * 2
    assert same["top1"] > 0.9
    assert r["pooled"]["idle_minutes"] == 40.0
    md = to_markdown(r | {"synthetic": True})
    assert "SYNTHETIC" in md and "pooled" in md


def test_rejects_unknown_format(tmp_path):
    (tmp_path / "x.mouna.json").write_text(json.dumps({"format": "nope"}))
    with pytest.raises(ValueError):
        load_dir(tmp_path)


def test_dtw_head_rejects_untaught():
    rng = np.random.default_rng(1)
    head = DTWHead()
    base = {p: rng.normal(0, 0.1, size=(50, 4)).cumsum(0) for p in "xyz"}
    for p, b in base.items():
        for _ in range(3):
            head.add(p, b + rng.normal(0, 0.01, b.shape))
    head.calibrate()
    assert head.classify(base["y"] + rng.normal(0, 0.01, (50, 4))).ranked[0][0] == "y"
    assert head.classify(rng.normal(0, 0.1, size=(50, 4)).cumsum(0) * 3).kind != "accept"


def test_plan_a_with_embeddings(tmp_path):
    write_synthetic(tmp_path, participants=1)
    clips = load_dir(tmp_path)
    rng = np.random.default_rng(3)
    centre = {p: rng.normal(size=32) for p in {c.phrase for c in clips if c.phrase}}
    emb = {c.id: centre[c.phrase] + rng.normal(0, 0.2, 32) for c in clips if c.kind == "protocol"}
    r = evaluate(clips, emb)
    assert r["head"] == "plan-a-prototype"
    assert r["pooled"]["same_session"]["top1"] > 0.9


def test_more_shots_on_the_same_test_set(tmp_path):
    write_synthetic(tmp_path, participants=1, noise=0.03)
    clips = load_dir(tmp_path)
    one = evaluate(clips, teach_reps=1, test_after=3)["pooled"]["same_session"]
    three = evaluate(clips, teach_reps=3, test_after=3)["pooled"]["same_session"]
    assert one["n"] == three["n"]  # identical held-out clips
    assert three["top1"] >= one["top1"]


def test_sequence_parity_with_lab():
    # Same input and expected value as lab/src/core/core.test.ts ("parity ... sequence").
    t = np.array([0.0, 40.0, 80.0, 120.0])
    f = np.array([[0.0, 1.0], [0.5, 1.5], [1.5, 1.0], [1.0, 0.0]], dtype=np.float32)
    s = to_sequence(t, f)
    assert s.shape == (4, 4)
    assert float(np.abs(s).sum()) == pytest.approx(SEQUENCE_PARITY, abs=1e-4)


def _clustered_clips(phrases, reps=5, seed=0):
    from mouna_harness.io import Clip

    rng = np.random.default_rng(seed)
    centres = {p: rng.normal(size=32) for p in phrases}
    clips, emb = [], {}
    for p in phrases:
        for rep in range(1, reps + 1):
            cid = f"x-{p}-{rep}"
            emb[cid] = centres[p] + rng.normal(0, 0.15, 32)
            clips.append(Clip(cid, "x", "s1", "protocol", p, rep, "kn", np.arange(3.0), np.zeros((3, 2), np.float32), [], 100.0))
    return clips, emb


def test_open_set_rejects_well_separated_untaught_words():
    from mouna_harness.openset import Backend, open_set

    clips, emb = _clustered_clips(list("abcdef"))
    r = open_set(clips, Backend(emb, None), vocab=3, draws=5)
    assert r["taught_words"]["top1"] == 1.0
    assert r["untaught_words"]["spoken_as_a_taught_phrase"] == 0.0


def test_sentence_list_decodes_listed_chains():
    from mouna_harness.openset import SENTENCES, Backend, chains

    words = sorted({w for s in SENTENCES for w in s})
    clips, emb = _clustered_clips(words)
    r = chains(clips, Backend(emb, None))
    assert r["2_blocks"]["with_sentence_list"] == 1.0
    assert r["3_blocks"]["n"] > 0


def test_cached_dtw_head_matches_dtw_head():
    from mouna_harness.openset import CachedDTWHead

    rng = np.random.default_rng(1)
    seqs = {f"{p}{i}": rng.normal(size=(12, 2)).astype(np.float32) + (3 if p == "b" else 0) for p in "ab" for i in range(3)}
    dist = {(a, b): dtw(sa, sb) for a, sa in seqs.items() for b, sb in seqs.items() if a != b}
    plain, cached = DTWHead(), CachedDTWHead(dist)
    for k, s in seqs.items():
        if k != "a2":
            plain.add(k[0], s)
            cached.add(k[0], k)
    assert plain.calibrate() == pytest.approx(cached.calibrate())
    a, b = plain.classify(seqs["a2"]), cached.classify("a2")
    assert a.kind == b.kind
    assert [p for p, _ in a.ranked] == [p for p, _ in b.ranked]
    assert [d for _, d in a.ranked] == pytest.approx([d for _, d in b.ranked])
