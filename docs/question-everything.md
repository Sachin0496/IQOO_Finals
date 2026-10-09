# Round 2: question everything

*Draft, 6 Oct 2026. Every assumption we've made so far, challenged, with what the evidence says and what changes.*

## The one-line reframe

> Mouna is not a lip reader. It is **a way for a person to be understood with whatever their body still allows**,
> in any language, on one phone. Lips are the fastest door, not the only one.

---

## Part A: twelve assumptions, questioned

### 1. "Our user is anyone who has lost their voice"
**Challenge.** A camera on the lips only works if the mouth is free and moves well.
- **Fits:** after laryngectomy and tracheostomy, the mouth is free, and laryngectomees are taught to *exaggerate*
  lip and tongue movement to be understood, which helps us.
- **Doesn't fit:** an oral breathing tube (intubated) covers the mouth. After a stroke, the mouth droops and slurs.

**What changes.** Two modes, one app:
- **Mouth mode** (laryngectomy, tracheostomy): lip reading.
- **Any-movement mode** (stroke, weakness): a personal switch, picture choices, eye looks, from the "beyond the lips"
  comment.

The pitch names the people we serve and the people we don't. Judges respect a clear scope.

### 2. "It's an app on the patient's phone"
**Challenge.** Who sets it up at 3 a.m.? Whose phone is it? Many patients are elderly, many don't own a smartphone,
and the stay lasts days.

**What changes.** Mouna is a **ward kit**: a phone or tablet on a bed stand, set up by the nurse in 25 seconds per
patient, wiped at discharge. Few-shot teaching makes this possible: **no account, no cloud, no training run**. For
home, it rides along like other rented equipment (see 9).

### 3. "Speaking aloud is the output"
**Challenge.** What does the patient actually need? The *nurse to come*, the *fan off*, the *request remembered*.

**What changes.** Outputs, not one output:
- **voice**, in the caregiver's language;
- **nurse phone** (link, done);
- **the real nurse-call button:** a Bluetooth adapter into the bed's call socket already exists commercially
  (BT RemoteSwitch), and an ESP32 relay could do it for a few hundred rupees;
- **the room**, through the IR blaster (fan, TV, AC);
- **a timestamped log** for the nursing handover: "pain, 02:13; nurse came 02:19". That is a ward-quality signal
  hospitals care about.

### 4. "Accuracy is the metric"
**Challenge.** Patients don't feel accuracy. They feel **time to be understood** and **whether they were
understood at all**. In the ICU, a letter board succeeded 80% of the time against 100% for eye tracking, with half
the messages per exchange.

**What changes.** New experiment (E10): **seconds from "I need something" to "the nurse knows"**, Mouna vs writing
vs pointing at a board, for 10 needs and 3 people, measured by us. It's an honest number judges and doctors both
understand. Expected order: a few seconds vs tens of seconds.

### 5. "The camera is the only sensor"
**Challenge.** The camera can't see the **tongue**, fails **in the dark**, and records faces.

**Evidence.** The phone's own speakers and microphones can work as **sonar**:
- **EchoWhisper** (2020): 8.33% word error on 45 words, robust to orientation and noise;
- **EchoLip** (2025): 13.9% / 19.7% WER at 15 / 40 cm;
- **SilentTalk**: 12 mouth motions at 95.4%.

Sonar sees mouth and tongue, needs no light, and stores no image.

**What changes.** A *Mouna v2* research direction: **camera + sonar fusion on one phone**. For the Finale, at most a
one-day spike: a Doppler "mouth is moving" detector as the night-time wake trigger. It's a slide, not the core.

### 6. "Silent means no sound"
**Challenge.** Many people without a larynx still make faint mouth sounds: clicks, pops, "pseudo-whisper". Many
also use an **electrolarynx**, whose robotic buzz is hard to understand.

**Evidence.** Using lip shape to shape the electrolarynx's sound raised word intelligibility by **18%** in an
experimental system. Audio-visual conversion of electrolarynx speech improves quality.

**What changes.** A future mode for electrolarynx users: **lips + electrolarynx audio → clearer speech**. Lip
reading as a *helper* for the 24% who already use an electrolarynx, not only a replacement.

### 7. "The model learns the person once"
**Challenge.** After surgery, faces swell, then heal; sedation and fatigue change mouthing day to day.

**What changes.**
- **Drift watch:** the learning meter already counts misses. When one phrase keeps failing, Mouna asks to re-teach
  *that* phrase only, with three new samples.
- **Morning check:** one quick confirmation round per day.
- Corrections already become samples. This turns that into a habit.

### 8. "The patient teaches Mouna after surgery"
**Challenge.** Laryngectomy is **scheduled**. The patient can still speak **before** surgery. The 2026 SRAVI study's
authors ask for exactly this: pre-operative enrolment.

**What changes.** A **"Before surgery" session**, 10 minutes:
- the patient says each phrase **aloud**; on-device speech recognition labels it automatically (LipLearner did
  voice-labelled teaching);
- the camera keeps the lip samples;
- the microphone banks the voice (and seeds an own-voice pack).

After surgery Mouna already knows their lips and speaks in their voice from minute one. Honest caveat: LipLearner
saw about 5.6 points lost from voiced to silent samples, so a short silent top-up after surgery is still wise.

### 9. "The listener is a nurse in a hospital"
**Challenge.** In India, family members often stay at the bedside, and care continues at home.

**Evidence.** AIIMS's AIR programme (PLOS One 2026) sent **300 tracheostomised patients** home with family-led care,
an equipment rental bank and a mobile communication platform. Families preferred WhatsApp.

**What changes.**
- The caregiver link works for **family** too.
- Mouna goes home **in the equipment rental bank** with the same phone.
- Discharge doesn't end the voice.

### 10. "Mouthing is in Kannada, Tamil, Hindi or English"
**Challenge.** Why four? Our recogniser **never transcribes**. It matches the person's own mouth movements to
*their own* examples.

**What changes.** **Mouna works for any language the person can mouth, including ones with no script or no data**:
Tulu, Konkani, Kodava, a village dialect, code-mixed "Kanglish". The *input* language is irrelevant to the
few-shot method. Only the *output* labels need a language, and pictures can stand in.

Evidence we already have: an encoder trained on English (LRW) reaches 89% on Kannada after 5 samples, with no
Kannada training. This is the single most under-sold fact in our deck.

### 11. "Phrases are text"
**Challenge.** Low literacy, aphasia, tiredness.

**What changes.** **Teach to a picture, not to a word.** The patient mouths whatever they naturally say for the water
picture; the caregiver hears it in *their* language. Pictures come from the family's photos or open symbol sets.

### 12. "We demo with a team member"
**Challenge.** The strongest evidence in the room would be a real laryngectomee or clinician.

**Evidence.**
- **Kidwai Memorial Institute of Oncology (Bengaluru)**, Karnataka's regional cancer centre, in the Finale city,
  has a **Laryngectomee Club** and long experience with voice rehabilitation.
- The **Laryngectomee Club of India** has about 1,400 members nationwide.
- Clubs meet pre-operative patients regularly.

**What changes.** This week: contact Kidwai's ENT / speech therapy department and the club. Ask for
**10 minutes of feedback** from a laryngectomee and a speech-language pathologist, by video call if needed, with
consent. A recorded quote ("I would have used this the week after my surgery") beats any feature. We must never put
a patient on stage or record identifiable video without written consent.

---

## Part B: what voicelessness feels like (for the pitch)

- Of voiceless ICU patients, **49%** face *extreme* communication difficulty, 23% moderate, 28% some.
- **90.7% felt trapped**, 64% frustrated, 56% not understood; 87% said communication was inadequate.
- Being voiceless ranks with **thirst, breathlessness and pain** among the most distressing ICU experiences.
- India: about 95,000 ICU beds; tracheostomy in 10–20% of the critically ill.

Pitch line: *"Nine in ten voiceless patients feel trapped. Mouna gives them a way out in 25 seconds, in their own
language, with no internet."*

---

## Part C: new ideas that came out of this

| Idea | Why it matters | Effort | Finale? |
|---|---|---|---|
| **Pre-surgery session** (voice-labelled lip teaching + voice bank) | Directly answers the SRAVI authors; day-one readiness | Medium (on-device ASR for labels, or tap to label) | **Yes**, tap-to-label version |
| **Any language, any dialect** message | Truth already in our method; huge for India | None (pitch + one demo in Tulu or Konkani by a friend) | **Yes** |
| **Teach to a picture** | Low literacy and aphasia | Low | **Yes** |
| **Time-to-understood experiment (E10)** | The metric patients feel | Low | **Yes** |
| **Ward log for handover** | Hospital value, response-time quality signal | Low | **Yes** |
| **Real nurse-call button** (BT adapter / ESP32 relay) | Plugs into existing hospital infrastructure | Medium (hardware) | Maybe: show the existing adapter as the path |
| **IR room control** | Dignity; creative phone use | Low | **Yes** |
| **Drift watch + targeted re-teach** | Faces change after surgery | Low | **Yes** |
| **Family link + home with rental bank** | India's bedside reality; AIIMS model | Low (link exists) | Pitch |
| **Camera + sonar fusion** | Tongue, dark, privacy | High (research) | Slide only |
| **Lips + electrolarynx audio** | Helps electrolarynx users (24% in one survey) | High | Slide only |
| **Articulation coach** (how consistently you mouth) | Rehab value for oesophageal / TEP speech, whose clarity depends on articulation | Medium; needs an SLP's view | Ask Kidwai's SLP |

## Part D: new experiments

| # | Experiment | Metric | Pass |
|---|---|---|---|
| E10 | Time to be understood: Mouna vs writing vs board, 10 needs × 3 people | seconds, success rate | Mouna faster in ≥ 8 of 10 |
| E11 | Any-language claim: one person teaches in a language we've never used (Tulu / Konkani / Malayalam) | top-1 on 5 phrases, 3 shots | Same range as our Kannada numbers |
| E12 | Pre-surgery effect: teach voiced, test silent (and voiced + 1 silent top-up) | top-1 silent | Gap ≤ 6 points with top-up |
| E13 | Drift: same person, morning vs evening vs next day | top-1 by session | Re-teach recovers ≥ 90% |

## Sources
- Pseudo-whispered speech after laryngectomy: https://www.tandfonline.com/doi/full/10.1080/02699206.2022.2092425
- Laryngectomee articulation advice: https://www.medbridge.com/blog/communication-strategies-for-laryngectomy-patients
- Patient perspectives after laryngectomy (IAL survey, 2025): https://pubmed.ncbi.nlm.nih.gov/39564981/
- SRAVI after laryngectomy (2026): https://pmc.ncbi.nlm.nih.gov/articles/PMC12860178/
- Board vs eye-tracking in ventilated patients: https://pubmed.ncbi.nlm.nih.gov/37871352
- Communication difficulty in voiceless ICU patients (2025): https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11969292/
- "Trapped" 90.7% and related figures: https://www.researchgate.net/publication/346783527_A_systematic_review_on_voiceless_patients'_willingness_to_adopt_high-technology_augmentative_and_alternative_communication_in_intensive_care_units
- EchoWhisper: https://dl.acm.org/doi/10.1145/3411830
- EchoLip (IEEE IoT J 2025): https://ieeexplore.ieee.org/iel8/6488907/11261327/11146579.pdf
- SilentTalk: https://www.researchgate.net/publication/320253778_SilentTalk_Lip_reading_through_ultrasonic_sensing_on_mobile_phones
- Sensing technologies for silent speech (Nature 2025): https://www.nature.com/articles/s44460-025-00010-2
- Lip-shape-controlled electrolarynx (+18%): https://www.sciencedirect.com/science/article/abs/pii/S0167639313001271
- Audio-visual electrolaryngeal voice conversion: https://arxiv.org/pdf/2306.06652
- BT RemoteSwitch nurse call adapter: https://www.prnewswire.com/news-releases/accessibility-services-inc-launches-bt-remoteswitch-nurse-call-adapter-302857515.html
- AIIMS AIR programme, tracheostomy home care (PLOS One 2026): https://pmc.ncbi.nlm.nih.gov/articles/PMC13221049/
- India ICU beds: https://www.medrxiv.org/content/10.1101/2020.06.16.20132787.full.pdf
- Kidwai Memorial Institute of Oncology: https://en.wikipedia.org/wiki/Kidwai_Memorial_Institute_of_Oncology ; TEP experience: https://pubmed.ncbi.nlm.nih.gov/11261233/ ; functional laryngeal surgery and the Laryngectomee Club: https://pmc.ncbi.nlm.nih.gov/articles/PMC3451364/
- Laryngectomee Club of India: https://www.laryngectomeeclubindia.org/about_us.html
- LipLearner (voice-labelled teaching, voiced→silent gap): https://arxiv.org/abs/2302.05907
