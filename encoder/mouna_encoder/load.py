"""Map the Core ML program's constants onto ``LipEncoder`` by following the graph's data flow."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import torch

from .coreml import load_program
from .model import BLOCKS, LipEncoder


def _args(op, key: str) -> list[str]:
    return [a.name for a in op.inputs[key].arguments] if key in op.inputs else []


def _arg(op, key: str) -> str | None:
    names = _args(op, key)
    return names[0] if names else None


def _conv_map(ops, consts) -> dict[str, np.ndarray]:
    convs = [op for op in ops if op.type == "conv"]
    sd: dict[str, np.ndarray] = {}

    def put(prefix: str, op) -> None:
        sd[f"{prefix}.weight"] = consts[_arg(op, "weight")]
        sd[f"{prefix}.bias"] = consts[_arg(op, "bias")]

    put("front", convs[0])
    queue = convs[1:]
    for i, (cin, cout, _, has_ds) in enumerate(BLOCKS):
        roles: dict[str, object] = {}
        need = {"conv1", "conv2", "se_reduce", "se_expand"} | ({"downsample"} if has_ds else set())
        while set(roles) != need:
            op = queue.pop(0)
            o, ci, kh = consts[_arg(op, "weight")].shape[:3]
            if kh == 3:
                roles["conv2" if "conv1" in roles else "conv1"] = op
            elif o == cout // 16:
                roles["se_reduce"] = op
            elif ci == cout // 16:
                roles["se_expand"] = op
            elif has_ds and (o, ci) == (cout, cin):
                roles["downsample"] = op
            else:
                raise ValueError(f"block {i}: unexpected conv {o}x{ci}x{kh}")
        for role, op in roles.items():
            put(f"blocks.{i}.{role}", op)
    if queue:
        raise ValueError(f"{len(queue)} convs left over")
    return sd


def _gru_map(ops, consts) -> dict[str, np.ndarray]:
    producer = {o.name: op for op in ops for o in op.outputs}
    loops = [op for op in ops if op.type == "while_loop"]
    if len(loops) != 6:
        raise ValueError(f"expected 6 GRU loops, found {len(loops)}")
    sd: dict[str, np.ndarray] = {}
    for k, loop in enumerate(loops):
        body = loop.blocks[1]
        inner = {o.name: op for op in body.operations for o in op.outputs}
        # backward direction reads a reversed copy of its input sequence
        src = next(_arg(op, "x") for op in body.operations if op.type == "gather" and _arg(op, "x") not in inner and _arg(op, "x") in producer)
        reverse = producer[src].type == "reverse" or any(
            producer.get(n) is not None and producer[n].type == "reverse" for n in _args(producer[src], "x")
        )
        linears = {o.outputs[0].name: o for o in body.operations if o.type == "linear"}
        # n gate: r * (W_hn h + b_hn) is the only mul of a sigmoid with a linear
        mul = next(op for op in body.operations if op.type == "mul" and _arg(op, "y") in linears and inner[_arg(op, "x")].type == "sigmoid")
        n_hh = linears[_arg(mul, "y")]
        r_sig = inner[_arg(mul, "x")]
        n_add = next(op for op in body.operations if op.type == "add" and _arg(op, "y") == mul.outputs[0].name)
        n_ih = linears[_arg(n_add, "x")]
        z_sig = next(op for op in body.operations if op.type == "sigmoid" and op is not r_sig)

        def pair(sig):
            add = inner[_arg(sig, "x")]
            return linears[_arg(add, "x")], linears[_arg(add, "y")]

        r_ih, r_hh = pair(r_sig)
        z_ih, z_hh = pair(z_sig)
        w = lambda op: consts[_arg(op, "weight")]  # noqa: E731
        b = lambda op: consts[_arg(op, "bias")]  # noqa: E731
        sfx = f"l{k // 2}" + ("_reverse" if reverse else "")
        sd[f"gru.weight_ih_{sfx}"] = np.concatenate([w(r_ih), w(z_ih), w(n_ih)])
        sd[f"gru.weight_hh_{sfx}"] = np.concatenate([w(r_hh), w(z_hh), w(n_hh)])
        sd[f"gru.bias_ih_{sfx}"] = np.concatenate([b(r_ih), b(z_ih), b(n_ih)])
        sd[f"gru.bias_hh_{sfx}"] = np.concatenate([b(r_hh), b(z_hh), b(n_hh)])
    return sd


def from_coreml(package: Path) -> LipEncoder:
    block, consts = load_program(package)
    ops = list(block.operations)
    sd = {**_conv_map(ops, consts), **_gru_map(ops, consts)}
    bn = next(op for op in ops if op.type == "batch_norm")
    sd.update(
        {
            "bn.weight": consts[_arg(bn, "gamma")],
            "bn.bias": consts[_arg(bn, "beta")],
            "bn.running_mean": consts[_arg(bn, "mean")],
            "bn.running_var": consts[_arg(bn, "variance")],
        }
    )
    head = [op for op in ops if op.type == "linear"][-1]
    sd["head.weight"] = consts[_arg(head, "weight")]
    sd["head.bias"] = consts[_arg(head, "bias")]

    model = LipEncoder()
    state = {k: torch.from_numpy(np.asarray(v, dtype=np.float32).copy()) for k, v in sd.items()}
    missing, unexpected = model.load_state_dict(state, strict=False)
    missing = [m for m in missing if not m.endswith("num_batches_tracked")]
    if missing or unexpected:
        raise ValueError(f"missing {missing}, unexpected {unexpected}")
    return model.eval()
