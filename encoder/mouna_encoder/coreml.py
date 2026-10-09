"""Reading the LipLearner Core ML package (MIT, rkmtlab/LipLearner) without macOS.

``load_program`` returns the MIL main block and every constant as numpy.
``run`` executes the graph op by op; it is the reference the PyTorch rebuild is checked against.
"""

from __future__ import annotations

import struct
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from coremltools.proto import MIL_pb2, Model_pb2

_NP = {
    "FLOAT16": np.float16,
    "FLOAT32": np.float32,
    "FLOAT64": np.float64,
    "INT32": np.int32,
    "INT64": np.int64,
    "BOOL": np.bool_,
    "INT8": np.int8,
    "UINT8": np.uint8,
}


def _dtype(t: MIL_pb2.ValueType) -> np.dtype:
    return _NP[MIL_pb2.DataType.Name(t.tensorType.dataType)]


def _shape(t: MIL_pb2.ValueType) -> tuple[int, ...]:
    return tuple(d.constant.size for d in t.tensorType.dimensions)


class BlobFile:
    """MIL blob storage v2: each blob has a 64-byte header (0xDEADBEEF, dtype, size, data offset)."""

    def __init__(self, path: Path) -> None:
        self.buf = Path(path).read_bytes()

    def read(self, offset: int, dtype: np.dtype, shape: tuple[int, ...]) -> np.ndarray:
        sentinel, _, size, data = struct.unpack_from("<IIQQ", self.buf, offset)
        if sentinel != 0xDEADBEEF:
            raise ValueError(f"bad blob header at {offset}")
        return np.frombuffer(self.buf, dtype=dtype, count=size // np.dtype(dtype).itemsize, offset=data).reshape(shape)


def _value(v: MIL_pb2.Value, blobs: BlobFile) -> np.ndarray | str | None:
    kind = v.WhichOneof("value")
    if kind == "blobFileValue":
        return blobs.read(v.blobFileValue.offset, _dtype(v.type), _shape(v.type))
    t = v.immediateValue.tensor
    field = t.WhichOneof("value")
    if field is None:
        return None
    if field == "strings":
        vals = list(t.strings.values)
        return vals[0] if len(vals) == 1 and not _shape(v.type) else np.array(vals)
    if field == "bytes":
        arr = np.frombuffer(t.bytes.values, dtype=_dtype(v.type))
    else:
        arr = np.array(list(getattr(t, field).values), dtype=_dtype(v.type))
    return arr.reshape(_shape(v.type))


def load_program(package: Path) -> tuple[MIL_pb2.Block, dict[str, np.ndarray]]:
    package = Path(package)
    spec = Model_pb2.Model()
    spec.ParseFromString((package / "model.mlmodel").read_bytes())
    fn = spec.mlProgram.functions["main"]
    block = fn.block_specializations[fn.opset]
    blobs = BlobFile(package / "weight.bin")
    consts: dict[str, np.ndarray] = {}

    def collect(b: MIL_pb2.Block) -> None:
        for op in b.operations:
            if op.type == "const":
                consts[op.outputs[0].name] = _value(op.attributes["val"], blobs)
            for inner in op.blocks:
                collect(inner)

    collect(block)
    return block, consts


# ---------- reference interpreter ----------


def _t(x) -> torch.Tensor:
    return torch.as_tensor(np.asarray(x, dtype=np.float32))


def _pad(x: torch.Tensor, pad_type: str, pad, value: float = 0.0) -> torch.Tensor:
    if pad_type != "custom":
        if pad_type == "valid":
            return x
        raise NotImplementedError(pad_type)
    p = np.asarray(pad).reshape(-1, 2)[::-1].reshape(-1).tolist()  # F.pad wants last dim first
    return F.pad(x, [int(v) for v in p], value=value)



def run(block: MIL_pb2.Block, consts: dict[str, np.ndarray], inputs: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    env: dict[str, object] = {**consts, **inputs}
    _exec(block, env)
    return {name: env[name] for name in block.outputs}


def _exec(block: MIL_pb2.Block, env: dict) -> None:
    for op in block.operations:
        if op.type == "const":
            continue
        a = {k: [env[arg.name] if arg.name else None for arg in v.arguments] for k, v in op.inputs.items()}
        g = lambda k, d=None: a[k][0] if k in a else d  # noqa: E731
        out = _op(op, g, a, env)
        outs = out if isinstance(out, tuple) else (out,)
        for o, val in zip(op.outputs, outs):
            env[o.name] = val


def _op(op, g, a, env):  # noqa: C901 - one branch per MIL op, kept flat on purpose
    t = op.type
    if t == "cast":
        dt = str(g("dtype"))
        return np.asarray(g("x")).astype(np.int32 if dt.startswith("int") else bool if dt == "bool" else np.float32)
    if t in ("relu", "sigmoid", "tanh"):
        return getattr(torch, t)(_t(g("x"))).numpy()
    if t in ("add", "sub", "mul"):
        x, y = np.asarray(g("x")), np.asarray(g("y"))
        return {"add": np.add, "sub": np.subtract, "mul": np.multiply}[t](x, y)
    if t == "less":
        return np.less(g("x"), g("y"))
    if t == "shape":
        return np.array(np.asarray(g("x")).shape, dtype=np.int32)
    if t == "gather":
        return np.take(np.asarray(g("x")), np.asarray(g("indices")).astype(np.int64), axis=int(g("axis")))
    if t == "scatter":
        data = np.array(g("data"), copy=True)
        idx = np.asarray(g("indices")).astype(np.int64)
        axis = int(g("axis"))
        sl = [slice(None)] * data.ndim
        sl[axis] = idx
        data[tuple(sl)] = g("updates")
        return data
    if t == "concat":
        return np.concatenate([np.atleast_1d(v) for v in a["values"]], axis=int(g("axis")))
    if t == "fill":
        return np.full(tuple(int(s) for s in np.asarray(g("shape"))), g("value"), dtype=np.float32)
    if t == "squeeze":
        return np.squeeze(g("x"), axis=tuple(int(i) for i in np.atleast_1d(g("axes"))))
    if t == "expand_dims":
        x = np.asarray(g("x"))
        for ax in sorted(int(i) for i in np.atleast_1d(g("axes"))):
            x = np.expand_dims(x, ax)
        return x
    if t == "reshape":
        return np.reshape(g("x"), tuple(int(s) for s in np.asarray(g("shape"))))
    if t == "transpose":
        return np.transpose(g("x"), tuple(int(i) for i in g("perm")))
    if t == "reverse":
        return np.flip(g("x"), axis=tuple(int(i) for i in np.atleast_1d(g("axes"))))
    if t == "reduce_mean":
        return np.mean(g("x"), axis=tuple(int(i) for i in np.atleast_1d(g("axes"))), keepdims=bool(g("keep_dims")))
    if t == "slice_by_index":
        x = np.asarray(g("x"))
        begin, end = np.asarray(g("begin")), np.asarray(g("end"))
        stride = np.asarray(g("stride", np.ones_like(begin)))
        bm = np.asarray(g("begin_mask", np.zeros(len(begin), bool)))
        em = np.asarray(g("end_mask", np.zeros(len(begin), bool)))
        sq = np.asarray(g("squeeze_mask", np.zeros(len(begin), bool)))
        sl = tuple(slice(None if bm[i] else int(begin[i]), None if em[i] else int(end[i]), int(stride[i])) for i in range(len(begin)))
        y = x[sl]
        return np.squeeze(y, axis=tuple(i for i in range(len(sq)) if sq[i])) if sq.any() else y
    if t == "linear":
        x = _t(g("x"))
        return F.linear(x, _t(g("weight")), _t(g("bias")) if g("bias") is not None else None).numpy()
    if t == "batch_norm":
        x = _t(g("x"))
        return F.batch_norm(x, _t(g("mean")), _t(g("variance")), _t(g("gamma")), _t(g("beta")), False, 0.0, float(g("epsilon"))).numpy()
    if t == "conv":
        x = _t(g("x"))
        w = _t(g("weight"))
        x = _pad(x, str(g("pad_type")), g("pad"))
        conv = F.conv3d if w.ndim == 5 else F.conv2d
        b = _t(g("bias")) if g("bias") is not None else None
        return conv(x, w, b, stride=[int(s) for s in g("strides")], dilation=[int(d) for d in g("dilations")], groups=int(g("groups"))).numpy()
    if t == "max_pool":
        x = _pad(_t(g("x")), str(g("pad_type")), g("pad"), value=-np.inf)
        k = [int(s) for s in g("kernel_sizes")]
        pool = F.max_pool3d if len(k) == 3 else F.max_pool2d
        return pool(x, k, [int(s) for s in g("strides")], ceil_mode=bool(g("ceil_mode"))).numpy()
    if t == "while_loop":
        cond, body = op.blocks
        loop_vars = [np.asarray(v) for v in a["loop_vars"]]
        while True:
            scope = dict(env)
            scope.update({inp.name: v for inp, v in zip(cond.inputs, loop_vars)})
            _exec(cond, scope)
            if not bool(np.asarray(scope[cond.outputs[0]])):
                break
            scope = dict(env)
            scope.update({inp.name: v for inp, v in zip(body.inputs, loop_vars)})
            _exec(body, scope)
            loop_vars = [np.asarray(scope[n]) for n in body.outputs]
        return tuple(loop_vars)
    raise NotImplementedError(t)



