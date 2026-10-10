# ISL: AI4Bharat OpenHands SL-GCN on INCLUDE (existing model, export only)

The app's sign mode uses AI4Bharat's published pose-based ISL recogniser: Decoupled GCN (SL-GCN) trained on INCLUDE,
263 isolated Indian Sign Language signs, 93.5% on INCLUDE in the OpenHands paper (their number, not ours).
We do not train anything: `export_isl.py` rebuilds their network from their code, loads their checkpoint and exports
ONNX.

```bash
# from https://github.com/AI4Bharat/OpenHands (Apache-2.0): openhands/models/encoder/graph/{decoupled_gcn,graph_utils}.py
# and openhands/models/decoder/fc.py into ohpkg/; checkpoint + labels from release checkpoints_v1:
#   include_slgcn.zip -> slgcn/include/sl_gcn/{config.yaml, epoch=112-step=12203.ckpt}
#   include_metadata.zip -> meta/Train_Test_Split/train_include.csv
python export_isl.py   # -> isl_include_slgcn.onnx (16 MB), isl_include_labels.json; ONNX vs PyTorch max diff 2.5e-6
adb push isl_include_slgcn.onnx isl_include_labels.json /sdcard/Android/data/app.mouna/files/isl/
```

Two outputs: `probs` (263 INCLUDE words) and `features` (the encoder's pooled 256-d output, before the classifier; added
10 Oct). The app matches a new sign against the person's own taught signs by the cosine of `features`
(`engine/SignBook.kt`), measured in `eval/teach.json` (`eval/teach_eval.py`). With the env this was rebuilt in:
`uv venv -p 3.12 && uv pip install torch onnx onnxruntime pyyaml omegaconf numpy`. Push the model to `/sdcard/Mouna/isl/`
(kept across reinstalls once "Keep models" is on) or `/sdcard/Android/data/app.mouna/files/isl/`.

Input `keypoints` float32 (1, 2, T, 27), any T: MediaPipe Holistic "minimal 27" points (pose 0, 2, 5, 11, 12, 13, 14;
each hand's wrist and finger points 4, 5, 8, 9, 12, 13, 16, 17, 20), x and y, centred on the mean shoulder midpoint
and scaled by the mean shoulder distance over the clip. Output `probs` (1, 263), labels in `isl_include_labels.json`
(sorted INCLUDE "Word" names, as in OpenHands' `INCLUDEDataset.read_glosses`).

Licences: OpenHands code Apache-2.0; INCLUDE dataset CC BY 4.0 (Zenodo 4010759).

Accuracy through the app's pipeline on INCLUDE's held-out signers, and on the iQOO 15: `eval/README.md`. Accuracy on
our own signers: not measured.
