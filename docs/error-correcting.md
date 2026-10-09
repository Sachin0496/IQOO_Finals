# Round 3: make Mouna an **error-correcting communication system**

*By Sachin (Sachin0496), issue #1, 6 Oct 2026. Triaged into [PLAN.md](../PLAN.md).*

I read the main plan, both comments above, `PLAN.md`, `docs/protocol.md`, and the current harness—especially `heads.py` and `openset.py`.

The first two rounds are strong on **who the user is**, **alternative signals**, **context**, **ward workflow**, and **phone features**. I think the biggest remaining opportunity is *not another sensor or another large model*. It is to redesign the recognition + teaching + clarification loop around one fact:

> **Lip reading is an intrinsically ambiguous channel. Instead of trying to make the model infallible, make every layer of Mouna correct the layer before it.**

That could be a stronger technical story than “we added a better lip reader”:
1. choose visually separable intent codes;
2. spend teaching effort only where the person is ambiguous;
3. return a calibrated *set* when uncertain instead of a brittle top-1;
4. explicitly model “none of these”;
5. ask the cheapest possible clarification question;
6. apply stricter policies to higher-consequence actions.

This is implementable with the architecture already here.

---

## 1. **Mouna Codebook**: optimise the *phrases*, not only the classifier

Right now the starter pack treats phrases as fixed labels. But visual speech has a physics problem: different words can look effectively identical on the lips (“homophenes” / viseme twins). A 2026 study of the “visome” found that words with more visual twins and visual neighbours are lip-read less accurately; AAAI 2026 work still explicitly targets viseme ambiguity.

So let Mouna change the problem.

For each *intent* (“water”, “nurse”, “toilet”, “pain”), the spoken output can stay natural, but the patient is allowed to teach the **mouth form that is easiest and most separable for them**:

- their normal word;
- a longer form (“water please” rather than “water”);
- a synonym or dialect phrase;
- an exaggerated mouth form they naturally prefer;
- in Any-Movement mode, even a short custom mouth gesture.

During teaching, Mouna builds the prototypes, then immediately computes a **pairwise confusion/separation graph**. If two intents overlap badly, it says:

> “Water and toilet look too similar for me. Add one more example, or teach a different mouth phrase for one of them.”

This is a very different product claim:

> **We do not force the user into a vocabulary the camera cannot distinguish. Mouna co-designs a safe visual vocabulary with that person.**

This also makes “any language / any dialect” stronger: the *semantic label* and the *visual code* are decoupled.

### Why this is especially suited to this repo

The data is already there. With `PrototypeHead`, every phrase has a prototype and every test has distances to all prototypes. No new encoder is required. A first version is just:

- within-class radius / variance;
- nearest competing prototype distance;
- leave-one-out margin;
- confusion matrix;
- a red/yellow/green “visual separability” score per phrase pair.

The lab could literally show a graph: **water ↔ toilet = dangerous pair**.

### Experiment E14 — visual-codebook optimisation

Compare:

A. fixed starter phrases  
B. same intents, but allow the participant to change/rephrase the worst 1–2 visually-confusable phrases after seeing the separation map.

Measure:
- top-1;
- spoke precision;
- worst pair confusion rate;
- time to teach.

**Pass:** reduce errors in the two worst confusion pairs by ≥50% without adding >20 s setup, or gain ≥5 top-1 points.

This may be more valuable than squeezing another 2 points out of the encoder.

---

## 2. **Active teaching**: stop asking for 3 samples of everything

The current flow is fixed-shot: teach every phrase three times. That wastes the person’s energy on phrases Mouna already understands and gives too little attention to a genuinely difficult pair.

Instead:

1. record one sample of every phrase;
2. estimate prototype separation;
3. ask for the *next* sample only from the phrase with the weakest margin / highest variance;
4. recompute;
5. stop when all phrases meet a safety criterion, or hit a maximum (say 5).

Example UI:

> 5 intents learned.  
> Water ✅  
> Pain ✅  
> Nurse ✅  
> Toilet ⚠ one more example  
> Breathe ⚠ one more example

This is **active few-shot learning as an onboarding flow**, not as a research paper hidden in the model.

It also fits stroke / fatigue better: a person should not perform 15 mouthings if 8 are enough.

Recent few-shot work continues to show value in selecting high-information examples rather than treating every support example equally. The exact model does not have to be complicated; your margin/variance heuristic is probably enough for the Finale.

### Experiment E15 — time to a safe phrase pack

Primary metric: **seconds until the pack reaches the target operating point**, not “accuracy after exactly 3 shots.”

Compare fixed 3-shot vs adaptive 1–5-shot.

Pass if adaptive teaching:
- reaches the same or better next-session accuracy;
- reaches the same or lower untaught-word false-speak rate;
- in ≥25% less teaching time.

This gives you a killer pitch metric: **“Mouna learns only what it still needs to learn.”**

---

## 3. Replace the single threshold with **set-valued uncertainty**

I looked at `heads.py`. Today the decision is:

- one global calibrated distance threshold;
- `MIN_MARGIN = 1.15`;
- accept / unsure / reject.

That is clean, but still heuristic. The model already produces a *ranking*, and ranking is exactly where modern conformal classification is useful. A 2026 Pattern Recognition paper specifically proposes **rank-based conformal prediction sets** for classifiers whose scores are not well-calibrated probabilities.

The UI mapping is almost perfect for Mouna:

- prediction set size **1** → speak;
- size **2–3** → show those choices / pictures;
- large set → Ask mode;
- empty / none plausible → “not taught.”

That is much more interpretable than “distance 0.31 is below 0.35.”

Important caveat: with only three examples per class, do **not** claim formal 95% coverage from per-user calibration unless you actually have a statistically valid calibration design. For the Finale, this can be described as **conformal-style / set-valued calibration**, then evaluated honestly. A proper version could meta-calibrate nonconformity scores from pooled volunteer sessions and validate on held-out people/sessions.

### Experiment E16 — prediction-set calibration

Report:
- target coverage (e.g. 90%);
- empirical next-session coverage;
- average prediction-set size;
- % singleton;
- % set of 2–3;
- untaught-word false-speak rate.

A good result would be:
- most known phrases give singleton sets;
- difficult clips naturally produce 2–3 choices;
- unknowns rarely produce a confident singleton.

That would turn uncertainty into part of the product rather than a hidden threshold.

---

## 4. Give “unknown” its own representation: **hard-negative rehearsal**

`openset.py` currently studies the right failure: an untaught word accidentally being spoken as a taught phrase. But the classifier itself only knows positive classes and then tries to reject them by distance / strictness.

There is a cheap extra step: explicitly collect a tiny **“none of my phrases” rehearsal set** during setup.

Ask the person for, say:
- 3–5 random mouthings that are *not* in the phrase pack;
- normal idle movements (already in the protocol);
- optionally the hardest near-boundary unknown examples found during testing.

Store only embeddings/features, not video.

Then compare a test clip to:
- known phrase prototypes;
- a compact background / negative representation;
- or use the negatives only to calibrate a rejection boundary.

Few-shot open-set recognition research increasingly uses negative prototypes / hard-unknown examples because the failure mode is exactly what Mouna has: a nearest positive class always exists even when the input belongs to none of them.

I would *not* add a complex GAN or train a new network for the hackathon. Start with real negative examples and a simple score:
- best-known distance;
- best-negative distance;
- best/second-known margin.

### Experiment E17 — negative rehearsal vs Careful mode

At matched **known-phrase spoke rate**, compare:

1. current global threshold;
2. Careful strictness;
3. current threshold + negative rehearsal.

Target: halve untaught-word false speaks versus the normal baseline while preserving more known-word coverage than simply lowering the threshold.

If this works, “Careful” becomes much smarter than “be more conservative everywhere.”

---

## 5. Turn Ask mode into **personalised Huffman questioning**

The fixed ICU tree is safe and understandable. Keep that as the baseline.

But once Mouna has a probability/prior over likely needs—from:
- time of day;
- recency;
- this person’s own history;
- current top lip candidates;

you can generate a binary decision tree that minimises expected yes/no answers. This is the same information-theoretic principle behind **Huffman scanning** in AAC: more likely targets get shorter binary codes.

This does **not** need an LLM.

Example:

At 2 a.m., for this person:
- pain 0.35
- toilet 0.30
- water 0.20
- reposition 0.10
- other 0.05

The first question should split probability mass roughly in half, not blindly walk a generic tree.

Because AAC interfaces benefit from stable motor patterns, I would **not reshuffle the entire interface constantly**. A safer design:

- keep 3–4 stable top-level categories;
- within a category, order or branch adaptively;
- or expose an “adaptive fast path” while preserving the fixed tree.

Huffman scanning has been tested in AAC and can reduce selection effort, but the literature also notes cognitive/motor trade-offs—so measure it rather than assuming it helps.

### Experiment E18 — adaptive Ask

For a scripted set of 20 needs sampled from realistic priors:

- fixed tree: average yes/no actions;
- semi-stable Huffman tree: average yes/no actions;
- error rate / wrong exits;
- subjective effort.

**Pass:** ≥25% fewer switch actions with no increase in wrong selections.

This could make the existing Ask mode feel genuinely intelligent without using generative AI.

---

## 6. **One-bit rescue** for known confusion pairs

The app already knows the runner-up and margin. Use that more aggressively.

After a few sessions, Mouna can build a personal **confusion graph**:

- water ↔ toilet: often confused;
- pain ↔ nurse: sometimes;
- thank-you: almost never.

When confidence collapses specifically between a known pair, do not send the user through the whole picture grid or Ask tree. Ask the one question that resolves *that pair*.

Examples:
- show just two large pictures;
- left/right gaze;
- “Pain?” yes/no;
- “Person or thing?” if the pair spans categories;
- the person’s custom switch.

This is essentially **error correction at the edge of the classifier**.

### Experiment E19 — pairwise rescue

Take every error where truth is rank-2.

Compare:
- top-4 grid;
- full Ask mode;
- pairwise one-bit rescue.

Measure:
- time to correct;
- number of actions;
- final communication success.

If rank-2 is already strong (the issue says top-3 is very high), this may recover a large fraction of mistakes with one extra bit.

---

## 7. Context should be a **bounded prior**, never the boss

The earlier comment’s contextual re-ranking idea is good. I would add one important rule:

> **Context may break ties; it may not overturn strong visual evidence.**

Implement the prior with a hard influence cap.

For example:
- strong visual margin → context weight = 0;
- ambiguous top-2 → apply recency/frequency/time-of-day prior;
- very weak visual evidence → context chooses which options to *show*, not what to speak.

This avoids a dangerous failure where “the patient usually asks for water at 8 p.m.” causes Mouna to speak water when their lips clearly indicated pain.

A 2026 open-source AAC system, CPR-SAT, reports large gains from contextual re-ranking of pictograms and reduced interaction effort. But 2026 AAC research on ultra-personalised AI also shows why user agency and privacy matter: personalised systems can surface the wrong private context or shape what the user appears to “say.”

So keep Mouna’s context tiny and auditable:
- counts, recency, time buckets;
- no cloud;
- no hidden biography model;
- “why this is first” visible;
- clear history button.

### Experiment E20 — context only when useful

Report two numbers, not just final accuracy:

1. **rescues**: ambiguous visual cases context changed from wrong → right;
2. **harms**: context changed from right → wrong.

Pass line: rescue/harm ratio ≥5:1 and **zero** strong-margin overrides by design.

---

## 8. Make confidence **risk-aware**, not phrase-agnostic

Not every wrong output has the same consequence.

“Thank you” mis-spoken is annoying.  
“Call nurse” is important.  
“Turn fan off” is an action.  
A future nurse-call relay is even more consequential.

So give intents/actions risk tiers:

### Tier A — low consequence
“thank you”, family message, comfort phrase  
→ may auto-speak at normal threshold.

### Tier B — care request
“pain”, “breathe”, “nurse”  
→ stricter acceptance or immediate visible confirmation when ambiguous.

### Tier C — physical action / external system
IR control, nurse-call relay, anything that changes the environment  
→ explicit confirmation before execution; clear undo where possible.

This is a product version of **cost-aware selective prediction**: abstain/confirm more when the cost of a wrong commitment is higher.

Do not frame this as clinical decision-making. It is just interaction safety.

---

## 9. A stronger metric: **time to safe communication**

The earlier comment correctly adds “time to be understood.” I would split that into two metrics:

### A. Time to *first successful message*
What the patient feels.

### B. Time to *safe setup*
How long from opening Mouna until the phrase pack satisfies:
- class separation threshold;
- open-set false-speak target;
- next-session / perturbation check if available.

This combines the strongest parts of ideas 1–4 into one number.

It also lets you tell a richer story than “40 seconds”:

> “Five phrases were usable in 24 seconds; Mouna noticed that water and toilet were visually close, asked for two extra examples, and declared the pack safe in 31 seconds.”

That feels like a product that understands its own limits.

---

## 10. The demo I would build around this

The existing demo is already good. A more technical “judge-proof” version:

1. Teach 5 intents with **adaptive teaching**.
2. Mouna notices two are close and asks for one extra sample / a more separable phrase.
3. Show the **confusion map** for 2 seconds: judges see real ML, not magic.
4. Judge mouths one phrase.
5. Easy case → singleton prediction → speaks immediately.
6. Deliberately ambiguous case → set = {water, toilet}; Mouna refuses to guess.
7. It uses **one-bit rescue**: two pictures, judge looks / eyebrow-confirms.
8. Judge mouths an untaught phrase → **negative/open-set path rejects it** rather than confidently saying something wrong.
9. Then an environmental action can still be the final wow moment, with explicit confirmation.

The story becomes:

> “We did not make lip reading perfect. We made a communication system that knows where lip reading fails—and recovers.”

That is much harder for another team to copy in a weekend because it is an architectural idea, not a single feature.

---

## Suggested experiments to add

| # | Experiment | Primary metric | Pass line |
|---|---|---|---|
| **E14** | Fixed phrases vs confusion-aware rephrasing / visual codebook | worst-pair confusion, top-1 | ≥50% reduction in worst pair or ≥+5 top-1 |
| **E15** | Fixed 3-shot vs adaptive teaching | seconds to target performance | ≥25% faster, no worse next-session/open-set |
| **E16** | Set-valued / conformal-style decision | coverage, mean set size, false singleton | ≥90% empirical coverage with useful set size |
| **E17** | Negative rehearsal for open set | untaught false-speak at matched known coverage | ≥50% reduction vs normal threshold |
| **E18** | Fixed Ask vs semi-stable Huffman Ask | yes/no actions per completed intent | ≥25% fewer, same success |
| **E19** | Top-4 vs one-bit confusion rescue | correction time/actions | one-bit faster in ≥75% of rank-2 errors |
| **E20** | Visual-only vs bounded context prior | rescues / harms | ≥5:1, zero strong-evidence overrides |
| **E21** | Risk-tier policy | unsafe external actions | 0 unconfirmed Tier-C actions in stress test |

---

## Finale priority order

If there is only enough time for a few:

1. **Confusion graph + active teaching** — extremely cheap, visibly technical, improves setup.
2. **Explicit open-set negatives** — directly attacks the current 23% → 7% failure mode instead of only turning the threshold down.
3. **Set-valued UI** — singleton speaks, 2–3 choices, otherwise Ask.
4. **One-bit rescue** — turns top-2 information into a real interaction.
5. **Bounded context prior** — only after the visual-only behavior is solid.
6. **Semi-stable Huffman Ask** — strong research/demo story, but keep the fixed tree fallback.
7. Full conformal guarantees / automatic codebook search — roadmap unless calibration data is sufficient.

---

## Sources

**Visual ambiguity / why phrase design matters**
- “The visome: Using cognitive networks to examine lip-reading errors in English words” (2026): https://pubmed.ncbi.nlm.nih.gov/42334602/
- LinProVSR, AAAI 2026 — explicitly targets viseme ambiguity: https://ojs.aaai.org/index.php/AAAI/article/view/38133
- Homophenes in lip reading: https://ojs.aaai.org/index.php/AAAI/article/view/20003

**Set-valued uncertainty / abstention**
- Rank-based conformal prediction sets (Pattern Recognition, 2026): https://www.sciencedirect.com/science/article/pii/S0031320325009914
- Classification with reject option via conformal prediction (2025): https://www.sciencedirect.com/science/article/pii/S2666827025000477
- Cost-aware conformal selective prediction (general safety principle; not a claim that Mouna is clinical triage): https://www.nature.com/articles/s41598-026-40637-w

**Few-shot / open-set**
- Active few-shot prototypical learning: https://www.sciencedirect.com/science/article/pii/S0167865523001940
- Transductive negative prototypes for few-shot open-set recognition: https://www.sciencedirect.com/science/article/pii/S0925231224020472
- Overall positive prototype for few-shot open-set recognition: https://www.sciencedirect.com/science/article/pii/S0031320324001511

**AAC interaction + context**
- Huffman scanning for single-switch AAC: https://pmc.ncbi.nlm.nih.gov/articles/PMC3828203/
- Follow-up evaluation including a user with severe speech/physical impairment: https://pmc.ncbi.nlm.nih.gov/articles/PMC4617344/
- CPR-SAT context-aware pictogram ranking (SoftwareX, 2026): https://www.sciencedirect.com/science/article/pii/S2352711026001287
- Ultra-personalised AAC, agency/identity/privacy (CHI 2026): https://doi.org/10.1145/3772318.3790310
- SPICA personalised AAC framework (IUI 2026): https://doi.org/10.1145/3742413.3789116

---

### One-line reframe after all three rounds

> **Mouna is not trying to solve lip reading. It turns whatever signal a person can still produce into a short, personalised, error-corrected path to being understood.**

For the Finale, I would make **“error-correcting communication”** the technical thesis and let the NPU lip encoder be one component inside it.
