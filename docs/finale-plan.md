# Finale plan: research and how we win (iQOO Hackathon 2026, Bengaluru, 9–11 Oct)

Written 6 Oct 2026, after the team was selected for the Grand Finale. Research covers algorithms, models,
datasets, runtimes, forums and issue trackers, the clinical literature and the event rules. Every claim links to its
source at the end. Numbers marked *ours* come from `deck/data/*.json`.

---

## 0. TL;DR

1. **The rubric decides the build.** 25% of the score is device telemetry (HackTracker: phone use 15%, Office Kit 10%),
   the demo **and the pitch** run on the iQOO 15, and a web-only app forfeits 25%. So: a **native Android app**,
   developed and tested on the phone, with the pitch inside it.
2. **Our core is right and still unmatched.** Personalised, closed-set silent speech on the NPU, in Indian languages,
   offline. Nobody in the 2025–26 literature or market does all four. The newest SRAVI study (cloud, English, not
   personalised) reached 43% first-try after laryngectomy; patients still preferred it to writing.
3. **Open-vocabulary lip reading is still out of reach** for Indian languages on a phone (best English, large models:
   18.7–19.4% WER on LRS3). Don't chase it; our Ask mode and sentence blocks cover the gap honestly.
4. **Biggest unknown: real silent mouthing by a stranger** (the judge). We must record 5–6 people on the phone in two
   sessions and measure before the Finale.
5. **Licence risk:** the LipLearner encoder was trained on LRW, which BBC licenses for non-commercial research only.
   Fine for a hackathon demo; say so, and show the path to a commercially clean encoder.
6. **Runtime choice:** LiteRT CompiledModel with the Qualcomm QNN accelerator (supports SM8650 *and* SM8850, AOT and
   on-device compilation), with ONNX Runtime ≥ 1.28 QNN EP as fallback. Several SM8850-specific runtime bugs are
   open: test on the real chip early, pin versions, and self-test on start.

---

## 1. What the Finale rewards

| Criterion | Weight | Measured by | What it means for us |
|---|---|---|---|
| End product quality | 30% | jury | A finished, polished Android app beats a broken clever one |
| Novelty and impact | 20% | jury | Silent speech → Indian languages, on device, for wards |
| Creative phone use (camera, voice, on-device AI) | 15% | **device data** | Front camera, NPU, speaker, mic (voice banking), vibration, second phone |
| Technical depth | 15% | jury | Encoder rebuilt for the NPU, measured open-set behaviour, parity tests |
| Office Kit usage | 10% | **device data** | Use the vivo Office Kit bridge for real work: mirroring, file transfer, clipboard |
| Demo and presentation | 10% | jury | One-minute live moment; pitch runs on the phone |

**Caution:** the organisers' preparation guide lists a different split (Office Kit usage 25%, phone-first execution 25%,
AI-native build 20%, problem fit 20%, craft and pitch 10% for the top 10). Either way, about a quarter is device
telemetry and on-device AI is rewarded. Ask the organisers which rubric applies at the Finale.

Also from the organisers' guides:
- **Red Light / Green Light:** 55% of build time is phone-only; 45% phone + laptop.
- **"Final product MUST run on the iQOO phone"**; web-only apps that bypass phone features forfeit 25%.
- **The pitch runs on the device itself.**
- Local / open-source models on the Snapdragon NPU earn extra points; cloud APIs are discouraged.
- Loaner phone: **iQOO 15** (Snapdragon 8 Elite Gen 5, SM8850, 32 MP front camera, up to 16 GB RAM).
- Pre-built code policy: **not stated** in the public guides. See §2.

**Implications**
- Mouna Lab (web) becomes the *research harness and fallback*; the Finale product is native Android.
- **Plan real work for the Red Light hours** so phone telemetry is earned honestly: recording silent sessions,
  on-phone evaluation, threshold tuning, phrase-pack editing, filming the demo, rehearsing. That needs in-app tools
  for these (see §8).
- **Pitch mode inside the app:** the deck's story told on the phone, with the live demo embedded. The pitch *is*
  the product.
- Install **Office Kit** on the laptop now and practise mirroring, file transfer and clipboard; mirror the phone on
  the projector during the pitch.

---

## 2. Rules, integrity and licences

- **Pre-building.** Our Phase 1 disclosure promised Finale code would be written in the event window in a new repo.
  The organisers have reportedly said the complete project may be built beforehand. **Get this in writing** and
  disclose it exactly ("built before the Finale with the organisers' permission, <date>"). Never imply it was
  built during the event.
- **Encoder weights.** LipLearner's code is MIT, but its encoder was pre-trained on **LRW**, which BBC R&D licenses
  for **non-commercial academic research** only. A hackathon demo is fine; a product is not.
  Commercially clean path, in order:
  1. self-supervised pre-training on **VoxCeleb2**, whose metadata is CC BY-SA 4.0;
  2. our own consented Indian silent-speech recordings;
  3. avoid **LRS3** (CC BY-NC-ND) and anything built on it, including AV-HuBERT weights, for the product.
- **Regulation.** CDSCO: software for general communication is not a medical device; any diagnostic or treatment
  claim makes it SaMD. Keep the wording "communication aid".
- **Privacy.** Facial data is personal data under the DPDP Act. Process on device, store no video, consent and delete.
  Already true in the Lab; keep it true in the app.

---

## 3. Algorithms: state of the art and what we take from it

| Line of work | Best known (2025–26) | Relevance to Mouna | Decision |
|---|---|---|---|
| **Few-shot, closed-set silent speech** (LipLearner, CHI 2023) | 81.7 / 96.0 / 98.8% at 1/3/5 shots, 30 English commands | Our core; *ours* on Kannada: 71.9 / 85.2 / 89.0% | **Keep.** Improve with shot augmentation (below) |
| **Shot / test-time augmentation for prototypes** | ~2% gain at 1-shot; ~4% from TTA (FSL-Rectifier); incremental prototypes (IPEC, 2026) | Cheap, no retraining: augment each taught clip (speed ±10%, small crop jitter, mirror) | **Do now**; measure on Kannada before shipping |
| **Speaker-adaptive / personalised VSR** | LoRA + prompts (AAAI 2025); Korean LoRA, 4.6% params, 4–29 min of a user's video: CER −2.1 to −3.6 points (Sep 2026) | Needs minutes of a user's video; not for a 25 s teach | **Later**, after a week of use |
| **Phoneme + LLM VSR** (VALLR, ICCV 2025) | 18.7% WER LRS3; phoneme CTC then an LLM reconstructs words | English; needs large labelled video | **No** for Indic now; cite as future |
| **Silent speech + word initials + LLM** (LipType, Su et al. 2025) | WER 20.3% → 9.19% with word initials as hints | Same team as LipLearner; validates our "lips choose, LLM writes" design | **Design reference** for sentence blocks |
| **LLM-decoded VSR** (Llama-AVSR, diffusion LLM VSR) | 19.4–24.9% WER LRS3 with 7B LLMs | Too large for a phone pipeline | **No** |
| **Head-pose-aware VSR** (FiLM, 2026) | Gains for yaw > 30° | Bed-bound patients film at angles | **Future**; for now keep the yaw quality gate |
| **Lip-to-speech synthesis** (LipSody 2026, LightL2S 0.8 GMACs) | Speech directly from silent video, speaker-aware | A "Mouna v2" story (the patient's own voice from lips), not for this Finale | **Roadmap slide only** |
| **Visual voice activity detection** | MobileNet VVAD ~92% on landmark/face features | Hands-free segmentation without the button | Our gate + blink already work; **later** |
| **Twenty questions with LLMs** | Small models plan poorly | Our fixed ICU tree is the right call | **Keep** the fixed tree |

---

## 4. Models: candidates and picks

| Role | Candidates | Pick for the Finale | Why |
|---|---|---|---|
| Face landmarks | MediaPipe Face Landmarker (478 pts, GPU ~8–12 ms on SD 7-series); commercial SDKs | **MediaPipe** | Already parity-tested with the Lab; Apache-2.0 |
| Lip encoder | LipLearner static (ours, int8 86 MB, 18.4 ms on SM8850 NPU); MobiVSR; LiteVSR | **Ours** | Measured, rebuilt, NPU-proven; licence caveat stated |
| Language model (sentences for unlisted pairs; question phrasing) | **Gemma 4 E2B** (140 langs, native audio, LiteRT-LM + QNN); **Qwen3.5-0.8B** (Apache-2.0, on Qualcomm AI Hub); Llama 3.2 1B (37–64 tok/s on SM8850; Hindi only of ours); Sarvam 30B/105B (too big) | **Optional.** Gemma 4 E2B if it runs clean on SM8850; else none | Only if it passes 20 clean runs; always confirmed |
| Caregiver speech → text (conversation mode) | Gemma 4 E2B/E4B audio (E4B FLEURS Kannada WER 0.312 base); IndicConformer (30M/120M, ONNX int8); Whisper on AI Hub | **IndicConformer** if time allows | Small, Indic-specific, CPU-friendly |
| Voices | Pre-rendered Sarvam Bulbul v3 + Indic Parler-TTS (*ours*, 208 clips); sherpa-onnx Piper/Kokoro (offline, Hindi among few Indic); **IndicF5** zero-shot voice cloning from 3–20 s | **Pre-rendered**, plus an **IndicF5 "own voice" pack** rendered once on a laptop | The patient's own voice in every language is a wow moment, offline after setup |

### Runtimes on the phone
| Runtime | Status (Oct 2026) | Notes |
|---|---|---|
| **LiteRT CompiledModel + QNN accelerator** | Supports SM8850, SM8750, **SM8650**, SM8550…; AOT and on-device compilation; API 31+ | First choice; replaces the old TFLite QNN delegate |
| ONNX Runtime QNN EP | Works; **ORT 1.27 miscomputes an encoder on SM8850, fixed in 1.28** (sherpa-onnx #3845) | Fallback; pin ≥ 1.28 |
| ExecuTorch + Qualcomm backend | QNN 2.37 recommended; needs static quantisation | Second fallback |
| Qualcomm AI Hub Workbench | Exports to LiteRT, ONNX Runtime or QAIRT; Conv3D lowering added for TFLite; GPU-backed quantise jobs | Use it to compile and profile for **SM8650 (OnePlus 13R) and SM8850 (iQOO 15)** |

---

## 5. Datasets

| Dataset | Content | Licence | Use |
|---|---|---|---|
| Kannada multi-speaker lip reading (Divya P, 2026) | 7 speakers usable, 27 words and phrases, voiced | CC BY 4.0 | **Have**; all our measurements |
| **Our own silent recordings** | Team and friends, phone camera, silent, two sessions | Ours, consented | **Must do before the Finale** |
| MultiVSR (Oxford 2025) | ~12,000 h, 13 languages, **no Indian language** | research | Not useful now |
| LRW / LRS2 / LRS3 | English | BBC research only / CC BY-NC-ND | Avoid for the product |
| VoxCeleb2 | Celebrity video, multilingual | CC BY-SA 4.0 metadata | Candidate for a clean encoder |
| Hindi (KLETech) | 10 sentences, 50 speakers | unclear | Check availability |
| SoniSpeech (2026) | Silent vs voiced, acoustic-sensing eyewear | — | Shows silent ≠ voiced; cite |
| ICU needs studies | What ventilated patients say | — | Already shapes the Ask tree |

---

## 6. Forums and issue trackers: known traps

- **LiteRT 2.1.1, SIGSEGV in `TensorBuffer.readFloat()` on a newer Snapdragon** (NPU and GPU alike; HTP/GPU shared-buffer
  protection). Use the newest LiteRT and test on SM8850 early.
- **Qwen3-0.6B LiteRT-LM bundle prints garbage on SM8850** (NPU KV-cache bug); Gemma-4-E2B NPU export garbage on
  SM8550. LLMs on the NPU are fragile: CPU/GPU fallback and a known-answer self-test.
- **ONNX Runtime 1.27 silently wrong on SM8850**, fixed in 1.28 (sherpa-onnx).
- **LiteRT GPU: unrolled LSTM beyond ~85 steps crashes**; our unrolled GRU is 48 steps (under it), but test the GPU
  fallback explicitly.
- **MediaPipe Face Landmarker slow inside apps vs. the benchmark tool** (#5872): use the GPU delegate, the
  LIVE_STREAM mode, and a 640×480 camera stream.
- LiteRT on **SM8650 with QNN HTP v75 works**: the OnePlus 13R is a sound development device.

**Lesson:** at app start, run a 1-second self-test (known clip → known embedding, NPU vs CPU) and show
"NPU verified" or fall back automatically. Judges see the check; we never demo a silent miscompute.

---

## 7. Clinical and user evidence

- **Who communicates how after laryngectomy** (157 members of the International Association of Laryngectomees, 2025):
  voice prosthesis 61.5%, electrolarynx 24.4%, writing 9%. The early post-operative window, before these work, is
  where Mouna fits.
- **SRAVI after laryngectomy** (16 patients, 2026): 43% first try, 67% within three; 69.7% preferred it to writing.
  Authors ask for **pre-operative enrolment** and **free text**: our voice banking, Ask mode and blocks.
- **India:** tracheostomy in 10–20% of critically ill patients; patients say losing the voice is the worst part.
- **AAC users on LLM help:** they want suggestions that are contextual, editable, in their own style, and they worry
  about losing authorship. So: confirm every generated sentence; never auto-speak a guess.
- **Ethics for the demo:** no real patient on stage. A clinician's quote or a short video is worth more than a feature.

---

## 8. The Finale build

### Architecture (Android, Kotlin + Compose)
```
CameraX (front, 640x480, 30 fps)
  -> MediaPipe Face Landmarker (GPU)          lips, eyes, head pose
  -> quality gate + activity gate + blink + nod/shake
  -> mouth crop 96x96 grey, 48-frame window
  -> lip encoder (LiteRT + QNN, NPU; GPU/CPU fallback; start-up self-test)
  -> prototype head (few-shot, Careful mode, open-set reject)
  -> Speak | Ask mode | Build a sentence
  -> voices (pre-rendered, own-voice pack) + caregiver link (second phone)
Pitch mode: the story in-app, live demo embedded, telemetry overlay (NPU ms, airplane mode, permissions)
QA tools: record protocol, on-phone evaluation, threshold tuner, phrase-pack editor
```
Port the Lab's TypeScript core to Kotlin with the same parity tests (TS ↔ Python ↔ Kotlin).

### Schedule
| When | Work | Done when |
|---|---|---|
| 6 Oct | AI Hub: compile + profile encoder for SM8650 and SM8850 (LiteRT); Android skeleton; camera → landmarks → crop on the OnePlus | Encoder on the OnePlus NPU, latency logged |
| 7 Oct | Head, gates, Speak, voices, Careful, Ask mode, blocks, link; QA tools; **silent recordings, session 1** | Lab parity tests pass in Kotlin |
| 8 Oct | **Session 2**; on-phone evaluation; thresholds; shot augmentation if it measured better; own-voice pack; Pitch mode; Office Kit drill; 5 rehearsals | Measured silent-speech numbers in the deck and in Pitch mode |
| 9–11 Oct | Install on the iQOO 15, self-test, warm the NPU; Red Light tasks planned; optional LLM/ASR only if clean; freeze 8 h before judging | 20 clean demo runs in a row |

### Red Light (phone-only) task list, prepared in advance
1. Record silent sessions with the in-app recorder (each team member, each phrase).
2. Run on-phone evaluation; tune thresholds per person.
3. Edit and check phrase packs on the phone, with native-speaker review over a call.
4. Film the 45 s demo video on the phone; cut it in the phone's editor.
5. Rehearse the pitch in Pitch mode; time it.
6. Soak test: 30 min continuous run, log temperature and latency.

---

## 9. Experiments (each with a pass line, all measured, nothing invented)

| # | Experiment | Data | Metric | Pass line |
|---|---|---|---|---|
| E1 | Encoder on SM8650 and SM8850 via LiteRT | AI Hub profile | ms per window, % ops on NPU | ≤ 30 ms, 100% NPU |
| E2 | Parity: phone embedding vs PyTorch | 20 Kannada clips | cosine | ≥ 0.999 |
| E3 | Shot augmentation (speed, jitter, mirror) | Kannada, 3 shots | top-1 | ≥ +1.5 points, else drop |
| E4 | **Silent speech, novice users** | our recordings, 5–6 people, 2 sessions | top-1 / spoke precision / next-day | ≥ 90% same session, ≥ 85% next day |
| E5 | Untaught words on silent data | E4 data | spoken-by-mistake rate, default vs Careful | Careful ≤ 10% |
| E6 | Stage conditions | E4 subset | top-1 under overhead light, backlight, glasses, 30° yaw | No case below 75% |
| E7 | Blink, nod, shake on real faces | team | false answers per 5 min of mouthing; detection rate | 0 false; ≥ 90% detected |
| E8 | End-to-end latency on the iQOO 15 | live | release → voice | ≤ 700 ms |
| E9 | Optional LLM sentence for unlisted pairs | 50 pairs | native-speaker acceptability | ≥ 80% acceptable, else cut |

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| Judge's silent mouthing far below dataset numbers | E4 before the Finale; Careful mode; quick set of long, distinct phrases; top-3 choices; Ask mode |
| SM8850 runtime bug | Self-test at start; ORT ≥ 1.28 / latest LiteRT; GPU path; AI Hub profile on SM8850 already green |
| Lighting / angle on stage | Own front light; quality gate tells the judge what to fix |
| Telemetry score low | Real Red Light task list; Office Kit for all transfers and mirroring |
| Licence question from a judge | State LRW terms and the clean-encoder plan (§2) |
| Pre-build question from a judge | Written permission, disclosed in the pitch |
| LLM/ASR flakiness | Off by default; shown only if 20 clean runs |

---

## 11. Decisions needed from the team

1. Forward the organisers' written permission for pre-building.
2. Who are the 5–6 people for the silent recordings (two sessions, ~10 min each)?
3. Clinician contact for a quote or a short call (ENT, SLP, or ICU nurse).
4. Native speakers for Tamil, Kannada, Hindi review of the phrase pack, Ask tree and sentences.
5. Approve: native Android (Kotlin/Compose) app, LiteRT + QNN, optional LLM only after E9.

---

## Sources

**Event**
- iQOO Hackathon 2026 overview and rubric: https://reskilll.com/blogs/iqoo-hackathon-2026-india-phone-first-ai-hackathon-iqoo-reskilll/
- Strategy guide (HackTracker, Red/Green Light): https://reskilll.com/blogs/how-to-win-iqoo-city-battles-strategy-guide-phone-first-ai-hackathon/
- Preparation guide (pitch on device, Office Kit): https://reskilll.com/blogs/how-to-prepare-for-iqoo-hackathon-2026-a-complete-guide-for-participants/
- vivo Office Kit: https://pc.vivoglobal.com/
- iQOO 15 specs: https://www.gsmarena.com/vivo_iqoo_15_5g-14198.php
- OnePlus 13R specs (SM8650): https://www.gsmarena.com/oneplus_13r-13548.php

**Algorithms**
- LipLearner (CHI 2023): https://arxiv.org/abs/2302.05907
- LipType, silent speech + word initials + LLM (Su, Fang, Rekimoto, CUI 2025): https://dl.acm.org/doi/10.1145/3719160.3736612
- VALLR (ICCV 2025): https://arxiv.org/abs/2503.21408
- Diffusion LLMs for VSR (2026): https://arxiv.org/abs/2605.28456
- Llama-AVSR: https://github.com/umbertocappellazzo/Llama-AVSR
- Personalized Lip Reading (AAAI 2025): https://arxiv.org/abs/2409.00986
- Personalized Korean lipreading (2026): https://arxiv.org/abs/2609.28988
- Head-pose-aware VSR with FiLM (2026): https://arxiv.org/abs/2606.00751
- FSL-Rectifier (test-time augmentation): https://arxiv.org/pdf/2402.18292
- IPEC (2026): https://arxiv.org/pdf/2601.11669
- LipSody (2026): https://arxiv.org/abs/2602.01908
- LightL2S (Interspeech 2025): https://www.isca-archive.org/interspeech_2025/liang25d_interspeech.pdf
- VVAD-LRS3: https://arxiv.org/pdf/2109.13789
- Entity-Deduction Arena (LLM twenty questions): https://arxiv.org/pdf/2310.01468
- Silent speech interfaces in the LLM era (review, 2026): https://arxiv.org/pdf/2603.11877

**Models and runtimes**
- LiteRT + Qualcomm NPU: https://ai.google.dev/edge/litert/next/qualcomm
- Google blog, LiteRT on Qualcomm NPU: https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/
- Qualcomm AI Hub release notes: https://workbench.aihub.qualcomm.com/docs/hub/release_notes.html
- Qwen3.5-0.8B on AI Hub: https://aihub.qualcomm.com/models/qwen3_5_0_8b
- Llama 3.2 1B on AI Hub: https://aihub.qualcomm.com/models/llama_v3_2_1b_instruct
- Gemma 4 E4B: https://huggingface.co/google/gemma-4-E4B-it ; Kannada ASR fine-tune: https://huggingface.co/FineEnvs/gemma-4-E4B-it-kannada-asr-grpo
- Sarvam 30B / 105B: https://www.buildfastwithai.com/blogs/sarvam-105b-india-s-open-source-llm-for-22-indian-languages-2026
- ExecuTorch Qualcomm backend: https://docs.pytorch.org/executorch/stable/backends-qualcomm.html
- IndicConformer: https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual
- IndicF5 (voice cloning): https://github.com/AI4Bharat/IndicF5
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- MediaPipe Face Landmarker: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker

**Datasets and licences**
- LRW terms: https://www.robots.ox.ac.uk/~vgg/data/lip_reading/lrw1.html
- VoxCeleb2: https://www.robots.ox.ac.uk/~vgg/data/voxceleb/vox2.html
- MultiVSR: https://www.robots.ox.ac.uk/~vgg/publications/2025/Prajwal25/prajwal25.pdf
- SoniSpeech (2026): https://arxiv.org/abs/2608.00803
- Kannada dataset: doi:10.17632/zbzrbs89pz.1

**Forums and issue trackers**
- LiteRT SIGSEGV in TensorBuffer.readFloat(): https://github.com/google-ai-edge/LiteRT/issues/5754
- LiteRT-LM NPU KV cache float32 bug: https://github.com/google-ai-edge/LiteRT-LM/issues/3846
- litert-torch NPU garbage on SM8550: https://github.com/google-ai-edge/litert-torch/issues/1290
- sherpa-onnx: ORT 1.27 wrong on SM8850, 1.28 fixes: https://github.com/k2-fsa/sherpa-onnx/issues/3845
- LiteRT GPU unrolled LSTM > 85 steps crash: https://github.com/google-ai-edge/LiteRT/issues/10300
- MediaPipe Face Landmarker slow in apps: https://github.com/google-ai-edge/mediapipe/issues/5872

**Clinical**
- Patient perspectives after laryngectomy (2025): https://pubmed.ncbi.nlm.nih.gov/39564981/
- SRAVI after laryngectomy (2026): https://pmc.ncbi.nlm.nih.gov/articles/PMC12860178/
- Communicating with ventilated patients (review): https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5070186/
- Tracheostomy in Indian ICUs: https://pmc.ncbi.nlm.nih.gov/articles/PMC13407472/
- AAC users and LLMs (CHI 2023): https://dl.acm.org/doi/fullHtml/10.1145/3544548.3581560
- Ultra-personalised AAC (2026): https://arxiv.org/abs/2509.13671
- SpeakFaster (Nature Comms 2024): https://www.nature.com/articles/s41467-024-53873-3
- CDSCO medical device software guidance: https://cdsco.gov.in/opencms/export/sites/CDSCO_WEB/Pdf-documents/Guidance-document-on-Medical-Device-Software-under-MDR-2017.pdf
