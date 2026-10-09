"""Pre-render the phrase pack into natural Indian voices, once, at build time.

The phrase pack is a closed list, so every clip can be generated ahead of time and shipped inside the app.
Mouna stays offline (no INTERNET permission, CSP connect-src 'self') yet speaks with a human-sounding voice
the patient chooses, instead of whatever robotic voice the device happens to have.

    python voices/render.py --engine parler            # AI4Bharat Indic Parler-TTS, Apache-2.0, runs locally
    SARVAM_API_KEY=... python voices/render.py --engine sarvam   # Sarvam Bulbul, build-time API call only

Writes lab/public/voices/<voice>/<lang>/<phrase>.m4a and lab/public/voices/manifest.json.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "lab" / "src" / "core" / "phrase-pack.json"
VOICES = Path(__file__).with_name("voices.json")
OUT = ROOT / "lab" / "public" / "voices"
LANGS = {"en": "en-IN", "ta": "ta-IN", "kn": "kn-IN", "hi": "hi-IN"}


def encode(wav: Path, dst: Path) -> None:
    """Trim silence and encode as small mono AAC (about 10 kB per phrase)."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    trim = "silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-af", trim, "-ac", "1", "-c:a", "aac", "-b:a", "48k", str(dst)],
        check=True,
    )


class Parler:
    name = "ai4bharat/indic-parler-tts"
    licence = "Apache-2.0"

    def __init__(self) -> None:
        import torch
        from parler_tts import ParlerTTSForConditionalGeneration
        from transformers import AutoTokenizer

        self.torch = torch
        self.model = ParlerTTSForConditionalGeneration.from_pretrained(self.name).eval()
        self.tok = AutoTokenizer.from_pretrained(self.name)
        self.desc_tok = AutoTokenizer.from_pretrained(self.model.config.text_encoder._name_or_path)

    def render(self, text: str, lang: str, voice: dict, wav: Path) -> None:
        import soundfile as sf

        cfg = voice["parler"]
        who = cfg["speakers"].get(lang)
        description = f"{who} {cfg['style']}" if who else f"A middle-aged speaker {cfg['style']}"
        d = self.desc_tok(description, return_tensors="pt")
        p = self.tok(text, return_tensors="pt")
        self.torch.manual_seed(0)  # same voice every render
        with self.torch.no_grad():
            audio = self.model.generate(
                input_ids=d.input_ids, attention_mask=d.attention_mask, prompt_input_ids=p.input_ids, prompt_attention_mask=p.attention_mask
            )
        sf.write(wav, audio.cpu().numpy().squeeze(), self.model.config.sampling_rate)


class Sarvam:
    name = "sarvam bulbul:v3"
    licence = "Sarvam AI terms of service"
    URL = "https://api.sarvam.ai/text-to-speech"

    def __init__(self) -> None:
        self.key = os.environ["SARVAM_API_KEY"]

    def render(self, text: str, lang: str, voice: dict, wav: Path) -> None:
        import urllib.request

        body = json.dumps(
            {"text": text, "target_language_code": LANGS[lang], "speaker": voice["sarvam"]["speaker"], "model": "bulbul:v3", "pace": 0.9}
        ).encode()
        import time
        import urllib.error

        req = urllib.request.Request(self.URL, data=body, headers={"api-subscription-key": self.key, "Content-Type": "application/json"})
        for attempt in range(6):
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    wav.write_bytes(base64.b64decode(json.load(r)["audios"][0]))
                return
            except urllib.error.HTTPError as e:
                if e.code != 429 or attempt == 5:
                    raise
                time.sleep(2 * 2**attempt)  # rate limited: back off and retry


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", choices=["parler", "sarvam"], default="parler", help="renders the voices.json entries of this engine")
    ap.add_argument("--voices", nargs="*", help="voice ids (default: all)")
    ap.add_argument("--langs", nargs="*", default=list(LANGS))
    args = ap.parse_args()

    pack = json.loads(PACK.read_text(encoding="utf-8"))
    voices = [
        v
        for v in json.loads(VOICES.read_text(encoding="utf-8"))["voices"]
        if v["engine"] == args.engine and (not args.voices or v["id"] in args.voices)
    ]
    engine = Parler() if args.engine == "parler" else Sarvam()

    manifest_path = OUT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {"voices": []}
    for voice in voices:
        entry = {"id": voice["id"], "label": voice["label"], "engine": engine.name, "licence": engine.licence, "clips": {}}
        with tempfile.TemporaryDirectory() as tmp:
            for lang in args.langs:
                for phrase in pack["phrases"]:
                    wav = Path(tmp) / "clip.wav"
                    rel = f"{voice['id']}/{lang}/{phrase['id']}.m4a"
                    if not (OUT / rel).exists():  # resumable: rendered clips are kept
                        engine.render(phrase["text"][lang], lang, voice, wav)
                        encode(wav, OUT / rel)
                    entry["clips"].setdefault(lang, {})[phrase["id"]] = rel
                    print(rel, flush=True)
        manifest["voices"] = [v for v in manifest["voices"] if v["id"] != voice["id"]] + [entry]
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
