"""Markdown report. Publishes everything, including the bad numbers."""

from __future__ import annotations


def _pct(v: float | None) -> str:
    return "—" if v is None else f"{100 * v:.1f}%"


def _rate(v: float | None) -> str:
    return "—" if v is None else f"{v:.2f}/min"


def to_markdown(r: dict) -> str:
    lines = [
        f"# Spike results ({r['head']})" + (" — SYNTHETIC DRY RUN, NOT REAL DATA" if r.get("synthetic") else ""),
        "",
        f"**Verdict:** {r['verdict']}",
        "",
        "| Who | Split | n | Top-1 | Top-3 | Spoke | Spoke precision |",
        "|---|---|---|---|---|---|---|",
    ]
    rows = [(p["participant"], p) for p in r["participants"]] + [("pooled", r["pooled"])]
    for who, block in rows:
        for split, name in (("same_session", "same session"), ("next_session", "next morning")):
            s = block[split]
            lines.append(
                f"| {who} | {name} | {s['n']} | {_pct(s['top1'])} | {_pct(s['top3'])} | {_pct(s['spoke'])} | {_pct(s['spoke_precision'])} |"
            )
    p = r["pooled"]
    lines += [
        "",
        f"Idle: {p['idle_minutes']} min · gate triggers {_rate(p['gate_triggers_per_min'])} · "
        f"would have spoken {_rate(p['false_speaks_per_min'])}",
        "",
        "## Confusion, pooled next morning (truth → predicted)",
        "",
    ]
    conf = p["next_session"]["confusion"] or p["same_session"]["confusion"]
    phrases = r["phrases"]
    if conf:
        lines.append("| truth / predicted | " + " | ".join(phrases) + " |")
        lines.append("|---" * (len(phrases) + 1) + "|")
        for t in phrases:
            row = conf.get(t, {})
            lines.append(f"| {t} | " + " | ".join(str(row.get(q, 0) or "·") for q in phrases) + " |")
    return "\n".join(lines) + "\n"
