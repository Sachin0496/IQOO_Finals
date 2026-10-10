"""Sign demos for Teach your signs: one real INCLUDE signer per sign, as a stick figure (no video is bundled).

Each clip -> the app's own keypoints (signs.keypoints: pose + hands, 27 points) -> the signing part (signs.segments,
the app's SignSegmenter) -> centred on the shoulders and scaled by shoulder width (as Isl.normalise) -> 15 fps ->
android/app/src/main/assets/sign_demos.json. INCLUDE (Zenodo 4010759) is CC BY 4.0: credited in the file and on screen.

    python demo_signs.py <videos dir> [<cache dir with Greetings npz>]
"""
import json, os, sys
import numpy as np
import signs as S

ZENODO = "https://zenodo.org/records/4010759/files/{}?download=1"
# demo key -> (zip, file inside it, sign name shown). The smallest clip of each word (found by listing the zips).
CLIPS = {
    "doctor": ("Jobs_1of2.zip", "Jobs/87. Doctor/MVI_8875.MP4", "Doctor"),
    "medicine": ("Society_1of3.zip", "Society/3. Medicine/MVI_8934.MP4", "Medicine"),
    "bathroom": ("Home_2of4.zip", "Home/32. Bathroom/MVI_9025.MP4", "Bathroom"),
    "hot": ("Adjectives_6of8.zip", "Adjectives/87. hot/MVI_9248.MOV", "Hot"),
    "cold": ("Adjectives_6of8.zip", "Adjectives/88. cold/MVI_9331.MOV", "Cold"),
    "family": ("People_3of5.zip", "People/68. Family/MVI_4232.MOV", "Family"),
}
# already extracted by include_eval.py (landscape framing)
CACHED = {"thank_you": ("Greetings__55._Thank_you", "Thank you"), "alright": ("Greetings__50._Alright", "Alright")}
FPS = 15
OUT = os.path.join(S.ASSETS, "sign_demos.json")


def frames(video):
    """The video at the app's landscape resolution (640x360), as include_eval.frames."""
    import cv2
    cap = cv2.VideoCapture(video); fps = cap.get(cv2.CAP_PROP_FPS) or 25; i = 0
    while True:
        ok, img = cap.read()
        if not ok: break
        img = cv2.resize(img, (640, 360), interpolation=cv2.INTER_AREA)
        yield cv2.cvtColor(img, cv2.COLOR_BGR2RGB), int(i * 1000 / fps); i += 1
    cap.release()
    frames.fps = fps


def demo(stream, src_fps):
    """The longest sign the app's segmenter cuts (else the whole clip), normalised, at FPS; missing hands -> null."""
    cut = max(S.segments(stream), key=len, default=None)  # frame indexes
    pts = [stream[i][0] for i in cut] if cut else [p for p, _, _ in stream]
    pts = [p for p in pts if p is not None]
    pts = [np.asarray(p, np.float32) for p in pts]
    a = np.stack(pts).reshape(len(pts), 27, 2)
    mid = ((a[:, 3] + a[:, 4]) / 2).mean(0)
    dist = np.linalg.norm(a[:, 3] - a[:, 4], axis=1).mean()
    step = max(1, round(src_fps / FPS))
    out = []
    for f in a[::step]:
        n = (f - mid) / dist
        row = []
        for v in range(27):
            missing = v >= 7 and not f[v].any()  # a hand not found that frame (all zeros)
            row.append(None if missing else [round(float(n[v, 0]), 3), round(float(n[v, 1]), 3)])
        out.append(row)
    return out


def main():
    videos = sys.argv[1]
    cache = sys.argv[2] if len(sys.argv) > 2 else os.path.join(S.HERE, "cache")
    os.makedirs(videos, exist_ok=True)
    demos = {}
    for key, (z, path, sign) in CLIPS.items():
        video = os.path.join(videos, os.path.basename(path))
        if not os.path.exists(video):
            from remotezip import RemoteZip
            with RemoteZip(ZENODO.format(z)) as rz:
                data = rz.read(path)
            open(video + ".part", "wb").write(data); os.rename(video + ".part", video)
        import cv2
        fps = cv2.VideoCapture(video).get(cv2.CAP_PROP_FPS) or 25
        stream = S.keypoints(frames(video))
        demos[key] = {"sign": sign, "frames": demo(stream, fps)}
        print(key, sign, len(demos[key]["frames"]), "frames")
    for key, (stem, sign) in CACHED.items():
        f = os.path.join(cache, sorted(n for n in os.listdir(cache) if n.startswith(stem) and n.endswith(".landscape.npz"))[0])
        d = np.load(f)
        stream = [(None if np.isnan(p[0]) else p, int(n), None if np.isnan(w[0]) else w) for p, n, w in zip(d["points"], d["hands"], d["wrists"])]
        demos[key] = {"sign": sign, "frames": demo(stream, 25)}
        print(key, sign, len(demos[key]["frames"]), "frames")
    doc = {
        "source": "INCLUDE (AI4Bharat, Zenodo 4010759, CC BY 4.0): one real signer per sign, keypoints only",
        "fps": FPS,
        "points": "27 x [x, y], centred on the shoulders, in shoulder widths, y down; Isl.V order (pose 0,2,5,11,12,13,14; "
                  "each hand 0,4,5,8,9,12,13,16,17,20); null = hand not seen that frame",
        "demos": demos,
    }
    json.dump(doc, open(OUT, "w"), separators=(",", ":"))
    print("->", OUT, round(os.path.getsize(OUT) / 1e3), "KB")


if __name__ == "__main__":
    main()
