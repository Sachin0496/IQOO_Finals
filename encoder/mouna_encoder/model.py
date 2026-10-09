"""LipLearner's visual-speech encoder (after Feng et al.), rebuilt for inference.

BatchNorm2d/3d are folded into the convolutions (as in the shipped Core ML model), so every conv has a bias.
Input:  (B, 1, T, 88, 88) grey mouth crops in [0, 1].   Output: (B, 500) embedding (L2-normalise for matching).
"""

from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

# (in, out, stride, has_downsample) for ResNet-18's eight blocks
BLOCKS = [
    (64, 64, 1, False),
    (64, 64, 1, False),
    (64, 128, 2, True),
    (128, 128, 1, False),
    (128, 256, 2, True),
    (256, 256, 1, False),
    (256, 512, 2, True),
    (512, 512, 1, False),
]
EMBED_DIM = 500


class SEBasicBlock(nn.Module):
    def __init__(self, cin: int, cout: int, stride: int, downsample: bool) -> None:
        super().__init__()
        self.conv1 = nn.Conv2d(cin, cout, 3, stride, 1)
        self.conv2 = nn.Conv2d(cout, cout, 3, 1, 1)
        self.downsample = nn.Conv2d(cin, cout, 1, stride) if downsample else None
        self.se_reduce = nn.Conv2d(cout, cout // 16, 1)
        self.se_expand = nn.Conv2d(cout // 16, cout, 1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        out = self.conv2(F.relu(self.conv1(x)))
        residual = self.downsample(x) if self.downsample is not None else x
        w = torch.sigmoid(self.se_expand(F.relu(self.se_reduce(out.mean((2, 3), keepdim=True)))))
        return F.relu(out * w + residual)


class LipEncoder(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.front = nn.Conv3d(1, 64, (5, 7, 7), (1, 2, 2), (2, 3, 3))
        self.pool = nn.MaxPool3d((1, 3, 3), (1, 2, 2), (0, 1, 1))
        self.blocks = nn.Sequential(*(SEBasicBlock(*b) for b in BLOCKS))
        self.bn = nn.BatchNorm1d(512)
        self.gru = nn.GRU(513, 1024, 3, batch_first=True, bidirectional=True)
        self.head = nn.Linear(2048, EMBED_DIM)

    def forward(self, v: torch.Tensor) -> torch.Tensor:
        b, _, t = v.shape[:3]
        x = self.pool(F.relu(self.front(v)))  # (B, 64, T, 22, 22)
        x = x.transpose(1, 2).reshape(b * t, 64, x.shape[-2], x.shape[-1])
        x = self.bn(self.blocks(x).mean((2, 3)))  # (B*T, 512)
        x = x.view(b, t, 512)
        border = torch.ones(b, t, 1, dtype=x.dtype, device=x.device)  # "inside the word" for every frame
        h, _ = self.gru(torch.cat([x, border], -1))
        return self.head(h).mean(1)


class Frontend2D(nn.Module):
    """The visual front end with the Conv3D rewritten as a Conv2D over 5 stacked frames (exactly equivalent).

    Input (T, 5, 88, 88): for each output frame t, frames t-2..t+2 (zero beyond the ends). Output (T, 512).
    Pure 2-D convolutions, so it maps onto the Hexagon NPU.
    """

    def __init__(self, enc: LipEncoder) -> None:
        super().__init__()
        w3 = enc.front.weight  # (64, 1, 5, 7, 7)
        self.front = nn.Conv2d(5, 64, 7, 2, 3)
        with torch.no_grad():
            self.front.weight.copy_(w3[:, 0])
            self.front.bias.copy_(enc.front.bias)
        self.blocks = enc.blocks
        self.bn = enc.bn

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = F.max_pool2d(F.relu(self.front(x)), 3, 2, 1)
        return self.bn(self.blocks(x).mean((2, 3)))


class Temporal(nn.Module):
    """GRU + projection: (1, T, 512) frame features -> (1, 500) embedding."""

    def __init__(self, enc: LipEncoder) -> None:
        super().__init__()
        self.gru = enc.gru
        self.head = enc.head

    def forward(self, f: torch.Tensor) -> torch.Tensor:
        border = torch.ones(f.shape[0], f.shape[1], 1, dtype=f.dtype)
        h, _ = self.gru(torch.cat([f, border], -1))
        return self.head(h).mean(1)


def stack_frames(v: torch.Tensor) -> torch.Tensor:
    """(1, 1, T, H, W) -> (T, 5, H, W): each frame with its two neighbours either side, zero-padded."""
    x = F.pad(v[0, 0], (0, 0, 0, 0, 2, 2))
    return torch.stack([x[k : k + v.shape[2]] for k in range(5)], dim=1)


class UnrolledGRU(nn.Module):
    """The 3-layer bidirectional GRU written as plain matrix multiplies for a fixed number of frames.

    No GRU or loop ops remain after export, so the Hexagon NPU (and the browser) can run the whole encoder.
    Identical maths to nn.GRU; the input projection of every frame is one matrix multiply.
    """

    def __init__(self, gru: nn.GRU) -> None:
        super().__init__()
        self.layers = gru.num_layers
        self.hidden = gru.hidden_size
        for name, p in gru.named_parameters():
            self.register_buffer(name, p.detach().clone())

    def _direction(self, x: torch.Tensor, layer: int, reverse: bool) -> torch.Tensor:
        sfx = f"l{layer}" + ("_reverse" if reverse else "")
        w_ih, w_hh = getattr(self, f"weight_ih_{sfx}"), getattr(self, f"weight_hh_{sfx}")
        b_ih, b_hh = getattr(self, f"bias_ih_{sfx}"), getattr(self, f"bias_hh_{sfx}")
        H = self.hidden
        gi = x @ w_ih.T + b_ih  # (T, 3H): all frames at once
        h = torch.zeros(1, H, dtype=x.dtype)
        steps = range(x.shape[0] - 1, -1, -1) if reverse else range(x.shape[0])
        out = [None] * x.shape[0]
        for t in steps:
            gh = h @ w_hh.T + b_hh
            r = torch.sigmoid(gi[t : t + 1, :H] + gh[:, :H])
            z = torch.sigmoid(gi[t : t + 1, H : 2 * H] + gh[:, H : 2 * H])
            n = torch.tanh(gi[t : t + 1, 2 * H :] + r * gh[:, 2 * H :])
            h = (1 - z) * n + z * h
            out[t] = h
        return torch.cat(out, 0)

    def forward(self, x: torch.Tensor) -> torch.Tensor:  # (T, in) -> (T, 2H)
        for layer in range(self.layers):
            x = torch.cat([self._direction(x, layer, False), self._direction(x, layer, True)], 1)
        return x


class StaticEncoder(nn.Module):
    """Whole encoder for a fixed window: (T, 5, 88, 88) stacked frames -> (1, 500). NPU- and browser-ready."""

    def __init__(self, enc: LipEncoder) -> None:
        super().__init__()
        self.frontend = Frontend2D(enc)
        self.gru = UnrolledGRU(enc.gru)
        self.head = enc.head

    def forward(self, frames: torch.Tensor) -> torch.Tensor:
        f = self.frontend(frames)  # (T, 512)
        x = torch.cat([f, torch.ones(f.shape[0], 1, dtype=f.dtype)], 1)
        return self.head(self.gru(x)).mean(0, keepdim=True)
