# Evaluation protocol

The same protocol is used for the spike and for the Finale, so the numbers are comparable.

## Data

- **People:** 3 (team members or consenting volunteers). Healthy speakers only.
- **Phrases:** 8 from the starter pack. Default set: `water`, `pain`, `nurse`, `breathe`, `toilet`,
  `medicine`, `sit_up`, `thank_you`. Chosen for distinct lengths and lip shapes.
- **Repetitions:** 5 silent mouthings per phrase per session.
- **Sessions:** `s1` (evening) and `s2` (next morning). Same phone, same stand, no re-teaching.
- **Idle:** 10 minutes per person of not speaking: neutral face, swallow, yawn, smile, talking to someone off camera.

## Split

| Use | Clips |
|---|---|
| Teach | `s1`, repetitions 1–3 |
| Test, same session | `s1`, repetitions 4–5 |
| Test, next morning | `s2`, repetitions 1–5 |
| False triggers | idle stream at the chosen threshold |

## Variants (recorded as extra `s1v-*` sessions when time allows)

Glasses on and off, dim light, head turned about 15°, speaking language switched (mouth Tamil, output Kannada).

## Report

Top-1, top-3, reject rate, accuracy on accepted clips, confusion matrix, false triggers per minute,
latency per stage, fps, battery per hour and temperature after a 30-minute soak. Publish the whole table,
including the bad numbers.

## Recording conventions (Mouna Lab)

- Phone on a stand, 30–45 cm, front light, no backlight.
- Push-to-talk: hold the button (or Space) for the whole mouthing, release after the lips close.
- A clip is rejected by the quality gate if the face is lost, the mouth is too small, the head is turned
  too far or the lips barely moved. Rejected clips are re-asked, not saved.

## Finale silent sessions (7–8 Oct 2026, PLAN.md E4, E5, E17)

Same as above, with three changes:

- **People:** 5–6, each in two sessions (`s1` evening of 7 Oct, `s2` morning of 8 Oct), **silent** mouthing only.
- **Step 2, untaught words:** after the 8 × 5 grid, the Lab asks for 10 everyday words
  (`UNTAUGHT_WORDS` in `lab/src/core/protocol.ts`; none is one of the 8 protocol phrases), once each per session, mouthed in the person's own language.
  Clips have kind `untaught`. Half become the person's "none of these" examples, the other half are tested, then swap.
- **Idle:** 5 minutes per person per session is enough (the false-trigger test in the Protocol tab).

Each person needs about 12 minutes per session. Export from the Data tab, put the files in `data/finale-silent/`, then:

```bash
python -m mouna_encoder embed data/finale-silent data/finale-silent-emb.npz --model static-int8
python -m mouna_harness finale-silent data/finale-silent --embeddings data/finale-silent-emb.npz --out deck/data/finale-silent.json
```

The decision constants are not re-tuned on these sessions: they come from the Kannada dataset, so the result also
tests whether they transfer to silent mouthing by new people. Publish every number, including the bad ones.

## E10: time to be understood (PLAN.md, 8 Oct)

The number patients feel: seconds from "I need something" to "the listener knows what".

- **Needs (10):** water, pain, nurse, toilet, breathe, cold, sit up, medicine, family, thank you.
- **People:** 3 "patients" (silent; hands allowed only for writing and the board), 1 listener who does not see the card.
- **Methods (3):** Mouna (taught beforehand; teaching time is logged separately as setup), writing on paper,
  pointing at a printed picture board (the 10 needs + 6 distractors). Method order rotates per person
  (P1: M W B, P2: W B M, P3: B M W); need order is shuffled per method.
- **Timing:** start when the patient turns the card over; stop when the listener says the right need aloud.
  Wrong guesses keep the clock running. Stop at 60 s and mark it not understood.
- **Sheet:** `docs/e10-sheet.csv`, one row per trial. Then
  `python -m mouna_harness e10 docs/e10-sheet.csv --out deck/data/e10.json`.

### Optional step 3: the pre-surgery session (E12)

Set the Lab's session to `voiced` and record the protocol grid **aloud** (normal speech), reps 1–3 only; stop
after the third column. `finale-silent` then teaches on these voiced clips (and voiced + one silent example) and
tests on the silent `s1` reps 4–5. Pass: silent top-1 within 6 points of silent teaching, with the one silent top-up.
