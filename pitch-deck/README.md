# Mouna pitch deck: "Every soul deserves a voice"

8 slides, 16:9, light beige + burgundy + warm grey. All charts are native PowerPoint charts (right-click > Edit Data).
Speaker notes are on every slide.

Rebuild: `NODE_PATH=$(npm root -g) node build.js` (pptxgenjs). Edit the text and numbers in `build.js`.

## Where each number comes from
| Slide | Number | Source |
|---|---|---|
| 2 | 430 M disabling hearing loss | WHO fact sheet on deafness and hearing loss (3 Mar 2026) |
| 2 | 97 M cannot rely on speech | J. Light, Penn State (AAC research). Secondary source: check the primary paper before publishing |
| 2 | 70 M deaf people use sign language | World Federation of the Deaf |
| 2 | 1.9 M speech disability (India) | Census 2011 via MoSPI statistical profile 2021 (7% of 26.8 M) |
| 2 | ~300 ISL interpreters, ~5 M deaf Indians | ISLRTC (counts in sources range 250 to 350; Census 2011 hearing impairment 5.07 M) |
| 2 | ~2,500 speech therapists and audiologists | ISHA, 2017 (dated; secondary source) |
| 4 | 71.9 / 85.2 / 89.0 % by 1 / 3 / 5 examples | `deck/data/kannada-curve.json` |
| 4 | 99.5 % right when it speaks | `deck/data/finale-core.json` (held-out speakers) |
| 4 | 18 ms per 1.9 s on the NPU | `deck/data/device-litert.json` (iQOO 15 chip, SM8850) |
| 4 | 23 % to 7 % untaught words spoken | `docs/roadmap.md` item 17 |
| 7 | 5.07 M hearing impairment, 1.88 M speech disability | Census 2011 |
| 7 | ~2 M aphasia after stroke | Pauranik et al. 2019 |
| 7 | ~70,000 new laryngeal cancers a year | GLOBOCAN 2024 |
| 7 | $4,000 to 23,000 eye-gaze devices | Tobii Dynavox price range (secondary source) |

## Honest limits stated in the deck
Accuracy is on a public Kannada dataset, not our own silent-speech recordings. Sign mode is integrated (AI4Bharat
OpenHands, 263 ISL signs) but not yet tested with real signers. Real phone-number calls are planned, not built.
