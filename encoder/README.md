# Encoder (spike test S2)

LipLearner's visual-speech encoder (3D-conv front end, SE-ResNet-18, 3-layer bidirectional GRU, 500-d projection),
recovered from the **Core ML package in the MIT-licensed [rkmtlab/LipLearner](https://github.com/rkmtlab/LipLearner) repo**.
The PyTorch checkpoint on Google Drive now requires a sign-in, so we do not use it.

```bash
pip install -e encoder[dev]
python -m mouna_encoder fetch       # Core ML package -> encoder/weights (not committed)
python -m mouna_encoder convert     # PyTorch rebuild, parity vs the Core ML graph, ONNX export
python -m mouna_encoder bench       # CPU latency per window
python -m mouna_encoder embed data/spike --out data/embeddings.npz
python -m mouna_harness evaluate data/spike --embeddings data/embeddings.npz   # plan A score
```

How it is checked: `coreml.py` runs the Core ML program op by op (a small MIL interpreter, including the GRU
`while_loop`s); `load.py` maps its constants onto `model.py` by following the graph's data flow, not by name.

## Measured (laptop CPU, not the phone)

| | |
|---|---|
| Parameters | 59.5 M (GRU ≈ 47 M) |
| PyTorch vs Core ML graph | max abs diff 1.5e-5 |
| ONNX vs PyTorch | cosine 1.0 |
| ONNX fp32 size | 228 MB (fp16 ≈ 114 MB; int8 ≈ 60 MB expected on QNN **[measure]**) |
| Latency, 1 s / 2 s / 2.7 s window | 58 / 100 / 195 ms |

Findings that change the plan:
- The bundle target (< 100 MB) needs int8 weights on the NPU path. ONNX Runtime's dynamic int8 skips GRU ops, so
  quantisation belongs to the QNN conversion (Qualcomm AI Hub), not ONNX Runtime.
- The encoder expects LRW-style crops: 88 px grey at 25 fps. `preprocess.py` maps the Lab's 96 px crop through a
  128 px ROI; the right field of view is still to be confirmed on real clips **[verify]**.
- Weights were pre-trained on LRW (research licence). The repository is MIT; the weights' terms are still to be confirmed.
