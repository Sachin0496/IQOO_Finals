"""E10: time to be understood, Mouna vs writing vs a picture board (docs/protocol.md). Pass: Mouna faster in at
least 8 of the 10 needs (median over people)."""

from __future__ import annotations

import csv
from collections import defaultdict
from pathlib import Path
from statistics import mean, median


def summarise(sheet: Path) -> dict:
    rows = [r for r in csv.DictReader(sheet.open(encoding="utf-8")) if r.get("person")]
    by_method: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        by_method[r["method"].strip().lower()].append(r)
    out: dict = {"trials": len(rows), "methods": {}}
    for m, rs in sorted(by_method.items()):
        ok = [float(r["seconds"]) for r in rs if r["understood"].strip() in ("1", "yes", "true")]
        out["methods"][m] = {
            "trials": len(rs),
            "understood": round(len(ok) / len(rs), 4),
            "median_seconds_when_understood": round(median(ok), 1) if ok else None,
            "mean_seconds_when_understood": round(mean(ok), 1) if ok else None,
        }
    # per need: median seconds per method (60 s when not understood), Mouna vs the faster of the others
    per_need: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
    for r in rows:
        s = float(r["seconds"]) if r["understood"].strip() in ("1", "yes", "true") else 60.0
        per_need[r["need"].strip().lower()][r["method"].strip().lower()].append(s)
    wins = 0
    compared = 0
    for need, ms in per_need.items():
        if "mouna" in ms and len(ms) > 1:
            compared += 1
            wins += median(ms["mouna"]) < min(median(v) for k, v in ms.items() if k != "mouna")
    out["mouna_faster_needs"] = {"wins": wins, "of": compared, "pass": compared >= 10 and wins >= 8}
    return out
