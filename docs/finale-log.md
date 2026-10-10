# Finale log (app lane)

*A running record of what we decided, built and checked. Newest first. Branch: `app/finale-ui`. Demo: 9 Oct 2026.*

## Where we are (updated 7 Oct, 21:00)

| Area | Status |
|---|---|
| **Direction** | Mouna gives a voice back to anyone who lost theirs. English first; Hindi and Tamil as voices. No training: existing models only. No presentation inside the app. |
| Lips (main) | ✅ Real LipLearner encoder in the app; self-test cosine 1.00000 on the emulator (CPU, 6.9 s per window there). NPU path: Maadhav's SM8650/SM8850 binaries are in `encoder/qnn_ctx/`, **but `model.onnx` wrappers are missing from git** (only `model.bin` via LFS). |
| Voice (Parkinson's, mild dysarthria) | ✅ Whisper tiny.en via sherpa-onnx, runs in the app on the emulator. 5 of 5 test recordings handled right (table below). ⏳ Not yet with a live microphone or a real dysarthric speaker. |
| Own words + warm phrases | ✅ Built ("I love you", "I'm okay", "Hold my hand", "I'm scared", "Stay with me" + family-typed words). |
| Personal movement, eyes, Ask, pictures | ✅ Built (from `android/core`). Not tested on real faces. |
| ISL (AI4Bharat OpenHands) | ✅ Built and measured on INCLUDE's held-out signers through the app's pipeline (`models/isl/eval`): six signs in a row, 79 / 120 spoken right, 16 wrong, 0 missed (landscape); 53 / 20 / 0 in the phone's portrait crop. Same results on the iQOO 15 as in Python. ⏳ Not tested with a live signer. |
| Nod / shake / double blink | ✅ Ported from the Lab (same constants); armed only while Mouna asks (prompts, Ask) so mouthing can't trigger it. 4 unit tests. ⏳ Not tried on a real face. |
| App icon + dark splash | ✅ |
| Git push | ✅ GitHub recovered ~21:10; branch is pushed. |

## Next

1. Maadhav: commit the two `model.onnx` wrappers (`git add -f`); asked on the `npu` thread.
2. On the phone (OnePlus 13R / iQOO 15): NPU encoder, Whisper, ISL, latency; push the model files (see "How to run").
3. Live checks with Sachin: lips (teach 3–4 phrases), voice with the mic, nod / blink, switch, eyes, a few ISL signs.
4. A 20-run soak of the demo script on the phone.

## Voice check on the emulator (7 Oct, 21:00)

macOS `say` recordings fed through the app's live path (`adb shell am broadcast -a app.mouna.HEAR`). Synthetic
voices, not a dysarthric speaker: this checks the pipeline, it is not an accuracy claim.

| Recording | Whisper heard | Best match | Mouna |
|---|---|---|---|
| "I need water", normal | "I need water." | water 1.00 | spoke it |
| "I… need… wah… ter", slow | "I need, watch her." | water 0.73 | "Did you mean… I need water?" |
| "I love you" | "I love you." | love 1.00 | spoke it |
| "I am… in… pain", slow | "I am in pain." | pain 1.00 | spoke it |
| "Can you open the window please" | same | pain 0.23 | "Did you say…?" then says the words clearly |

Fixed on the way: filler words ("you", "can"…) had pulled the last one onto "I love you" (0.49).

## Log

**10 Oct, morning (branch `nakul/sign-mode-fix`)**
- Sign mode checked on real signers (nobody on the team signs): 73 INCLUDE held-out test videos through the app's own pipeline (`models/isl/eval`). The model is fine (whole clip top-1 81% landscape, 58% in the phone's portrait crop), but the app almost never closed a sign: the hand model sees resting hands in 94–97% of frames, so "hands down" never came (4 / 73 clips). Six signs in a row: 12 of 120 spoken right, 25 wrong, 77 missed.
- Fixed: a sign now starts when a wrist rises above chest level and ends when both come down (`SignSegmenter`, needs 6 raised frames). Speak only at p ≥ 0.9, else offer 3. Readable words ("Thankyou" → "thank you", "you(plural)" → "you all"). Six in a row now: 79 right, 16 wrong, 0 missed (landscape); 53 / 20 / 0 (portrait).
- iQOO 15: the 00:19 reinstall had left `files/isl/` empty, so Sign showed "isn't available"; model pushed back, "isl ready in 216 ms". 25 real signs through the phone (`app.mouna.SIGN --es stream`): identical to the Python reference. `files/encoder/` and `files/asr/` are still empty on that phone.

**10 Oct, night (branch `app/polish`)**
- Calls (merged 9 Oct, PR #1): web-link call through `call-server/` (guest joins from a browser, no SIM needed); phone-number call for phones with a SIM; Intro, quick phrases, type-to-speak, lips/sign during a call; Sarvam live voice with offline fallback. Audited; 13 findings fixed.
- Call audio now goes out as AAC m4a instead of WAV: 100 KB → 15 KB per sentence (measured, emulator). Over the laptop tunnel a sentence arrived 4 s after its caption instead of 9.5 s; a cloud host (Render) would cut it further.
- iQOO 15, cold start (MounaPerf log): camera 241 ms (GPU), lip encoder 1.19 s on the NPU (fp16), ISL 248 ms, Whisper 562 ms.
- APK: Hexagon libraries only for V75 (OnePlus 13R) and V81 (iQOO 15); 189 MB → 181 MB debug.
- Stage polish: Welcome screen before the camera prompt; plain status words (technical status only after 7 taps on the version in Settings); Indic text never cut off; contrast ≥ 4.5:1; hold-to-hang-up; Start over confirms and resets everything; said text fades after 8 s.
- No surprises: screen stays on, Back keeps Mouna running, gestures arm 1 s after a prompt, nothing leaves the Call screen mid-call, a killed NPU compile no longer demotes the encoder to the CPU, phone voice speaks at once off a call.
- Not yet checked: a real person as the web guest on a phone browser, Sarvam with a real key, lips/sign/switch with a real face on the polished build.

**7 Oct, night**
- ISL: AI4Bharat OpenHands SL-GCN integrated (export only), Sign mode with MediaPipe pose + hands; preprocessing parity test.
- Nod / shake / double blink in the app: yes and no with no setup. Total app tests: 30.
- GitHub push recovered; branch pushed. Asked Maadhav for the missing `model.onnx` wrappers.

**7 Oct, evening**
- Removed the in-app story slides (Sachin: no presentation inside the app). Kept the app icon and dark splash.
- Voice channel, own words, warm phrases, voice-matcher unit tests (4 pass). Total app tests: 21.
- Exported the encoder ourselves from LipLearner's MIT release (`python -m mouna_encoder static`), cosine 1.0 vs the self-test. Mac M4 CPU: 857 ms per window; the phone needs the NPU.
- App compiles the plain ONNX for the NPU on the phone if no precompiled binary works; crash guard skips a model that killed the app last time.
- Maadhav pushed the QNN context binaries for both chips to `main` (merged into the branch).

**7 Oct, afternoon**
- Direction set with Sachin: one job (a voice for anyone who lost theirs), English first, Kannada out of the app, existing models only. `docs/impact.md` (cited numbers), `docs/existing-models.md`.
- Emulator on the Mac with the real MacBook camera (start it from Terminal: `mouna-emulator.command`). Fixed: face-model CPU fallback, GPU failure mid-run, camera re-binding stalls, missing front camera, Back closing the app, prompts when nothing is taught.

**7 Oct, morning**
- Built the product app on `android/core`: Speak, Teach (active teaching, look-alike pairs, negatives), decision prompts, Ask, Settings, switch and eye setup. Replied to Maadhav on D6 (ISL via existing model) and D7 (iQOO 15 IR blaster).

## How to run on the Mac

```bash
open -a Terminal "/Users/sachin/Development/IQOO FINALS/mouna-emulator.command"
cd android && ./gradlew assembleDebug && adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb push ../encoder/weights/encoder_static48.onnx /sdcard/Android/data/app.mouna/files/encoder/
adb push tiny.en-encoder.int8.onnx tiny.en-decoder.int8.onnx tiny.en-tokens.txt /sdcard/Android/data/app.mouna/files/asr/
adb push isl_include_slgcn.onnx isl_include_labels.json /sdcard/Android/data/app.mouna/files/isl/   # models/isl/README.md
# QA without a person: adb shell am broadcast -a app.mouna.HEAR --es wav <file.wav>   (voice path)
```
