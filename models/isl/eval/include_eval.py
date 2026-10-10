# Sign mode on INCLUDE's test-split videos (clips never seen in training; the signers were) through the app's pipeline.
#   python include_eval.py extract   # fetch the test videos of CATEGORIES from Zenodo (range reads), keypoints -> cache/
#   python include_eval.py score     # whole clips, then continuous signing with the old and new segmenter -> results.json
#   python include_eval.py vectors   # real keypoint streams + expected signs for the Kotlin test (SignSegmenterTest)
#   python include_eval.py device    # streams for the phone (app.mouna.SIGN --es stream) + the reference's answers
import csv, glob, json, os, random, sys, numpy as np
from concurrent.futures import ProcessPoolExecutor
import signs as S

HERE = S.HERE
CACHE = os.path.join(HERE, "cache"); os.makedirs(CACHE, exist_ok=True)
VIDEOS = os.environ.get("INCLUDE_VIDEOS", os.path.join(CACHE, "videos")); os.makedirs(VIDEOS, exist_ok=True)
SPLIT = os.environ.get("INCLUDE_TEST_CSV", os.path.join(HERE, "..", "meta", "Train_Test_Split", "test_include.csv"))
ZIPS = ["Greetings_1of2.zip", "Greetings_2of2.zip", "Pronouns_1of2.zip", "Pronouns_2of2.zip"]
ZENODO = "https://zenodo.org/records/4010759/files/{}?download=1"  # INCLUDE, CC BY 4.0
FRAMINGS = {"landscape": None, "portrait": None}  # see frames()


def name(path): return path.replace("/", "__").replace(" ", "_")


def jobs():
    from remotezip import RemoteZip
    want = {r["FilePath"]: r["Word"] for r in csv.DictReader(open(SPLIT))}
    out = []
    for z in ZIPS:
        with RemoteZip(ZENODO.format(z)) as rz:
            out += [(want[i.filename], i.filename, z) for i in rz.infolist() if i.filename in want]
    return out


def frames(video, framing):
    """landscape: the training videos' framing at the app's resolution (640x360).
    portrait: the phone's 480x640 frame, a centre 3:4 crop of the same video."""
    import cv2
    cap = cv2.VideoCapture(video); fps = cap.get(cv2.CAP_PROP_FPS) or 25; i = 0
    while True:
        ok, img = cap.read()
        if not ok: break
        h, w = img.shape[:2]
        if framing == "landscape":
            img = cv2.resize(img, (640, 360), interpolation=cv2.INTER_AREA)
        else:
            cw = int(h * 3 / 4); x0 = (w - cw) // 2
            img = cv2.resize(img[:, x0:x0 + cw], (480, 640), interpolation=cv2.INTER_AREA)
        yield cv2.cvtColor(img, cv2.COLOR_BGR2RGB), int(i * 1000 / fps); i += 1
    cap.release()


def extract(job):
    word, path, z = job
    video = os.path.join(VIDEOS, name(path))
    if not os.path.exists(video):
        from remotezip import RemoteZip
        with RemoteZip(ZENODO.format(z)) as rz: data = rz.read(path)
        open(video + ".part", "wb").write(data); os.rename(video + ".part", video)
    for f in FRAMINGS:
        out = os.path.join(CACHE, f"{name(path)}.{f}.npz")
        if os.path.exists(out): continue
        seq = S.keypoints(frames(video, f))
        np.savez(out, word=S.key(word), points=np.stack([p if p is not None else np.full(54, np.nan, np.float32) for p, _, _ in seq]),
                 hands=np.array([n for _, n, _ in seq]), wrists=np.stack([w if w is not None else np.full(4, np.nan, np.float32) for _, _, w in seq]))
    return path


def load(framing):
    clips = []
    for f in sorted(glob.glob(os.path.join(CACHE, f"*.{framing}.npz"))):
        d = np.load(f)
        stream = [(None if np.isnan(p[0]) else p, int(n), None if np.isnan(w[0]) else w) for p, n, w in zip(d["points"], d["hands"], d["wrists"])]
        clips.append((str(d["word"]), stream))
    return clips


def continuous(clips, cut, speak, reps=20, per=6, seed=0):
    """A person signing `per` words in a row: held-out clips joined into one stream (each starts and ends with the
    hands down). Every closed sign is scored against the clip it mostly covers."""
    rnd = random.Random(seed); st = dict(signs=0, spoken_right=0, spoken_wrong=0, offered_with_right=0, offered_without=0, missed=0)
    for _ in range(reps):
        pick = rnd.sample(clips, per)
        stream, owner = [], []
        for ci, (_, s) in enumerate(pick): stream += s; owner += [ci] * len(s)
        hit = set(); st["signs"] += per
        for seg in cut(stream):
            g = S.classify([stream[j][0] for j in seg])
            o = [owner[j] for j in seg]; ci = max(set(o), key=o.count); hit.add(ci); t = pick[ci][0]
            if S.speaks(g, speak): st["spoken_right" if g[0][0] == t else "spoken_wrong"] += 1
            else: st["offered_with_right" if t in [k for k, _ in g[:3]] else "offered_without"] += 1
        st["missed"] += per - len(hit)
    return st


def score():
    res = {"source": "INCLUDE test split (clips held out from training, signers not), categories " + ", ".join(sorted({z.split('_')[0] for z in ZIPS})),
           "model": "AI4Bharat OpenHands SL-GCN, INCLUDE checkpoint (models/isl)", "pipeline": "the app's: Signer, Isl, MounaApp.signed (models/isl/eval/signs.py)"}
    for f in FRAMINGS:
        clips = load(f)
        r = {"clips": len(clips), "words": len({w for w, _ in clips})}
        top = [S.classify([p for p, _, _ in s if p is not None]) for _, s in clips]
        for k in (1, 3, 5): r[f"whole_clip_top{k}"] = sum(w in [x for x, _ in g[:k]] for (w, _), g in zip(clips, top))
        r["hands_seen_frames"] = round(float(np.mean([np.mean([n > 0 for p, n, _ in s if p is not None]) for _, s in clips])), 3)
        r["old_segmenter_fired_on_clips"] = sum(bool(S.old_segments(s)) for _, s in clips)
        r["new_segmenter_fired_on_clips"] = sum(bool(S.segments(s)) for _, s in clips)
        r["continuous_old"] = continuous(clips, S.old_segments, S.OLD_SPEAK)
        r["continuous_new_speak0.6"] = continuous(clips, S.segments, S.OLD_SPEAK)
        r["continuous_new"] = continuous(clips, S.segments, S.SPEAK)
        res[f] = r
        print(f, json.dumps(r, indent=1))
    json.dump(res, open(os.path.join(HERE, "results.json"), "w"), indent=1)


def vectors():
    """Two real continuous streams (portrait framing, as on the phone) and the signs the reference cuts from them."""
    clips = load("portrait"); rnd = random.Random(1); out = []
    r4 = lambda a: None if a is None else [round(float(x), 4) for x in a]
    for _ in range(2):
        stream = []
        for _, s in rnd.sample(clips, 4): stream += s
        stream = [(None if p is None else np.array(r4(p), np.float32), n, None if w is None else np.array(r4(w), np.float32)) for p, n, w in stream]
        out.append({"points": [r4(p) for p, _, _ in stream], "wrists": [r4(w) for _, _, w in stream],
                    "signs": S.segments(stream)})
    dst = os.path.join(S.ROOT, "android", "app", "src", "test", "resources", "sign_segments.json")
    json.dump({"note": "models/isl/eval/include_eval.py vectors; INCLUDE (CC BY 4.0) held-out keypoints, no video", "streams": out},
              open(dst, "w"), separators=(",", ":"))
    print(dst, [len(v["signs"]) for v in out], "signs")


def device(n=5):
    """Streams for the phone (MainActivity: app.mouna.SIGN --es stream) and what the reference expects from each."""
    clips = load("portrait"); rnd = random.Random(2); d = os.path.join(CACHE, "device"); os.makedirs(d, exist_ok=True)
    r4 = lambda a: None if a is None else [round(float(x), 4) for x in a]
    want = {}
    for k in range(n):
        pick = rnd.sample(clips, 5); stream = []
        for _, s in pick: stream += s
        stream = [(None if p is None else np.array(r4(p), np.float32), m, None if w is None else np.array(r4(w), np.float32)) for p, m, w in stream]
        json.dump({"points": [r4(p) for p, _, _ in stream], "wrists": [r4(w) for _, _, w in stream]}, open(os.path.join(d, f"stream{k}.json"), "w"))
        want[f"stream{k}"] = {"signed": [w for w, _ in pick],
                              "expected": [[len(seg)] + S.classify([stream[j][0] for j in seg])[:3] for seg in S.segments(stream)]}
    json.dump(want, open(os.path.join(d, "expected.json"), "w"), indent=1)
    print(json.dumps(want, indent=1))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "score"
    if cmd == "extract":
        js = jobs(); print(len(js), "test videos")
        with ProcessPoolExecutor(int(os.environ.get("J", "6"))) as ex:
            for p in ex.map(extract, js): print("ok", p, flush=True)
    elif cmd == "score": score()
    elif cmd == "vectors": vectors()
    elif cmd == "device": device()
