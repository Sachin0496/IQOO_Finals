# Pivot options: make Mouna the **voice layer for any expression**

*By Sachin (Sachin0496), issue #1, 6 Oct 2026. Triaged into [PLAN.md](../PLAN.md).*

I think the strongest pivot is to stop framing Mouna as “a lip-reading app” or “a sign-language recognizer.”

Those already exist in research and open source. The product story should be:

> **Mouna is an easy-to-use voice for people who cannot speak. It listens to whatever they *can* still express — ISL, personal gestures, lips, gaze, face and head movement — and turns that into speech.**

The app experience should be the star. Local AI/NPU is supporting tech, not the headline.

---

## Important distinction: ISL is not just “gesture mode”

Indian Sign Language should be a **first-class language mode**.

ISL uses:
- hand shape and motion;
- two-handed structure;
- signing location;
- facial expression;
- head/body movement;
- mouthing/non-manual markers.

So the app can have two different concepts:

1. **ISL mode** → recognizes actual Indian Sign Language.
2. **Personal gesture mode** → “my eyebrow raise means yes”, “this finger motion means water”, etc.

We should not claim to have invented sign recognition. Existing work includes AI4Bharat/OpenHands, INCLUDE/ISL research, BSL-1K using mouthing cues, MediaPipe-based sign recognition, continuous sign-language translation, etc.

---

# Strong pivot directions

## 1. **Mouna Universal Voice** — strongest product direction

Inputs:
- ISL;
- personal signs/gestures;
- lip movements;
- gaze;
- facial movements;
- nod/shake/head motion;
- faint mouth sounds/clicks.

Output:
- natural spoken sentence;
- text/pictures;
- caregiver phone;
- optional actions.

The system decides which modality is currently reliable.

If the hands are hidden → trust lips/gaze more.  
If the lips are weak → trust hands/face more.  
If only one eyebrow moves → let that become a switch.

### Pitch

> **“You don’t learn how to talk to Mouna. Mouna learns how you communicate.”**

This is much stronger than “our lip model gets X% accuracy.”

---

## 2. **EchoMouna: the phone hears silent speech**

This is the most “how is the PHONE doing that?” research direction.

Research such as **EchoLip / EchoWhisper / SilentTalk** explores using ordinary speakers + microphones as active acoustic sensing:

- phone emits high-frequency / inaudible sound;
- microphone records reflections;
- mouth/tongue/jaw motion changes the reflected signal;
- model classifies silent articulation.

No external sensor.

For the Finale, we do **not** need open-vocabulary silent speech. A realistic spike:

- 5–10 commands;
- one person;
- phone 15–30 cm away;
- compare acoustic-only vs camera-only vs fusion.

### Research question

> **Does camera + acoustic sensing recognize personalized silent commands better than either modality alone?**

If it works even modestly, this is a very strong technical demo because it uses the phone itself in an unusual way.

Reference:
- EchoLip: https://doi.org/10.1109/JIOT.2025.3603582
- EchoWhisper: https://doi.org/10.1145/3411830

---

## 3. **Personal Communication Language**

Instead of forcing the user to know ISL, let them teach Mouna *their own vocabulary*.

Examples:

- two fingers + mouth “wa…” = water;
- eyebrow + look right = yes;
- half-smile = call daughter;
- one custom sign = pain;
- real ISL sign where they already know it.

Teach each with 2–5 examples.

Then Mouna learns the combination of:
- hand landmarks;
- face landmarks;
- mouth motion;
- gaze;
- timing.

This is much more defensible than claiming universal sign-language translation.

### Research question

> **Can few-shot multimodal personalization outperform hands-only, lips-only and face-only recognition for an individual user?**

That gives a clean experiment:

**hands only vs lips only vs face only vs fused personalized model.**

---

## 4. **Eye Voice**

For somebody with extremely limited movement:

- look left/right/up;
- blink;
- dwell;
- eyebrow;
- tiny head movement.

Mouna predicts likely intents and reduces the communication to one or two selections.

Google’s Look to Speak proves phone-camera gaze communication is already practical; our twist is that gaze becomes one input inside the same Mouna voice system rather than a separate app.

Reference:
https://experiments.withgoogle.com/looktospeak

---

## 5. **Intent → action, not only speech**

A request should sometimes *do something*.

After confirmation:

- notify caregiver;
- activate nurse-call integration if available;
- control compatible room devices;
- send “I need water” to caregiver;
- speak in another language;
- log the request.

So Mouna is not just an AAC keyboard.

> **Expression → understood intent → voice/action.**

This is much more emotionally powerful in a demo.

---

# “Dreamy” roadmap without making fake claims

The same Mouna interface could eventually accept:

- camera;
- acoustic silent-speech sensing;
- EMG;
- EEG/BCI;
- implanted neural speech devices.

But **the iQOO itself cannot measure EEG**. There are no scalp electrodes in a phone. Cloud compute cannot recover a signal the phone never sensed.

So for this Finale:

### Build
**hands + ISL + personal gesture + lips + gaze + face + acoustic experiment**

### Research/roadmap slide
**EMG / EEG / neural speech → same Mouna interface later**

That gives the futuristic story without pretending the phone reads thoughts.

---

# What I would actually build

### Product layer — must look spectacular

One camera screen. Almost no menus.

The person simply communicates.

Mouna visually shows:

**I see your hands 👋 · lips 👄 · eyes 👀**

Then:

**“I think you mean: I need water.”**

If confidence is low:
two giant choices → gaze/gesture confirms.

Then beautiful speech/output.

The judge should remember the interaction, not the model architecture.

---

# Suggested Finale stack

### Core
1. MediaPipe face + hands + pose.
2. ISL recognizer for a small useful vocabulary.
3. Few-shot personal gesture learner.
4. Existing lip recognizer.
5. Gaze / blink / nod confirmation.
6. Multimodal decision/fusion layer.
7. Natural voice output.

### Experimental wow
8. Acoustic silent-speech spike using speaker + microphone.

### Optional
9. Cloud model for semantic/context reasoning **only if rules permit**, with deterministic/on-device fallback.

---

# Claims we should NOT make

- ❌ “We invented sign-language recognition.”
- ❌ “We translate all ISL.”
- ❌ “We can read thoughts using the phone.”
- ❌ “Lip reading is clinically 95% accurate.”
- ❌ “One model works for every disability.”

Instead:

> **“We built one beautiful communication interface that adapts to whatever reliable signal a person can produce.”**

That is the pivot I think has the strongest mix of **heart + usefulness + spectacular demo + technical depth**.
