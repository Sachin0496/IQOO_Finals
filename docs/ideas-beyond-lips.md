# Beyond the lips: ideas for people who can't mouth clearly or touch

*Draft, 6 Oct 2026. Thinking outside the box, starting from people rather than models.*

## Start from the person

Picture a 62-year-old man three days after a stroke. The right side of his face droops, so his mouthing is lopsided
and slurred. His right hand doesn't work and his left is taped to a drip. He tires after a few minutes. He reads
Kannada slowly and English not at all. His daughter can tell what he wants half the time, from a look, the time of
day, a frown. The nurse, who speaks Hindi, can't.

What he *does* still have:
- **his eyes:** he can look left or right, and blink;
- **a few movements:** one eyebrow, a half smile, a puff of the cheek, a nod;
- **his face:** a frown or a wince tells you a lot;
- **context:** it's been four hours since he drank anything, it's 2 a.m., he winced when they turned him;
- **recognition:** he knows *his* water cup, *his* daughter's face, faster than any word.

His family works by **guessing, then checking**. That's the design principle:

> **Mouna guesses from weak signals; the person chooses with whatever still moves.**

Lip reading becomes one signal among several, not the only door.

---

## The ideas

### 1. Whatever still moves: a personal switch
Let the person teach Mouna **any** movement they can repeat, using the same few-shot method we use for phrases:
eyebrow raise, a look up, cheek puff, lip pucker, jaw drop, a half smile on the good side.
- MediaPipe already gives **52 blendshapes** (eyebrow raise, jaw open, cheek puff, lip pucker…) from the face we
  track. Google's open-source **Project Gameface** and the **Eyebrow Clicker** do the same for cursor control.
- After a stroke, the strong side still works: Mouna learns the *one-sided* smile, not a symmetric one.
- This turns nod and blink from fixed gestures into **"your yes"**, chosen by the person.
- **Finale: yes.** The data is already in the frame loop; the few-shot head and DTW exist in the Lab.

### 2. Look to choose: pictures picked by the eyes
Two to four large pictures; the person looks at one and confirms with their switch (or holds the look).
- Google's **Look to Speak** proves eye-look selection works on a phone (left / right / up, 16 phrases halved per
  look). It speaks Hindi, Tamil, Marathi and Telugu, but not Kannada. It has no pictures ranked by context, no lip
  reading and no caregiver link.
- MediaPipe gives **iris landmarks**; left/right (and maybe up/down) is reliable even when fine gaze isn't.
  Research smartphone gaze reaches about 1° in the lab, but we only need **two to four big zones**.
- **Finale: yes**, left/right at least.

### 3. "Mouna guesses, you choose": fusing weak signals
When the lips are unclear, don't say "not sure". **Combine** what we have and show the four likeliest needs as
pictures:
- the top guesses from the lips, even weak ones (on Kannada, the right answer is in the top three 94–97% of the
  time even when top-1 is wrong);
- **time since** last water, toilet, medicine, turn (logged automatically from earlier answers);
- **time of day** (night: pain, toilet, blanket, sleep);
- the **face** (wince, see idea 5);
- what this person usually asks next (our context prior).

A 40% guess becomes a pick from four with a much better chance of holding the answer. Measurable on the dataset as
top-4 accuracy with and without context.
- **Finale: yes.** It's a re-ranker plus a picture grid.

### 4. Pictures, not words, and *their* pictures
For aphasia, low literacy or tiredness, pictures beat text. Research on **visual scene displays** found photos are
quicker to recognise and less tiring than symbol grids for people with aphasia.
- **Day-one kit:** the family photographs *his* cup, *his* daughter, *his* blanket, the fan by the bed. Mouna shows
  those, not clip-art.
- Fall back on open symbol sets: **Mulberry / Global Symbols** (open licence). Avoid **ARASAAC** for a product
  (CC BY-NC-SA, no commercial use).
- **Finale: yes**, with a small photo set we take ourselves (no real patient).

### 5. The face speaks first: a gentle pain check
A sustained wince (brow lowered, eyes squeezed, nose wrinkled: the facial action units behind pain scales) triggers
a **gentle question to the patient**, "Are you in pain?", answered with their switch. It is not an alarm and not a
diagnosis.
- ICU research detects pain from facial action units at about 0.85 accuracy in controlled data. Real wards (masks,
  lighting, angle) degrade it, and it isn't clinically validated. So it **asks, it never decides**.
- MediaPipe blendshapes map onto these action units.
- **Finale: maybe**, as an opt-in prompt only.

### 6. Act, don't only speak: control the room
The **iQOO 15 has an IR blaster** (so does the OnePlus 13R). "Fan off", "TV off", "AC warmer": Mouna can *do* it,
through Android's `ConsumerIrManager`, after confirming.
- Dignity: the patient changes their own room without waiting for anyone.
- This is "creative phone use" (15% of the score) that no one else will think of.
- **Finale: yes**, with an IR fan or TV on stage, or an IR receiver LED that lights up.

### 7. A sound without a voice: tongue or teeth clicks
Many people who can't voice can still **click the tongue** or **tap their teeth**. The microphone detects the click
(a sharp transient, easy to separate from speech) as a switch. Research shows mouth "pop" and "click" sounds work as
reliable inputs for people with speech disorders.
- It works in the dark, and when the face is half covered by an oxygen mask.
- **Finale: maybe**, as a second switch.

### 8. Two-way: the nurse just talks
The nurse speaks normally in Hindi; the phone shows **one big picture and the question in the patient's language**
("Water?" with a glass), and the patient answers with their switch. It removes the language barrier in both
directions. On-device speech recognition (IndicConformer or Gemma 4's audio input) does the listening.
- **Finale: stretch.**

### 9. Fatigue-aware
Stroke fatigue is real. Mouna notices slower answers and missed switches, drops from four choices to two, offers
"rest", and logs the pattern for the family.
- **Finale: small and cheap; yes.**

### 10. Half a face is enough (experiment)
With facial droop, mirror the **strong half of the mouth** to make a symmetric crop for the encoder. We can test it
on the Kannada data by masking one half and mirroring the other. If it holds up, it's a real result; if not, we drop
it.
- **Finale: only if the experiment passes.**

### 11. Comfort at night
When fear or loneliness is chosen (it's the commonest need in ventilated-patient studies, "to be loved and to
belong"), Mouna can play a short message the family recorded on day one. It's small, human, and very memorable.
- **Finale: yes, tiny.**

### 12. Things we will not do
- **Generating images on the phone** to show a vague idea: slow, and it invents. Real photos and symbols are
  clearer.
- **Automatic alarms from the face alone:** false alarms would make nurses ignore Mouna.
- **Any claim of diagnosis.**

---

## A demo built on this

1. "Ravi" (a team member) had a stroke: one side weak, mouthing unclear.
2. He mouths something; the lips give a weak guess. Instead of "not sure", Mouna shows **four photos**: his cup,
   the fan, his daughter, the toilet. Water is first, because it's been four hours.
3. He **looks left** at the cup and **raises his good eyebrow**, his own switch. The phone says, in Kannada,
   *"ನನಗೆ ನೀರು ಬೇಕು"*.
4. He looks at the fan photo: the iQOO's **IR blaster turns the fan off**. The judges hear it stop.
5. The nurse's phone, linked over Wi-Fi, shows what he asked.

The whole flow uses no words, no hands and no clear mouthing, and it is all offline on the phone.

---

## Priorities for the Finale
| Rank | Idea | Effort | Why |
|---|---|---|---|
| 1 | Personal switch (1) | low | Unlocks everything for stroke users |
| 2 | Guess-then-choose picture grid (3, 4) | medium | Turns weak lip reading into a high-accuracy pick |
| 3 | IR room control (6) | low | Unforgettable demo moment; creative phone use |
| 4 | Eye look left/right (2) | medium | Hands-free selection |
| 5 | Fatigue-aware, comfort message (9, 11) | low | Human touches judges remember |
| 6 | Pain prompt, clicks, two-way (5, 7, 8) | medium–high | Only if time allows |
| 7 | Half-face experiment (10) | low | Research result if it passes |

## Sources
- Look to Speak (Google): https://experiments.withgoogle.com/looktospeak ; languages: https://www.androidpolice.com/googles-incredible-look-to-speak-app-now-works-with-more-languages/
- Project Gameface on Android: https://developers.googleblog.com/en/project-gameface-launches-on-android/
- MediaPipe Face Landmarker (blendshapes, iris): https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker
- Blendshapes mapped to action units: https://www.sciencedirect.com/science/article/pii/S2451958826001995
- Smartphone eye tracking accuracy: https://www.nature.com/articles/s41467-020-18360-5 ; https://www.nature.com/articles/s44184-026-00211-8
- Pain from facial action units in the ICU: https://pmc.ncbi.nlm.nih.gov/articles/PMC13522443/ ; https://arxiv.org/pdf/2005.02121
- Visual scene displays for aphasia: https://rerc-aac.psu.edu/2015/10/08/visual-scene-displays-as-communication-support-options-for-people-with-chronic-severe-aphasia/ ; https://pubs.asha.org/doi/abs/10.1044/2017_AJSLP-16-0190
- ARASAAC licence (CC BY-NC-SA): https://openassistive.org/item/arasaacpictograms/
- Nonverbal mouth sounds as input: https://arxiv.org/pdf/2202.07750
- iQOO 15 IR blaster: https://www.gizmochina.com/product/iqoo-15/
- Ventilated patients' needs: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5070186/
