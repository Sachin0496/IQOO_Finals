"""python -m mouna_harness evaluate DATA_DIR [--publish deck/data/spike-results.json]
python -m mouna_harness synth OUT_DIR"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from .io import load_dir
from .protocol import evaluate
from .report import to_markdown
from .synth import write_synthetic


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")  # Windows consoles default to cp1252
    ap = argparse.ArgumentParser(prog="mouna_harness")
    sub = ap.add_subparsers(dest="cmd", required=True)
    ev = sub.add_parser("evaluate", help="score Lab exports with the spike protocol")
    ev.add_argument("data", type=Path)
    ev.add_argument("--out", type=Path, default=Path("harness/reports"))
    ev.add_argument("--publish", type=Path, help="also write results here for the deck (refused for synthetic data)")
    ev.add_argument("--embeddings", type=Path, help="encoder embeddings (python -m mouna_encoder embed): score plan A")
    ki = sub.add_parser("import-kannada", help="convert the Kannada lip-reading dataset into Lab exports")
    ki.add_argument("root", type=Path)
    ki.add_argument("--out", type=Path, default=Path("data/kannada-lab"))
    ki.add_argument("--workers", type=int, default=4)
    fc = sub.add_parser("fewshot-curve", help="top-1 when teaching 1, 3 or 5 samples, on the same held-out clips")
    fc.add_argument("data", type=Path)
    fc.add_argument("--embeddings", type=Path)
    fc.add_argument("--shots", type=int, nargs="+", default=[1, 3, 5])
    fc.add_argument("--out", type=Path, required=True)
    kb = sub.add_parser("kannada-breakdown", help="word vs phrase accuracy from a Kannada results file")
    kb.add_argument("results", type=Path)
    kb.add_argument("--labels", type=Path, default=Path("data/kannada/class_labels.csv"))
    kb.add_argument("--out", type=Path, required=True)
    os_ = sub.add_parser("open-set", help="untaught words and chained blocks (Kannada)")
    os_.add_argument("data", type=Path)
    os_.add_argument("--embeddings", type=Path, help="plan A; without it, plan B with a cached DTW matrix")
    os_.add_argument("--dtw-cache", type=Path, default=Path("../data/kannada-dtw.npz"))
    os_.add_argument("--vocab", type=int, default=8)
    os_.add_argument("--draws", type=int, default=20)
    os_.add_argument("--out", type=Path, required=True)
    fc2 = sub.add_parser("finale-core", help="E15-E19: decision core vs today's head, held out by speaker (Kannada)")
    fc2.add_argument("data", type=Path)
    fc2.add_argument("--embeddings", type=Path, required=True)
    fc2.add_argument("--out", type=Path, required=True)
    fs = sub.add_parser("finale-silent", help="E4, E5, E17 on our silent recordings, core.py constants unchanged")
    fs.add_argument("data", type=Path)
    fs.add_argument("--embeddings", type=Path, required=True)
    fs.add_argument("--out", type=Path, required=True)
    fs.add_argument("--publish", action="store_true", help="allow writing under deck/data (refused for synthetic exports)")
    e10 = sub.add_parser("e10", help="time to be understood: Mouna vs writing vs board (docs/protocol.md)")
    e10.add_argument("sheet", type=Path)
    e10.add_argument("--out", type=Path, required=True)
    sub.add_parser("pitch", help="headline numbers with sources -> deck/data/pitch-numbers.json")
    ve = sub.add_parser("vectors", help="parity vectors for ports of core.py (Kotlin)")
    ve.add_argument("--out", type=Path, default=Path(__file__).resolve().parents[1] / "vectors" / "core.json")
    sy = sub.add_parser("synth", help="write synthetic exports for a dry run")
    sy.add_argument("out", type=Path)
    args = ap.parse_args()

    if args.cmd == "import-kannada":
        from .kannada import import_dataset

        import_dataset(args.root, args.out, args.workers)
        return

    if args.cmd == "fewshot-curve":
        clips = load_dir(args.data)
        emb = None
        if args.embeddings:
            z = np.load(args.embeddings)
            emb = dict(zip(z["ids"].tolist(), z["embeddings"]))
        held_out = max(args.shots)
        curve = []
        for k in args.shots:
            r = evaluate(clips, emb, teach_reps=k, test_after=held_out)
            curve.append({"shots": k, "top1": r["pooled"]["same_session"]["top1"], "top3": r["pooled"]["same_session"]["top3"], "n": r["pooled"]["same_session"]["n"]})
            print(curve[-1], flush=True)
        doc = {"head": r["head"], "test": f"reps > {held_out} of every class, identical for every point", "curve": curve}
        args.out.write_text(json.dumps(doc, indent=2), encoding="utf-8")
        return

    if args.cmd == "kannada-breakdown":
        from .kannada import breakdown

        b = breakdown(args.results, args.labels)
        args.out.write_text(json.dumps(b, indent=2), encoding="utf-8")
        print(json.dumps(b, indent=2))
        return

    if args.cmd == "open-set":
        from .openset import Backend, chains, chains_off_list, load_or_build_dtw, open_set, strictness_curve

        clips = load_dir(args.data)
        emb = dist = None
        if args.embeddings:
            z = np.load(args.embeddings)
            emb = dict(zip(z["ids"].tolist(), z["embeddings"]))
        else:
            dist = load_or_build_dtw([c for c in clips if c.kind == "protocol" and c.clean and len(c.features) > 1], args.dtw_cache)
        b = Backend(emb, dist)
        doc = {"head": b.name, "source": "Kannada multi-speaker dataset (voiced speech), same teach/test split as the deck"}
        for key, fn in (
            (f"small_vocabulary_{args.vocab}", lambda: open_set(clips, b, args.vocab, args.draws)),
            ("large_vocabulary", lambda: open_set(clips, b, None, 0)),
            ("chains", lambda: chains(clips, b)),
            ("chains_not_on_list", lambda: chains_off_list(clips, b)),
            ("strictness_curve", lambda: strictness_curve(clips, emb, dist, args.vocab, args.draws)),
        ):
            doc[key] = fn()
            print(key, json.dumps(doc[key], ensure_ascii=False), flush=True)
        args.out.write_text(json.dumps(doc, indent=2), encoding="utf-8")
        return

    if args.cmd == "finale-core":
        from .finale import run

        z = np.load(args.embeddings)
        doc = run(load_dir(args.data), dict(zip(z["ids"].tolist(), z["embeddings"])))
        doc["embeddings"] = args.embeddings.name
        doc["generated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        args.out.write_text(json.dumps(doc, indent=2), encoding="utf-8")
        print(json.dumps(doc, indent=2))
        return

    if args.cmd == "finale-silent":
        from .silent import evaluate as evaluate_silent

        synthetic = any(json.loads(f.read_text(encoding="utf-8")).get("synthetic") for f in args.data.glob("*.mouna.json"))
        if synthetic and "deck" in args.out.parts:
            raise SystemExit("refusing to write synthetic results under deck/")
        z = np.load(args.embeddings)
        doc = evaluate_silent(load_dir(args.data), dict(zip(z["ids"].tolist(), z["embeddings"])))
        doc["synthetic"] = synthetic
        doc["generated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        args.out.write_text(json.dumps(doc, indent=2), encoding="utf-8")
        print(json.dumps(doc["pooled"], indent=2))
        return

    if args.cmd == "e10":
        from .e10 import summarise

        doc = summarise(args.sheet)
        doc["generated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        args.out.write_text(json.dumps(doc, indent=2), encoding="utf-8")
        print(json.dumps(doc, indent=2))
        return

    if args.cmd == "pitch":
        from .pitch import write as write_pitch

        p = write_pitch()
        for c in json.loads(p.read_text(encoding="utf-8"))["claims"]:
            print(f"{c['id']:32} {c['text']}")
        return

    if args.cmd == "vectors":
        from .vectors import write

        print(write(args.out))
        return

    if args.cmd == "synth":
        for p in write_synthetic(args.out):
            print(p)
        return

    clips = load_dir(args.data)
    synthetic = any(json.loads(f.read_text(encoding="utf-8")).get("synthetic") for f in args.data.glob("*.mouna.json"))
    emb = None
    if args.embeddings:
        z = np.load(args.embeddings)
        emb = dict(zip(z["ids"].tolist(), z["embeddings"]))
    results = evaluate(clips, emb)
    results["synthetic"] = synthetic
    results["generated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    (args.out / "results.md").write_text(to_markdown(results), encoding="utf-8")
    print(to_markdown(results))

    if args.publish:
        if synthetic:
            raise SystemExit("refusing to publish synthetic results to the deck")
        args.publish.parent.mkdir(parents=True, exist_ok=True)
        args.publish.write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(f"published -> {args.publish}")


if __name__ == "__main__":
    main()
