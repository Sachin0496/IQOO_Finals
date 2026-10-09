"""Lip geometry, quality gate and mouth crop: a port of lab/src/core/lips.ts, gate.ts and vision/crop.ts,
used to turn recorded videos into the same clips the Lab records live."""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

OUTER = [61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146]
INNER = [78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95]
LIPS = OUTER + INNER
FEATURE_DIM = 2 * len(LIPS)
CROP = 96
CROP_IOD = 1.1


@dataclass
class FaceFrame:
    features: np.ndarray
    aperture: float
    center: tuple[float, float]
    roll: float
    iod: float
    yaw: float


def analyse_face(xy: np.ndarray, w: int, h: int) -> FaceFrame:
    """xy: (478, 2) normalised landmarks."""
    p = xy * np.array([w, h], dtype=np.float64)
    eye_r, eye_l = p[33], p[263]
    iod = float(np.hypot(*(eye_l - eye_r)))
    roll = math.atan2(eye_l[1] - eye_r[1], eye_l[0] - eye_r[0])
    c, s = math.cos(-roll), math.sin(-roll)
    center = p[OUTER].mean(axis=0)
    d = p[LIPS] - center
    rot = np.stack([d[:, 0] * c - d[:, 1] * s, d[:, 0] * s + d[:, 1] * c], axis=1) / iod
    width = float(np.hypot(*(p[291] - p[61])))
    aperture = float(np.hypot(*(p[14] - p[13]))) / width if width > 0 else 0.0
    mid = (eye_r + eye_l) / 2
    along = ((p[1][0] - mid[0]) * math.cos(roll) + (p[1][1] - mid[1]) * math.sin(roll)) / (iod / 2)
    yaw = math.degrees(math.asin(max(-1.0, min(1.0, along))))
    return FaceFrame(rot.reshape(-1).astype(np.float32), aperture, (float(center[0]), float(center[1])), roll, iod, yaw)


def lip_motion(a: np.ndarray, b: np.ndarray) -> float:
    d = (a - b).reshape(-1, 2)
    return float(np.hypot(d[:, 0], d[:, 1]).mean())


def crop_matrix(f: FaceFrame) -> np.ndarray:
    """2x3 affine, source pixels -> crop pixels, identical to MouthCropper's canvas transform."""
    s = CROP / (CROP_IOD * f.iod)
    c, n = math.cos(-f.roll), math.sin(-f.roll)
    a = np.array([[s * c, -s * n], [s * n, s * c]])
    t = np.array([CROP / 2, CROP / 2]) - a @ np.array(f.center)
    return np.hstack([a, t[:, None]])


LIMITS = {"min_duration_ms": 400, "max_duration_ms": 4500, "min_face_ratio": 0.9, "min_iod_px": 55, "max_yaw_deg": 25, "min_motion": 0.12}


def check_quality(duration_ms: float, face_ratio: float, median_iod: float, max_abs_yaw: float, motion: float, limits: dict = LIMITS) -> list[str]:
    issues = []
    if duration_ms < limits["min_duration_ms"]:
        issues.append("too_short")
    if duration_ms > limits["max_duration_ms"]:
        issues.append("too_long")
    if face_ratio < limits["min_face_ratio"]:
        issues.append("face_lost")
    if median_iod < limits["min_iod_px"]:
        issues.append("too_far")
    if max_abs_yaw > limits["max_yaw_deg"]:
        issues.append("head_turned")
    if motion < limits["min_motion"]:
        issues.append("lips_still")
    return issues
