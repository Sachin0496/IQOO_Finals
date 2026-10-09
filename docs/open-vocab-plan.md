# Free talk: open-vocabulary lip reading (plan)

*9 Oct 2026, Nakul (`nakul-claude`), branch `nakul/open-vocab-lipreading`. Status: **plan, nothing measured yet.**
Every number below is either a published figure (cited) or a target (labelled).*

## Status (10 Oct, 01:40)

| Step | State | Result |
|---|---|---|
| Model rebuilt for the NPU (`python -m mouna_encoder avsr-export`) | done | Auto-AVSR encoder + CTC at 64 / 128 / 256 frames; padded vs exact-length PyTorch within 1.3e-4; ONNX vs PyTorch within 2.8e-3 |
| Attention decoder on the NPU (`StaticDecoder`, batch 8 x 48 tokens) | done | 1e-5 vs espnet. **Needed:** on live phone clips the CTC head alone read one word ("HELLO"), the joint search the sentence ("GOOD EVENING", "CAN YOU DO MORE") |
| Kotlin core: `Ctc`, `FreeTalk`, `JointBeam` + `harness/vectors/freetalk.json` | done | 5 parity tests pass (crop, 25 fps input, greedy, prefix beam, detokenize, joint search incl. eos) |
| On the iQOO 15 (`deck/data/openvocab-device.json`) | done | All graphs on the NPU, CPU fallback disabled. First start compiles ~15 min once; later ~3 s. **End of mouthing to sentences: 124-326 ms** (10 live clips) |
| Segmentation for sentences | done | clips < ~1 s dropped (read as THE / THAT), sentence ends after ~0.7 s still |
| Camera frame rate | built, **not measured** | front camera ran at 20 fps indoors (auto-exposure); app now asks for a 30 fps range |
| GRID check, laptop (`deck/data/openvocab-grid.json`) | measured | 71.7% WER joint, 73.3% CTC, 30 voiced out-of-domain clips; our crop 70.6% vs official 71.7% |
| Accuracy on our own silent sentences (E-OV1) | **not measured** | needs prompted recordings (next: in-app recorder) |

## 1. The ask and the honest starting point

Today Mouna recognises only what the person has taught it: a few phrases, matched against their own examples
(LipLearner encoder + `android/core` Learner). The ask: a person who can't make sound should be able to say
**most English words and sentences** with their lips alone, without teaching each one first.

What the literature says, so we plan for the right thing:

| Fact | Source |
|---|---|
| Best open, pretrained English lip reader we can download: Auto-AVSR visual-only, **20.3% WER on LRS3** (3,291 h of training video, 250 M params). Code Apache-2.0; the weights carry the training data's terms | [mpc001/auto_avsr](https://github.com/mpc001/auto_avsr) |
| That 20.3% is TED talks, voiced, frontal. Model cards for the same weights say accuracy "drops sharply" with head angle, lighting and **silent or casual speech**; webcam figures are not those numbers | [ebowwa/silentvsr-models](https://huggingface.co/ebowwa/silentvsr-models) |
| Voiced-trained recognisers lose about 8.5 points on silent speech | docs/research-unknown-words.md |
| Word initials as hints cut VSR WER from 20.3% to 9.19% (LipType, CUI '25, fine-tuned LLM) | [Su, Fang, Rekimoto 2025](https://www.sonycsl.co.jp/publications/id24423/) |
| Our own earlier call: "open vocabulary is out of reach, don't chase it" (Indian languages). This plan is **English only**, which is the case where pretrained models exist | docs/finale-plan.md, summary point 3 |

So: we can't promise a transcriber. We can build **free talk with fast correction**. The phone proposes the most
likely sentences, the person picks or fixes one with a nod, a look or a tap, and only then does Mouna speak. That
fits the core thesis (PLAN.md §1: "knows when it hasn't understood, and recovers").

## 2. Which model, for this phone

**The phone (read over adb, 9 Oct):** the USB-connected device is the judging phone itself, vivo **I2501 = iQOO 15**,
`ro.soc.model=SM8850` (Snapdragon 8 Elite Gen 5, Hexagon NPU), Android 16 (API 36), 15.6 GB RAM (8.4 GB available
with our app's stack idle), 444 GB free. The app already ships `onnxruntime-android-qnn:1.29.0`, the runtime that
runs our LipLearner encoder fully on this NPU in 18.06 ms (`deck/data/device-litert.json`). So memory is not the
limit; what matters is accuracy on silent English and whether the graph compiles for QNN.

**Every open-vocabulary visual-only English model we could find (LRS3 WER, lower is better):**

| Model | LRS3 WER | Size | Weights? | Code licence | Fit for the iQOO 15 |
|---|---|---|---|---|---|
| **Auto-AVSR** VSR (Ma et al. 2023), `vsr_trlrs2lrs3vox2avsp_base` | **20.3%** (19.1% for the 3,448 h variant + LM in the VSR-for-multiple-languages repo) | 250 M params | **yes** | Apache-2.0 (auto_avsr); non-commercial (VSR-ML repo) | **Pick.** Conv3D + ResNet-18 front end + 12-layer Conformer + CTC: the same stem as the LipLearner encoder we already run on this NPU (`Conv3d(1, 64, (5, 7, 7))` + ResNet-18, `encoder/mouna_encoder/model.py`), so `Frontend2D` (Conv3D rewritten as an exact Conv2D over 5 stacked frames) carries over. A third party has already converted these exact weights to Core ML (fixed 105 frames, fp16, 362 MB), so the graph exports |
| **BRAVEn** Large w/ self-training (Haliassos et al. 2024) | 20.9% (20.1% with LM) | Large (parameter count not stated in the README) | yes | MIT | **Runner-up.** Same accuracy band; test it on our silent sentences next to Auto-AVSR (E-OV1b) and keep whichever wins |
| USR Large, high-resource (Haliassos et al.) | 22.3% | Large | yes | not stated | Worse than both above; skip |
| VALLR (Thomas, Fish, Bowden, ICCV 2025) | 18.7% | Video Transformer + fine-tuned LLM | **no** (code "after review", none found) | — | Can't use |
| DLLM-VSR (2026 preprint) | 19.4% | USR 2.0 Huge + Dream-7B diffusion LLM | code only | — | 7B decoder: too big and too slow |
| Chang et al. 2024 (Google) | 12.8% | — | no (100,000 h private data) | — | Can't use |
| VSP-LLM, Llama-AVSR | 24.9–25.4% | 7B LLM | partly | — | Too big, and worse |
| LiteVSR (ICASSP 2024) | 45.7% | small | not found | — | Too inaccurate |
| LRW word classifiers (mpc001 TCN) | 88.9% top-1 on **500 isolated words** | 139 MB | yes | non-commercial | Not open vocabulary |

**Decision: Auto-AVSR VSR as the main model, BRAVEn Large as the challenger, picked by E-OV1 on our own silent
sentences**, not by the LRS3 table. The two are within about one WER point of each other on LRS3, and LRS3 is voiced
TED speech, so it can't break the tie for silent mouthing.

**Will it run fast enough? (estimate, to be measured in E-OV4).** A 4 s sentence = 100 frames at 25 fps. Rough
count: ResNet-18 at 88×88 is about 0.3 GFLOPs per frame (30 GFLOPs), and 12 Conformer layers with d = 768 are about
33 GFLOPs: **about 60 GFLOPs per 4 s sentence**. The LipLearner encoder runs in 18 ms on this NPU, so the target of
≤ 1.5 s from end of mouthing to sentences has a wide margin on the NPU. The CPU int8 fallback is the real unknown.
CTC beam search over 5,049 units × 100 frames is a few tens of ms in Kotlin (estimate).

**Phone-specific risks:** (1) ORT 1.27 miscomputed an encoder on SM8850, fixed in 1.28; we are on 1.29, and the
self-test still compares with the laptop output (cosine ≥ 0.999). (2) A Conformer has dynamic shapes; we compile fixed
buckets, one QNN context binary each. (3) The attention decoder is autoregressive; it stays on the CPU and only
rescores the n-best.

Sources: [auto_avsr](https://github.com/mpc001/auto_avsr),
[VSR for multiple languages](https://github.com/mpc001/Visual_Speech_Recognition_for_Multiple_Languages),
[BRAVEn / RAVEn](https://github.com/ahaliassos/raven), [USR](https://github.com/ahaliassos/usr),
[VALLR](https://arxiv.org/abs/2503.21408), [DLLM-VSR, with its comparison table](https://arxiv.org/html/2605.28456),
[LiteVSR](https://arxiv.org/abs/2312.09727), [TCN word models](https://github.com/mpc001/Lipreading_using_Temporal_Convolutional_Networks),
[Core ML port of the Auto-AVSR weights](https://huggingface.co/ebowwa/silentvsr-models).

## 3. What we build

```
utterance clip (existing Segmenter, extended to sentences: up to ~10 s, ends after ~0.8 s still mouth)
  ├─ A. My phrases (unchanged): LipLearner → Learner → decide → SPEAK if sure          ← fast path, wins if sure
  └─ B. Free talk (new): same 96×96 crops → 25 fps → 88×88 → Auto-AVSR visual encoder (ONNX, NPU or CPU)
          → CTC log-probs (5,049 SentencePiece units)
          → CTC prefix beam search (Kotlin, android/core) + English word LM + personal word boost
          → n-best sentences → (P1) rescore: attention decoder, then context (caregiver's last question, history)
          → UI: best sentence big + 2 alternatives + tap-a-word repair → confirm → speak
```

Rules carried over:
- **Free-talk sentences are never auto-spoken.** They are always shown and confirmed (Tier B). Free talk can't
  trigger an action (Tier C); actions stay on taught phrases with their own confirm.
- **Context breaks ties, never writes.** The language model and the caregiver's question only re-order sentences the
  lips already proposed; nothing is generated that the beam didn't produce.
- Offline on the phone, no video stored, "communication aid" wording.
- No training (docs/existing-models.md): personalisation is decoding-time only (word boosts, correction history).

### Pieces

| # | Piece | Where | Tier |
|---|---|---|---|
| 1 | Export Auto-AVSR visual encoder + CTC head to ONNX (fixed length buckets: 50 / 100 / 150 / 250 frames = 2 / 4 / 6 / 10 s), fp16 + int8 | `encoder/mouna_encoder/avsr.py` | **P0** |
| 2 | Crop parity: our crop (mouth-centred, roll undone, side 1.1 × inter-ocular) vs Auto-AVSR's mean-face crop; switch to theirs if WER differs | `encoder/`, `Sensor.kt` (via Sachin) | **P0** |
| 3 | Python reference decoder: CTC prefix beam + word LM + boosts; eval script reporting WER, sentence-in-top-3 | `harness/mouna_harness/openvocab.py` | **P0** |
| 4 | Kotlin port of the decoder, pinned by `harness/vectors/openvocab.json` (logits in, n-best out) | `android/core` | **P0** |
| 5 | Runtime in the app: ORT (QNN context binary per bucket; CPU int8 fallback; start-up self-test like the lip encoder) | `android/app/.../engine/OpenVsr.kt` | **P0** |
| 6 | Free-talk screen: sentence + 2 alternatives, nod / double blink = speak, shake = next, tap a word → its beam alternatives | `android/app/.../ui/` (Sachin's lane) | **P0** |
| 7 | Vocabulary knob: LM limited to the top N English words + the person's own words; N chosen by measurement (E-OV3) | decoder | **P0** |
| 8 | Attention-decoder rescoring of the n-best (one teacher-forced pass per hypothesis, CPU) | `OpenVsr.kt` | P1 |
| 9 | Context rescoring: the caregiver's last sentence (Whisper is already in the app) + recent conversation, bounded weight | decoder | P1 |
| 10 | Initials hint: person types or picks first letters; the beam keeps only words with those initials (LipType idea, no fine-tuning) | decoder + UI | P1 |
| 11 | Learn from corrections: confirmed sentences raise those words and word pairs for this person | decoder | P1 |
| 12 | Small LLM rescoring of the n-best (log-likelihood only, no generation; Llama 3.2 1B / Qwen on the NPU) | app | P2 |
| 13 | TS port in the Lab (`lab/src/core`), so all three implementations pass the vectors as CLAUDE.md requires | `lab/` | P2, **needs team OK to defer** |

Not doing: training or fine-tuning a VSR model (team rule, and no time); LLM-decoded VSR (VSP-LLM, Llama-AVSR: 7B,
too big); a cloud API (offline, privacy); the LRW 500-word classifier as the main path (500 words is not "most
English", and its code licence is non-commercial).

## 4. Experiments (results go to `deck/data/openvocab-*.json`)

| # | Experiment | Pass line (**targets**) | Tier |
|---|---|---|---|
| E-OV0 | Laptop sanity: Auto-AVSR in PyTorch vs our ONNX export on 10 clips | CTC log-probs max abs diff ≤ 1e-3; same greedy text | P0 |
| E-OV1 | **Our silent sentences**: 3–5 team members × 40 sentences, silent and voiced (protocol below). WER greedy vs beam + LM, top-1 and top-3 sentence accuracy | Go: top-3 sentence ≥ 50% *or* WER ≤ 40% with LM on deliberate silent mouthing | P0, **go/no-go** |
| E-OV1b | Same clips through BRAVEn Large (w/ ST, + LM): the challenger | keep whichever has lower WER with our decoder; tie → Auto-AVSR (Apache-2.0 code, front end already proven on this NPU) | P0 |
| E-OV2 | Crop parity: our crop vs their preprocessing on the E-OV1 clips | ≤ 2 WER points apart, else ship theirs | P0 |
| E-OV3 | Vocabulary size: 1k / 3k / 10k / 50k words + personal words | pick the largest N within 5 WER points of the best | P0 |
| E-OV4 | On device: latency per bucket, NPU vs CPU int8, SM8650 and SM8850 (AI Hub, then our phones) | 4 s sentence → n-best ≤ 1.5 s on the iQOO 15; cosine ≥ 0.999 vs laptop | P0 |
| E-OV5 | Time to say a free sentence with correction vs writing on a board (E10 style) | faster in ≥ 6 of 10 | P1 |
| E-OV6 | Context and initials rescoring on E-OV1 | ≥ 5 WER points better, no case where it overrides a confident beam | P1 |
| E-OV7 | Fast path stays safe: free talk on, taught phrases still spoken as before; untaught clips never auto-spoken | 0 auto-spoken free-talk sentences | P0 |

**Recording protocol (E-OV1), team only, written consent, mouth crops only (no raw video), local and gitignored
(`data/openvocab-silent/`).** 40 sentences: 20 ward ("Can you raise the bed a little", "My throat hurts when I
swallow") and 20 everyday ("What time is it", "Call my son after lunch"); each mouthed silently twice and voiced once.
Read from a prompt screen in the Lab or the in-app recorder; transcripts are the prompts.

## 5. Schedule (Finale: 9–11 Oct)

| When | Nakul | Needs from |
|---|---|---|
| **9 Oct, day** | Download checkpoint (`vsr_trlrs2lrs3vox2avsp_base`), ONNX export, E-OV0; Python decoder + word LM; record E-OV1 on the team (30 min each) | Maadhav: preprocessing code, `npu` thread; team: recording time |
| **9 Oct, 22:00: go/no-go** | E-OV1 + E-OV2 + E-OV3 on the laptop | — |
| **10 Oct, day** (if go) | AI Hub compile + profile per bucket (E-OV4); Kotlin decoder + vectors; `OpenVsr.kt` with self-test; hand the screen spec to Sachin | Sachin: Free-talk screen, Segmenter `maxFrames` for sentences |
| **10 Oct, evening** | On the iQOO 15: latency, end to end, E-OV7; P1 items only if P0 is green | Sachin |
| **11 Oct** | Soak with the demo script; numbers into the deck; freeze with everyone else | Maadhav: deck |

**If no-go** (silent accuracy too low): keep everything off the main screen and ship one of, in order:
(a) free talk with a small vocabulary (E-OV3's best N, e.g. ward words) clearly labelled; (b) free talk only as
"what did you mean?" suggestions inside Ask; (c) a roadmap slide with the E-OV1 numbers. The current demo never
depends on free talk.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Silent mouthing by novices is much worse than LRS3 | E-OV1 decides tonight; correction UI + confirm-first; vocabulary knob |
| 250 M params, Conv3D front end and Conformer may not compile for QNN (TFLite already failed on our GRU) | Fixed buckets; ORT QNN EP via AI Hub first; CPU int8 fallback (latency measured, not assumed) |
| Memory with LipLearner, Whisper, ISL, face model all loaded | Load free talk lazily; int8 (~250 MB, estimate) |
| Licence: Auto-AVSR weights are trained on LRS2/LRS3/VoxCeleb2/AVSpeech; LRS3 is CC BY-NC-ND (finale-plan §2) | Same status as the LRW encoder: fine for a hackathon demo, stated if asked, not a product path. Weights stay out of git (`encoder/weights/`) |
| Cross-lane work during the Finale | Wire messages on `npu` (export, AI Hub) and `android` (screen, Segmenter) before touching those files |

## 7. Decisions needed

| # | Decision | Who |
|---|---|---|
| OV-D1 | Accept free talk as a Finale stretch with tonight's go/no-go (it reverses finale-plan summary point 3, for English only) | team |
| OV-D2 | Nakul owns this lane (PLAN.md D2) | team |
| OV-D3 | 30 minutes of each person's time for E-OV1 recordings today | team |
| OV-D4 | Defer the TS (Lab) port of the decoder until after the Finale | team |
| OV-D5 | Pitch line if it ships: "say anything, Mouna proposes, you confirm" with E-OV1 numbers only | Maadhav |
