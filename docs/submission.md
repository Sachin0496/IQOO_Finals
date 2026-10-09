# Phase 1 submission: paste-ready

Numbers below come from `deck/data/*.json` (harness and Qualcomm AI Hub runs). Items marked **[verify]** are
still unconfirmed and stay out of the form until they are confirmed.

| Field | Value |
|---|---|
| Track | Open Innovation |
| Idea title | Mouna: a voice without sound |
| Short description (50 words) | see below |
| Deck | `deck/Mouna-Phase1-Deck.pdf` (17 slides: summary up front, key facts in Appendix B; fonts embedded, 0.3 MB) |
| Prototype URL | https://mouna-spike.vercel.app |
| Video URL | the 45 s clip (optional) |

## Description for the form (1,876 characters, limit 2,000)

> Mouna (மௌனம், "silence") gives a voice back to people who can move their lips but cannot make a sound after a laryngectomy, a tracheostomy or a stroke. Today they point at whiteboards. India sees about 70,000 new laryngeal and hypopharyngeal cancers a year (GLOBOCAN 2024).
>
> How it works: the patient teaches Mouna a few phrases in about 25 seconds by silently mouthing each one three times. From then on, mouthing a phrase makes the phone speak it in the caregiver's language (Kannada, Tamil, Hindi or English), in a natural voice or their own voice banked before surgery. A Tamil-speaking patient can be heard in Kannada by the nurse.
>
> Beyond a fixed list: two taught words make a sentence ("water · want" becomes "I want water"), always confirmed first. For anything never taught, Ask mode finds it by yes and no: nod or blink twice for yes, shake for no.
>
> Built for a ward: two blinks start listening; urgent phrases ring the caregiver's phone over a direct local link; when unsure, Mouna shows choices instead of guessing, and every correction teaches it.
>
> Measured on a public Kannada lip-reading dataset (7 speakers, 27 words and phrases): 89.0% top-1 with five samples, 97.3% on two-word phrases, right 94.3% of the times it chose to speak. Untaught words spoken by mistake fall from 23% to 7% in Careful mode; a sentence list lifts two-word sentences from 69% to 89%. The whole encoder runs on the Snapdragon Hexagon NPU: 18.4 ms per 1.92 s of lips on the iQOO 15's chip (SM8850, Qualcomm AI Hub), int8, 86 MB, no accuracy loss.
>
> Private by design: no cloud, no stored video, no internet permission. The cloud app SRAVI is English-only and scored 22 to 35% in UK critical care.
>
> We build on LipLearner (CHI 2023) and credit it. The working prototype, from first commit to NPU-profiled encoder, took 21 hours (git history, 3-4 Oct 2026): https://mouna-spike.vercel.app

## Short description (49 words)

> Mouna gives a voice to patients who can move their lips but make no sound after laryngectomy, tracheostomy or stroke. Taught in 25 seconds, it speaks silently mouthed phrases aloud in Kannada, Tamil, Hindi or English, offline on the iQOO 15's NPU, and finds anything untaught by yes/no questions.

## Prior builds & hackathons (form field, 1,000-character limit: 919)

> Wins: 1st, Great Agent Hackathon (Freshworks); 1st, HACK2TECHSUSTAIN 2.0 (MIT, Anna University); 1st, Innovsense (KPR IET); 1st, GCCXSHIFT (6S Consulting); 1st, QUANTUM SHIELD (SIET); 1st, Paper Presentation (KPR IET); 2nd, SEMICON India Hackathon 2026 (SEMI & Applied Materials); 2nd, AVANZARE V19.0 (Kongu); 3rd, CODE REDEMPTION (Jai Shriram).
>
> Finalists: Qualcomm Snapdragon Multiverse; Caterpillar Tech Challenge 2026; Odoo x KAHE 26; iQOO Hackathon Chennai City Battle (top 26).
>
> Published patent: portable edge-based traffic violation and hazard detection using YOLO26n.
>
> Shipped/open source: VeriTransit, Qwen3-VL on the Snapdragon NPU (github.com/nakultt/VeriTransit); PunarGati, pose on the Hexagon NPU in 7 Indian languages (github.com/Sachin0496/punargati); Gaja Alert (github.com/Team-Highest/Gaja-alert); DriftSense (github.com/vhmaadhav/semicon-driftsense). Mouna prototype: https://mouna-spike.vercel.app

## What makes the team stand out (form field, 1,000-character limit: 964)

> We ship AI that runs on the device, including on this phone: VeriTransit ran Qwen3-VL on the iQOO 15's NPU at the Chennai City Battle, and PunarGati runs pose estimation on the Hexagon NPU, offline, in 7 Indian languages. We hold a published patent on edge vision (YOLO26n) and placed 2nd at SEMICON India with sub-pixel image registration, the precision lip reading needs.
>
> For Mouna we did the hard part early: we rebuilt the open-source LipLearner encoder exactly and made the whole model run on the Hexagon NPU, 18.4 ms per 1.92 s of lips on the iQOO 15's chip (Qualcomm AI Hub). On a public Kannada dataset it reaches 89.0% top-1 and 97.3% on phrases, and we measured its failure too: untaught words spoken by mistake, cut from 23% to 7%. All of it in 21 hours.
>
> Why us: we have built for Indian healthcare before (Thodar, PunarGati) and keep seeing tools that assume English, the cloud and a patient who can type. Mouna is offline, personal and multilingual.

## Originality disclosure

> Pre-existing components: (1) the method of contrastive lip embeddings with few-shot personalisation from LipLearner
> (Su, Fang, Rekimoto, CHI 2023), cited; (2) open-source software and models, used with attribution: MediaPipe Face
> Landmarker, the LipLearner encoder from its MIT-licensed repository (pre-trained on LRW; weight terms being
> confirmed), ONNX Runtime, the Qualcomm AI stack, phrase audio pre-rendered with Sarvam Bulbul and AI4Bharat Indic
> Parler-TTS; (3) the Kannada multi-speaker lip-reading dataset
> (Divya P, Mendeley Data 2026, CC BY 4.0), used only for evaluation; (4) a feasibility-spike repository made before
> the event, linked. All Finale application code will be written inside the event window in a new repository.
> No patient data is used.

## Before pressing submit

- [x] Lab deployed: https://mouna-spike.vercel.app
- [ ] Our own silent recordings (s1 tonight, s2 tomorrow morning), then `--publish`; the evidence slide fills itself.
- [ ] Native-speaker check of the Tamil, Kannada and Hindi phrase pack, Ask-mode questions (lab/src/core/ask-tree.json) and sentences (lab/src/core/blocks.json).
- [ ] Rotate the Qualcomm AI Hub token that was shared in chat.
- [ ] Only the team leader can submit.
