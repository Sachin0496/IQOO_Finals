# The app's Sign mode, step for step in Python, so it can be measured on real signers off the phone.
#   keypoints()  = Signer.keypoints   (pose_landmarker_lite + hand_landmarker, VIDEO mode, the app's own .task files)
#   Segmenter    = SignSegmenter      (new: wrist raised above chest level)
#   old_segments = Engine.onSignFrame before this change (hands visible)
#   classify     = Isl.normalise + the ONNX + Isl.top;  speaks() = MounaApp.signed
import json, os, numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ASSETS = os.path.join(ROOT, "android", "app", "src", "main", "assets")
ONNX = os.environ.get("ISL_ONNX", os.path.join(HERE, "..", "isl_include_slgcn.onnx"))
LABELS = json.load(open(os.path.join(HERE, "..", "isl_include_labels.json")))
POSE = [0, 2, 5, 11, 12, 13, 14]
HAND = [0, 4, 5, 8, 9, 12, 13, 16, 17, 20]
SPEAK, MARGIN = 0.9, 0.25          # MounaApp.SIGN_SPEAK, SIGN_MARGIN
OLD_SPEAK = 0.6                    # before this change


def key(label):
    """Label -> comparable word: "55.Thankyou" and "Thank you" both -> "thankyou"."""
    return label.split(".", 1)[-1].strip().lower().replace(" ", "")


def keypoints(frames):
    """frames: iterable of (rgb uint8 HxWx3, t_ms). Returns per frame (points[54] or None, hands, wrists[4] or None)."""
    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions, vision
    pose = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=os.path.join(ASSETS, "pose_landmarker_lite.task")),
        running_mode=vision.RunningMode.VIDEO, num_poses=1))
    hands = vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=os.path.join(ASSETS, "hand_landmarker.task")),
        running_mode=vision.RunningMode.VIDEO, num_hands=2))
    out = []
    for rgb, t in frames:
        img = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb))
        pr = pose.detect_for_video(img, t)
        if not pr.pose_landmarks:
            out.append((None, 0, None)); continue
        body = pr.pose_landmarks[0]
        found = hands.detect_for_video(img, t).hand_landmarks
        H, W = rgb.shape[:2]; sx, sy = W / 16, H / 9
        p = np.zeros(54, np.float32)
        def put(slot, q): p[2 * slot] = q.x * sx; p[2 * slot + 1] = q.y * sy
        for k, idx in enumerate(POSE): put(k, body[idx])
        lw, rw = body[15], body[16]
        d2 = lambda a, b: (a.x - b.x) ** 2 + (a.y - b.y) ** 2
        left = right = None
        for h in sorted(found, key=lambda h: min(d2(h[0], lw), d2(h[0], rw))):
            to_left = d2(h[0], lw) <= d2(h[0], rw)
            if to_left and left is None: left = h
            elif not to_left and right is None: right = h
            elif left is None: left = h
            elif right is None: right = h
        if left is not None:
            for k, idx in enumerate(HAND): put(len(POSE) + k, left[idx])
        if right is not None:
            for k, idx in enumerate(HAND): put(len(POSE) + len(HAND) + k, right[idx])
        wr = np.array([lw.y * sy, rw.y * sy, lw.visibility or 0.0, rw.visibility or 0.0], np.float32)
        out.append((p, len(found), wr))
    pose.close(); hands.close()
    return out


class Segmenter:
    """SignSegmenter.kt, line for line."""
    RAISE, START, STOP, PAD, MAX, MIN, MIN_RAISED, VIS = 1.6, 3, 8, 6, 150, 12, 6, 0.5

    def __init__(self):
        self.recent, self.rec, self.up, self.down, self.n_raised = [], None, 0, 0, 0

    @classmethod
    def raised(cls, p, w):
        if p is None or w is None: return False
        sy = (p[7] + p[9]) / 2
        sw = float(np.hypot(p[6] - p[8], p[7] - p[9]))
        if sw <= 0: return False
        return bool((w[2] > cls.VIS and (w[0] - sy) / sw < cls.RAISE) or (w[3] > cls.VIS and (w[1] - sy) / sw < cls.RAISE))

    def push(self, step):
        """step: (index, points or None, wrists or None). Returns a finished sign as a list of steps with a body."""
        r = self.raised(step[1], step[2])
        if self.rec is None:
            self.up = self.up + 1 if r else 0
            self.recent.append(step)
            if len(self.recent) > self.PAD + self.START:
                self.recent.pop(0)
            if self.up >= self.START:
                self.rec, self.down, self.recent, self.n_raised = list(self.recent), 0, [], self.up
            return None
        self.rec.append(step)
        self.down = 0 if r else self.down + 1
        self.n_raised += r
        if self.down < self.STOP and len(self.rec) < self.MAX: return None
        cur, self.rec, self.up = self.rec, None, 0
        keep = len(cur) - max(0, self.down - self.PAD)
        frames = [s for s in cur[:keep] if s[1] is not None]
        return frames if len(frames) >= self.MIN and self.n_raised >= self.MIN_RAISED else None


def segments(stream):
    """stream: list of (points, hands, wrists). Returns finished signs as lists of frame indexes."""
    seg, out = Segmenter(), []
    for i, (p, _, w) in enumerate(stream):
        s = seg.push((i, p, w))
        if s: out.append([x[0] for x in s])
    return out


def old_segments(stream):
    """Engine.onSignFrame before this change: hands visible >= 3 frames starts, no hands >= 10 frames ends."""
    out, rec, up, down = [], None, 0, 0
    for i, (p, n, _) in enumerate(stream):
        has = p is not None
        if rec is None:
            up = up + 1 if (has and n > 0) else 0
            if up >= 3 and has: rec, down = [i], 0
            continue
        if has: rec.append(i)
        down = down + 1 if (not has or n == 0) else 0
        if down >= 10 or len(rec) >= 150:
            fr = rec[:len(rec) - min(down, len(rec))]
            if len(fr) >= 12: out.append(fr)
            rec, up = None, 0
    return out


def normalise(frames):
    """Isl.normalise: centre on the mean shoulder midpoint, scale by the mean shoulder distance -> (1, 2, T, 27)."""
    a = np.stack(frames).reshape(len(frames), 27, 2)
    c = ((a[:, 3] + a[:, 4]) / 2).mean(0); d = np.linalg.norm(a[:, 3] - a[:, 4], axis=1).mean()
    a = (a - c) * (1 / d if d > 0 else 1)
    return a.transpose(2, 0, 1)[None].astype(np.float32)


_sess = None
def classify(frames):
    """Top 5 (word key, p), labels reading as the same word summed (Isl.top)."""
    global _sess
    if _sess is None:
        import onnxruntime as ort
        _sess = ort.InferenceSession(ONNX, providers=["CPUExecutionProvider"])
    p = _sess.run(None, {"keypoints": normalise(frames)})[0][0]
    by = {}
    for i, l in enumerate(LABELS):
        k = key(l); k = "second" if k == "second(number)" else k
        by[k] = by.get(k, 0.0) + float(p[i])
    return sorted(by.items(), key=lambda kv: -kv[1])[:5]


def speaks(g, speak=SPEAK):
    return g[0][1] >= speak and g[0][1] - (g[1][1] if len(g) > 1 else 0) >= MARGIN
