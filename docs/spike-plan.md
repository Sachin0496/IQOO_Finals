# Mouna spike: build plan

*Archived 6 Oct 2026: the spike is finished. The Finale build plan is [PLAN.md](../PLAN.md).*

This repository is the **pre-event feasibility spike** for Mouna (iQOO Hackathon, Open Innovation track).
It answers one question before Phase 1 closes: *can a phone learn a person's silent lip movements for a
short phrase list in about 40 seconds, and recognise them reliably the next morning?*

Finale application code is **not** written here. It is written inside the event window in a new
repository, as the originality rule requires. Everything here is disclosed in the README.

## Go / no-go

| Same-session top-1 | Decision |
|---|---|
| ≥ 95 % and ≥ 90 % next morning | Go: Mouna as pitched |
| 90 – 95 % | Go with 5 repetitions per phrase and the top-3 fallback; say so in the deck |
| < 90 % | Drop Mouna; move to the backup idea |

Measured on 3 people × 8 phrases × 5 repetitions × 2 sessions (protocol in [protocol.md](protocol.md)).

## Phases

Each phase ends with a commit, a working artefact and an updated deck.

| Phase | Deliverable | Spike test | Status |
|---|---|---|---|
| 0 | Repo, plan, disclosure, deck skeleton | — | done |
| 1 | **Mouna Lab** (`lab/`): browser tool that runs on a laptop or a phone. Camera, face landmarks, aligned mouth crop, activity and quality gates, teaching flow, protocol recorder, live Plan B recogniser, telemetry, dataset export | S1, S4 capture, S7 footage | done |
| 2 | **Harness** (`harness/`): loads Lab exports, few-shot heads (Plan B geometry + DTW, prototype/linear head for encoder embeddings), the evaluation protocol, confusion matrix, false-trigger rate, a `results.json` the deck reads | S3, S4 scoring | done |
| 3 | **Deck v1** (`deck/`): 10 slides plus appendix, generated from `deck/facts.json` and the harness results so numbers are never typed by hand | Phase 1 submission | v1 done; refresh with numbers |
| 4 | **Encoder** (`encoder/`): LipLearner encoder → ONNX → LiteRT; parity check against PyTorch; latency on CPU, GPU, NPU | S2 | done: parity 1.5e-5, ONNX, CPU latency; QNN/int8 next |
| 5 | **Android probe** (`android/`): Kotlin + Compose, CameraX, MediaPipe, mouth crop, telemetry overlay, offline voices check, **no INTERNET permission**, 30-minute soak | S1, S5, S6 | done: APK builds, CAMERA-only permissions, tests pass; run on phones |
| 6 | Measured before the event: Kannada dataset (7 speakers) through both recognisers; encoder on Galaxy S26 (SM8850) via Qualcomm AI Hub, front end fully on the NPU; Lab hardened (CSP) with Vercel/Render configs | S2, S4 (proxy) | done |
| 7 | Phase 1 clip and submission ([submission.md](submission.md)): deploy, own silent recordings, 45 s clip, freeze the deck | S4, S7 | Mon 5 Oct |

## Rules for this repo

- Numbers in the deck come from `harness/` output or a cited source. Targets are labelled as targets.
- No patient data. Volunteers consent. Raw video never leaves the device that recorded it; exports hold
  landmarks and grayscale mouth crops only.
- Pure logic (geometry, gates, classifiers, metrics) is unit-tested. UI stays thin.
