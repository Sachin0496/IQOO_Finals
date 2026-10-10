# Mouna: Finale build plan (iQOO Hackathon 2026, Bengaluru, 9–11 Oct)

Written 6 Oct 2026. This is the **single plan** every person and agent works from. It triages every idea in issue #1
(the research plan and four rounds of ideas) into what we build, what we measure and what we leave out.
The pre-event spike plan is archived in [docs/spike-plan.md](docs/spike-plan.md).

Source documents, in order:
1. [docs/finale-plan.md](docs/finale-plan.md): rubric, rules, licences, models, runtimes, risks (issue #1 body).
2. [docs/ideas-beyond-lips.md](docs/ideas-beyond-lips.md): round 1, people who can't mouth clearly or touch.
3. [docs/question-everything.md](docs/question-everything.md): round 2, twelve assumptions questioned.
4. [docs/error-correcting.md](docs/error-correcting.md): round 3 (Sachin), error-correcting communication.
5. [docs/pivot-universal-voice.md](docs/pivot-universal-voice.md): round 4 (Sachin), voice layer for any expression.

---

## 1. What Mouna is (after all four rounds)

> **You don't learn to talk to Mouna. Mouna learns how you communicate, and knows when it hasn't understood.**

- **Product story (rounds 2, 4):** one phone, one camera screen. The person communicates with whatever their body
  still allows (lips, a personal movement, eyes, a nod), and Mouna turns it into speech in the caregiver's language,
  a message on the caregiver's phone, or an action in the room. Offline, on the NPU.
- **Technical thesis (round 3):** lip reading is an ambiguous channel. Instead of claiming a perfect recogniser, every
  layer corrects the one before it: a visual vocabulary chosen for this person, teaching effort spent only where they
  are ambiguous, a *set* of answers when unsure, an explicit "none of these", the cheapest clarifying question, and
  stricter rules for higher-consequence actions.
- **Design principle (round 1):** Mouna guesses from weak signals; the person chooses with whatever still moves.

**Who we serve.** Mouth mode: laryngectomy and tracheostomy (mouth free, lips move well). Any-movement mode: stroke
and weakness (personal switch, picture choices, eye looks). **Who we don't:** intubated patients with an oral tube;
we say so in the pitch.

**Any language.** The recogniser never transcribes; it matches a person's movements to their own examples. Input can
be any language or dialect (Tulu, Konkani, Kanglish). Only the output needs a language, and pictures can stand in.
Measured basis: English-trained encoder, 89.0% top-1 on Kannada with 5 shots (`deck/data`).

---

## 2. Who does what

| Lane | Owner | Owns |
|---|---|---|
| **App** | **Sachin** (`sachin-claude`) | The Android app (Kotlin + Compose): CameraX, MediaPipe face (+ hands), mouth crop, LiteRT/QNN runtime and self-test in the app, all screens (Teach, Speak, choices, Ask, sentences, caregiver link, Pitch mode, QA tools), voices, IR, Office Kit flow, on-phone packaging and soak |
| **Core + evidence** | **Maadhav** (`maadhav-claude`) | The recognition and decision core as a spec + reference implementation (Python harness, TS Lab) with **test vectors the Kotlin port must pass**; encoder builds for SM8650/SM8850; all experiments and numbers; silent recordings; deck and pitch numbers |
| Open | Nakul (`nakul-claude`) | **To confirm.** Proposed: recordings logistics, clinician/Kidwai outreach, native-speaker review, IR fan + stage kit, demo filming, rehearsal timing |

**The seam between App and Core** is §5: Sachin builds against the interface; Maadhav delivers the behaviour as
JSON test vectors plus a reference implementation. Neither lane blocks the other: the app starts with the current
`PrototypeHead` behaviour and swaps in each core upgrade as its vectors land.

---

## 3. Every idea, triaged

**P0** = demo spine, must ship. **P1** = ship if P0 is green by 8 Oct evening. **P2** = only with spare time.
**Slide** = roadmap or pitch only. **No** = deliberately out.

### Recognition and decisions (Core lane, UI in App lane)

| Idea | Source | Tier | Note |
|---|---|---|---|
| Set-valued decision: 1 → speak, 2–3 → choices, many → Ask, none → "not taught" | R3 §3, R1 §3 | **P0** | Replaces threshold + `MIN_MARGIN`. Call it "conformal-style"; no formal coverage claim with 3 shots |
| Confusion map + separability score per pair | R3 §1 | **P0** | From prototype distances; no new model. Shown for 2 s in the demo |
| Active teaching: 1 sample each, then more only for weak pairs, stop at safe (max 5) | R3 §2 | **P0** | "Mouna learns only what it still needs to learn" |
| Negative rehearsal: 3–5 "none of my phrases" mouthings at setup | R3 §4 | **P0** | Attacks the measured 23% untaught false-speak |
| Risk tiers: A auto-speak, B stricter/confirm, C (actions) always confirm | R3 §8 | **P0** | Interaction safety, not clinical triage |
| One-bit rescue for a known confused pair (two pictures, look or switch) | R3 §6, R1 §2 | **P0** | Uses rank-2, which is already strong |
| Rephrase suggestion when a pair overlaps ("teach a different mouth phrase") | R3 §1 | P1 | Falls out of the confusion map |
| Bounded context prior (time since, time of day, habits); never overrides strong evidence | R3 §7, R1 §3 | P1 | Lab already re-ranks choices only; add the cap and "why first" |
| Drift watch: re-teach only the failing phrase | R2 §7 | P1 | Same machinery as active teaching |
| Shot augmentation (speed, jitter, mirror) | finale-plan §3 | P1 | Ship only if E3 passes |
| Huffman Ask (semi-stable, fixed tree kept) | R3 §5 | P2 | Fixed tree stays the default |
| Half-face mirror for facial droop | R1 §10 | P2 | Harness experiment only (E23) |
| Small on-device LLM for unlisted pairs | roadmap #19 | P2 | Only after E9; always confirmed |
| Full conformal guarantees, automatic codebook search | R3 §10 | Slide | Needs calibration data we don't have |

### Input channels

| Idea | Source | Tier | Note |
|---|---|---|---|
| Lips (encoder on NPU) | spike | **P0** | Exists; port to app |
| Nod, double blink, shake | spike | **P0** | Exists in the Lab; real-face check E7 |
| **Personal switch**: teach any repeatable movement (blendshapes) as "your yes" | R1 §1 | **P0** | Same few-shot learner over MediaPipe's 52 blendshapes; one-sided smile after stroke |
| Look left / right to choose between 2 big pictures | R1 §2, R4 §4 | **P0** | Iris landmarks, two zones only; powers one-bit rescue |
| Personal gestures with hands (few-shot over hand landmarks) | R4 §3 | P1 | Same modality-agnostic learner; any ISL sign the person already knows can be taught this way |
| Fused personalised model (lips + face + hands) | R4 §1, §3 | P1 | Measure first (E22); ship fusion only if it beats the best single channel |
| Tongue / teeth click as a switch (mic) | R1 §7 | P2 | Works in the dark and under an oxygen mask |
| Pain prompt from a sustained wince (asks, never alarms) | R1 §5 | P2 | Opt-in |
| Caregiver speaks, patient sees one picture + question (ASR) | R1 §8 | P2 | IndicConformer; stretch |
| Sonar silent speech (speaker + mic) | R2 §5, R4 §2 | Slide (P2 spike) | One-day Doppler "mouth moving" spike only if someone is free |
| **ISL recogniser** | R4 | **No** for the Finale; slide | Different user group (Deaf signers), needs data and native review we can't do in 3 days; cite AI4Bharat/INCLUDE/OpenHands as the path |
| EMG / EEG / neural input | R4 | Slide | The phone has no such sensors; "same interface later" |
| Lips + electrolarynx audio | R2 §6 | Slide | |

### Outputs and ward workflow

| Idea | Source | Tier | Note |
|---|---|---|---|
| Voice in the caregiver's language, pre-rendered + own-voice pack | spike | **P0** | Exists |
| Caregiver phone link (local, no internet) | spike | **P0** | Exists in the Lab; port |
| Picture grid, family's own photos, teach to a picture | R1 §4, R2 §11 | **P0** | We take a small photo set ourselves; Mulberry/Global Symbols as fallback; not ARASAAC (NC) |
| **IR room control** (fan / TV off), Tier C confirmed | R1 §6, R4 §5 | P1 | iQOO 15 and OnePlus 13R have IR; `ConsumerIrManager`. Demo wow; needs an IR fan or LED receiver on stage |
| Ward log for handover ("pain 02:13, nurse 02:19") | R2 §3 | P1 | |
| Fatigue-aware (4 → 2 choices, offer rest) | R1 §9 | P1 | |
| Comfort message recorded by family | R1 §11 | P1 | Tiny |
| Pre-surgery session, tap-to-label voiced teaching + voice bank | R2 §8 | P1 | E12 measures the voiced → silent gap |
| Ward kit framing (bed-stand phone, 25 s setup, wiped at discharge) | R2 §2 | Pitch | |
| Family link, home via equipment rental bank (AIIMS AIR) | R2 §9 | Pitch | |
| Real nurse-call button (BT adapter / ESP32 relay) | R2 §3 | Slide | Show the existing adapter as the path |
| Articulation coach | R2 table | Slide | Ask Kidwai's SLP |
| On-device image generation, face-only alarms, diagnosis claims | R1 §12 | **No** | |

### Pitch and evidence

| Item | Source | Tier |
|---|---|---|
| Pitch mode inside the app, phone mirrored via Office Kit | finale-plan §1 | **P0** |
| Real numbers on our own silent recordings (E4, E5) | finale-plan §9 | **P0** |
| "Any language" demo by a friend in Tulu or Konkani (E11) | R2 §10 | P1 |
| Time to be understood vs writing vs board (E10) | R2 §4, R3 §9 | P1 |
| Clinician or laryngectomee feedback (Kidwai, Laryngectomee Club), with consent | R2 §12 | P1 (outreach starts now) |
| "Nine in ten feel trapped" framing | R2 Part B | Pitch |
| Claims we never make (invented sign recognition, all ISL, reading thoughts, clinical accuracy, one model for every disability) | R4 | Rule |

---

## 4. Architecture

```
CameraX front 640x480 30 fps
  -> MediaPipe Face Landmarker (GPU): lips, iris, blendshapes, head pose   [+ Hand Landmarker, P1]
  -> gates: quality, activity, still-mouth; blink / nod / shake / personal switch / gaze L-R
  -> mouth crop 96x96 grey, 48-frame window -> lip encoder (ONNX Runtime QNN EP, context binary per chip; CPU fallback; start-up self-test)
  -> CORE (§5): prototype head + negatives -> prediction set -> context cap -> risk tier -> Decision
  -> UI: speak | choices (pictures) | one-bit rescue | Ask | sentences | not taught
  -> outputs: voice, caregiver link, IR, ward log
Pitch mode, QA tools (recorder, on-phone eval, thresholds, phrase editor), telemetry overlay
```

## 5. Core interface (the App ↔ Core contract)

**Built:** `android/core` (Kotlin, CI-tested against Python) and [docs/core.md](docs/core.md). `Decision` shipped as a data class with a `kind` (easier in Compose) instead of the sealed interface below; the speak rule is margin + negatives on person-normalised scores, the set only picks the fallback (measured, `deck/data/finale-core.json`).

Kotlin names are suggestions; the behaviour is fixed by test vectors in `harness/vectors/*.json`
(input embeddings or feature sequences, expected outputs). The Kotlin port passes the same vectors as Python and TS.

```kotlin
// One learner for every channel: lip embeddings, blendshape sequences, hand landmarks.
interface FewShotLearner {
    fun addSample(intent: String, x: FloatArray)          // or a sequence, for DTW channels
    fun addNegative(x: FloatArray)                        // "none of my phrases"
    fun separability(): List<PairScore>                   // (a, b, margin, GREEN|YELLOW|RED)
    fun nextToTeach(): TeachRequest?                      // null = pack is safe; else intent + reason
    fun predict(x: FloatArray): Ranked                    // distances to every intent + best negative
}

sealed interface Decision {
    data class Speak(val intent: String) : Decision                       // singleton set, tier allows
    data class Confirm(val intent: String) : Decision                     // tier B/C or near threshold
    data class Rescue(val a: String, val b: String) : Decision            // known confused pair: two pictures
    data class Choose(val set: List<String>, val why: List<String>) : Decision  // 2–3, context-ordered
    data object Ask : Decision                                            // large set: yes/no tree
    data object NotTaught : Decision                                      // empty set / negative wins
}

fun decide(r: Ranked, ctx: ContextPrior, tiers: Map<String, Tier>, careful: Boolean): Decision
```

Delivery order of vectors (Maadhav → Sachin): **(1)** current `PrototypeHead` + decision, **(2)** set-valued
decision + negatives, **(3)** separability + `nextToTeach`, **(4)** rescue + risk tiers, **(5)** blendshape switch
learner, **(6)** context cap. Each lands as a commit with its vectors and a `wire send` on the `android` thread.

---

## 6. Experiments (all measured, nothing invented; numbers go to `deck/data/*.json`)

| # | Experiment | Owner | Pass line | Tier |
|---|---|---|---|---|
| E1 | Encoder on SM8650 + SM8850 (AI Hub). **Done:** LiteRT fails to convert; ORT QNN 18.1 / 32.4 ms, 100% NPU | Maadhav | ≤ 30 ms, 100% NPU | P0 |
| E2 | Phone embedding vs PyTorch, 20 clips. **On AI Hub phones:** cosine 0.99978; on our phones: Sachin | Sachin + Maadhav | cosine ≥ 0.999 | P0 |
| E4 | **Silent speech, novice users**, 5–6 people, 2 sessions | Maadhav | ≥ 90% same session, ≥ 85% next day | P0 |
| E5 | Untaught words on silent data, default vs Careful vs negatives | Maadhav | Careful ≤ 10% | P0 |
| E7 | Blink, nod, shake, personal switch on real faces | Sachin + Maadhav | 0 false in 5 min mouthing; ≥ 90% detected | P0 |
| E8 | End-to-end latency on the iQOO 15 | Sachin | release → voice ≤ 700 ms | P0 |
| E14 | Confusion-aware rephrasing | Maadhav | worst pair −50% or +5 top-1 | P1 |
| E15 | Fixed 3-shot vs active 1–5-shot teaching. **Fails:** −19% examples, −0.8 top-1 | Maadhav | ≥ 25% less teaching time, no worse accuracy/open-set | P0 |
| E16 | Set-valued decision. **Pass:** 91.1% held out, mean set 1.33 | Maadhav | ≥ 90% empirical coverage, mostly singletons on known | P0 |
| E17 | Negative rehearsal vs today's head. **Kannada:** same false-speak point, taught spoken 55.0% → 64.8%; repeated off-list words 3.7% → 1.0% | Maadhav | untaught false-speak −50% | P0 |
| E19 | One-bit rescue vs top-4 grid vs Ask on rank-2 errors. **Kannada:** when not spoken, truth on screen 94.9%; two pictures 95.8% | Maadhav | faster in ≥ 75% | P0 |
| E21 | Risk tiers stress test | Sachin | 0 unconfirmed Tier C actions | P0 |
| E3 | Shot augmentation. **Fails, dropped:** −0.36 points at 3 shots | Maadhav | ≥ +1.5 points, else drop | P1 |
| E6 | Stage conditions (light, glasses, 30° yaw) | Maadhav | no case below 75% | P1 |
| E10 | Time to be understood vs writing vs board | all | Mouna faster in ≥ 8 of 10 | P1 |
| E11 | Any language (Tulu / Konkani / Malayalam) | Maadhav | same range as Kannada | P1 |
| E12 | Voiced teach → silent test (+1 silent top-up) | Maadhav | gap ≤ 6 points | P1 |
| E13 | Drift: morning / evening / next day, re-teach | Maadhav | re-teach recovers ≥ 90% | P1 |
| E20 | Context prior rescues vs harms | Maadhav | ≥ 5:1, zero strong-margin overrides | P1 |
| E22 | Lips-only vs face-only vs hands-only vs fused, per person | Maadhav | fused beats best single, else ship single | P1 |
| E9 | LLM sentence for unlisted pairs | Maadhav | ≥ 80% native-acceptable | P2 |
| E18 | Fixed vs Huffman Ask | Maadhav | ≥ 25% fewer actions, same success | P2 |
| E23 | Half-face mirror on Kannada | Maadhav | within 5 points of full face | P2 |

Data for E4–E7, E12, E13, E15–E17 comes from **one recording protocol** (session 1 on 7 Oct, session 2 on 8 Oct):
each person teaches 8 intents, 5 repetitions, plus 5 untaught mouthings, plus idle, plus 2 minutes of personal-switch
and gaze trials. Recorded with the in-app recorder if ready, else the Lab.

---

## 7. Schedule

| When | App (Sachin) | Core + evidence (Maadhav) | Done when |
|---|---|---|---|
| **6 Oct** (tonight) | Android skeleton on the OnePlus 13R: camera → landmarks → crop | This plan; vectors (1); AI Hub compile/profile for SM8650 + SM8850 (E1) | Plan pushed; encoder builds exist |
| **7 Oct** | Encoder in app + self-test (E2); head + decision (1); Teach / Speak / voices; caregiver link | Vectors (2)–(4); E15–E17, E19 on Kannada; recording protocol; **silent session 1** | App speaks a taught phrase on the phone |
| **8 Oct** | Choices / rescue / Ask UI; personal switch + gaze; picture grid; Pitch mode; QA tools; P1: IR | Vectors (5)–(6); **session 2**; E4, E5, E7; numbers into deck and Pitch mode | Demo script runs end to end on the OnePlus |
| **9–11 Oct** | Install on the iQOO 15; NPU warm-up; E8, E21; soak; Red Light tasks | E6, E10, E11 if time; final numbers; rehearsal | 20 clean demo runs in a row; freeze 8 h before judging |

**Gate on 8 Oct, 20:00:** if any P0 is red, P1 work stops and everyone fixes P0.

## 8. The demo (about 90 s)

1. Teach five intents with **active teaching**; Mouna flags "water ↔ toilet look close", asks for one more, shows the
   **confusion map** for 2 s, declares the pack safe. Time shown on screen.
2. Judge mouths a clear phrase → **singleton** → spoken in the caregiver's language; caregiver phone lights up.
3. Ambiguous mouthing → set {water, toilet} → Mouna refuses to guess → **two pictures**; the person **looks left**
   and confirms with **their own switch** (a raised eyebrow).
4. Judge mouths an untaught phrase → **"not taught"** (negatives): "Did you mean…" or "Something else", never a guess.
5. "Fan off" → **Tier C confirm** → the **IR blaster** switches the fan off (P1; else the caregiver phone and ward log).
6. Close on the numbers from E4, E15, E17 and the line: *"We did not make lip reading perfect. We made a
   communication system that knows where lip reading fails, and recovers."*

## 9. Decisions still needed

| # | Decision | Who |
|---|---|---|
| D1 | Organisers' written permission for pre-building; decides whether the app lives in `android/` here or a new repo | Maadhav |
| D2 | Nakul's lane (proposal in §2) | team |
| D3 | 5–6 people for the two silent sessions | team |
| D4 | Clinician / Kidwai / Laryngectomee Club contact | Nakul (proposed) |
| D5 | Native speakers for Kannada, Tamil, Hindi review | team |
| D6 | Accept "ISL recogniser: No for the Finale, personal gestures instead" (§3) | Sachin |
| D7 | IR target for the stage (fan model or IR LED receiver) | Sachin |

## 10. Rules (unchanged)

Numbers only from measured files or cited sources; targets labelled as targets. Pre-existing work disclosed. No
patient on stage, no identifiable video without written consent, no stored raw video. "Communication aid", never a
diagnosis. Licence caveat on the LRW-trained encoder stated if asked (finale-plan §2).
