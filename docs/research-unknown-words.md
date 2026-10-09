# Research: words the patient never taught (2026-10-04)

Question: can Mouna catch, or say, a word the patient never taught, perhaps with a small on-device language model?

## Findings

| Topic | Finding | Source |
|---|---|---|
| Open-vocabulary lip reading in Indian languages | None exists. The largest multilingual set (MultiVSR, ~12,000 h, 13 languages) has no Indian language; best English WER is 12.8. Its model fine-tunes to low-resource languages, but needs sentence-level video we do not have. | Prajwal, Hegde, Zisserman 2025 |
| Zero-shot lip reading in unseen languages | Zero-AVSR (ICCV 2025): predict romanised text, let an LLM convert to script. Research-scale, audio-visual, not silent, not on-device. | arXiv 2503.06273 |
| Silent vs voiced mouthing | Visual recognisers trained on voiced speech lose ~8.5 points on silent speech. LipLearner was built for silent speech, which is why we use it. | iBUG normal/whisper/silent; arXiv 2305.14203 |
| Catching an untaught word | Standard few-shot open-set method: prototype head + rejection (Mouna's threshold), or a learned "dummy" unknown class. Mouna's rejection rate on untaught words is **not yet measured**. | Interspeech 2023 (arXiv 2306.02161); Dummy Prototypical Networks |
| Keywords → sentence | KWickChat (GPT-2, IUI 2022): bag of keywords + context → sentence, ~71% keystroke saving, judges rated best sentence 4/5. | ACM 10.1145/3490099.3511145 |
| Abbreviation → sentence | SpeakFaster (Nature Comms 2024): word initials → phrase with a 64B LLM, up to 77% exact with context; 29-60% faster for two ALS users. | s41467-024-53873-3 |
| Users' view of LLM help | AAC users trade some authorship for speed only under time pressure; worry about losing their voice and identity. Patient must approve every generated sentence. | CHI 2023 (3544548.3581560); arXiv 2509.13671 |
| Small LLMs at twenty questions | Strong models search like binary search; small open models do poorly without extra training. Use a fixed question tree, not a 1B model, to plan questions. | Entity-Deduction Arena, arXiv 2310.01468 |
| What ventilated patients need to say | Thirst 69%, pain on repositioning 69%, sleep 66%, fatigue 64%, anxiety 64%; love/belonging most common need. Eye-tracking users: suction, rest, position, pain, temperature, discharge. | ICU reviews (PMC5070186, PMC12828804) |

## Models (on-device, Indian languages)

| Model | Languages | Fit | Catch |
|---|---|---|---|
| Sarvam-1 (2B) | 10 Indic + English | best Indic tokeniser | **base model only, non-commercial licence** |
| Gemma 3 1B | **English only** | fast in browser (~34 tok/s on a phone) | useless for Kannada/Tamil |
| Gemma 3n / Gemma 4 E2B | 140 languages | ~2 GB memory, NPU decode ~28 tok/s (MediaTek figure) | LiteRT NPU export of Gemma-4-E2B gave garbage on SM8550 (litert-torch #1290) |
| Qwen3 0.6B / 1.7B | 119 languages | small, Apache-2.0, WebLLM-ready | Kannada/Tamil quality unmeasured |
| IndicTrans2 distilled 200M | 22 Indian languages | offline translation of custom phrases | no official ONNX |
| IndicConformer (30M / 120M) | 22 Indian languages | on-device caregiver speech → text; community int8 ONNX | — |

No small model has published Kannada/Tamil generation numbers at this size: a native-speaker check is required before any claim.

## Decision

1. Lips choose, the model only writes; every generated sentence is shown and confirmed before it is spoken.
2. Prefer two-word blocks over single words (Kannada: 97.3% vs 83.5%). beku/beda (want / don't want) is the top confusion and no language model can fix it.
3. Twenty questions uses a fixed tree built from ICU needs; the model only phrases questions.
4. Measure before building: (a) rejection rate on untaught Kannada classes, (b) sequence accuracy with and without a sequence prior.

## Round 3: who else is doing this, and what the phone can run

| Topic | Finding | Source |
|---|---|---|
| SRAVI after laryngectomy (newest study) | 16 patients, English, fixed phrase library, video sent to a server, no personalisation. Right first time **43%**, within its three offers **67%**; 69.7% preferred it to writing. Authors ask for pre-operative enrolment and free text. | Fassler et al., Otolaryngol Head Neck Surg 2026 (PMID 41485241) |
| LiRA (UNC, US) | Lip-reading app for voiceless patients; NSF SBIR funded; English. | UNC Innovate; WRAL TechWire 2023 |
| IIIT Hyderabad (Vineet Gandhi) | Dysarthric speech → clear speech in English, Hindi in progress; lip-to-speech planned, not shipped. ANRF award 2026. | BharatFirst, 2026-03-15 |
| Llama 3.2 1B on SM8850 | Qualcomm AI Hub: 37 tok/s (w4), 64 tok/s (w4a16) on Snapdragon 8 Elite Gen 5. Officially English + 7 languages incl. Hindi; not Tamil or Kannada. | aihub.qualcomm.com/models/llama_v3_2_1b_instruct |
| Param-1 2.9B (BharatGen) | Hindi + English only, CC BY 4.0, early instruct checkpoint. | HF bharatgenai/Param-1-2.9B-Instruct |
| Regulation | CDSCO: software for general communication is not a medical device; a medical purpose makes it SaMD (Class A-D by risk). Draft MDS guidance Oct 2025. | CDSCO guidance |

Comparison caution: SRAVI's numbers are real patients in hospital; Mouna's are a public dataset of healthy speakers. Same metric (top-1 / top-3), different conditions; never put them side by side without saying so.
