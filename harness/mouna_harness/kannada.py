"""Import the Kannada multi-speaker lip-reading dataset (Divya P, Mendeley Data, 2026, doi:10.17632/zbzrbs89pz.1,
CC BY 4.0) as Mouna Lab exports, so the harness scores it exactly like our own recordings.

Each speaker becomes an anonymous participant (k01...k08); every clip is a protocol clip in session s1, numbered per
class in file order. Teach on reps 1-3, test on the rest: the same-session protocol. The videos are voiced speech.

    python -m mouna_harness import-kannada data/kannada --out data/kannada-lab
"""

from __future__ import annotations

import json
import re
import tempfile
import zipfile
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

from .lips import CROP, FEATURE_DIM, LIPS, analyse_face, check_quality, crop_matrix, lip_motion

MODEL = Path(__file__).resolve().parents[2] / "lab" / "public" / "models" / "face_landmarker.task"
MIN_PER_CLASS = 4  # 3 to teach, at least 1 to test


def _label(folder: str) -> str:
    return re.sub(r"[^a-z]+", "_", folder.strip().lower()).strip("_")


def _process_video(path: Path, landmarker) -> dict | None:
    import cv2
    import mediapipe as mp

    cap = cv2.VideoCapture(str(path))
    frames, ts = 0, []
    feats, aps, iods, yaws, motion, crops = [], [], [], [], [], []
    prev = None
    last_ms = -1
    while True:
        ok, bgr = cap.read()
        if not ok:
            break
        frames += 1
        ms = cap.get(cv2.CAP_PROP_POS_MSEC)
        ms = ms if ms > last_ms else last_ms + 33.3
        last_ms = ms
        h, w = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        res = landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), int(ms))
        if not res.face_landmarks:
            prev = None
            continue
        xy = np.array([[p.x, p.y] for p in res.face_landmarks[0]])
        f = analyse_face(xy, w, h)
        motion.append(lip_motion(f.features, prev) if prev is not None else 0.0)
        prev = f.features
        grey = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        crops.append(cv2.warpAffine(grey, crop_matrix(f), (CROP, CROP), flags=cv2.INTER_LINEAR))
        ts.append(ms)
        feats.append(f.features)
        aps.append(f.aperture)
        iods.append(f.iod)
        yaws.append(abs(f.yaw))
    cap.release()
    if len(ts) < 2:
        return None
    duration = ts[-1] - ts[0]
    stats = {
        "durationMs": duration,
        "frames": frames,
        "faceRatio": len(ts) / max(frames, 1),
        "medianIod": float(np.median(iods)),
        "maxAbsYaw": float(max(yaws)),
        "motionEnergy": float(sum(motion)),
    }
    # Recorded videos run longer than a push-to-talk clip; only the face checks apply.
    issues = [i for i in check_quality(duration, stats["faceRatio"], stats["medianIod"], stats["maxAbsYaw"], stats["motionEnergy"]) if i not in ("too_long",)]
    t0 = ts[0]
    return {
        "t": [round(t - t0, 1) for t in ts],
        "features": [np.round(x, 4).tolist() for x in feats],
        "aperture": [round(a, 4) for a in aps],
        "stats": stats,
        "issues": issues,
        "crops": np.stack(crops).astype(np.uint8),
    }


def _import_speaker(args: tuple[Path, str, Path]) -> str:
    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions, vision

    zpath, pid, out = args
    opts = vision.FaceLandmarkerOptions(base_options=BaseOptions(model_asset_path=str(MODEL)), running_mode=vision.RunningMode.VIDEO, num_faces=1)
    z = zipfile.ZipFile(zpath)
    by_class: dict[str, list[str]] = {}
    for name in sorted(n for n in z.namelist() if n.lower().endswith(".mp4")):
        by_class.setdefault(_label(Path(name).parent.name), []).append(name)
    by_class = {k: v for k, v in by_class.items() if len(v) >= MIN_PER_CLASS}
    if not by_class:
        return f"{pid}: skipped (fewer than {MIN_PER_CLASS} clips per class)"

    clips, blobs, offset = [], [], 0
    with tempfile.TemporaryDirectory() as tmp:
        for phrase, names in by_class.items():
            for rep, name in enumerate(names, start=1):
                video = Path(tmp) / "clip.mp4"
                video.write_bytes(z.read(name))
                with vision.FaceLandmarker.create_from_options(opts) as lm:  # fresh timestamps per video
                    c = _process_video(video, lm)
                if c is None:
                    continue
                crops = c.pop("crops")
                blobs.append(crops.tobytes())
                clips.append(
                    {
                        "id": f"{pid}-{phrase}-{rep}",
                        "kind": "protocol",
                        "phrase": phrase,
                        "rep": rep,
                        "spokenLang": "kn",
                        "createdAt": 0,
                        **c,
                        "cropOffset": offset,
                        "cropFrames": len(crops),
                    }
                )
                offset += crops.size
    base = f"{pid}_s1"
    doc = {
        "format": "mouna-lab/1",
        "source": "Kannada multi-speaker lip-reading dataset, Divya P, Mendeley Data 2026, doi:10.17632/zbzrbs89pz.1, CC BY 4.0 (voiced speech)",
        "participant": pid,
        "session": "s1",
        "featureDim": FEATURE_DIM,
        "landmarks": LIPS,
        "crop": {"size": CROP, "file": f"{base}.crops.bin"},
        "clips": clips,
    }
    (out / f"{base}.crops.bin").write_bytes(b"".join(blobs))
    (out / f"{base}.mouna.json").write_text(json.dumps(doc), encoding="utf-8")
    return f"{pid}: {len(clips)} clips, {len(by_class)} classes"


def import_dataset(root: Path, out: Path, workers: int = 4) -> None:
    out.mkdir(parents=True, exist_ok=True)
    zips = sorted(Path(root).glob("SPEAKER_*.zip"))
    jobs = [(z, f"k{int(re.findall(r'\d+', z.stem)[0]):02d}", out) for z in zips]
    with ProcessPoolExecutor(workers) as pool:
        for line in pool.map(_import_speaker, jobs):
            print(line, flush=True)


def breakdown(results: Path, labels: Path) -> dict:
    """Top-1 by utterance type (word vs two-word phrase) and the hardest classes, from a results file."""
    import csv

    kind = {_label(r["class_id"]): r["utterance_type"].lower() for r in csv.DictReader(open(labels, encoding="utf-8"))}
    acc: dict[str, list[int]] = {}
    per: dict[str, list[int]] = {}
    r = json.loads(Path(results).read_text(encoding="utf-8"))
    for p in r["participants"]:
        for truth, row in p["same_session"]["confusion"].items():
            n, ok = sum(row.values()), row.get(truth, 0)
            # one class folder is spelt differently from the label file (DHANYAVADA); it is a single word
            k = kind.get(truth, "word")
            acc.setdefault(k, [0, 0])
            per.setdefault(truth, [0, 0])
            acc[k][0] += ok
            acc[k][1] += n
            per[truth][0] += ok
            per[truth][1] += n
    hardest = sorted(per.items(), key=lambda kv: kv[1][0] / kv[1][1])[:4]
    return {
        "head": r["head"],
        "by_type": {k: {"top1": round(a / b, 4), "n": b} for k, (a, b) in acc.items()},
        "hardest": [{"class": k, "top1": round(a / b, 4), "n": b} for k, (a, b) in hardest],
    }
