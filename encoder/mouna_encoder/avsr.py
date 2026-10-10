"""Free talk: open-vocabulary English lip reading with Auto-AVSR (docs/open-vocab-plan.md).

Auto-AVSR's visual-only model (Ma et al. 2023, `vsr_trlrs2lrs3vox2avsp_base.pth`, 250 M params, 20.3% WER on LRS3;
code Apache-2.0, weights carry their training data's terms) is rebuilt here for the phone's NPU:

- the Conv3D stem becomes an exact Conv2D over 5 stacked frames (as `model.Frontend2D` does for LipLearner);
- the Conformer runs at fixed lengths (buckets), padded frames hidden by an additive mask;
- outputs: CTC log-probs (T, 5049) for the beam search and the encoder states (T, 768) for the attention decoder.

The mouth crop follows Auto-AVSR's own preprocessing (similarity transform of eyes, nose and mouth onto their
20-word mean face, 96 x 96 patch around the mouth, centre 88 x 88, grey, (x/255 - 0.421) / 0.165), but takes its four
points from the MediaPipe face mesh the phone already runs.

    python -m mouna_encoder avsr-export        # ONNX per bucket + parity against PyTorch
    python -m mouna_encoder avsr-read clip.mp4 # transcript of one video, joint CTC/attention and CTC only

Needs the auto_avsr code (git clone https://github.com/mpc001/auto_avsr) and the checkpoint, found through
MOUNA_AVSR_DIR (default ~/.cache/mouna-avsr/auto_avsr) and MOUNA_AVSR_CKPT.
"""

from __future__ import annotations

import math
import os
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from torch import nn

ROOT = Path(__file__).resolve().parents[1]
AVSR_DIR = Path(os.environ.get("MOUNA_AVSR_DIR", "~/.cache/mouna-avsr/auto_avsr")).expanduser()
CKPT = Path(os.environ.get("MOUNA_AVSR_CKPT", "~/.cache/mouna-avsr/weights/vsr_trlrs2lrs3vox2avsp_base.pth")).expanduser()
OUT = ROOT / "weights" / "avsr"

FPS = 25
CROP, ROI = 96, 88
MEAN, STD = 0.421, 0.165
BUCKETS = (64, 128, 256)  # frames at 25 fps: 2.6 / 5.1 / 10.2 s; one NPU graph each
BLANK = 0
D_MODEL = 768

# Four stable points in Auto-AVSR's 256 x 256 mean face: subject's right eye, left eye, nose (iBUG 31-35), mouth.
# From preparation/detectors/*/20words_mean_face.npy (means of iBUG 36-41, 42-47, 31-35, 48-67).
REFERENCE = np.array(
    [
        [102.0739, 94.2723],
        [156.3613, 93.5782],
        [129.0037, 135.9034],
        [129.3134, 157.8230],
    ],
    dtype=np.float64,
)

# The same four points on the MediaPipe face mesh (468/478 points): the closest mesh points to each iBUG group.
MESH_RIGHT_EYE = (33, 160, 158, 133, 153, 144)
MESH_LEFT_EYE = (362, 385, 387, 263, 373, 380)
MESH_NOSE = (98, 97, 2, 326, 327)
MESH_MOUTH = (61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91, 78, 81, 13, 311, 308, 402, 14, 178)
MESH_GROUPS = (MESH_RIGHT_EYE, MESH_LEFT_EYE, MESH_NOSE, MESH_MOUTH)


def _import_avsr():
    if str(AVSR_DIR) not in sys.path:
        sys.path.insert(0, str(AVSR_DIR))


def tokens() -> list[str]:
    """Index -> SentencePiece unit: 0 blank, 1..5047 units, 5048 sos/eos (as auto_avsr's TextTransform)."""
    units = (AVSR_DIR / "spm" / "unigram" / "unigram5000_units.txt").read_text(encoding="utf8").splitlines()
    return ["<blank>"] + [u.split()[0] for u in units] + ["<eos>"]


def load(ckpt: Path = CKPT):
    _import_avsr()
    from espnet.nets.pytorch_backend.e2e_asr_conformer import E2E

    model = E2E(len(tokens()), "video")
    model.load_state_dict(torch.load(ckpt, map_location="cpu", weights_only=True))
    return model.eval()


def detokenize(ids, toks: list[str]) -> str:
    return "".join(toks[i] for i in ids if 0 < i < len(toks) - 1).replace("▁", " ").strip()


# ---------------------------------------------------------------------------------------------------------------------
# Mouth crop


def stable_points(mesh: np.ndarray) -> np.ndarray:
    """(N, 2+) face-mesh points in pixels -> the four (x, y) stable points."""
    return np.stack([mesh[list(g), :2].mean(axis=0) for g in MESH_GROUPS])


def similarity(src: np.ndarray, dst: np.ndarray) -> np.ndarray:
    """Least-squares similarity transform (rotation, uniform scale, shift) src -> dst as a 2 x 3 matrix.

    Closed form, so the Kotlin port matches it exactly (Auto-AVSR uses cv2.estimateAffinePartial2D with LMEDS,
    which for four good points gives the same answer up to noise)."""
    sm, dm = src.mean(0), dst.mean(0)
    s, d = src - sm, dst - dm
    den = (s**2).sum()
    a = (s[:, 0] * d[:, 0] + s[:, 1] * d[:, 1]).sum() / den
    b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / den
    m = np.array([[a, -b, 0.0], [b, a, 0.0]])
    m[:, 2] = dm - m[:, :2] @ sm
    return m


def crop_matrix(points: np.ndarray) -> np.ndarray:
    """Frame pixels -> 96 x 96 crop pixels: onto the mean face, then the patch centred on the mouth."""
    m = similarity(points, REFERENCE)
    mouth = m[:, :2] @ points[3] + m[:, 2]
    m[:, 2] += CROP / 2 - mouth
    return m


def smooth(points: np.ndarray, margin: int = 6) -> np.ndarray:
    """Centred moving average over +-margin frames, re-centred on each frame's own points (Auto-AVSR's VideoProcess)."""
    out = np.empty_like(points)
    n = len(points)
    for i in range(n):
        w = min(margin, i, n - 1 - i)
        avg = points[i - w : i + w + 1].mean(axis=0)
        out[i] = avg + (points[i].mean(axis=0) - avg.mean(axis=0))
    return out


def crops_from_video(frames_rgb: np.ndarray, smooth_margin: int = 6) -> np.ndarray:
    """(T, H, W, 3) uint8 video at 25 fps -> (T, 96, 96) uint8 grey mouth crops, using the MediaPipe face mesh."""
    import cv2
    import mediapipe as mp

    pts = []
    with mp.solutions.face_mesh.FaceMesh(static_image_mode=False, max_num_faces=1, refine_landmarks=True) as fm:
        for f in frames_rgb:
            r = fm.process(f)
            if not r.multi_face_landmarks:
                pts.append(None)
                continue
            h, w = f.shape[:2]
            lm = np.array([[p.x * w, p.y * h] for p in r.multi_face_landmarks[0].landmark])
            pts.append(stable_points(lm))
    ok = [i for i, p in enumerate(pts) if p is not None]
    if not ok:
        raise ValueError("no face in the video")
    for i in range(len(pts)):  # nearest detected frame for the misses
        if pts[i] is None:
            pts[i] = pts[min(ok, key=lambda j: abs(j - i))]
    pts = np.stack(pts)
    if smooth_margin:
        pts = smooth(pts, smooth_margin)
    out = np.empty((len(frames_rgb), CROP, CROP), np.uint8)
    for i, f in enumerate(frames_rgb):
        grey = cv2.cvtColor(f, cv2.COLOR_RGB2GRAY)
        out[i] = cv2.warpAffine(grey, crop_matrix(pts[i]), (CROP, CROP), flags=cv2.INTER_LINEAR, borderValue=0)
    return out


def model_input(crops: np.ndarray) -> np.ndarray:
    """(T, 96, 96) uint8 -> (T, 88, 88) float32, centre crop and normalise."""
    o = (CROP - ROI) // 2
    x = crops[:, o : o + ROI, o : o + ROI].astype(np.float32) / 255.0
    return (x - MEAN) / STD


def read_video(path: str | Path) -> np.ndarray:
    """Video file -> (T, H, W, 3) RGB uint8 at 25 fps (nearest frame in time)."""
    import cv2

    cap = cv2.VideoCapture(str(path))
    fps = cap.get(cv2.CAP_PROP_FPS) or FPS
    frames = []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        frames.append(cv2.cvtColor(f, cv2.COLOR_BGR2RGB))
    cap.release()
    if not frames:
        raise ValueError(f"no frames in {path}")
    if abs(fps - FPS) > 0.5:
        n = int(round(len(frames) * FPS / fps))
        frames = [frames[min(len(frames) - 1, int(round(i * fps / FPS)))] for i in range(n)]
    return np.stack(frames)


# ---------------------------------------------------------------------------------------------------------------------
# Static model for the NPU


class StaticVsr(nn.Module):
    """Auto-AVSR visual encoder + CTC head at a fixed length T.

    In:  x (1, T, 88, 88) normalised crops, zeros after the clip; valid (1, T) 1.0 for real frames, 0.0 for padding.
    Out: logp (1, T, 5049) CTC log-probs; enc (1, T, 768) encoder states for the attention decoder.
    """

    def __init__(self, model, frames: int):
        super().__init__()
        self.t = frames
        fe = model.frontend
        conv3, bn3 = fe.frontend3D[0], fe.frontend3D[1]
        self.front = nn.Conv2d(5, 64, 7, 2, 3, bias=False)
        with torch.no_grad():
            self.front.weight.copy_(conv3.weight[:, 0])  # (64, 5, 7, 7): time taps become input channels
        self.bn = nn.BatchNorm2d(64)
        self.bn.load_state_dict(bn3.state_dict())
        self.trunk = fe.trunk
        self.proj = model.proj_encoder
        self.embed_scale = math.sqrt(D_MODEL)
        enc = model.encoder
        pos = enc.embed[0]  # RelPositionalEncoding
        pos.extend_pe(torch.zeros(1, frames))
        centre = pos.pe.size(1) // 2
        self.register_buffer("pos_emb", pos.pe[:, centre - frames + 1 : centre + frames].clone())
        self.layers = enc.encoders
        self.after_norm = enc.after_norm
        self.ctc_lo = model.ctc.ctc_lo

    def forward(self, x: torch.Tensor, valid: torch.Tensor):
        t = self.t
        xp = F.pad(x, (0, 0, 0, 0, 2, 2))  # (1, T + 4, 88, 88)
        stack = torch.stack([xp[0, k : k + t] for k in range(5)], dim=1)  # (T, 5, 88, 88)
        h = F.max_pool2d(F.silu(self.bn(self.front(stack))), 3, 2, 1)  # (T, 64, 22, 22)
        h = self.trunk(h).view(1, t, 512)
        h = self.proj(h) * self.embed_scale
        bias = ((1.0 - valid) * -1e4).view(1, 1, 1, t)  # additive key mask, fp16-safe
        keep = valid.view(1, 1, t)
        for layer in self.layers:
            h = _conformer_layer(layer, h, self.pos_emb, bias, keep)
        h = self.after_norm(h)
        return F.log_softmax(self.ctc_lo(h), dim=-1), h


def _rel_attention(att, x, pos_emb, bias):
    b, t, _ = x.shape
    hd, dk = att.h, att.d_k
    q = att.linear_q(x).view(b, t, hd, dk)
    k = att.linear_k(x).view(b, t, hd, dk).transpose(1, 2)
    v = att.linear_v(x).view(b, t, hd, dk).transpose(1, 2)
    p = att.linear_pos(pos_emb).view(1, 2 * t - 1, hd, dk).transpose(1, 2)
    ac = torch.matmul((q + att.pos_bias_u).transpose(1, 2), k.transpose(-2, -1))
    bd = torch.matmul((q + att.pos_bias_v).transpose(1, 2), p.transpose(-2, -1))  # (b, h, t, 2t - 1)
    # rel_shift for a fixed t: row i keeps columns (t - 1 - i) .. (2t - 2 - i), a static gather
    bd = torch.gather(bd, 3, _shift_index(t, bd.device).expand(b, hd, t, t))
    scores = (ac + bd) / math.sqrt(dk) + bias
    w = torch.softmax(scores, dim=-1)
    y = torch.matmul(w, v).transpose(1, 2).reshape(b, t, hd * dk)
    return att.linear_out(y)


_SHIFT: dict[int, torch.Tensor] = {}


def _shift_index(t: int, device) -> torch.Tensor:
    if t not in _SHIFT:
        i = torch.arange(t).view(t, 1)
        j = torch.arange(t).view(1, t)
        _SHIFT[t] = (t - 1 - i + j).view(1, 1, t, t)
    return _SHIFT[t].to(device)


def _conv_module(cm, x, keep):
    """The Conformer convolution with padded frames zeroed before the depthwise conv, so a padded clip sees the same
    zeros past its end as the exact-length clip does."""
    y = F.glu(cm.pointwise_cov1(x.transpose(1, 2)), dim=1) * keep
    y = F.silu(cm.norm(cm.depthwise_conv(y)))
    return cm.pointwise_cov2(y).transpose(1, 2)


def _conformer_layer(layer, x, pos_emb, bias, keep):
    assert layer.normalize_before
    if layer.macaron_style:
        x = x + layer.ff_scale * layer.feed_forward_macaron(layer.norm_ff_macaron(x))
    x = x + _rel_attention(layer.self_attn, layer.norm_mha(x), pos_emb, bias)
    if layer.conv_module is not None:
        x = x + _conv_module(layer.conv_module, layer.norm_conv(x), keep)
    x = x + layer.ff_scale * layer.feed_forward(layer.norm_ff(x))
    if layer.conv_module is not None:
        x = layer.norm_final(x)
    return x


def bucket(n: int) -> int:
    for b in BUCKETS:
        if n <= b:
            return b
    return BUCKETS[-1]


def pad_to(x: np.ndarray, frames: int) -> tuple[np.ndarray, np.ndarray]:
    """(T, 88, 88) -> (1, frames, 88, 88) with zeros after the clip (clips longer than `frames` are cut), (1, frames) mask."""
    n = min(len(x), frames)
    out = np.zeros((1, frames, ROI, ROI), np.float32)
    out[0, :n] = x[:n]
    valid = np.zeros((1, frames), np.float32)
    valid[0, :n] = 1.0
    return out, valid


# ---------------------------------------------------------------------------------------------------------------------
# Decoding


def ctc_greedy(logp: np.ndarray) -> list[int]:
    ids, prev = [], BLANK
    for k in logp.argmax(-1):
        if k != prev and k != BLANK:
            ids.append(int(k))
        prev = k
    return ids


def ctc_prefix_beam(logp: np.ndarray, beam: int = 16, prune: float = -12.0, top: int = 10) -> list[tuple[list[int], float]]:
    """CTC prefix beam search over units. Returns [(ids, log-prob)] best first. Reference for the Kotlin port.

    Per frame only the [top] most likely units above [prune] are tried (ties: lower id first): without it a phone
    spends seconds on units that never win."""
    neg = -math.inf

    def lse(*xs):
        m = max(xs)
        return m if m == neg else m + math.log(sum(math.exp(x - m) for x in xs))

    beams: dict[tuple, tuple[float, float]] = {(): (0.0, neg)}  # prefix -> (log p ending in blank, ending in unit)
    for row in logp:
        ok = np.nonzero(row > prune)[0]
        cand = [int(k) for k in sorted(ok, key=lambda k: (-row[k], k))[:top]]
        nxt: dict[tuple, list[float]] = {}

        def add(prefix, pb, pnb):
            cur = nxt.get(prefix)
            if cur is None:
                nxt[prefix] = [pb, pnb]
            else:
                cur[0], cur[1] = lse(cur[0], pb), lse(cur[1], pnb)

        for prefix, (pb, pnb) in beams.items():
            total = lse(pb, pnb)
            for k in cand:
                p = float(row[k])
                if k == BLANK:
                    add(prefix, total + p, neg)
                    continue
                last = prefix[-1] if prefix else None
                ext = prefix + (k,)
                if k == last:
                    add(ext, neg, pb + p)  # a repeat needs a blank in between
                    add(prefix, neg, pnb + p)  # or it continues the same unit
                else:
                    add(ext, neg, total + p)
        beams = dict(sorted(((p, (v[0], v[1])) for p, v in nxt.items()), key=lambda kv: -lse(*kv[1]))[:beam])
    return [(list(p), lse(*v)) for p, v in beams.items()]


def joint_beam(model, enc: torch.Tensor, beam: int = 10, ctc_weight: float = 0.1) -> list[tuple[list[int], float]]:
    """Auto-AVSR's own decoder: joint CTC / attention beam search (espnet BatchBeamSearch), as in its eval."""
    _import_avsr()
    from espnet.nets.batch_beam_search import BatchBeamSearch
    from espnet.nets.scorers.length_bonus import LengthBonus

    toks = tokens()
    scorers = model.scorers()  # as auto_avsr's lightning.get_beam_search_decoder, without pytorch_lightning
    scorers["length_bonus"] = LengthBonus(len(toks))
    search = BatchBeamSearch(
        beam_size=beam,
        vocab_size=len(toks),
        weights={"decoder": 1.0 - ctc_weight, "ctc": ctc_weight, "length_bonus": 0.0},
        scorers=scorers,
        sos=model.sos,
        eos=model.eos,
        token_list=toks,
        pre_beam_score_key=None if ctc_weight == 1.0 else "decoder",
    )
    with torch.no_grad():
        hyps = search(enc[0], maxlenratio=0.0, minlenratio=0.0)
    return [([int(i) for i in h.yseq[1:-1]], float(h.score)) for h in hyps]


def wer(ref: str, hyp: str) -> tuple[int, int]:
    """(word edits, reference words)."""
    r, h = ref.upper().split(), hyp.upper().split()
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)], len(r)


# ---------------------------------------------------------------------------------------------------------------------
# Export


def export(frames: int, model=None, out_dir: Path = OUT) -> Path:
    """ONNX of StaticVsr at one bucket, checked against PyTorch with ONNX Runtime (CPU, fp32)."""
    import onnxruntime as ort

    model = model or load()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"avsr_vsr_t{frames}.onnx"
    s = StaticVsr(model, frames).eval()
    rng = np.random.default_rng(0)
    x, valid = pad_to(rng.standard_normal((frames * 3 // 4, ROI, ROI)).astype(np.float32) * 0.5, frames)
    with torch.no_grad():
        ref_lp, ref_enc = s(torch.from_numpy(x), torch.from_numpy(valid))
        torch.onnx.export(
            s,
            (torch.from_numpy(x), torch.from_numpy(valid)),
            path,
            input_names=["x", "valid"],
            output_names=["logp", "enc"],
            opset_version=17,
            do_constant_folding=True,
        )
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    lp, enc = sess.run(None, {"x": x, "valid": valid})
    n = int(valid.sum())
    print(
        f"{path.name}: {path.stat().st_size / 1e6:.0f} MB, ONNX vs PyTorch logp {np.abs(lp[0, :n] - ref_lp.numpy()[0, :n]).max():.2e}, "
        f"enc {np.abs(enc[0, :n] - ref_enc.numpy()[0, :n]).max():.2e}"
    )
    return path


def phone_files(model=None, out_dir: Path = OUT) -> None:
    """tokens.txt and the NPU self-test files (selftest_t{T}_x/valid/logp.bin, little-endian float32) for the app."""
    import onnxruntime as ort

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "tokens.txt").write_text("\n".join(tokens()) + "\n", encoding="utf8")
    rng = np.random.default_rng(1)
    for t in BUCKETS:
        x, valid = pad_to((rng.standard_normal((t * 3 // 4, ROI, ROI)) * 0.5).astype(np.float32), t)
        sess = ort.InferenceSession(str(out_dir / f"avsr_vsr_t{t}.onnx"), providers=["CPUExecutionProvider"])
        logp = sess.run(["logp"], {"x": x, "valid": valid})[0]
        for name, a in (("x", x), ("valid", valid), ("logp", logp)):
            a.astype("<f4").tofile(out_dir / f"selftest_t{t}_{name}.bin")
    print(f"phone files in {out_dir}")


def input_from_crops(crops: np.ndarray, t_ms: np.ndarray) -> tuple[np.ndarray, np.ndarray, int]:
    """Camera-rate crops (T, 96, 96) uint8 + timestamps -> (1, B, 88, 88) input at 25 fps in its bucket, (1, B) mask, frames used.
    Nearest crop in time for each 25 fps tick (ties go to the later one), as FreeTalk.input in android/core."""
    dur = (t_ms[-1] - t_ms[0]) / 1000.0
    n25 = max(1, int(round(dur * FPS)) + 1)  # Kotlin roundToInt: half up (dur * 25 is never exactly .5 for ms stamps)
    b = bucket(n25)
    n = min(n25, b)
    pick, j = [], 0
    for i in range(n):
        want = t_ms[0] + i * 1000.0 / FPS
        while j + 1 < len(t_ms) and abs(t_ms[j + 1] - want) <= abs(t_ms[j] - want):
            j += 1
        pick.append(j)
    x, valid = pad_to(model_input(crops[pick]), b)
    return x, valid, n


def vectors(out: Path) -> Path:
    """harness/vectors/freetalk.json: crop geometry, model input and CTC decoding, for android/core's FreeTalkParityTest."""
    import json

    rng = np.random.default_rng(7)
    crops_cases = []
    for _ in range(4):  # a face-mesh-like cloud, rotated, scaled, shifted
        mesh = rng.uniform(0, 1, (478, 2)) * 40 + np.array([300.0, 260.0])
        ang, sc = rng.uniform(-0.3, 0.3), rng.uniform(1.5, 4.0)
        r = np.array([[np.cos(ang), -np.sin(ang)], [np.sin(ang), np.cos(ang)]]) * sc
        for g, ref in zip(MESH_GROUPS, REFERENCE):
            mesh[list(g)] = (ref - 128) @ r.T + np.array([320.0, 240.0]) + rng.normal(0, 1.5, (len(g), 2))
        crops_cases.append({"xs": mesh[:, 0].tolist(), "ys": mesh[:, 1].tolist(), "matrix": crop_matrix(stable_points(mesh)).ravel().tolist()})
    t_ms = np.cumsum(rng.integers(30, 38, 40)).astype(np.int64)
    t_ms -= t_ms[0]
    # crops from a formula both sides can rebuild (keeps the vectors small): pixel = (37 f + 11 i) mod 256
    crops = ((37 * np.arange(40)[:, None] + 11 * np.arange(CROP * CROP)[None, :]) % 256).astype(np.uint8).reshape(40, CROP, CROP)
    x, valid, n = input_from_crops(crops, t_ms)
    ctc_cases = []
    for frames, units in ((14, 6), (30, 9)):
        logits = rng.normal(0, 2.5, (frames, units))
        logits[:, 0] += 1.5  # blanks dominate, as in real CTC output
        logp = logits - np.log(np.exp(logits).sum(-1, keepdims=True))
        hyps = ctc_prefix_beam(logp.astype(np.float32), beam=6, prune=-8.0, top=4)
        ctc_cases.append(
            {
                "frames": frames,
                "units": units,
                "logp": logp.astype(np.float32).ravel().tolist(),
                "greedy": ctc_greedy(logp),
                "beam": [{"ids": h, "logp": s} for h, s in hyps],
            }
        )
    # joint search: a toy attention model, a table of next-token log-probs by (last token, prefix length)
    jt, jv, jl = 12, 7, 5
    jl_ctc = rng.normal(0, 2.0, (jt, jv))
    jl_ctc[:, 0] += 1.0
    jctc = (jl_ctc - np.log(np.exp(jl_ctc).sum(-1, keepdims=True))).astype(np.float32)
    jtab = rng.normal(0, 1.5, (jv, jl + 1, jv))
    jtab[:, :, 0] -= 4.0  # the attention decoder rarely wants the blank id
    jtab[:, 2:, jv - 1] += 2.5  # and ends sentences after a few tokens (covers the eos path)
    jtab = (jtab - np.log(np.exp(jtab).sum(-1, keepdims=True))).astype(np.float32)

    def jdecode(prefixes):
        return np.stack([jtab[p[-1], len(p) - 1] for p in prefixes])

    jhyps = joint_search(jctc.astype(np.float64), jdecode, sos=jv - 1, beam=3, pre_beam=4, max_len=jl)
    # personal sentences: a small piece table (tokenizer), CTC sequence scores, whole-sentence scores, the merge rule
    ptoks = ["<blank>", "<unk>", "\u2581I", "\u2581NEED", "\u2581WA", "TER", "\u2581WATER", "\u2581A", "B", "C", "\u2581AB", "<eos>"]
    ppieces = {"\u2581I": -2.0, "\u2581NEED": -4.0, "\u2581WA": -6.0, "TER": -5.0, "\u2581WATER": -9.5, "\u2581A": -3.0,
               "B": -4.0, "C": -4.5, "\u2581AB": -6.5, "\u2581": -7.0, "E": -3.5, "D": -3.9, "N": -3.1}
    spm_cases = [{"text": t, "ids": text_units(t, ppieces, ptoks)} for t in ("I need water", "  i NEED   water ", "abc", "ab cab", "I need xyz", "")]
    seq_cases = []
    for ids in ([1, 3, 5], [2, 2], [4], []):
        if not ids:
            continue
        seq_cases.append({"ids": ids, "logp": ctc_sequence_logp(jctc.astype(np.float64), ids)})
    att_cases = []
    for ids in ([1, 3, 5], [2, 2, 4]):
        att = rng.normal(-1.0, 0.7, len(ids) + 1).tolist()
        att_cases.append({"ids": ids, "att": att, "score": 0.9 * sum(att) + 0.1 * ctc_sequence_logp(jctc.astype(np.float64), ids)})
    merge_cases = []
    for openl, mine in (
        ([("A", -8.0), ("B", -9.0)], [("X", -20.0, -5.0), ("Y", -30.0, -9.0)]),
        ([("A", -8.0), ("B", -9.0)], [("X", -29.0, -5.0), ("Y", -50.0, -1.0)]),
        ([("A", -8.0)], [("a", -10.0, -3.0), ("X", -12.0, -4.0)]),
        ([], [("X", -40.0, -2.0), ("Y", -20.0, -6.0)]),
        ([("A", -8.0), ("B", -9.0), ("C", -9.5)], []),
        ([("A", -8.0)], [("Thank you", -15.0, -14.0), ("Z", -24.0, -2.0)]),
        ([("A", -8.0), ("B", -9.0)], [("X", -15.0, -3.0), ("Y", -16.0, -4.0)]),  # 7 behind: leads at -8, not at -6
    ):
        for fw in (FIRST_WITHIN, FIRST_WITHIN_TUNED):
            merge_cases.append({"open": [list(o) for o in openl], "listed": [list(o) for o in mine], "first_within": fw,
                                "out": [[t, sc, p] for t, sc, p in merge_options(openl, mine, first_within=fw)]})
    rank_cases = [{"score": sc, "prior": pr, "rank": personal_rank(sc, pr)} for sc, pr in ((-20.0, -9.0), (-15.5, -3.25))]
    # trimming: a still face, then a moving mouth, then stillness again (crops from a formula the test rebuilds)
    span_cases = []
    for still_a, moving, still_b, amp in ((20, 30, 25, 40), (0, 12, 40, 25), (10, 0, 10, 0)):
        n_f = still_a + moving + still_b
        f_idx = np.arange(n_f)[:, None]
        px = np.arange(CROP * CROP)[None, :]
        base = (px * 7) % 200
        wobble = np.where((f_idx >= still_a) & (f_idx < still_a + moving), (amp * np.sin(f_idx * 1.3 + px * 0.01)).astype(np.int64), 0)
        cc = np.clip(base + wobble, 0, 255).astype(np.uint8).reshape(n_f, CROP, CROP)
        a, b = active_span(cc)
        span_cases.append({"still_a": still_a, "moving": moving, "still_b": still_b, "amp": amp, "start": a, "end": b})
    v = {
        "span": span_cases,
        "personal_rank": rank_cases,
        "personal": {"tokens": ptoks, "pieces": ppieces, "spm": spm_cases, "ctc_seq": seq_cases, "score": att_cases,
                     "merge": merge_cases},
        "joint": {
            "frames": jt, "units": jv, "max_len": jl, "ctc": jctc.ravel().tolist(), "table": jtab.ravel().tolist(),
            "hyps": [{"ids": h, "score": sc} for h, sc in jhyps],
        },
        "crop": crops_cases,
        "input": {
            "t_ms": t_ms.tolist(),
            "frames": n,
            "bucket": int(valid.shape[1]),
            "x_sum": float(x.astype(np.float64).sum()),
            "x_first": x[0, 0, 0, :8].tolist(),
            "x_last": x[0, n - 1, -1, -8:].tolist(),
        },
        "ctc": ctc_cases,
        "detok": {"ids": [2, 0, 3, 4], "tokens": ["<blank>", "<unk>", "\u2581HELLO", "\u2581THE", "RE", "<eos>"], "text": "HELLO THERE"},
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(v), encoding="utf-8")
    print(f"free talk vectors -> {out}")
    return out


def read_cli(videos: list[Path]) -> None:
    """Transcribe videos with the PyTorch model: joint CTC/attention (Auto-AVSR's decoder) and CTC prefix beam."""
    model = load()
    toks = tokens()
    for v in videos:
        x = model_input(crops_from_video(read_video(v)))
        with torch.no_grad():
            enc, _ = model.encoder(model.proj_encoder(model.frontend(torch.from_numpy(x)[None, :, None])), None)
            logp = torch.log_softmax(model.ctc.ctc_lo(enc), -1)[0].numpy()
        joint = joint_beam(model, enc)
        ctc = ctc_prefix_beam(logp)
        print(f"{v.name} ({len(x)} frames)")
        print(f"  joint: {detokenize(joint[0][0], toks)}")
        print(f"  ctc:   {detokenize(ctc[0][0], toks)}")


# ---------------------------------------------------------------------------------------------------------------------
# Attention decoder for the NPU (joint CTC / attention rescoring)

DEC_BATCH, DEC_LEN = 8, 48  # beam hypotheses per run, tokens per hypothesis (sos + up to 47 units)


class StaticDecoder(nn.Module):
    """Auto-AVSR's 6-layer Transformer decoder at fixed shapes, all positions at once (causal).

    In:  x (B, L, 768) = token embeddings * sqrt(768) + positions (embed_inputs: the lookup runs on the CPU; the
         phone's NPU compile of the embedding Gather gave wrong rows for every token after sos); memory (1, T, 768) =
         StaticVsr's enc; valid (1, T) as for the encoder; sel (B, L) one-hot position per row.
    Out: logp (B, L, 5049): row i is the next-token distribution after ys[:, : i + 1].
    """

    def __init__(self, model, frames: int, batch: int = DEC_BATCH, length: int = DEC_LEN):
        super().__init__()
        dec = model.decoder
        self.b, self.l, self.t = batch, length, frames
        self.emb = dec.embed[0]
        pos = dec.embed[1]
        pos.extend_pe(torch.zeros(1, length))
        self.register_buffer("pe", pos.pe[:, :length].clone())
        self.scale = math.sqrt(D_MODEL)
        self.layers = dec.decoders
        self.after_norm = dec.after_norm
        self.out = dec.output_layer
        causal = torch.triu(torch.full((length, length), -1e4), diagonal=1)
        self.register_buffer("causal", causal.view(1, 1, length, length))

    def embed_inputs(self, ys: np.ndarray) -> np.ndarray:
        """(B, L) token ids -> the decoder's x input (what the phone computes from embed.bin + pos.bin)."""
        with torch.no_grad():
            return (self.emb(torch.from_numpy(ys).long()) * self.scale + self.pe).numpy()

    def forward(self, x: torch.Tensor, memory: torch.Tensor, valid: torch.Tensor, sel: torch.Tensor):
        """sel (B, L) one-hot: the position whose next-token distribution each row wants -> logp (B, 5049)."""
        mem = memory.expand(self.b, self.t, D_MODEL)
        # Masks at full shape (B, heads, L, keys): the phone's NPU compiler applied a mask broadcast over the batch to
        # row 0 only (measured: row 0 matched the laptop, rows 1-7 looked past their prefix and ended sentences early).
        heads = self.layers[0].self_attn.h
        causal = self.causal.expand(self.b, heads, self.l, self.l)
        mem_bias = ((1.0 - valid) * -1e4).view(1, 1, 1, self.t).expand(self.b, heads, self.l, self.t)
        for layer in self.layers:
            h = layer.norm1(x)
            x = x + _mha(layer.self_attn, h, h, causal)
            h = layer.norm2(x)
            x = x + _mha(layer.src_attn, h, mem, mem_bias)
            x = x + layer.feed_forward(layer.norm3(x))
        h = torch.matmul(sel.unsqueeze(1), self.after_norm(x)).squeeze(1)  # (B, 768): one row per hypothesis
        return F.log_softmax(self.out(h), dim=-1)


def _mha(att, q_in, kv_in, bias):
    b, tq, _ = q_in.shape
    tk = kv_in.shape[1]
    hd, dk = att.h, att.d_k
    q = att.linear_q(q_in).view(b, tq, hd, dk).transpose(1, 2)
    k = att.linear_k(kv_in).view(b, tk, hd, dk).transpose(1, 2)
    v = att.linear_v(kv_in).view(b, tk, hd, dk).transpose(1, 2)
    w = torch.softmax(torch.matmul(q, k.transpose(-2, -1)) / math.sqrt(dk) + bias, dim=-1)
    return att.linear_out(torch.matmul(w, v).transpose(1, 2).reshape(b, tq, hd * dk))


def export_decoder(frames: int, model=None, out_dir: Path = OUT) -> Path:
    import onnxruntime as ort

    model = model or load()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"avsr_dec_t{frames}.onnx"
    d = StaticDecoder(model, frames).eval()
    rng = np.random.default_rng(2)
    n = frames * 3 // 4
    mem = np.zeros((1, frames, D_MODEL), np.float32)
    mem[0, :n] = rng.standard_normal((n, D_MODEL)).astype(np.float32)
    valid = np.zeros((1, frames), np.float32)
    valid[0, :n] = 1
    sos = len(tokens()) - 1
    ys = rng.integers(1, sos, (DEC_BATCH, DEC_LEN)).astype(np.int32)
    ys[:, 0] = sos
    sel = np.zeros((DEC_BATCH, DEC_LEN), np.float32)
    sel[np.arange(DEC_BATCH), np.arange(DEC_BATCH) + 3] = 1.0  # row b reads position b + 3
    xe = d.embed_inputs(ys)
    args = [torch.from_numpy(a) for a in (xe, mem, valid, sel)]
    with torch.no_grad():
        ref = d(*args).numpy()
        # against espnet's own incremental decoder on hypothesis 0, prefix of 4 (position 3)
        y0 = torch.from_numpy(ys[:1, :4]).long()
        from espnet.nets.pytorch_backend.transformer.mask import subsequent_mask

        lp, _ = model.decoder.forward_one_step(y0, subsequent_mask(4).unsqueeze(0), torch.from_numpy(mem[:, :n]))
        print(f"static decoder vs espnet (prefix 4): {np.abs(ref[0] - lp.numpy()[0]).max():.2e}")
        torch.onnx.export(d, tuple(args), path, input_names=["x", "memory", "valid", "sel"], output_names=["logp"],
                          opset_version=17, do_constant_folding=True)
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    got = sess.run(None, {"x": xe, "memory": mem, "valid": valid, "sel": sel})[0]
    # the lookup tables for the phone: token embeddings * sqrt(768) (5049 x 768) and positions (48 x 768), float32 LE
    with torch.no_grad():
        (d.emb.weight * d.scale).numpy().astype("<f4").tofile(out_dir / "dec_embed.bin")
        d.pe[0].numpy().astype("<f4").tofile(out_dir / "dec_pos.bin")
    print(f"{path.name}: {path.stat().st_size / 1e6:.0f} MB, ONNX vs PyTorch {np.abs(got - ref).max():.2e}")
    return path


# ---------------------------------------------------------------------------------------------------------------------
# Evaluation on GRID (CC BY 4.0, https://zenodo.org/records/3625687): English sentences, frontal, voiced


def grid_transcript(align: Path) -> str:
    words = [ln.split()[2] for ln in align.read_text().splitlines() if len(ln.split()) == 3]
    return " ".join(w for w in words if w not in ("sil", "sp")).upper()


def grid_eval(videos: list[Path], aligns: Path | None = None, beam: int = 10, smooth_margin: int = 6) -> dict:
    """WER of joint CTC/attention vs CTC prefix beam (and greedy) over GRID clips, with this module's mesh crop."""
    import time

    model = load()
    toks = tokens()
    tot = {"joint": [0, 0], "ctc_beam": [0, 0], "ctc_greedy": [0, 0]}
    rows = []
    for v in videos:
        a = aligns / (v.stem + ".align") if aligns else None
        ref = grid_transcript(a) if a and a.exists() else grid_from_name(v.stem)
        try:
            x = model_input(crops_from_video(read_video(v), smooth_margin))
        except ValueError as e:
            print(f"{v.name}: {e}")
            continue
        t0 = time.time()
        with torch.no_grad():
            enc, _ = model.encoder(model.proj_encoder(model.frontend(torch.from_numpy(x)[None, :, None])), None)
            logp = torch.log_softmax(model.ctc.ctc_lo(enc), -1)[0].numpy()
        hyp = {
            "joint": detokenize(joint_beam(model, enc, beam)[0][0], toks),
            "ctc_beam": detokenize(ctc_prefix_beam(logp, beam=16)[0][0], toks),
            "ctc_greedy": detokenize(ctc_greedy(logp), toks),
        }
        for k, h in hyp.items():
            e, n = wer(ref, h)
            tot[k][0] += e
            tot[k][1] += n
        rows.append({"clip": v.stem, "ref": ref, **hyp})
        print(f"{v.stem}  REF {ref}\n          JNT {hyp['joint']}\n          CTC {hyp['ctc_beam']}   ({time.time() - t0:.1f} s)")
    res = {k: (e / n if n else None) for k, (e, n) in tot.items()}
    print("WER " + "  ".join(f"{k} {100 * r:.1f}%" for k, r in res.items() if r is not None) + f"  over {len(rows)} clips")
    return {"wer": res, "clips": len(rows), "rows": rows}


GRID_WORDS = (
    {"b": "BIN", "l": "LAY", "p": "PLACE", "s": "SET"},
    {"b": "BLUE", "g": "GREEN", "r": "RED", "w": "WHITE"},
    {"a": "AT", "b": "BY", "i": "IN", "w": "WITH"},
    None,  # the letter itself
    {"z": "ZERO", "1": "ONE", "2": "TWO", "3": "THREE", "4": "FOUR", "5": "FIVE", "6": "SIX", "7": "SEVEN", "8": "EIGHT", "9": "NINE"},
    {"a": "AGAIN", "n": "NOW", "p": "PLEASE", "s": "SOON"},
)


def grid_from_name(stem: str) -> str:
    """GRID clip names spell their sentence: 'lbbk6p' -> 'LAY BLUE BY K SIX PLEASE'."""
    return " ".join(c.upper() if m is None else m[c] for c, m in zip(stem, GRID_WORDS))


# ---------------------------------------------------------------------------------------------------------------------
# Joint CTC / attention beam search, portable (reference for android/core JointBeam)

CTC_WEIGHT = 0.1  # Auto-AVSR's own decoding weight
NEG_INF = -1e30


def ctc_prefix_init(ctc: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """State of the empty prefix over T frames: (r_nonblank, r_blank) log-probs, (T,) each."""
    rb = np.cumsum(ctc[:, BLANK].astype(np.float64))
    return np.full(len(ctc), NEG_INF), rb


def ctc_prefix_extend(ctc: np.ndarray, state, last: int, c: int, empty: bool):
    """CTC prefix score of prefix + c (Watanabe et al. 2017, Algorithm 2) and the new state."""
    rn_g, rb_g = state
    t_len = len(ctc)
    rn = np.full(t_len, NEG_INF)
    rb = np.full(t_len, NEG_INF)
    if empty:
        rn[0] = ctc[0, c]
    psi = rn[0]
    for t in range(1, t_len):
        phi = rb_g[t - 1] if c == last and not empty else np.logaddexp(rn_g[t - 1], rb_g[t - 1])
        rn[t] = np.logaddexp(rn[t - 1], phi) + ctc[t, c]
        rb[t] = np.logaddexp(rn[t - 1], rb[t - 1]) + ctc[t, BLANK]
        psi = np.logaddexp(psi, phi + ctc[t, c])
    return float(psi), (rn, rb)


def joint_search(ctc: np.ndarray, decode, sos: int, beam: int = DEC_BATCH, pre_beam: int = 12,
                 max_len: int = DEC_LEN - 1, ctc_weight: float = CTC_WEIGHT) -> list[tuple[list[int], float]]:
    """Joint CTC / attention beam search over the real frames' CTC log-probs ctc (T, V).

    decode(prefixes) -> (len(prefixes), V) attention log-probs of the next token, each prefix starting with sos.
    Score = sum over tokens of (1 - w) * attention + w * (CTC prefix score gain); eos (= sos id) closes a hypothesis
    with the full-sequence CTC score. Per hypothesis only its [pre_beam] best attention tokens are scored (ties: lower
    id). Stops when no running hypothesis can beat the best ended one (every step only lowers a score), or at max_len.
    Returns ended hypotheses (token ids without sos/eos, score), best first."""
    eos = sos
    running = [([sos], 0.0, 0.0, ctc_prefix_init(ctc))]  # (tokens, score, ctc psi, ctc state)
    ended: list[tuple[list[int], float]] = []
    for _ in range(max_len):
        att = decode([h[0] for h in running])
        cands = []
        for hi, (toks, score, psi, st) in enumerate(running):
            empty = len(toks) == 1
            row = att[hi]
            order = sorted(range(len(row)), key=lambda k: (-row[k], k))[:pre_beam]
            for c in order:
                if c == BLANK:
                    continue
                if c == eos:
                    new_psi, new_st = float(np.logaddexp(st[0][-1], st[1][-1])), None
                else:
                    new_psi, new_st = ctc_prefix_extend(ctc, st, toks[-1], c, empty)
                s_new = score + (1 - ctc_weight) * float(row[c]) + ctc_weight * (new_psi - psi)
                cands.append((s_new, hi, c, new_psi, new_st))
        cands.sort(key=lambda x: (-x[0], x[1], x[2]))
        nxt = []
        for s_new, hi, c, new_psi, new_st in cands[:beam]:
            toks = running[hi][0]
            if c == eos:
                ended.append((toks[1:], s_new))
            else:
                nxt.append((toks + [c], s_new, new_psi, new_st))
        running = nxt
        best_end = max((e[1] for e in ended), default=-math.inf)
        if not running or max(h[1] for h in running) < best_end:
            break
    if not ended:  # ran out of length: close what is left
        ended = [(h[0][1:], h[1]) for h in running]
    ended.sort(key=lambda e: -e[1])
    return ended


# ---------------------------------------------------------------------------------------------------------------------
# Personal sentences (issue #5, part A): text -> units, and the model's own score of a whole sentence

SPM_MODEL = AVSR_DIR / "spm" / "unigram" / "unigram5000.model"


def spm_pieces() -> dict[str, float]:
    """SentencePiece unigram pieces and their log-prob scores (written to the phone as spm_pieces.tsv)."""
    import sentencepiece as spm

    sp = spm.SentencePieceProcessor(model_file=str(SPM_MODEL))
    return {sp.id_to_piece(i): sp.get_score(i) for i in range(sp.get_piece_size()) if not sp.is_control(i) and not sp.is_unknown(i)}


def text_units(text: str, pieces: dict[str, float], toks: list[str]) -> list[int]:
    """Text -> Auto-AVSR unit ids: upper case, words joined with '▁', then the unigram Viterbi segmentation
    (best total piece score; a character no piece covers becomes <unk>). Port target for android/core Spm."""
    words = text.upper().split()
    if not words:
        return []
    s = "".join("▁" + w for w in words)
    unk_score = min(pieces.values()) - 10.0
    max_len = max(len(p) for p in pieces)
    best = [0.0] + [-math.inf] * len(s)
    back: list[tuple[int, str | None]] = [(0, None)] * (len(s) + 1)
    for end in range(1, len(s) + 1):
        for start in range(max(0, end - max_len), end):
            if best[start] == -math.inf:
                continue
            piece = s[start:end]
            sc = pieces.get(piece)
            if sc is None:
                if end - start != 1:
                    continue
                sc, piece = unk_score, None  # an unknown single character
            if best[start] + sc > best[end]:
                best[end] = best[start] + sc
                back[end] = (start, piece)
    out, end = [], len(s)
    while end > 0:
        start, piece = back[end]
        out.append(piece)
        end = start
    index = {t: i for i, t in enumerate(toks)}
    return [index.get(p, index["<unk>"]) if p is not None else index["<unk>"] for p in reversed(out)]


def ctc_sequence_logp(ctc: np.ndarray, ids: list[int]) -> float:
    """log P(ids | frames) under CTC (forward algorithm over the blank-extended label sequence)."""
    t_len = len(ctc)
    ext = [BLANK]
    for i in ids:
        ext += [i, BLANK]
    s_len = len(ext)
    alpha = np.full(s_len, NEG_INF)
    alpha[0] = ctc[0, BLANK]
    if s_len > 1:
        alpha[1] = ctc[0, ext[1]]
    for t in range(1, t_len):
        new = np.full(s_len, NEG_INF)
        for s in range(s_len):
            a = alpha[s]
            if s >= 1:
                a = np.logaddexp(a, alpha[s - 1])
            if s >= 2 and ext[s] != BLANK and ext[s] != ext[s - 2]:
                a = np.logaddexp(a, alpha[s - 2])
            new[s] = a + ctc[t, ext[s]]
        alpha = new
    return float(np.logaddexp(alpha[-1], alpha[-2]) if s_len > 1 else alpha[-1])


class ScoreDecoder(StaticDecoder):
    """The decoder at every position at once, for scoring whole sentences (8 per NPU run).

    Same inputs as StaticDecoder minus sel. Out: h (B, L, 768) final states and lse (B, L) the log-sum-exp of the
    logits, so log p(token | prefix) = h . W[token] + b[token] - lse (W, b in dec_out_w.bin / dec_out_b.bin on the
    phone: one dot product per token instead of shipping B x L x 5049 log-probs off the NPU)."""

    def forward(self, x: torch.Tensor, memory: torch.Tensor, valid: torch.Tensor):
        mem = memory.expand(self.b, self.t, D_MODEL)
        heads = self.layers[0].self_attn.h
        causal = self.causal.expand(self.b, heads, self.l, self.l)
        mem_bias = ((1.0 - valid) * -1e4).view(1, 1, 1, self.t).expand(self.b, heads, self.l, self.t)
        for layer in self.layers:
            h = layer.norm1(x)
            x = x + _mha(layer.self_attn, h, h, causal)
            h = layer.norm2(x)
            x = x + _mha(layer.src_attn, h, mem, mem_bias)
            x = x + layer.feed_forward(layer.norm3(x))
        h = self.after_norm(x)
        z = self.out(h)
        top = z.max(dim=-1, keepdim=True).values  # log-sum-exp spelled out: ReduceLogSumExp does not compile for the NPU
        return h, (top + torch.log(torch.exp(z - top).sum(dim=-1, keepdim=True))).squeeze(-1)


def score_sentences(ctc: np.ndarray, score_rows, sentences: list[list[int]], sos: int, w: np.ndarray, b: np.ndarray,
                    ctc_weight: float = CTC_WEIGHT) -> list[float]:
    """Joint score of whole sentences, the same objective joint_search maximises:
    (1 - w) * sum log p_att(token | prefix) over the tokens and eos + w * log p_ctc(sentence).
    score_rows(prefixes) -> (h (n, L, 768), lse (n, L)) for up to DEC_BATCH prefixes [sos, t1..tk]."""
    out = []
    for i in range(0, len(sentences), DEC_BATCH):
        chunk = sentences[i : i + DEC_BATCH]
        h, lse = score_rows([[sos] + s for s in chunk])
        for r, s in enumerate(chunk):
            targets = s + [sos]  # eos = sos id
            att = sum(float(h[r, j] @ w[t] + b[t] - lse[r, j]) for j, t in enumerate(targets))
            out.append((1 - ctc_weight) * att + ctc_weight * ctc_sequence_logp(ctc, s))
    return out


def export_scorer(frames: int, model=None, out_dir: Path = OUT) -> Path:
    """ONNX of ScoreDecoder at one bucket, checked against PyTorch, plus dec_out_w.bin / dec_out_b.bin / spm_pieces.tsv."""
    import onnxruntime as ort

    model = model or load()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"avsr_score_t{frames}.onnx"
    d = ScoreDecoder(model, frames).eval()
    rng = np.random.default_rng(3)
    n = frames * 3 // 4
    mem = np.zeros((1, frames, D_MODEL), np.float32)
    mem[0, :n] = rng.standard_normal((n, D_MODEL)).astype(np.float32)
    valid = np.zeros((1, frames), np.float32)
    valid[0, :n] = 1
    sos = len(tokens()) - 1
    ys = rng.integers(1, sos, (DEC_BATCH, DEC_LEN)).astype(np.int32)
    ys[:, 0] = sos
    xe = d.embed_inputs(ys)
    args = [torch.from_numpy(a) for a in (xe, mem, valid)]
    with torch.no_grad():
        ref_h, ref_l = (t.numpy() for t in d(*args))
        torch.onnx.export(d, tuple(args), path, input_names=["x", "memory", "valid"], output_names=["h", "lse"],
                          opset_version=17, do_constant_folding=True)
        (model.decoder.output_layer.weight.numpy()).astype("<f4").tofile(out_dir / "dec_out_w.bin")
        (model.decoder.output_layer.bias.numpy()).astype("<f4").tofile(out_dir / "dec_out_b.bin")
    (out_dir / "spm_pieces.tsv").write_text("".join(f"{p}\t{s!r}\n" for p, s in spm_pieces().items()), encoding="utf8")
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    h, lse = sess.run(None, {"x": xe, "memory": mem, "valid": valid})
    print(f"{path.name}: {path.stat().st_size / 1e6:.0f} MB, ONNX vs PyTorch h {np.abs(h - ref_h).max():.2e} lse {np.abs(lse - ref_l).max():.2e}")
    return path


# A listed sentence leads when its score is within FIRST_WITHIN of the open best. Was -20 (GRID,
# freetalk-personal-grid.json). Laptop replay of Nakul's 21 held-out silent clips (adapt.py seed 0, 10 Oct; not yet saved
# in deck/data): -20 put a wrong listed sentence first 19/21 when the truth was not listed; -6 (tuned model) / -8
# (original) put it first 0/21 and kept listed truths first or second 21/21.
FIRST_WITHIN, FIRST_WITHIN_TUNED, OFFER_WITHIN = -8.0, -6.0, -35.0
PRIOR_WEIGHT = 0.8  # listed sentences rank by score - PRIOR_WEIGHT * (1 - CTC_WEIGHT) * prior (no-video attention score)


def personal_rank(score: float, prior: float) -> float:
    """How listed sentences are ranked against each other: their score minus part of what the decoder expects with no
    video at all, so a short common sentence ("Thank you very much") can't win every clip (GRID + care phrases:
    without it a care phrase came first 24/30, with 0.7-1.0 never; the true sentence first 6/30 -> 30/30)."""
    return score - PRIOR_WEIGHT * (1 - CTC_WEIGHT) * prior


def merge_options(open_: list[tuple[str, float]], listed: list[tuple[str, float, float]], max_n: int = 4,
                  first_within: float = FIRST_WITHIN) -> list[tuple[str, float, bool]]:
    """What "Did you mean…?" shows (reference for Personal.merge). listed: (text, score, rank). Listed sentences go in
    rank order; the best leads when its score is within first_within of the open best (FIRST_WITHIN_TUNED for a model
    tuned to the person), else the open reading leads;
    then they alternate; listed sentences scoring further than OFFER_WITHIN behind are dropped; the same text (any
    case) keeps its first place."""
    open_best = open_[0][1] if open_ else -math.inf
    mine = [(t, sc) for t, sc, _ in sorted(listed, key=lambda o: -o[2])]
    mine = [o for o in mine if not open_ or o[1] - open_best > OFFER_WITHIN]
    mine_first = bool(mine) and (not open_ or mine[0][1] - open_best > first_within)
    a, b = ((mine, True), (open_, False)) if mine_first else ((open_, False), (mine, True))
    out: dict[str, tuple[str, float, bool]] = {}
    for i in range(max(len(a[0]), len(b[0]))):
        for lst, personal in (a, b):
            if i < len(lst):
                out.setdefault(lst[i][0].lower(), (lst[i][0], lst[i][1], personal))
    return list(out.values())[:max_n]


TRIM_REL, TRIM_MIN, TRIM_PAD = 0.35, 1.2, 6


def active_span(crops: np.ndarray) -> tuple[int, int]:
    """[start, end) of the moving part of a clip, from the mouth crops themselves: mean absolute frame-to-frame change
    over the mouth (rows 24-71, columns 16-79 of the 96 px crop), averaged over 5 frames, above
    max(TRIM_MIN, TRIM_REL * its 90th percentile), padded by TRIM_PAD frames. Reference for FreeTalk.activeSpan.
    On 24 silent recordings the clips ran on for seconds (15/24 hit the 10 s cap) and the decoder filled the stillness
    with invented words; trimming took WER 114% -> 85% (deck/data/freetalk-silent-nakul.json)."""
    x = crops.astype(np.float32)[:, 24:72, 16:80]
    mv = np.r_[0.0, np.abs(np.diff(x, axis=0)).mean(axis=(1, 2))]
    sm = np.convolve(mv, np.ones(5) / 5, mode="same")
    thr = max(TRIM_MIN, TRIM_REL * float(np.percentile(sm, 90)))
    act = np.nonzero(sm > thr)[0]
    if len(act) == 0:
        return 0, len(crops)
    return max(0, int(act[0]) - TRIM_PAD), min(len(crops), int(act[-1]) + TRIM_PAD + 1)
