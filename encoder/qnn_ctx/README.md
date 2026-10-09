# Mouna encoder: QNN context binaries (Qualcomm AI Hub, fp16)

Compiled from `encoder_static48.onnx` (AI Hub model `mmrgxgx6q`) with `--target_runtime precompiled_qnn_onnx`.

| dir | chip | AI Hub compile job | on-device parity job | model.bin |
|---|---|---|---|---|
| `sm8650/` | Snapdragon 8 Gen 3 (Galaxy S24 family) | `jp2o0oz6g` (SUCCESS) | `j5m9qknqg` (SUCCESS) | 125,195,000 B |
| `sm8850/` | Snapdragon 8 Elite Gen 5 (Galaxy S26 family) | `jpy8r8y0g` (SUCCESS) | `jpx0nrk9p` (SUCCESS) | 127,292,152 B |

Each dir holds `model.onnx` (330-byte EPContext wrapper: `ep_cache_context=./model.bin`, `embed_mode=0`, `source=QNN`)
and `model.bin` (the QNN context, Git LFS). Load `model.onnx` with ONNX Runtime + QNN EP (HTP); keep `model.bin` next to it.

- Input: `frames` float32 `[48, 5, 88, 88]`
- Output: `output_0` float32 `[1, 500]`

int8 QNN-context and LiteRT (npu/gpu/cpu) compiles of the same model failed on AI Hub (jobs of 2026-10-06 22:16-22:35 UTC);
fp16 context is the working NPU path.