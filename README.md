# Mouna · மௌனம் · ಮೌನ · मौन

**Mouth it. Mouna says it.**

Mouna lets a person who cannot make sound speak by mouthing words. It learns a person's lips for a short
phrase list in about 40 seconds and speaks the phrase aloud in the caregiver's language, offline, on the
phone's NPU.

This repository is the **pre-event feasibility spike**. It is small on purpose. Spike plan: [docs/spike-plan.md](docs/spike-plan.md); Finale build plan: [PLAN.md](PLAN.md).

## Try the prototype

**Live: https://mouna-spike.vercel.app** (Chrome or Edge; allow the camera).

Mouna Lab runs in a browser on a laptop or a phone. Nothing leaves the device.

```bash
cd lab && npm install && npm run dev        # http://localhost:5173
npm run dev:lan                             # HTTPS on your network, to open it on a phone
```

1. **Teach**: mouth each of five phrases three times (hold the button or Space).
2. **Speak**: pick the caregiver's language, mouth a phrase. It speaks when sure and asks when not.
   Not taught? **Something else? Ask me questions** finds it by yes and no (nod, two blinks, or tap).
   **Build a sentence** turns two taught blocks (Teach › Blocks) into one of 23 sentences, confirmed first.
   **Careful** speaks only when very sure (measured: untaught words spoken by mistake 23% → 7%).
3. **Protocol**: record the spike dataset (8 phrases × 5, sessions `s1` and `s2`); the score updates live.
4. **Data**: export for the harness, check offline voices, delete everything.

The Lab uses the plan B recogniser (lip geometry + DTW, no weights). The phone build adds the lip encoder on the NPU.
No camera to hand? "Play a video file" on the start screen runs the same pipeline on a recording.

Hosting: static site on Vercel or Render, with a CSP that forbids any outbound request. See [docs/deploy.md](docs/deploy.md).

## What is here

| Folder | What | Status |
|---|---|---|
| [`lab/`](lab/) | Mouna Lab: browser prototype for capture, teaching, live recognition and dataset export | done |
| [`harness/`](harness/) | Evaluation harness: few-shot heads and the protocol in [docs/protocol.md](docs/protocol.md) | done |
| [`deck/`](deck/) | Phase 1 deck ([PDF](deck/Mouna-Phase1-Deck.pdf)), generated; evidence slide fills from measured results | v1 |
| [`encoder/`](encoder/) | LipLearner encoder recovered from Core ML, rebuilt in PyTorch, parity-checked, ONNX | done |
| [`android/`](android/) | Android probe: camera → crop at 25 fps, offline voices, CAMERA-only permissions, soak log | done |

## Disclosure

Pre-existing components used in this spike:

1. The method of contrastive lip embeddings with few-shot personalisation from **LipLearner**
   (Su, Fang, Rekimoto, CHI 2023, [arXiv 2302.05907](https://arxiv.org/abs/2302.05907)), cited.
2. Open-source libraries and models, used with attribution: MediaPipe Face Landmarker (Apache-2.0),
   the LipLearner pre-trained encoder (MIT repository; weight terms to be confirmed), ONNX Runtime,
   LiteRT, the Qualcomm AI stack, Android and browser text-to-speech; phrase audio pre-rendered with Sarvam AI Bulbul v3
   and AI4Bharat Indic Parler-TTS (Apache-2.0); the Kannada multi-speaker lip-reading dataset (CC BY 4.0) for evaluation.
3. This feasibility-spike repository, made before the event and linked in the submission.

All Finale application code will be written inside the event window in a new repository.
No patient data is used. Volunteers consent. No raw video is stored or exported.
