# Sign mode on real signers

Nobody on the team signs, so Sign mode is measured here on **INCLUDE's held-out test videos**: real deaf signers,
clips the model never saw in training. Each one goes through the app's own steps in Python (`signs.py`), with the
app's own MediaPipe `.task` files and the same model: `Signer.keypoints` → `SignSegmenter` → `Isl` → `MounaApp.signed`.

```bash
# needs: mediapipe onnxruntime opencv-python-headless remotezip numpy, models/isl/isl_include_slgcn.onnx (export_isl.py)
# and INCLUDE's test split CSV (include_metadata.zip from OpenHands' checkpoints_v1 release)
export INCLUDE_TEST_CSV=.../Train_Test_Split/test_include.csv
python include_eval.py extract   # 73 test videos (Greetings + Pronouns), read from Zenodo by range, keypoints -> cache/
python include_eval.py score     # -> results.json
python include_eval.py vectors   # -> android/app/src/test/resources/sign_segments.json (SignSegmenterTest)
python include_eval.py device    # -> cache/device/stream*.json + expected.json, for the phone:
adb push cache/device/stream0.json /sdcard/Android/data/app.mouna/files/qa/
adb shell am broadcast -a app.mouna.SIGN --es stream /sdcard/Android/data/app.mouna/files/qa/stream0.json   # logcat tag Mouna
```

Two framings of every clip: **landscape** (the training videos' own framing, at 640×360) and **portrait** (the
phone's 480×640 frame, a centre 3:4 crop, which cuts off some arm movement). **Continuous** = 6 held-out clips
joined into one stream, a person signing six words in a row, 20 random streams = 120 signs (seed 0).

## Results (10 Oct 2026, `results.json`)

| 73 clips, 17 words | Landscape | Portrait |
|---|---|---|
| Whole clip, model top-1 / top-5 | 59 (81%) / 67 (92%) | 42 (58%) / 55 (75%) |
| Frames where the hand model sees a hand | 94% | 97% |
| Clips where the **old** segmenter (hands visible) ever closed a sign | 4 / 73 | 0 / 73 |
| Clips where the **new** segmenter (wrist above chest) closed a sign | 72 / 73 | 72 / 73 |

Continuous signing, 120 signs:

| | Spoken right | Spoken wrong | Offered 3, right one inside | Offered 3, not inside | Missed |
|---|---|---|---|---|---|
| Old app, landscape | 12 | 25 | 3 | 3 | 77 |
| **New app, landscape** | **79** | **16** | **16** | 9 | **0** |
| Old app, portrait | 3 | 18 | 7 | 12 | 80 |
| **New app, portrait** | **53** | **20** | **17** | 30 | **0** |

"New app" = the wrist-height segmenter and speaking only at p ≥ 0.9 (was 0.6). With the new segmenter, 0.6 spoke
82 right and 31 wrong in landscape (61 / 42 in portrait); 0.9 trades 3 right ones for 15 fewer wrong ones and offers
the top 3 instead.

**On the iQOO 15** (5 streams, 25 signs, `include_eval.py device`): same cut points, same words and same
probabilities as the Python reference for all 25; about 0.1 s per sign.

## What this does not show

- These are INCLUDE's signers, standing, filmed in a studio. Nobody has signed live in front of the phone yet.
- Only 17 of the 263 words (Greetings, Pronouns). The other categories are on Zenodo (`ZIPS` in `include_eval.py`).
- Portrait is a crop of landscape video, not a phone held by a seated person.

Data: INCLUDE (Sridhar et al., ACM MM 2020), CC BY 4.0, Zenodo 4010759. Only keypoints are kept (`cache/`, not
committed); the test vectors contain keypoints, no video.
