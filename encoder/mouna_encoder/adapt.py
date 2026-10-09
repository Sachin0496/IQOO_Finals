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


def _contiguous_conv_module():
    """The Conformer convolution passes a transposed tensor to conv1d, whose backward fails on Apple MPS ("view size is
    not compatible with input tensor's size and stride"); the same maths with contiguous tensors."""
    avsr._import_avsr()
    from espnet.nets.pytorch_backend.encoder.conformer_encoder import ConvolutionModule

    def forward(self, x):
        x = x.transpose(1, 2).contiguous()
        x = torch.nn.functional.glu(self.pointwise_cov1(x), dim=1)
        dw = self.depthwise_conv  # the depthwise conv1d as an identical conv2d over a height of 1 (MPS can train that)
        x = torch.nn.functional.conv2d(x.unsqueeze(2), dw.weight.unsqueeze(2), dw.bias, padding=(0, dw.padding[0]), groups=dw.groups).squeeze(2)
        x = self.activation(self.norm(x))
        return self.pointwise_cov2(x).transpose(1, 2).contiguous()

    ConvolutionModule.forward = forward


def add_lora(model, r: int = 8) -> list[nn.Parameter]:
    """Wrap the attention and feed-forward linears of every encoder and decoder layer; returns the trainable params."""
    for p in model.parameters():
        p.requires_grad_(False)
    params: list[nn.Parameter] = []

    def wrap(parent, name):
        lin = getattr(parent, name)
        if isinstance(lin, nn.Linear):
            lo = LoRALinear(lin, r)
            setattr(parent, name, lo)
            params.extend([lo.a, lo.b])

    for layer in model.encoder.encoders:
        for n in ATTN:
            wrap(layer.self_attn, n)
        for ff in (layer.feed_forward, getattr(layer, "feed_forward_macaron", None)):
            if ff is None:
                continue
            wrap(ff, "w_1")
            wrap(ff, "w_2")
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


def batch(items, toks, pieces, device, train: bool):
    xs, labels = [], []
    o = (avsr.CROP - avsr.ROI) // 2
    for it in items:
        c = it["crops96"]
        if train:  # Auto-AVSR's training crop: a random 88 px window, not the centre
            dy, dx = random.randint(0, 8), random.randint(0, 8)
        else:
            dy = dx = o
        v = (c[:, dy : dy + avsr.ROI, dx : dx + avsr.ROI].astype(np.float32) / 255.0 - avsr.MEAN) / avsr.STD
        xs.append(torch.from_numpy(v))
        labels.append(torch.tensor(avsr.text_units(it["text"], pieces, toks)))
    lengths = torch.tensor([len(x) for x in xs])
    xpad = torch.zeros(len(xs), int(lengths.max()), avsr.ROI, avsr.ROI)
    for i, x in enumerate(xs):
        xpad[i, : len(x)] = x
    lpad = torch.full((len(labels), max(len(l) for l in labels)), -1, dtype=torch.long)
    for i, l in enumerate(labels):
        lpad[i, : len(l)] = l
    return xpad.unsqueeze(2).to(device), lengths.to(device), lpad.to(device)


def evaluate(model, clips, toks, pieces, sentences, label: str) -> dict:
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
            n = len(it["x"])
            t_b = avsr.bucket(n)
            x, valid = avsr.pad_to(it["x"], t_b)
            lp, enc = avsr.StaticVsr(model, t_b).eval()(torch.from_numpy(x), torch.from_numpy(valid))
            dd = avsr.StaticDecoder(model, t_b).eval()
            sd = avsr.ScoreDecoder(model, t_b).eval()

            def decode(prefixes):
                ys = np.full((avsr.DEC_BATCH, avsr.DEC_LEN), sos, np.int32)
                sel = np.zeros((avsr.DEC_BATCH, avsr.DEC_LEN), np.float32)
                for i, p in enumerate(prefixes):
                    ys[i, : len(p)] = p
                    sel[i, len(p) - 1] = 1
                return dd(torch.from_numpy(dd.embed_inputs(ys)), enc, torch.from_numpy(valid), torch.from_numpy(sel)).numpy()[: len(prefixes)]

            ctc = lp[0, :n].numpy().astype(np.float64)
            hy = avsr.joint_search(ctc, decode, sos, max_len=min(avsr.DEC_LEN - 1, n))
            read = avsr.detokenize(hy[0][0], toks) if hy else ""
            e, nw = avsr.wer(it["text"], read)
            edits += e
            words += nw
            exact += int(e == 0)
            sc = np.array(avsr.score_sentences(ctc, rows_fn(sd, enc, valid), ids_all, sos, W, B))
            best = sentences[int(np.argmax(sc - avsr.PRIOR_WEIGHT * (1 - avsr.CTC_WEIGHT) * prior))]
            top1 += int(best.lower() == it["text"].lower())
            rows.append({"id": it["id"], "ref": it["text"], "read": read, "list_first": best})
    res = {"wer": round(edits / max(words, 1), 4), "exact": f"{exact}/{len(clips)}", "list_first": f"{top1}/{len(clips)}", "rows": rows}
    print(f"{label}: open-reading WER {100 * res['wer']:.1f}%, exact {res['exact']}, from the sentence list {res['list_first']}")
    return res


def main() -> None:
    ap = argparse.ArgumentParser(prog="mouna_encoder.adapt")
    ap.add_argument("data", type=Path, help="folder of recording sessions (avsr/train pulled from the phone)")
    ap.add_argument("--out", type=Path, default=avsr.OUT / "lora_person.pt")
    ap.add_argument("--report", type=Path, help="deck/data/freetalk-adapt-<person>.json")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--rank", type=int, default=8)
    ap.add_argument("--lr", type=float, default=5e-4)
    ap.add_argument("--held-out", type=float, default=0.2)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--device", default="cpu", help="cpu (default) or mps: on torch 2.5 the Conformer's backward fails on MPS")
    a = ap.parse_args()

    random.seed(a.seed)
    torch.manual_seed(a.seed)
    toks, pieces = avsr.tokens(), avsr.spm_pieces()
    clips = load_clips(a.data)
    order = list(range(len(clips)))
    random.Random(a.seed).shuffle(order)
    n_test = max(1, int(round(len(clips) * a.held_out)))
    test = [clips[i] for i in order[:n_test]]
    train = [clips[i] for i in order[n_test:]]
    sentences = [l.strip() for l in PROMPTS.read_text().splitlines() if l.strip()]
    print(f"{len(clips)} clips: {len(train)} to train, {len(test)} held out (sentences never trained on)")

    model = avsr.load()
    before = evaluate(model, test, toks, pieces, sentences, "before")

    _contiguous_conv_module()
    params = add_lora(model, a.rank)
    print(f"trainable: {sum(p.numel() for p in params) / 1e6:.2f} M of {sum(p.numel() for p in model.parameters()) / 1e6:.0f} M")
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
            x, lengths, label = batch(train[i : i + bs], toks, pieces, device, train=True)
            loss, loss_ctc, loss_att, acc = model(x, lengths, label)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(params, 5.0)
            opt.step()
            sched.step()
            losses.append(float(loss))
        print(f"epoch {ep + 1}/{a.epochs}: loss {np.mean(losses):.3f} ({time.time() - t0:.0f} s)")

    after = evaluate(model, test, toks, pieces, sentences, "after")
    a.out.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"rank": a.rank, "state": lora_state(model.cpu())}, a.out)
    print(f"adapter -> {a.out}")
    if a.report:
        report = {
            "what": "Free talk tuned to one person (issue #5 B): LoRA rank %d on encoder + decoder attention/feed-forward and the front end's BatchNorm; base weights unchanged" % a.rank,
            "data": f"{len(clips)} prompted silent recordings from the app; held out by sentence: {len(test)} sentences never trained on",
            "training": {"epochs": a.epochs, "lr": a.lr, "batch": bs, "device": str(device), "seconds": round(time.time() - t0)},
            "before": {k: v for k, v in before.items() if k != "rows"},
            "after": {k: v for k, v in after.items() if k != "rows"},
            "held_out": [{"ref": b["ref"], "before": b["read"], "after": c["read"]} for b, c in zip(before["rows"], after["rows"])],
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
    add_lora(model, blob["rank"])
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
