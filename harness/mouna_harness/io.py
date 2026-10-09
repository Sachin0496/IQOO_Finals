"""Reading Mouna Lab exports (format ``mouna-lab/1``, written by lab/src/store/export.ts)."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

FORMAT = "mouna-lab/1"


@dataclass
class Clip:
    id: str
    participant: str
    session: str
    kind: str  # teach | protocol | idle | idle_span
    phrase: str | None
    rep: int
    spoken_lang: str
    t: np.ndarray  # (frames,) ms
    features: np.ndarray  # (frames, feature_dim)
    issues: list[str]
    duration_ms: float
    crops: np.ndarray | None = field(default=None, repr=False)  # (frames, size, size) uint8

    @property
    def clean(self) -> bool:
        return not self.issues and len(self.features) > 1


def load_export(path: Path, with_crops: bool = False) -> list[Clip]:
    doc = json.loads(Path(path).read_text(encoding="utf-8"))
    if doc.get("format") != FORMAT:
        raise ValueError(f"{path}: expected format {FORMAT}, got {doc.get('format')}")
    dim = doc["featureDim"]
    blob = None
    size = 0
    if with_crops and doc.get("crop"):
        size = doc["crop"]["size"]
        blob = np.fromfile(Path(path).parent / doc["crop"]["file"], dtype=np.uint8)

    clips = []
    for c in doc["clips"]:
        feats = np.asarray(c["features"], dtype=np.float32).reshape(-1, dim)
        crops = None
        if blob is not None and c.get("cropOffset") is not None:
            n = c["cropFrames"] * size * size
            crops = blob[c["cropOffset"] : c["cropOffset"] + n].reshape(-1, size, size)
        clips.append(
            Clip(
                id=c["id"],
                participant=doc["participant"],
                session=doc["session"],
                kind=c["kind"],
                phrase=c["phrase"],
                rep=int(c["rep"]),
                spoken_lang=c["spokenLang"],
                t=np.asarray(c["t"], dtype=np.float64),
                features=feats,
                issues=list(c["issues"]),
                duration_ms=float(c["stats"]["durationMs"]),
                crops=crops,
            )
        )
    return clips


def load_dir(root: Path, with_crops: bool = False) -> list[Clip]:
    files = sorted(Path(root).glob("*.mouna.json"))
    if not files:
        raise FileNotFoundError(f"no *.mouna.json files in {root}")
    return [clip for f in files for clip in load_export(f, with_crops)]
