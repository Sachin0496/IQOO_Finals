/**
 * Context re-ranking. When Mouna is unsure, the order of the three choices matters: the right one should be first.
 * The prior combines what this person tends to say next (learned from their own history) with a few gentle,
 * built-in ward patterns. Safety rule: context only reorders choices; it never makes Mouna speak on its own.
 */
import type { Ranked } from './recognizer'

/** Built-in follow-ups seen in ward communication: after the first phrase, the second is a bit likelier. */
const FOLLOWS: Record<string, string[]> = {
  pain: ['medicine', 'nurse', 'family'],
  breathe: ['nurse', 'sit_up'],
  water: ['thank_you', 'no'],
  medicine: ['thank_you', 'water'],
  sit_up: ['thank_you', 'water'],
  cold: ['thank_you', 'nurse'],
  hot: ['water', 'thank_you'],
  nurse: ['pain', 'toilet', 'thank_you'],
}
/** Phrases a little likelier at night (22:00-06:00). */
const NIGHT = ['toilet', 'water', 'pain', 'cold']

export type Transitions = Record<string, Record<string, number>>

export class ContextPrior {
  readonly transitions: Transitions

  constructor(transitions: Transitions = {}) {
    this.transitions = transitions
  }

  observe(prev: string | null, next: string): void {
    if (!prev) return
    const row = (this.transitions[prev] ??= {})
    row[next] = (row[next] ?? 0) + 1
  }

  /** Multiplicative weight >= 1 for `phrase` given the previous phrase and the hour (0-23). */
  weight(phrase: string, prev: string | null, hour: number): number {
    let w = 1
    if (prev) {
      const row = this.transitions[prev] ?? {}
      const total = Object.values(row).reduce((a, b) => a + b, 0)
      if (total > 0) w *= 1 + (2 * (row[phrase] ?? 0)) / total // learned: up to x3
      if (FOLLOWS[prev]?.includes(phrase)) w *= 1.25 // built-in: gentle
    }
    if ((hour >= 22 || hour < 6) && NIGHT.includes(phrase)) w *= 1.15
    return w
  }

  /** Reorders candidates by lip score x context weight. Scores are renormalised; distances are untouched. */
  rerank(ranked: Ranked[], prev: string | null, hour: number): (Ranked & { boosted: boolean })[] {
    const rows = ranked.map((r) => {
      const w = this.weight(r.phrase, prev, hour)
      return { ...r, score: r.score * w, boosted: w > 1.05 }
    })
    const z = rows.reduce((a, r) => a + r.score, 0) || 1
    return rows.map((r) => ({ ...r, score: r.score / z })).sort((a, b) => b.score - a.score)
  }
}
