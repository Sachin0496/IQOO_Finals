"""Teach-your-own-signs, measured on INCLUDE's held-out test clips (engine/SignBook.kt).

Each clip -> Isl.normalise -> the ONNX's "features" output (256-d, L2-normalised). A word is "taught" with k of its
clips; every other clip of the taught words is matched to the nearest taught example (cosine), best per word. The
clips of one word are from different INCLUDE recordings, so this is harder than one person matching their own signs.

Also: a word that was never taught must not be spoken. For that, every word in turn is left out of the taught set and
its clips are matched against the others.

    python teach_eval.py <cache dir with *.npz> <isl_include_slgcn.onnx> [out.json]
"""
import glob, itertools, json, os, random, sys
import numpy as np
import onnxruntime as ort

V, S1, S2 = 27, 3, 4


def normalise(points):
    """Isl.normalise: (T, 54) -> (1, 2, T, 27), centred on the mean shoulder midpoint, scaled by the mean shoulder distance."""
    p = points.reshape(len(points), V, 2)
    mid = (p[:, S1] + p[:, S2]) / 2
    dist = np.linalg.norm(p[:, S1] - p[:, S2], axis=1).mean()
    x = (p - mid.mean(0)) / (dist if dist > 0 else 1.0)
    return x.transpose(2, 0, 1)[None].astype(np.float32)


def load(cache, framing, sess):
    clips = []
    for f in sorted(glob.glob(os.path.join(cache, f"*.{framing}.npz"))):
        d = np.load(f, allow_pickle=True)
        pts = d["points"]
        if len(pts) < 8:
            continue
        feat = sess.run(None, {"keypoints": normalise(pts)})[1][0]
        clips.append((os.path.basename(f).split("__MVI")[0], feat / np.linalg.norm(feat)))
    return clips


def match(feat, book):
    """Best cosine per taught word, best first: [(word, cos), ...] (SignBook.match)."""
    best = {}
    for w, e in book:
        s = float(feat @ e)
        best[w] = max(best.get(w, -1.0), s)
    return sorted(best.items(), key=lambda kv: -kv[1])


def run(clips, shots, seed=0, rounds=20):
    rng = random.Random(seed)
    by = {}
    for w, f in clips:
        by.setdefault(w, []).append(f)
    words = sorted(w for w, fs in by.items() if len(fs) > shots)
    taught_trials, untaught_trials = [], []
    for _ in range(rounds):
        pick = {w: rng.sample(range(len(by[w])), shots) for w in words}
        book = [(w, by[w][i]) for w in words for i in pick[w]]
        for w in words:
            for i, f in enumerate(by[w]):
                if i in pick[w]:
                    continue
                r = match(f, book)
                taught_trials.append((w, r))
                # the same clip when its word was never taught
                r2 = match(f, [(bw, e) for bw, e in book if bw != w])
                untaught_trials.append((w, r2))
    return words, taught_trials, untaught_trials


def rule(trials, untaught, cos, margin):
    """Speak when the best word's cosine >= cos and it leads the next word by >= margin; else offer the top 3."""
    def speaks(r):
        return r[0][1] >= cos and r[0][1] - (r[1][1] if len(r) > 1 else -1) >= margin
    n = len(trials)
    spoken_right = sum(1 for w, r in trials if speaks(r) and r[0][0] == w)
    spoken_wrong = sum(1 for w, r in trials if speaks(r) and r[0][0] != w)
    offered_in = sum(1 for w, r in trials if not speaks(r) and w in [x for x, _ in r[:3]])
    untaught_spoken = sum(1 for _, r in untaught if speaks(r))
    return {
        "cos": cos, "margin": margin,
        "spoken_right": spoken_right / n, "spoken_wrong": spoken_wrong / n,
        "offered_right_inside": offered_in / n, "untaught_spoken": untaught_spoken / len(untaught),
    }


def main():
    cache, model = sys.argv[1], sys.argv[2]
    out = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(__file__), "teach.json")
    sess = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    res = {"what": "Teach-your-own-signs on INCLUDE held-out test clips (different recordings per word): nearest taught "
                   "example by cosine of SL-GCN features. 20 random picks of the taught clips per setting, seed 0.",
           "framings": {}}
    for framing in ("portrait", "landscape"):
        clips = load(cache, framing, sess)
        fr = {"clips": len(clips)}
        for shots in (1, 2, 3):
            words, tt, ut = run(clips, shots)
            top1 = sum(1 for w, r in tt if r[0][0] == w) / len(tt)
            top3 = sum(1 for w, r in tt if w in [x for x, _ in r[:3]]) / len(tt)
            grid = [rule(tt, ut, c, m) for c, m in itertools.product((0.6, 0.7, 0.75, 0.8, 0.85, 0.9), (0.0, 0.02, 0.05, 0.08, 0.1))]
            # the most signs spoken right while untaught words are spoken <= 5% and wrong speaks <= 5%
            ok = [g for g in grid if g["untaught_spoken"] <= 0.05 and g["spoken_wrong"] <= 0.05]
            best = max(ok, key=lambda g: g["spoken_right"]) if ok else None
            fr[f"{shots}_shot"] = {"words": len(words), "trials": len(tt), "top1": top1, "top3": top3, "chosen": best, "grid": grid}
            print(f"{framing} {shots}-shot: {len(words)} words, top-1 {top1:.1%}, top-3 {top3:.1%}; chosen {best}")
        res["framings"][framing] = fr
    json.dump(res, open(out, "w"), indent=1)
    print("->", out)


if __name__ == "__main__":
    main()
