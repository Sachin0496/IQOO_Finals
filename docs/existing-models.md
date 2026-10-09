# Existing models only (no training)

*7 Oct 2026, Sachin. Decision: Mouna trains no new models. Every channel uses a published, pretrained model; Mouna's
own part is the per-person few-shot `Learner` in `android/core`, which stores a person's examples and matches against
them. It does not train a network.*

**Language:** English is the main language for mouthing, speech and the app. Hindi and Tamil are secondary voice
options. Mouna is not built for one state; Kannada is no longer in the app (the measured Kannada results stay as
evidence that input can be any language).

| Channel | Who | Existing model | Licence | On the phone | Status |
|---|---|---|---|---|---|
| **Silent mouthing (main)** | laryngectomy, tracheostomy, CP / stroke with moving lips | LipLearner lip encoder (CHI 2023), trained on LRW, which is **English**: English is its home language | weights: see finale-plan §2 caveat | ONNX Runtime QNN, 18 ms on SM8850 (measured) | ✅ in the app |
| Personal movement | stroke, ALS, severe CP | MediaPipe Face Landmarker blendshapes | Apache-2.0 | GPU / CPU | ✅ in the app |
| Eyes left / right | ALS, locked-in | MediaPipe iris landmarks | Apache-2.0 | GPU / CPU | ✅ in the app |
| **Indian Sign Language** | Deaf ISL users | **AI4Bharat OpenHands SL-GCN on INCLUDE** (263 isolated ISL signs; 93.5% on INCLUDE in the paper), checkpoint `include_slgcn.zip` | code Apache-2.0; **check the INCLUDE data licence** | MediaPipe pose + hand keypoints → SL-GCN exported to ONNX → ORT (QNN or CPU) | 🔴 to integrate |
| Personal hand gestures | anyone who can move a hand | MediaPipe Hand Landmarker + the existing `Learner` | Apache-2.0 | GPU / CPU | 🔴 to integrate |
| **Mild dysarthria, Parkinson's voice** | stroke, Parkinson's, CP with some voice | **Whisper** (tiny.en / base.en) through **sherpa-onnx** (offline, Android, QNN supported), answers matched against the person's phrases; to evaluate as drop-ins: `JJaysz/cohear-whisper-small-dysarthric` (Apache-2.0, TORGO), `jmaczan/wav2vec2-large-xls-r-300m-dysarthria-big-dataset` (Apache-2.0) | Whisper MIT; sherpa-onnx Apache-2.0; **TORGO is research data: check before using a model trained on it** | needs `RECORD_AUDIO` (still no INTERNET) | 🔴 to integrate |

## Rules that still hold

- No accuracy claims for a model until we measure it ourselves; the papers' numbers are quoted as the papers' numbers.
- ISL: "existing AI4Bharat model, 263 signs", never "we recognise ISL".
- Dysarthria: the community fine-tunes are small, unvetted uploads; Whisper is the default, they are only candidates.

## Sources

- OpenHands (AI4Bharat): https://github.com/AI4Bharat/OpenHands ; paper: https://aclanthology.org/2022.acl-long.150.pdf
- sherpa-onnx (Whisper, Moonshine, QNN, Android): https://github.com/k2-fsa/sherpa-onnx
- Whisper on dysarthric speech (Interspeech 2025, SAP challenge): https://www.isca-archive.org/interspeech_2025/tan25b_interspeech.pdf
- Hugging Face models: https://huggingface.co/JJaysz/cohear-whisper-small-dysarthric , https://huggingface.co/jmaczan/wav2vec2-large-xls-r-300m-dysarthria-big-dataset
