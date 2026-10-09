# Mouna technology brief (2023–2026) — lip-reading, few-shot, ISL, TTS
Research date 2026-10-09. `web_search` was down (401); evidence gathered via arXiv/OpenAlex/HF-API/GitHub fetches. Items marked **(verify)** are from model knowledge, not a fetched source.

## 1. Lip-reading / silent speech
### (i) Speaker-dependent few-shot phrase/keyword spotting (Mouna's core task)
- **LipLearner (CHI 2023)** remains the closest published system to Mouna: contrastive lip encoder + prototype/metric head, **F1 0.8947 on 25 commands with ONE shot**, on-device fine-tuning + online incremental learning, in-the-wild lighting/posture robustness ([arXiv:2302.05907](https://arxiv.org/abs/2302.05907) · [DOI](https://doi.org/10.1145/3544548.3581465)). Your prototype-mean head + ONNX encoder is essentially this — good baseline, not a gap in itself.
- **Prompt tuning for speaker-adaptive VSR** shows tiny learned prompts recover most unseen-speaker loss of a frozen VSR backbone ([arXiv:2302.08102](https://arxiv.org/abs/2302.08102)); **Personalized Lip Reading (AAAI 2025)** adapts both the visual *and* language/vocabulary side to one speaker ([DOI](https://doi.org/10.1609/aaai.v39i9.33026)).
- Per-user variance dominates error: a 2026 personalized Korean VSR study reports per-speaker CER of **1.0%–52.2%** on one corpus, and 7–9 CER points gained from familiar wording ([arXiv:2609.28988](https://arxiv.org/pdf/2609.28988)). → design enrollment/re-enrollment UX around this.
- Realistic target: 5–40 closed phrases, 3 shots each → ~90–97% closed-set + reliable rejection is realistic; arbitrary continuous silent-speech transcription on-device is **not**.

### (ii) Continuous visual speech recognition — a server-side game
| Model | Size | Reported accuracy | On phone NPU? |
|---|---|---|---|
| AV-HuBERT | ~30M base / ~90M large | VSR WER ~19.1% LRS3, AVSR ~1.4% **(verify)** | no — standard backbone for distillation ([arXiv:2201.02184](https://arxiv.org/abs/2201.02184)) |
| Robust self-supervised AVSR (Interspeech 2022) | — | numbers **(verify)** | no ([arXiv:2201.01763](https://arxiv.org/abs/2201.01763)) |
| Auto-AVSR | large Conformer | AVSR WER ~1.4% LRS3 **(verify)** | no ([arXiv:2303.14307](https://arxiv.org/abs/2303.14307)) |
| Whisper-Flamingo | Whisper-scale | visual features injected into Whisper | no — server ([arXiv:2406.10082](https://arxiv.org/abs/2406.10082)) |
| LiteVSR / LiteVSR2 | small, distillation | baseline WER 47.4%; LiteVSR2 best CTC-based VSR | **yes — realistic on-phone continuous-VSR candidate** ([arXiv:2312.09727](https://arxiv.org/abs/2312.09727), [arXiv:2409.07210](https://arxiv.org/abs/2409.07210)) |
| SparseVSR | pruned AV-HuBERT | SOTA at 10% sparsity on LRS3 | borderline ([arXiv:2307.04552](https://arxiv.org/abs/2307.04552)) |
| MobiVSR (2019) | tiny | superseded | yes, but don't ([arXiv:1905.03968](https://arxiv.org/abs/1905.03968)) |
- AVSRBench (2026): LRS3's "sub-1% WER" is broadcast-domain; visual-only deteriorates on read/lipspeaker/spontaneous speech ([arXiv:2609.10366](https://arxiv.org/pdf/2609.10366)); WildVSR same gap ([arXiv:2311.14063](https://arxiv.org/abs/2311.14063)).
- Offline vs server split: keep closed-phrase spotting on-device (encoder + prototype head; 18 ms/1.9 s is right). Open-vocabulary transcription = optional server mode or skip. Whisper pseudo-labels for low-resource VSR ([arXiv:2309.08535](https://arxiv.org/abs/2309.08535)).
- Positioning: *Sensing technologies for silent speech interfaces* (2026) ([DOI](https://doi.org/10.1038/s44460-025-00010-2)).

## 2. Few-shot / rapid personalization
- Metric learning + prototype head is the validated default: Few-Shot Open-Set Learning for on-device KWS customization = encoder + prototype classifier with rejection, 76% acc at 10-shot over 10 user keywords on GSC, FAR trade-off reported ([arXiv:2306.02161](https://arxiv.org/abs/2306.02161)). Copy its FAR/FRR protocol.
- Supporting: metric-learning user-defined KWS ([arXiv:2211.00439](https://arxiv.org/abs/2211.00439)); prototypical networks ([arXiv:1703.05175](https://arxiv.org/abs/1703.05175)) **(verify)**; open-set survey ([arXiv:2110.14051](https://arxiv.org/abs/2110.14051)).
- Adapters beat full fine-tuning for 1–5 shots: text-aware adapters ([arXiv:2412.18142](https://arxiv.org/abs/2412.18142)); prompt tuning ([arXiv:2302.08102](https://arxiv.org/abs/2302.08102)). Freeze encoder, per-user adapter + prototype mean.
- Test-time adaptation: SUTA/SGEM for child ASR ([arXiv:2409.13095](https://arxiv.org/abs/2409.13095)); ([arXiv:2105.03544](https://arxiv.org/abs/2105.03544)). Use as self-training on confident inferences (= LipLearner's incremental scheme).
- Privacy precedent: on-device ASR adaptation ([arXiv:1909.06678](https://arxiv.org/abs/1909.06678)).
- Few-shot prototypical nets for sign ([DOI](https://doi.org/10.15388/26-infor632), [arXiv:2512.10562](https://arxiv.org/pdf/2512.10562)).
- Rapid enrollment for assistive SSI: LipLearner's user study is the only directly relevant one — novelty slot for Mouna.

## 3. Indian Sign Language
- AI4Bharat OpenHands ([repo](https://github.com/AI4Bharat/OpenHands), Apache-2.0): pose-based pretrained models incl. INCLUDE ([arXiv:2110.05877](https://arxiv.org/abs/2110.05877)); INCLUDE ([NeurIPS D&B 2022](https://openreview.net/forum?id=zBBmV-i84Go)).
- iSign (ACL Findings 2024), >118K ISL-English pairs ([arXiv:2407.05404](https://arxiv.org/abs/2407.05404)).
- SignFormer ([DOI](https://doi.org/10.1109/access.2022.3231130)); HaMeR ([arXiv:2312.05251](https://arxiv.org/pdf/2312.05251)); Fast-HaMeR ([arXiv:2603.16444](https://arxiv.org/pdf/2603.16444)); SignFlow ([DOI](https://doi.org/10.1109/access.2025.3554618)).
- For a tiny custom vocabulary: landmarks → small temporal encoder/DTW → prototype head with the same rejection as lips; 8–15 signs × 5–10 enrollments in 2–4 days. Signer-dependent; no continuous ISL translation.

## 4. Voice output (no recording of the user exists)
- Persona/style-prompted TTS, not cloning: IndicParler-TTS ([ai4bharat/indic-parler-tts](https://huggingface.co/ai4bharat/indic-parler-tts)) — license **(verify)**; IndicVoices-R ([arXiv:2409.05356](https://arxiv.org/pdf/2409.05356)).
- Kokoro-82M ([hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M), Apache-2.0) — no Kannada/Tamil/Telugu **(verify)**.
- Piper ([rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices), MIT), incl. Hindi.
- sherpa-onnx ([k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache-2.0): Android TTS + VAD + KWS.
- Cross-lingual identity↔intelligibility trade-off ([DOI](https://doi.org/10.18653/v1/2026.iwslt-1.12)) — supports caregiver-language neutral persona. Coqui XTTS CPML non-commercial **(verify)**.

## 5. Ranked "what to adopt"
| # | Component | Method | Effort |
|---|---|---|---|
| 1 | Few-shot lip head | prototype-mean + calibrated open-set rejection w/ dummy-negative class, FAR/FRR (arXiv:2306.02161) | 1–2 d |
| 2 | Voice out (IN languages) | IndicParler persona (server) + Kokoro/Piper offline via sherpa-onnx | 3–5 d |
| 3 | ISL module | OpenHands landmarks → temporal encoder/DTW + prototype head | 2–4 d |
| 4 | Lip encoder | per-user adapter/prompt (arXiv:2302.08102) | 3–5 d |
| 5 | Enrollment UX | 3-shot + incremental self-training on confident inferences + re-enroll prompts | 2–3 d |
| 6 | Optional continuous VSR | LiteVSR2 ONNX or server AV-HuBERT/Whisper-Flamingo | 1–2 w |
| 7 | Evaluation | FAR/FRR + WildVSR-style unseen-condition set | 2 d |

## 6. Gap summary (as the brief saw it, without knowing the repo)
1. Rejection quality (calibrated thresholds, negatives, FAR/FRR) is the real gap, not the encoder.
2. No per-user adaptation beyond prototype means.
3. DTW on MediaPipe landmarks is the weakest link for lips.
4. ISL under-specified vs OpenHands/iSign/SignFlow.
5. TTS coverage: Kokoro/Piper don't cover kn/ta/te; persona control is the feature.
6. No continuous silent-speech transcription — correctly absent.
