# Signers INCLUDE never had: the government's ISL dictionary (ISLRTC, one clip per word) and a YouTube teacher
# signing 11 greetings in a row ("Indian sign language - Greetings", captions give the timing).
#   python new_signers.py   # needs yt-dlp; -> new_signers.json
import json, os, cv2, numpy as np
import signs as S

HERE = S.HERE
CACHE = os.path.join(HERE, "cache", "new_signers"); os.makedirs(CACHE, exist_ok=True)
ISLRTC = {"yBQA3coUjH0": "hello", "L37McuHckSI": "goodmorning", "8hPNnF65IMg": "goodafternoon", "vB72KncjZis": "evening",
          "zxwuZPkYjrQ": "goodnight", "wK8amEJAK1Y": "thankyou", "Yz4vZ5ALDfw": "thankyou"}
SHORT = "j5c7Ls3Lgyk"  # 30 fps; caption intervals in frames, found from the burnt-in captions
CAPTIONS = [("namaste", 23, 64), ("hi", 78, 112), ("hello", 122, 166), ("goodmorning", 178, 242), ("goodafternoon", 256, 310),
            ("goodevening", 323, 384), ("goodnight", 399, 469), ("sorry", 483, 524), ("please", 538, 592), ("thankyou", 606, 677),
            ("welcome", 695, 745)]


def fetch(vid):
    out = os.path.join(CACHE, vid + ".mp4")
    if not os.path.exists(out):
        os.system(f'yt-dlp -q --no-warnings -f "bv*[height<=720]/b[height<=720]/b" -o "{out}" "https://www.youtube.com/watch?v={vid}"')
    return out


def keypoints(path, size=None):
    cap = cv2.VideoCapture(path); fps = cap.get(cv2.CAP_PROP_FPS) or 25; fr = []; i = 0
    while True:
        ok, img = cap.read()
        if not ok: break
        if size: img = cv2.resize(img, size, interpolation=cv2.INTER_AREA)
        fr.append((cv2.cvtColor(img, cv2.COLOR_BGR2RGB), int(i * 1000 / fps))); i += 1
    return S.keypoints(fr)


def rank(w, g):
    k = [x for x, _ in g]; return k.index(w) + 1 if w in k else None


if __name__ == "__main__":
    vocab = {S.key(l) for l in S.LABELS}
    res = {"model": "AI4Bharat OpenHands SL-GCN, INCLUDE checkpoint (the app's)", "islrtc": [], "youtube_cut_at_captions": [], "youtube_app": []}
    for vid, w in ISLRTC.items():
        seq = keypoints(fetch(vid), (640, 360))
        g = S.classify([p for p, _, _ in seq if p is not None])
        res["islrtc"].append({"video": vid, "word": w, "top3": g[:3], "rank": rank(w, g)})
    seq = keypoints(fetch(SHORT))
    for w, a, b in CAPTIONS:
        if w not in vocab: res["youtube_cut_at_captions"].append({"word": w, "in_vocabulary": False}); continue
        g = S.classify([seq[j][0] for j in range(a, b + 1) if seq[j][0] is not None])
        res["youtube_cut_at_captions"].append({"word": w, "in_vocabulary": True, "top3": g[:3], "rank": rank(w, g)})
    res["youtube_raised_frames"] = round(float(np.mean([S.Segmenter.raised(p, x) for p, _, x in seq])), 3)
    for s in S.segments(seq):
        g = S.classify([seq[j][0] for j in s])
        res["youtube_app"].append({"frames": [s[0], s[-1]], "top3": g[:3], "spoken": S.speaks(g)})
    json.dump(res, open(os.path.join(HERE, "new_signers.json"), "w"), indent=1)
    t = lambda xs: (sum(x.get("rank") == 1 for x in xs), sum(x.get("rank") is not None for x in xs), sum("rank" in x for x in xs))
    print("ISLRTC top1 %d top5 %d of %d" % t(res["islrtc"]), "| YouTube cut at captions top1 %d top5 %d of %d" % t(res["youtube_cut_at_captions"]),
          "| app on YouTube:", [x["top3"][0][0] for x in res["youtube_app"]])
