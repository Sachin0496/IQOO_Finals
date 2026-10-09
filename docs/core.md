# The decision core (App ↔ Core contract)

Reference: `harness/mouna_harness/core.py`. Kotlin port for the app: `android/core` (`app.mouna.core`), pinned to
Python by `harness/vectors/core.json` (`CoreParityTest`, run in CI). Lab ports: `lab/src/core/switch.ts`, `gaze.ts`.
Change Python first, regenerate the vectors (`python -m mouna_harness vectors`), then port.

## Calls

```kotlin
val learner = Learner()                       // one per person
learner.teach("water", embedding)             // check-then-learn; returns the check (null for a first example)
learner.addNegative(embedding)                // "none of my phrases": ask for 5 at setup, add every "none of these"
learner.nextToTeach(pack, minShots = 2)       // first | check | missed | close_to:<other>, or null when safe
learner.separability()                        // RED pairs look alike for this person: show them, offer to rephrase
val d = decide(learner.predict(embedding), tiers = mapOf("fan_off" to Tier.C), careful = false, prior = null)
```

Also in `app.mouna.core`:

| Piece | Kotlin | Port of |
|---|---|---|
| Encoder input from 96 px crops | `EncoderInput.build(crops, frames, 96, tMs)` → (48, 5, 88, 88) | `encoder/mouna_encoder/preprocess.py` + `stack_frames` |
| Start-up self-test | `SelfTest.load()`: `.input` to the NPU, then `.passes(embedding)` (cosine >= 0.99); clip in `/mouna/selftest/` | `encoder/mouna_encoder/selftest.py` |
| Personal switch | `teachSwitch(rest, moves, names)`, `PersonalSwitch.push(blend, tMs)` | `lab/src/core/switch.ts` |
| Look to choose | `irisPosition(lm, w, h)`, `calibrateGaze(...)`, `GazeSelector.push(pos, tMs)` | `lab/src/core/gaze.ts` |
| Ask mode | `AskTree.bundled()`, `AskSession(tree).yes() / no() / back()` | `lab/src/core/ask.ts` |
| Build a sentence | `BlockPack.bundled()`, `readBlocks(rows, pack)`, `plainWords(words, pack)` | `lab/src/core/blocks.ts` |

The Ask tree, blocks and phrase pack JSON come from `lab/src/core` (packed as resources under `/mouna/`).

## What the app shows for each decision

| `Decision.kind` | Show | Answer with |
|---|---|---|
| `SPEAK` | Say `options[0]` in the caregiver's language; "Wrong?" link | nothing |
| `CONFIRM` | One big picture: "Did you mean …?" | yes / no (switch, nod, double blink, tap) |
| `RESCUE` | Two big pictures, left and right | look and hold (`GazeSelector`), or switch |
| `CHOOSE` | Three or four pictures | look, switch-scan, or tap |
| `ASK` | The yes/no tree, `options` as first guesses | yes / no |
| `NOT_TAUGHT` | "Not one of your phrases", `options` as two "maybe" pictures, Ask | yes / no |

`maybeNone = true`: put "None of these" first. Tier C (IR, nurse call) never comes back as `SPEAK`.
Context (`prior`) only reorders the pictures of a set; it never makes Mouna speak.

## Constants (measured, Kannada, held out by speaker: `deck/data/finale-core.json`)

| Constant | Value | Meaning |
|---|---|---|
| `PRIOR_TAU` | 0.14 | Typical within-intent cosine distance; scores are distances in units of this person's own spread |
| `Q_SPEAK` A / B | 1.7 / 1.4 | Best score needed to speak (B: design choice, stricter) |
| `MARGIN` | 1.3 | Runner-up must be this many times further |
| `NEG_RATIO` | 1.2 | No "none of these" example within this many times the best score |
| `Q_SET` | 1.95 | Prediction set threshold (truth inside 91.1% held out, target 90%) |
| `RED_PAIR` / `YELLOW_PAIR` | 1.0 / 1.8 | Prototype gap / tau. Test confusion: < 1.0: 2–3.5%; > 1.8: ≤ 0.3% |

## Encoder on the phone

The static 48-frame encoder (`encoder_static48`, 1.92 s window, input `frames` (48, 5, 88, 88), output a
500-d embedding) does not convert to TFLite/LiteRT: every AI Hub TFLite compile (fp32, fp16, int8) fails with a size
overflow. QNN works. The app runs it with ONNX Runtime's QNN execution provider from a context binary precompiled
per chip (`python -m mouna_encoder.litert qnn_ctx_fp16 qnn_ctx_int8 --report qnn`, results in
`deck/data/device-litert.json`). Run a known-clip self-test at start and fall back to CPU if it fails (finale-plan §6).
