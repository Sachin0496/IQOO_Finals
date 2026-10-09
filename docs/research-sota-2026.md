# Research check: Mouna against the 2023-2026 state of the art

The brief is [`research-sota-2026-brief.md`](research-sota-2026-brief.md) (research date 2026-10-09, condensed). Its §5 ranked list and §6 gap summary
set the table's rows. Statuses: **done**, **partly**, **not for the Finale**, **this branch**. Paths under `engine/` are
`android/app/src/main/java/app/mouna/app/engine/`.

## Summary

The brief's main conclusion: rejection quality and per-user adaptation matter more than a bigger encoder. A
prototype-mean head on an ONNX encoder is a good baseline (its §1, citing LipLearner), not a gap. Continuous
silent-speech transcription is server-side work, if done at all.

Mouna already has most of the first point. Calibrated rejection with "none of these" negatives is built and measured
on held-out Kannada. Its FAR/FRR evaluation is built on this branch but has not been run. Per-user adaptation beyond
prototype means is not built. The encoder is the app's main recogniser; DTW on lip geometry is only the Lab fallback.
Voice output covers English, Hindi and Tamil in the app. Offline Indic TTS for custom phrases is not built. ISL is
wired into the app, which contradicts PLAN §3 (see Discrepancies). Continuous VSR is absent by design.

## Status table

| Brief item | Mouna status | Where | Brief source |
|---|---|---|---|
| §5.1 + §6.1 Few-shot head with calibrated open-set rejection, a dummy-negative class, FAR/FRR protocol | done for the head and negatives: thresholds in units of each person's spread, "none of these" examples scored by distance (not a trained dummy class). FAR/FRR evaluation: **this branch**, not run | `harness/mouna_harness/core.py`, `Core.kt`, `Engine.noneOfThese()`; roadmap F2, F9 | §5 #1 (1-2 d, done); arXiv:2306.02161 |
| §5.4 + §6.2 Per-user adaptation: adapter or prompt tuning of the encoder, beyond prototype means | **not for the Finale**. Per-person prototypes are built. Shot augmentation was tried and dropped (-0.36 points at 3 shots) | roadmap F4; `Learner` in `core.py` | §5 #4 (3-5 d); arXiv:2302.08102 |
| §6.3 DTW on MediaPipe landmarks is the weakest link for lips | applies to the **Lab fallback only**. The app has no DTW; the encoder is the main path. In Lab careful mode, taught words spoken directly: 66.9% encoder, 36.2% lip geometry | `harness/mouna_harness/heads.py` (`DTWHead`), `lab/src/core/dtw.ts`; roadmap rows 10, 17 | §6 #3 |
| §5.2 + §6.5 Voice out for Indian languages: persona TTS, offline Kokoro/Piper | **partly**. Pre-rendered pack: 4 voices x 4 languages x 13 phrases (208 clips). Live Sarvam Bulbul v3 (speakers `kavitha`, `anand`). Open voices `anu`, `suresh` (Parler, rendered at build time). Offline Indic TTS for custom phrases not built | `voices/render.py`, `voices/voices.json`, `engine/Voice.kt`, `engine/Sarvam.kt`, `engine/Phrases.kt` | §5 #2 (3-5 d); §4 |
| §5.3 + §6.4 ISL module: OpenHands landmarks, temporal encoder, prototype head | **discrepancy**. Wired in the app: `Isl.kt` and `MounaApp.kt` `signed()` (speaks a clear winner, else offers the top three words). Pretrained SL-GCN on INCLUDE (263 signs). Personal signs not built | `engine/Isl.kt`, `MounaApp.kt`, `models/isl/`; PLAN §3, D6 | §5 #3 (2-4 d); §3 |
| §5.5 Enrollment UX: 3 shots, incremental self-training on confident inferences, re-enrol prompts | **partly**. Three examples by default (Quick mode fails, F3). Red-pair warning and re-teach prompt in `TeachScreen`; "Wrong? Pick the right phrase" (row 12). Self-training: **this branch**, off by default (F10) | roadmap rows 12, F3, F10; `engine/SelfTrain.kt`; `ui/TeachScreen.kt` | §5 #5 (2-3 d); §1 (i) |
| §1 (i) Closed-phrase lip encoder on the phone (LipLearner-style) | **done**. Whole encoder on the NPU, 18.06 ms per 1.92 s window on SM8850. int8 at 86.3 MB with no top-1 loss on Kannada (86.2% to 86.2%) | `encoder/`; roadmap rows 9, 11, F8, F4 | §1 (i): "18 ms/1.9 s is right" |
| §5.6 + §6.6 Continuous VSR, on-phone or server | **not for the Finale** (intentional). LiteVSR is listed as a candidate in `finale-plan.md` §4 and not built. AV-HuBERT excluded for the product (licence) | `docs/finale-plan.md` lines 75, 89-93, 105 | §5 #6 (1-2 w); §1 (ii) |
| §5.7 Evaluation: FAR/FRR and a WildVSR-style unseen-condition set | **partly**. FAR/FRR built (F9), not run: Kannada data not on this machine. Held out by speaker. Half-face mirror (E23) exists. No WildVSR-style set | roadmap F9; `deck/data/kannada-halfface.json` | §5 #7 (2 d) |

Merged rows: §5 #1 with §6 #1, §5 #4 with §6 #2, §5 #2 with §6 #5, §5 #3 with §6 #4, §5 #6 with §6 #6.

## Claims we can make

- Calibrated rejection with negatives, Kannada held out by speaker: taught words spoken directly 55.0% (today's head)
  to 64.8% (core, 5 negatives); wrong speaks on taught words 0.63% to 0.33%; untaught words spoken 4.1% and 4.5%
  (roadmap F2).
- Kannada top-1 at 3 shots on the deck split: 86.19%; at 1 shot: 71.97% (roadmap F4). The brief's "90-97%" for
  5-40 phrases is its target, not a measurement of ours.
- Careful mode: untaught words spoken by mistake fall from 23% to 7.2% (encoder) (roadmap row 17).
- Half-face mirror, healthy speakers only: left half 84.4% top-1 against 86.19% full face (E23,
  `deck/data/kannada-halfface.json`).
- Encoder on the phone: 18.06 ms per 1.92 s window on SM8850 (Galaxy S26, the iQOO 15's chip), all 5,265 layers on the
  NPU (roadmap F8). Not measured on an iQOO 15.
- The encoder is a LipLearner rebuild from LipLearner's MIT release (`docs/finale-log.md`).
- The FAR/FRR protocol follows arXiv:2306.02161 (`harness/mouna_harness/farfrr.py`, this branch). No results yet.
- Self-training is the brief's "incremental self-training on confident inferences", opt-in. Its thresholds are targets.

## Claims we must not make

- Continuous or open-vocabulary silent-speech transcription, on the phone or on a server.
- Lip accuracy of 90-97%, or accuracy on unseen speakers beyond the Kannada held-out split.
- That DTW or lip geometry is the app's recogniser. It is the Lab fallback.
- ISL accuracy on our signers, signer-independent ISL, or personal signs. The 93.5% on INCLUDE is OpenHands' figure
  (`models/isl/README.md`), not ours.
- Any FAR/FRR or equal-error figure before F9 is run.
- Voice cloning, or "the patient's own voice" in the app. The Lab plays the person's recording back; no IndicF5 code
  exists.
- Kannada or Telugu spoken in the app. The app's languages are English, Hindi and Tamil (`Lang`). Kannada is in the
  pack and the Lab only. Telugu is nowhere.
- Offline Indic TTS for custom phrases.
- That self-training improves accuracy. It is not measured.
- Any AV-HuBERT or LRS3-trained weights in the product (`docs/finale-plan.md` line 75).

## Discrepancies for the team

1. **ISL.** PLAN §3 says "No for the Finale; slide", and decision D6 ("Accept ... No for the Finale") is still open
   for Sachin. The code wires the ISL classifier into the app, and `signed()` speaks a clear sign. The model is not in
   the repo; it is read from the app's external files folder, so it runs only once pushed to the phone. The team
   must decide what the deck says.
2. **The brief assumed DTW was the lip path** (§6 #3). In the app the encoder is the main path. DTW is the Lab
   fallback, and the Lab's own numbers (above) show its weakness.
3. **The brief's top gap is already built.** Rejection (§6 #1) is in `core.py`. The open part is the FAR/FRR
   evaluation (F9, not run).
4. **Server VSR options conflict with the licence rule.** The brief lists AV-HuBERT and Whisper-Flamingo as server
   options. `docs/finale-plan.md` line 75 says to avoid LRS3 and anything built on it, including AV-HuBERT weights.
5. **IndicParler** is pre-rendered at build time in the app (`voices/render.py`). The brief's §5 #2 places it on a
   server. The app itself does not call a server for it.

## Open items

| Item | Effort (brief) | Note |
|---|---|---|
| Per-user adapter or prompt tuning of the lip encoder (arXiv:2302.08102) | 3-5 d (§5 #4) | `finale-plan.md` puts personalised VSR "later, after a week of use" |
| Offline Indic TTS for custom phrases: sherpa-onnx Kokoro or Piper; IndicParler persona | 3-5 d (§5 #2) | Kokoro and Piper coverage of kn, ta, te: (verify). Piper covers Hindi |
| Personal-sign prototype head: landmarks, temporal encoder or DTW, prototype head; 8-15 signs x 5-10 enrolments | 2-4 d (§5 #3) | PLAN D6 open. Signer-dependent per the brief |
| Enrollment: re-enrol prompts, self-training threshold tuning | 2-3 d (§5 #5) | F10 thresholds are targets; tune on recorded sessions |
| Optional continuous VSR (LiteVSR2 ONNX) | 1-2 w (§5 #6) | Not for the Finale. No AV-HuBERT weights (licence) |
| Run FAR/FRR (F9) on the Kannada data; WildVSR-style unseen-condition set | 2 d (§5 #7) | Kannada data not on this machine |
| Lip geometry as the primary recogniser | no estimate in brief | Not planned |

## Note

The brief is dated 2026-10-09. Items marked **(verify)** in the brief are still unverified; keep the marker. Repo
numbers come from the roadmap, `docs/finale-log.md` or `deck/data/*.json`. The brief's external figures (AV-HuBERT
WER, LiteVSR, per-speaker CER of 1.0-52.2%) are its citations, not ours.
