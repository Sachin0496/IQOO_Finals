# Voice pack

Mouna's phrases are a closed list, so they are rendered once, at build time, into natural Indian voices and shipped
with the app. At runtime nothing is synthesised over the network: the app has no INTERNET permission and the Lab's
CSP forbids outbound requests. The patient picks the voice that suits them; the device voice is the fallback.

| Engine | Model | Licence | Needs |
|---|---|---|---|
| `parler` (default) | AI4Bharat Indic Parler-TTS (0.9B), named voices per language | Apache-2.0 | accept the model's terms on Hugging Face once |
| `sarvam` | Sarvam Bulbul v3 (voices Kavitha, Anand) | Sarvam AI terms for generated audio **[verify]** | `SARVAM_API_KEY` |

```bash
pip install git+https://github.com/huggingface/parler-tts.git soundfile
python voices/render.py --engine parler
SARVAM_API_KEY=... python voices/render.py --engine sarvam --voices warm-female
```

Voices are defined in `voices.json`; phrases come from `lab/src/core/phrase-pack.json` (the single source shared with
the Lab and the Android build). Output: `lab/public/voices/<voice>/<lang>/<phrase>.m4a` plus `manifest.json`.
The texts still need a native-speaker check before any clip is trusted in a ward.
