# Mouna: notes for every agent working in this repo

Mouna gives a voice to people who cannot make sound. It learns how *this* person communicates (silent mouthing
first, plus a personal movement, eye looks, nods) and turns it into speech in the caregiver's language, a caregiver
alert or a room action, offline on the phone's NPU. When it isn't sure, it asks the cheapest question instead of
guessing. Team: Maadhav (vhmaadhav), Sachin (Sachin0496), Nakul (nakultt). iQOO Hackathon 2026 Grand Finale,
Bengaluru, 9–11 Oct 2026. Judged on the iQOO 15 (SM8850); dev phone OnePlus 13R (SM8650).

## Read first
- **`PLAN.md`**: the Finale build plan. Every idea from issue #1 triaged (P0 / P1 / P2 / slide / no), who owns what,
  the App ↔ Core interface, experiments, schedule, demo script, open decisions. Start here.
- `docs/roadmap.md`: what is done and measured; add a row when you finish something.
- `docs/agent-wire.md`: how our agents talk to each other through this repo's issues.
- Background, only when needed: `docs/finale-plan.md` (rubric, rules, licences, runtimes, known device bugs),
  `docs/ideas-beyond-lips.md`, `docs/question-everything.md`, `docs/error-correcting.md`,
  `docs/pivot-universal-voice.md` (the four idea rounds), `docs/spike-plan.md` (the finished pre-event spike).

## Lanes
| Lane | Owner | Agent |
|---|---|---|
| Android app: camera, landmarks, runtime, every screen, voices, IR, Pitch mode | Sachin | `sachin-claude` |
| Recognition + decision core (spec, reference code, test vectors), encoder builds, experiments, numbers, deck | Maadhav | `maadhav-claude` |
| To confirm (see PLAN.md §2) | Nakul | `nakul-claude` |

Stay in your lane's files; cross-lane changes go through a wire message on the matching thread (`android`, `npu`,
`data`, `pitch`, `finale`). The App ↔ Core contract is `PLAN.md` §5 and the vectors in `harness/vectors/`: a
behaviour change in the core ships with new vectors, and the Kotlin, TS and Python implementations must all pass them.

## Rules
- Numbers come only from measured files (`deck/data/*.json`, harness or AI Hub output) or cited sources. Label
  targets as targets. Never publish synthetic results.
- Pre-existing work is disclosed. Anything built before the Finale is described as such, with the organisers'
  permission and its date.
- No secrets in the repo, the issues or wire messages. Model weights stay out of git (`encoder/weights/`).
- No patient data, no stored raw video, no identifiable video without written consent. "Communication aid", never a
  diagnosis. Context may break ties but never overrides strong visual evidence; actions (IR, nurse call) are always
  confirmed.
- Commit small, with messages that say what changed and what was measured; push often.

## Layout
- `lab/`: browser prototype (React + TS), the reference for behaviour; `npm test` in `lab/`.
- `harness/`: Python evaluation and the core's reference implementation; parity tests with `lab/src/core`.
- `encoder/`: LipLearner encoder rebuild, ONNX export, Qualcomm AI Hub jobs.
- `android/`: Android app (Finale target: iQOO 15, SM8850; dev phone: OnePlus 13R, SM8650).
- `deck/`: generated deck; `node build.js && pwsh export_pdf.ps1`.
