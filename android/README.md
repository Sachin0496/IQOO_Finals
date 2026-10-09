# Mouna for Android

Kotlin + Compose, CameraX, MediaPipe Face Landmarker, ONNX Runtime (QNN EP) and the decision core in `core/`.

| Module | What |
|---|---|
| `core/` | Pure Kotlin port of `harness/mouna_harness/core.py` (Learner, `decide`, switch, gaze, Ask, blocks, encoder input, self-test), pinned to Python by `harness/vectors/core.json`. Core lane. |
| `app/` `app.mouna.app` | The product app (app lane). |
| `app/` `app.mouna.probe` | The spike probe (S1 crop/fps, S5 voices, S6 permissions + soak), now a QA tool opened from Settings. |

## The app

| Screen | What it does |
|---|---|
| **Speak** | Camera card with the lip contour; the activity gate finds each utterance hands-free → encoder → `Learner.predict` → `decide`. SPEAK plays the phrase (pre-rendered voice pack, phone TTS fallback). Phrases as pictures for touch. |
| **Prompts** | CONFIRM (one picture, yes/no; Tier C always), RESCUE (two pictures; look and hold, or eyes + personal switch), CHOOSE (3–4 pictures; switch scanning), ASK, NOT_TAUGHT (two "maybe" pictures). `maybeNone` puts "None of these" first. A pick is learned from that mouthing; "None of these" becomes a negative. |
| **Teach** | Active teaching from `nextToTeach` (first / check / missed / close_to), auto-continues until the pack is safe; look-alike pairs from `separability` with Rephrase; 5 "none of these" negatives; add/remove phrases. |
| **Ask** | The Lab's yes/no ward tree; the personal switch answers yes. |
| **Settings** | Caregiver language and voice, Careful mode, personal switch and eye setup, encoder status + self-test, QA probe, start over. |

Lip encoder, in order: QNN context binary on the NPU → plain ONNX on the CPU → lip-landmark trajectory (`ShapeEncoder`),
each gated by the start-up self-test (cosine ≥ 0.99). The weights are gitignored; push them to the phone:

```bash
adb push qnn_ctx_fp16.onnx /sdcard/Android/data/app.mouna/files/encoder/
```

Examples stay on the phone (app-private files, `allowBackup=false`); no video is stored; no INTERNET permission.

## Build, test, run

```bash
cd lab && npm run vendor            # face_landmarker.task, shared with the Lab
cd android && ./gradlew :core:test testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle 8.9 needs JDK 17–21 (not 25). On a Mac without a phone, use the arm64 emulator with the Mac webcam as the
front camera (`scripts/emulator.sh` in this folder). The NPU path can only be checked on a Snapdragon phone; the
emulator exercises the CPU and landmark paths and every screen.

The manifest removes `INTERNET` and `ACCESS_NETWORK_STATE` with `tools:node="remove"`, so no library can merge them back;
CI fails the build if the APK ever requests `INTERNET`.
