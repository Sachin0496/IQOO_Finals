import numpy as np
import pytest

from mouna_harness import core, farfrr, finale
from mouna_harness.io import Clip


def _arrays(kind, S, tr, neg=5.0):
    S = np.asarray(S, dtype=float)
    return {
        "kind": np.asarray(kind),
        "tr": np.asarray(tr),
        "S": S,
        "NEG": np.full(len(kind), neg),
        "sp": np.zeros(len(kind), int),
    }


def test_far_rises_and_frr_falls_with_q():
    rng = np.random.default_rng(0)
    n = 200
    a = {
        "kind": rng.integers(0, 3, n),
        "tr": rng.integers(-1, 3, n),
        "S": np.sort(rng.uniform(0.3, 4.0, (n, 3)), axis=1),
        "NEG": rng.uniform(0.3, 5.0, n),
        "sp": np.zeros(n, int),
    }
    rows = farfrr.curve(a)
    far = [r["far"] for r in rows]
    frr = [r["frr"] for r in rows]
    assert all(x <= y for x, y in zip(far, far[1:]))
    assert all(x >= y for x, y in zip(frr, frr[1:]))


def test_rates_on_hand_built_rows():
    # r0 taught right at q>=0.5; r1 taught, wrong word, speaks from 0.9; r2 taught right from 1.2;
    # r3 untaught from 0.8; r4 untaught from 1.5 (margins and negatives chosen so only these rules fire)
    a = _arrays(
        kind=[0, 0, 0, 1, 1],
        S=[[0.5, 1.0, 2.0], [0.9, 2.0, 3.0], [1.2, 2.0, 3.0], [0.8, 2.0, 3.0], [1.5, 3.0, 4.0]],
        tr=[0, 1, 0, -1, -1],
    )
    rows = farfrr.curve(a, m=1.3, rho=1.2, qs=[0.5, 1.0, 1.5])
    assert [r["far"] for r in rows] == pytest.approx([0.0, 0.5, 1.0], abs=1e-4)
    assert [r["frr"] for r in rows] == pytest.approx([0.6667, 0.6667, 0.3333], abs=1e-4)
    assert [r["taught_spoken_wrong"] for r in rows] == pytest.approx([0.0, 0.3333, 0.3333], abs=1e-4)
    assert set(rows[0]) == {"q", "far", "frr", "taught_spoken_wrong"}


def test_eer_interpolates_on_a_crossing():
    rows = [
        {"q": 0.5, "far": 0.0, "frr": 0.9, "taught_spoken_wrong": 0.0},
        {"q": 1.0, "far": 0.0, "frr": 0.5, "taught_spoken_wrong": 0.0},
        {"q": 2.0, "far": 0.4, "frr": 0.1, "taught_spoken_wrong": 0.0},
    ]
    out = farfrr.eer(rows)
    # FAR - FRR goes -0.5 -> +0.3 between q=1.0 and q=2.0: t = 0.625
    assert out == {"eer": 0.25, "q": 1.625, "interpolated": True}


def test_eer_without_crossing_returns_closest_row():
    rows = [
        {"q": 1.0, "far": 0.1, "frr": 0.6, "taught_spoken_wrong": 0.0},
        {"q": 2.0, "far": 0.2, "frr": 0.3, "taught_spoken_wrong": 0.0},
        {"q": 3.0, "far": 0.25, "frr": 0.27, "taught_spoken_wrong": 0.0},
    ]
    assert farfrr.eer(rows) == {"eer": 0.26, "q": 3.0, "interpolated": False}


def test_empty_masks_give_none():
    no_taught = _arrays(kind=[1, 1, 2], S=[[0.5, 2.0, 3.0]] * 3, tr=[-1, -1, 0])
    rows = farfrr.curve(no_taught, qs=[1.0])
    assert rows[0]["frr"] is None and rows[0]["far"] == 1.0
    no_untaught = _arrays(kind=[0, 0, 2], S=[[0.5, 2.0, 3.0]] * 3, tr=[0, 0, 0])
    rows = farfrr.curve(no_untaught, qs=[1.0])
    assert rows[0]["far"] is None and rows[0]["frr"] is not None
    assert farfrr.eer([{"q": 1.0, "far": None, "frr": None, "taught_spoken_wrong": None}]) == {
        "eer": None,
        "q": None,
        "interpolated": False,
    }


def test_operating_point_is_the_shipped_constant_row():
    a = _arrays(kind=[0, 1, 1], S=[[0.5, 2.0, 3.0], [1.6, 3.0, 4.0], [1.8, 4.0, 5.0]], tr=[0, -1, -1])
    assert farfrr.operating_point(a) == farfrr.curve(a, qs=[core.Q_SPEAK["A"]])[0]


def _clustered_clips(phrases, participants, reps=7, seed=0):
    rng = np.random.default_rng(seed)
    centres = {p: rng.normal(size=32) for p in phrases}
    clips, emb = [], {}
    for who in participants:
        for p in phrases:
            for rep in range(1, reps + 1):
                cid = f"{who}-{p}-{rep}"
                emb[cid] = centres[p] + rng.normal(0, 0.15, 32)
                clips.append(Clip(cid, who, "s1", "protocol", p, rep, "kn", np.arange(3.0), np.zeros((3, 2), np.float32), [], 100.0))
    return clips, emb


def test_run_wires_through_finale(monkeypatch):
    monkeypatch.setattr(finale, "DRAWS", 2)  # keep the smoke run fast; the structure is what is checked
    clips, emb = _clustered_clips([f"w{i}" for i in range(20)], ["p1", "p2"])
    doc = farfrr.run(clips, emb)
    assert "2306.02161" in doc["protocol"]
    assert doc["participants"] == ["p1", "p2"]
    for n in (0, 5, 10):
        block = doc[f"negatives_{n}"]
        assert block["curve"] and set(block["eer"]) == {"eer", "q", "interpolated"}
        assert len(block["per_speaker_eer"]["values"]) == 2
        assert set(block["operating_point"]) == {"q", "far", "frr", "taught_spoken_wrong"}
