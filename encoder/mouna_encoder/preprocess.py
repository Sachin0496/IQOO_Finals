"""Lab mouth crops -> encoder input.

LRW (the encoder's pre-training data) crops a 128 px mouth ROI from 256 px face frames and centre-crops 88 px.
The Lab crop is 96 px spanning 1.1 inter-ocular distances; scaling it to 128 and centre-cropping 88 approximates
the same field of view. [verify against real clips: try 112 and 144 too]
"""

from __future__ import annotations

import numpy as np
import torch
import torch.nn.functional as F

LRW_ROI = 128
INPUT = 88
FPS = 25  # LRW is 25 fps


def to_encoder_input(crops: np.ndarray, t_ms: np.ndarray, roi: int = LRW_ROI, frames: int | None = None) -> np.ndarray:
    """(frames, 96, 96) uint8 at the camera's rate -> (1, 1, T, 88, 88) float32.

    T follows the clip at 25 fps, or is fixed to `frames` by stretching the clip in time (a static shape the NPU
    and the browser can compile once).
    """
    if frames:
        grid = np.linspace(t_ms[0], t_ms[-1], frames)
    else:
        grid = np.arange(t_ms[0], t_ms[-1] + 1e-6, 1000 / FPS)
    idx = np.clip(np.searchsorted(t_ms, grid), 0, len(t_ms) - 1)
    x = torch.from_numpy(crops[idx].astype(np.float32) / 255.0)[:, None]  # (T, 1, 96, 96)
    x = F.interpolate(x, size=(roi, roi), mode="bilinear", align_corners=False)
    o = (roi - INPUT) // 2
    x = x[:, :, o : o + INPUT, o : o + INPUT]
    return x.permute(1, 0, 2, 3)[None].numpy().astype(np.float32)  # (1, 1, T, 88, 88)
