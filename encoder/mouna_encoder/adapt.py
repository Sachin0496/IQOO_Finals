"""Free talk, issue #5 part B: tune Auto-AVSR to one person's silent mouthing with LoRA (laptop, Apple MPS).

    python -m mouna_encoder.adapt ~/.cache/mouna-avsr/train --out encoder/weights/avsr/lora_<person>.pt

The person's recordings (the app's Record screen: 96 px mouth crops + the prompted sentence) are split by sentence:
80% train, 20% held out. Only small low-rank adapters (LoRA, rank 8) on the encoder's and decoder's attention and
feed-forward layers, plus the visual front end's BatchNorm scale/shift, are trained, with Auto-AVSR's own loss
(0.1 CTC + 0.9 attention, label smoothing). The base weights never change. The held-out sentences are read before
and after with the same joint search as the phone (avsr.joint_search), and the person's sentence list is scored
before and after: the numbers go to deck/data/freetalk-adapt-<person>.json.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import time
from pathlib import Path

os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")  # CTC loss has no MPS kernel

import numpy as np
import torch
from torch import nn

from . import avsr

PROMPTS = Path(__file__).resolve().parents[2] / "android/app/src/main/res/raw/freetalk_prompts.txt"


class LoRALinear(nn.Module):
    """y = W x + b + (alpha / r) * B A x, with W, b frozen and B starting at zero (so it starts as the base model)."""

    def __init__(self, base: nn.Linear, r: int = 8, alpha: float = 16.0):
        super().__init__()
        self.base = base
        self.a = nn.Parameter(torch.randn(r, base.in_features) * (1.0 / math.sqrt(base.in_features)))
        self.b = nn.Parameter(torch.zeros(base.out_features, r))
        self.scale = alpha / r

    def forward(self, x):
        return self.base(x) + (x @ self.a.t() @ self.b.t()) * self.scale


ATTN = ("linear_q", "linear_k", "linear_v", "linear_out")


_KEEP: list = [None]  # (B, T, 1) real frames of the batch being trained on (train_loss sets it)


def _contiguous_conv_module():
    """The Conformer convolution passes a transposed tensor to conv1d, whose backward fails on Apple MPS ("view size is
    not compatible with input tensor's size and stride"); the same maths with contiguous tensors. Padded frames are
    zeroed before the depthwise conv, as the phone's graph does (avsr._conv_module): a padded clip trains on exactly
    what the phone computes for it."""
    avsr._import_avsr()
    from espnet.nets.pytorch_backend.encoder.conformer_encoder import ConvolutionModule

    def forward(self, x):
        f = torch.nn.functional
        # the pointwise (kernel 1) conv1ds as identical linears over (B, T, C): their conv1d backward fails on MPS too
        x = f.glu(f.linear(x, self.pointwise_cov1.weight.squeeze(-1), self.pointwise_cov1.bias), dim=-1)
        if _KEEP[0] is not None:
            x = x * _KEEP[0]
        x = x.transpose(1, 2).contiguous()
        dw = self.depthwise_conv  # the depthwise conv1d as an identical conv2d over a height of 1 (MPS can train that)
        x = f.conv2d(x.unsqueeze(2), dw.weight.unsqueeze(2), dw.bias, padding=(0, dw.padding[0]), groups=dw.groups).squeeze(2)
        x = f.silu(self.norm(x)).transpose(1, 2).contiguous()
        return f.linear(x, self.pointwise_cov2.weight.squeeze(-1), self.pointwise_cov2.bias)

    ConvolutionModule.forward = forward


def _frontend_pool2d():
    """The front end's MaxPool3d has no MPS kernel (it ran on the CPU and back every step): its (1, 3, 3) window is a
    max_pool2d per frame, the same maths."""
    avsr._import_avsr()
    from espnet.nets.pytorch_backend.frontend.resnet import Conv3dResNet, threeD_to_2D_tensor

    def forward(self, xs_pad):
        b = xs_pad.size(0)
        f = self.frontend3D
        x = f[2](f[1](f[0](xs_pad.transpose(2, 1))))
        t = x.size(2)
        x = self.trunk(torch.nn.functional.max_pool2d(threeD_to_2D_tensor(x), 3, 2, 1))
        return x.view(b, t, x.size(1))

    Conv3dResNet.forward = forward


# encoder: the decoder (the model's sense of English) stays as it was; all: the decoder's attention too; frontend:
# only the front end's BatchNorm (how the pixels look)
TARGETS = ("encoder", "all", "frontend")


def add_lora(model, r: int = 8, targets: str = "all", layers: int | None = None) -> list[nn.Parameter]:
    """Wrap the attention and feed-forward linears of the first `layers` encoder layers (all 12 by default), and with
    targets="all" the attention of every decoder layer too; returns the trainable params."""
    for p in model.parameters():
        p.requires_grad_(False)
    params: list[nn.Parameter] = []

    def wrap(parent, name):
        lin = getattr(parent, name)
        if isinstance(lin, nn.Linear):
            lo = LoRALinear(lin, r)
            setattr(parent, name, lo)
            params.extend([lo.a, lo.b])

    for layer in model.encoder.encoders[:layers] if targets != "frontend" else []:
        for n in ATTN:
            wrap(layer.self_attn, n)
        for ff in (layer.feed_forward, getattr(layer, "feed_forward_macaron", None)):
            if ff is None:
                continue
            wrap(ff, "w_1")
            wrap(ff, "w_2")
    if targets == "all":
        for layer in model.decoder.decoders:
            for att in (layer.self_attn, layer.src_attn):
                for n in ATTN:
                    wrap(att, n)
    # the camera, light and silent mouthing differ most at the pixels: let the front end's BatchNorm rescale
    for m in model.frontend.modules():
        if isinstance(m, (nn.BatchNorm2d, nn.BatchNorm3d, nn.BatchNorm1d)):
            m.weight.requires_grad_(True)
            m.bias.requires_grad_(True)
            params += [m.weight, m.bias]
    return params


def lora_state(model) -> dict[str, torch.Tensor]:
    """Only what training changed: the adapters and the front end's BatchNorm scale/shift."""
    return {k: p.detach().cpu() for k, p in model.named_parameters() if p.requires_grad}


def load_clips(root: Path) -> list[dict]:
    """Every recorded clip: trimmed crops at 25 fps (the phone's input) and the sentence."""
    out = []
    for f in sorted(root.glob("*/[0-9][0-9][0-9].bin")):
        txt = f.with_suffix(".txt")
        if not txt.exists():
            continue
        c = np.fromfile(f, np.uint8).reshape(-1, avsr.CROP, avsr.CROP)
        t = np.array([int(x) for x in f.with_suffix(".t.txt").read_text().split()], np.int64)
        a, b = avsr.active_span(c)
        c, t = c[a:b], t[a:b] - t[a]
        x, valid, n = avsr.input_from_crops(c, t)
        # keep the 96 px crops at 25 fps too, for random 88 px crops while training
        dur = (t[-1] - t[0]) / 1000.0
        n25 = max(1, int(round(dur * avsr.FPS)) + 1)
        pick, j = [], 0
        for i in range(min(n25, avsr.BUCKETS[-1])):
            want = t[0] + i * 1000.0 / avsr.FPS
            while j + 1 < len(t) and abs(t[j + 1] - want) <= abs(t[j] - want):
                j += 1
            pick.append(j)
        out.append({"id": f"{f.parent.name}/{f.stem}", "text": txt.read_text().strip(), "crops96": c[pick], "x": x[0, :n]})
    return out


LABEL_WIDTH = avsr.DEC_LEN - 1  # training batches: frames padded to the longest bucket (256), labels to 47 units


def time_mask(n: int, window: int = 10, stride: int = 25) -> list[tuple[int, int]]:
    """Auto-AVSR's AdaptiveTimeMask (its video training transform): one span of under `window` frames per `stride`
    frames, blacked out, so no single moment of the mouth can carry a word."""
    spans = []
    for _ in range(int((n + stride - 0.1) // stride)):
        t, length = random.randrange(window), random.randrange(window)
        if n - t <= 0 or t == 0:
            continue
        start = random.randrange(n - t)
        spans.append((start, start + length))
    return spans


def batch(items, toks, pieces, device, train: bool, mask: bool = False):
    xs, labels = [], []
    o = (avsr.CROP - avsr.ROI) // 2
    for it in items:
        c = it["crops96"]
        if train:  # Auto-AVSR's training crop: a random 88 px window, not the centre
            dy, dx = random.randint(0, 8), random.randint(0, 8)
        else:
            dy = dx = o
        c = c[:, dy : dy + avsr.ROI, dx : dx + avsr.ROI].astype(np.float32) / 255.0
        if train and mask:
            for a, b in time_mask(len(c)):
                c[a:b] = 0.0  # black, before normalising, as Auto-AVSR
        xs.append(torch.from_numpy((c - avsr.MEAN) / avsr.STD))
        labels.append(torch.tensor(avsr.text_units(it["text"], pieces, toks)))
    lengths = torch.tensor([len(x) for x in xs])
    frames, width = int(lengths.max()), max(len(l) for l in labels)
    if train:  # one fixed shape: MPS compiles a graph per input shape (measured on an M2 Pro: 2 s a step at one shape;
        # minutes a step and 17 GB of memory when every batch had its own)
        frames, width = avsr.BUCKETS[-1], max(width, LABEL_WIDTH)
    xpad = torch.zeros(len(xs), frames, avsr.ROI, avsr.ROI)
    for i, x in enumerate(xs):
        xpad[i, : len(x)] = x
    lpad = torch.full((len(labels), width), -1, dtype=torch.long)
    for i, l in enumerate(labels):
        lpad[i, : len(l)] = l
    return xpad.unsqueeze(2).to(device), lengths.to(device), lpad.to(device)


def train_loss(model, x, lengths, label):
    """Auto-AVSR's loss as its E2E.forward (ctc_weight * CTC + the rest * label-smoothed attention), at the batch's
    padded shapes: frames past each clip are masked out, label columns past each sentence are ignored."""
    from espnet.nets.pytorch_backend.nets_utils import make_pad_mask
    from espnet.nets.pytorch_backend.transformer.mask import subsequent_mask

    keep = (~make_pad_mask(lengths.cpu(), maxlen=x.size(1))).to(x.device).unsqueeze(-2)
    _KEEP[0] = keep.transpose(1, 2).float()
    try:
        h, _ = model.encoder(model.proj_encoder(model.frontend(x)), keep)
    finally:
        _KEEP[0] = None
    loss_ctc, _ = model.ctc(h, lengths, label)
    b, w = label.shape
    ys_in = torch.full((b, w + 1), model.eos, dtype=torch.long, device=label.device)
    ys_out = torch.full((b, w + 1), model.ignore_id, dtype=torch.long, device=label.device)
    ys_in[:, 0] = model.sos
    for i, n in enumerate((label != model.ignore_id).sum(1).tolist()):
        ys_in[i, 1 : n + 1] = label[i, :n]
        ys_out[i, :n] = label[i, :n]
        ys_out[i, n] = model.eos
    causal = subsequent_mask(w + 1, device=x.device).unsqueeze(0).expand(b, w + 1, w + 1)
    pred, _ = model.decoder(ys_in, causal, h, keep)
    loss_att = model.criterion(pred, ys_out)
    return model.ctc_weight * loss_ctc + (1 - model.ctc_weight) * loss_att


def encode(model, x: np.ndarray):
    """One clip's (n, 88, 88) input through the static encoder graph, as the phone runs it: CTC log-probs (n, V), the
    encoder states and mask at the clip's bucket, and the bucket."""
    n = len(x)
    t_b = avsr.bucket(n)
    xp, valid = avsr.pad_to(x, t_b)
    lp, enc = avsr.StaticVsr(model, t_b).eval()(torch.from_numpy(xp), torch.from_numpy(valid))
    return lp[0, :n].numpy().astype(np.float64), enc, valid, t_b


def step_decoder(model, enc, valid, t_b: int, sos: int):
    """joint_search's decode(prefixes): the static decoder graph's next-token log-probs for up to DEC_BATCH prefixes."""
    dd = avsr.StaticDecoder(model, t_b).eval()

    def decode(prefixes):
        ys = np.full((avsr.DEC_BATCH, avsr.DEC_LEN), sos, np.int32)
        sel = np.zeros((avsr.DEC_BATCH, avsr.DEC_LEN), np.float32)
        for i, p in enumerate(prefixes):
            ys[i, : len(p)] = p
            sel[i, len(p) - 1] = 1
        return dd(torch.from_numpy(dd.embed_inputs(ys)), enc, torch.from_numpy(valid), torch.from_numpy(sel)).numpy()[: len(prefixes)]

    return decode


def read_open(model, x: np.ndarray, toks) -> tuple[str, tuple]:
    """Open reading of one clip with the phone's joint search; also returns what the encoder gave (for the list)."""
    sos = len(toks) - 1
    ctc, enc, valid, t_b = encode(model, x)
    hy = avsr.joint_search(ctc, step_decoder(model, enc, valid, t_b, sos), sos, max_len=min(avsr.DEC_LEN - 1, len(x)))
    return (avsr.detokenize(hy[0][0], toks) if hy else ""), (ctc, enc, valid, t_b)


def evaluate(model, clips, toks, pieces, sentences, label: str, listed: bool = True) -> dict:
    """Open reading (joint search, as the phone) and the sentence list (score - prior, as the phone), on CPU."""
    model = model.cpu().eval()
    sos = len(toks) - 1
    w = model.decoder.output_layer
    # LoRA wraps nothing in the output layer, so W, b are the base ones
    W, B = w.weight.detach().numpy(), w.bias.detach().numpy()
    ids_all = [avsr.text_units(s, pieces, toks) for s in sentences]
    with torch.no_grad():
        sd64 = avsr.ScoreDecoder(model, 64).eval()

        def rows_fn(sd, enc, valid):
            def f(prefixes):
                ys = np.full((avsr.DEC_BATCH, avsr.DEC_LEN), sos, np.int32)
                for i, p in enumerate(prefixes):
                    ys[i, : len(p)] = p
                h, lse = sd(torch.from_numpy(sd.embed_inputs(ys)), enc, torch.from_numpy(valid))
                return h.numpy()[: len(prefixes)], lse.numpy()[: len(prefixes)]
            return f

        rz = rows_fn(sd64, torch.zeros(1, 64, avsr.D_MODEL), np.zeros((1, 64), np.float32))
        prior = []
        for i in range(0, len(ids_all), avsr.DEC_BATCH):
            ch = ids_all[i : i + avsr.DEC_BATCH]
            h, lse = rz([[sos] + s for s in ch])
            prior += [sum(float(h[r, j] @ W[t] + B[t] - lse[r, j]) for j, t in enumerate(s + [sos])) for r, s in enumerate(ch)]
        prior = np.array(prior)
        edits = words = exact = top1 = 0
        rows = []
        for it in clips:
            read, (ctc, enc, valid, t_b) = read_open(model, it["x"], toks)
            e, nw = avsr.wer(it["text"], read)
            edits += e
            words += nw
            exact += int(e == 0)
            best = None
            if listed:
                sd = avsr.ScoreDecoder(model, t_b).eval()
                sc = np.array(avsr.score_sentences(ctc, rows_fn(sd, enc, valid), ids_all, sos, W, B))
                best = sentences[int(np.argmax(sc - avsr.PRIOR_WEIGHT * (1 - avsr.CTC_WEIGHT) * prior))]
                top1 += int(best.lower() == it["text"].lower())
            rows.append({"id": it["id"], "ref": it["text"], "read": read, "list_first": best})
    res = {"wer": round(edits / max(words, 1), 4), "exact": f"{exact}/{len(clips)}", "list_first": f"{top1}/{len(clips)}" if listed else None, "rows": rows}
    print(f"{label}: open-reading WER {100 * res['wer']:.1f}%, exact {res['exact']}, from the sentence list {res['list_first']}")
    return res


GRID_S1 = Path("~/.cache/mouna-avsr/samples/grid_partial/s1").expanduser()


def grid_clips(n: int) -> list[dict]:
    """n GRID s1 clips (CC BY 4.0: voiced, another person, a 51-word grammar), mouth-cropped once and cached next to
    the videos. The check for everything the person did not record: an adapter that learnt their sentences instead
    of their lips says those sentences' words here."""
    cache = GRID_S1.parent / f"s1_crops_{n}.npz"
    if not cache.exists():
        vids = random.Random(0).sample(sorted(GRID_S1.glob("*.mpg")), n)
        np.savez_compressed(cache, **{v.stem: avsr.crops_from_video(avsr.read_video(v)) for v in vids})
    z = np.load(cache)
    return [{"id": k, "text": avsr.grid_from_name(k), "x": avsr.model_input(z[k])} for k in sorted(z.files)]


def evaluate_grid(model, clips, toks, taught: set[str], label: str) -> dict:
    """Open reading of GRID clips: WER, and how many of the words read are the person's training words (not GRID's)."""
    model = model.cpu().eval()
    grid_words = {w for m in avsr.GRID_WORDS if m for w in m.values()} | {chr(c) for c in range(65, 91)}
    foreign = taught - grid_words
    edits = words = said = intruded = 0
    rows = []
    with torch.no_grad():
        for it in clips:
            read, _ = read_open(model, it["x"], toks)
            e, nw = avsr.wer(it["text"], read)
            edits, words = edits + e, words + nw
            out = read.upper().split()
            said, intruded = said + len(out), intruded + sum(w in foreign for w in out)
            rows.append({"id": it["id"], "ref": it["text"], "read": read})
    res = {"wer": round(edits / max(words, 1), 4), "taught_words_read": f"{intruded}/{said}", "rows": rows}
    print(f"{label} GRID: open-reading WER {100 * res['wer']:.1f}%, words from the training sentences {res['taught_words_read']}")
    return res


def main() -> None:
    ap = argparse.ArgumentParser(prog="mouna_encoder.adapt")
    ap.add_argument("data", type=Path, help="folder of recording sessions (avsr/train pulled from the phone)")
    ap.add_argument("--out", type=Path, default=avsr.OUT / "lora_person.pt")
    ap.add_argument("--report", type=Path, help="deck/data/freetalk-adapt-<person>.json")
    ap.add_argument("--targets", choices=TARGETS, default="all", help="encoder: tune how it sees the lips only (decoder unchanged)")
    ap.add_argument("--layers", type=int, help="adapt only the first N of the encoder's 12 layers (the ones nearest the pixels)")
    ap.add_argument("--ctc", type=float, default=0.1, help="CTC share of the training loss (Auto-AVSR's own: 0.1)")
    ap.add_argument("--mask", action="store_true", help="Auto-AVSR's time masking on the training clips")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--rank", type=int, default=8)
    ap.add_argument("--lr", type=float, default=5e-4)
    ap.add_argument("--held-out", type=float, default=0.2)
    ap.add_argument("--fold", type=int, default=0, help="which held-out fifth (0-4) of the shuffled sentences")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--grid", type=int, default=30, help="GRID clips for the general-English check (0: skip)")
    ap.add_argument("--no-before", action="store_true", help="skip reading with the base model (same for every run on a split)")
    ap.add_argument("--no-list", action="store_true", help="skip scoring the sentence list (faster experiments)")
    ap.add_argument("--device", default="mps" if torch.backends.mps.is_available() else "cpu")
    a = ap.parse_args()

    random.seed(a.seed)
    torch.manual_seed(a.seed)
    toks, pieces = avsr.tokens(), avsr.spm_pieces()
    clips = load_clips(a.data)
    order = list(range(len(clips)))
    random.Random(a.seed).shuffle(order)
    n_test = int(round(len(clips) * a.held_out))  # 0: train on every clip (the model to ship), nothing held out
    held = set(order[a.fold * n_test : (a.fold + 1) * n_test])
    test = [clips[i] for i in order if i in held]
    train = [clips[i] for i in order if i not in held]
    sentences = [l.strip() for l in PROMPTS.read_text().splitlines() if l.strip()]
    taught = {w for it in train for w in it["text"].upper().split()}
    grid = grid_clips(a.grid) if a.grid else []
    print(f"{len(clips)} clips: {len(train)} to train, {len(test)} held out (sentences never trained on), fold {a.fold}")

    model = avsr.load()
    before = None if a.no_before or not test else evaluate(model, test, toks, pieces, sentences, "before", not a.no_list)
    grid_before = evaluate_grid(model, grid, toks, taught, "before") if grid and not a.no_before else None

    _contiguous_conv_module()
    _frontend_pool2d()
    params = add_lora(model, a.rank, a.targets, a.layers)
    model.ctc_weight = a.ctc
    print(f"trainable ({a.targets}): {sum(p.numel() for p in params) / 1e6:.2f} M of {sum(p.numel() for p in model.parameters()) / 1e6:.0f} M")
    device = torch.device(a.device)
    torch.set_num_threads(max(1, (os.cpu_count() or 4) - 2))
    model.to(device).eval()  # eval: dropout off and BatchNorm's running statistics kept; only the adapters learn
    opt = torch.optim.AdamW(params, lr=a.lr, weight_decay=0.01)
    bs = 4
    steps = a.epochs * math.ceil(len(train) / bs)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=steps, pct_start=0.15)
    t0 = time.time()
    for ep in range(a.epochs):
        random.shuffle(train)
        losses = []
        for i in range(0, len(train), bs):
            x, lengths, label = batch(train[i : i + bs], toks, pieces, device, train=True, mask=a.mask)
            loss = train_loss(model, x, lengths, label)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(params, 5.0)
            opt.step()
            sched.step()
            losses.append(float(loss))
        print(f"epoch {ep + 1}/{a.epochs}: loss {np.mean(losses):.3f} ({time.time() - t0:.0f} s)")
    seconds = round(time.time() - t0)

    after = evaluate(model, test, toks, pieces, sentences, "after", not a.no_list) if test else None
    grid_after = evaluate_grid(model, grid, toks, taught, "after") if grid else None
    a.out.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"rank": a.rank, "targets": a.targets, "layers": a.layers, "state": lora_state(model.cpu())}, a.out)
    print(f"adapter -> {a.out}")
    if a.report:
        span = f"the first {a.layers} of the encoder's 12 layers'" if a.layers else "the encoder's"
        where = {"all": f"LoRA rank {a.rank} on {span} attention/feed-forward and the decoder's attention, plus ",
                 "encoder": f"LoRA rank {a.rank} on {span} attention/feed-forward (decoder unchanged), plus ", "frontend": "only "}[a.targets]
        drop = lambda r: r and {k: v for k, v in r.items() if k != "rows"}
        report = {
            "what": f"Free talk tuned to one person (issue #5 B): {where}the front end's BatchNorm scale/shift; base weights unchanged",
            "data": f"{len(clips)} prompted silent recordings from the app; held out by sentence (fold {a.fold}, seed {a.seed}): {len(test)} sentences never trained on",
            "training": {"targets": a.targets, "layers": a.layers or 12, "rank": a.rank, "ctc_weight": a.ctc, "time_mask": a.mask,
                         "epochs": a.epochs, "lr": a.lr, "batch": bs, "device": str(device), "seconds": seconds},
            "before": drop(before),
            "after": drop(after),
            "grid": {"what": f"{len(grid)} GRID s1 clips (CC BY 4.0; voiced, another person, none of the person's sentences): "
                             "open-reading WER and how many words read are the person's training words",
                     "before": drop(grid_before), "after": drop(grid_after)} if grid else None,
            "held_out": [{"ref": c["ref"], **({"before": b["read"]} if b else {}), "after": c["read"]}
                         for b, c in zip(before["rows"] if before else [None] * len(test), after["rows"])] if after else None,
            "grid_reads": [{"ref": c["ref"], **({"before": b["read"]} if b else {}), "after": c["read"]}
                           for b, c in zip(grid_before["rows"] if grid_before else [None] * len(grid), grid_after["rows"])] if grid else None,
            "caveat": "one person, one evening (three sessions minutes apart), 20% held out; list = the 154 prompts",
        }
        a.report.write_text(json.dumps(report, indent=1))
        print(f"report -> {a.report}")


if __name__ == "__main__":
    main()


def merged(path: Path, model=None):
    """The base model with a saved adapter folded into its weights (W += scale * B A; BatchNorm scale/shift replaced):
    a plain model again, so the NPU graphs export exactly as before (python -m mouna_encoder avsr-export --lora)."""
    blob = torch.load(path, map_location="cpu", weights_only=True)
    model = model or avsr.load()
    add_lora(model, blob["rank"], blob.get("targets", "all"), blob.get("layers"))
    missing = model.load_state_dict(blob["state"], strict=False)
    assert not missing.unexpected_keys, missing.unexpected_keys
    with torch.no_grad():
        for parent in list(model.modules()):
            for name, child in list(parent.named_children()):
                if isinstance(child, LoRALinear):
                    child.base.weight += child.scale * (child.b @ child.a)
                    setattr(parent, name, child.base)
    for p in model.parameters():
        p.requires_grad_(False)
    return model.eval()
