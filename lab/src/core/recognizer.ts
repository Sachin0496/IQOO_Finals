/**
 * Plan B few-shot recogniser: lip-geometry sequences matched by DTW against the person's own templates.
 * No weights, no network. It sets the floor the encoder (plan A) has to beat.
 *
 * Safety rule from the product brief: never auto-speak a low-confidence phrase.
 *   accept  -> best distance under the person's threshold and clearly ahead of the runner-up
 *   unsure  -> close, but ambiguous: show the top three for a tap
 *   reject  -> nothing taught looks like this: "not sure, please repeat"
 */

import { dtw } from './dtw'

export type Seq = Float32Array[]

export interface Ranked {
  phrase: string
  distance: number
  /** Softmax over negative distances, for display only. */
  score: number
}

export type DecisionKind = 'accept' | 'unsure' | 'reject'

export interface Decision {
  kind: DecisionKind
  ranked: Ranked[]
  /** runner-up distance / best distance; >1 means the best is ahead. */
  margin: number
  threshold: number
  ms: number
}

export interface RecognizerConfig {
  minMargin: number
  unsureFactor: number
  /** Fallback threshold before calibration is possible. */
  defaultThreshold: number
}

export const DEFAULT_RECOGNIZER: RecognizerConfig = { minMargin: 1.15, unsureFactor: 1.6, defaultThreshold: 0.35 }

const percentile = (xs: number[], p: number): number => {
  if (xs.length === 0) return NaN
  const s = [...xs].sort((a, b) => a - b)
  const k = Math.min(s.length - 1, Math.max(0, Math.round(p * (s.length - 1))))
  return s[k]
}

export class FewShotDTW {
  readonly cfg: RecognizerConfig
  private templates = new Map<string, Seq[]>()
  threshold: number

  constructor(cfg: RecognizerConfig = DEFAULT_RECOGNIZER) {
    this.cfg = cfg
    this.threshold = cfg.defaultThreshold
  }

  add(phrase: string, seq: Seq): void {
    const list = this.templates.get(phrase) ?? []
    list.push(seq)
    this.templates.set(phrase, list)
  }

  clear(phrase?: string): void {
    if (phrase) this.templates.delete(phrase)
    else this.templates.clear()
  }

  count(phrase: string): number {
    return this.templates.get(phrase)?.length ?? 0
  }

  get phrases(): string[] {
    return [...this.templates.keys()]
  }

  /** Distance from a sequence to a phrase: mean of its two closest templates. */
  private phraseDistance(seq: Seq, templates: Seq[], skip?: Seq): number {
    const d = templates
      .filter((t) => t !== skip)
      .map((t) => dtw(seq, t))
      .sort((a, b) => a - b)
    if (d.length === 0) return Infinity
    return d.length === 1 ? d[0] : (d[0] + d[1]) / 2
  }

  rank(seq: Seq, skip?: Seq): Ranked[] {
    const rows = [...this.templates].map(([phrase, ts]) => ({ phrase, distance: this.phraseDistance(seq, ts, skip) }))
    rows.sort((a, b) => a.distance - b.distance)
    const finite = rows.filter((r) => Number.isFinite(r.distance))
    const tau = Math.max(1e-6, (finite[0]?.distance ?? 1) * 0.25)
    const w = rows.map((r) => (Number.isFinite(r.distance) ? Math.exp(-(r.distance - finite[0].distance) / tau) : 0))
    const z = w.reduce((a, b) => a + b, 0) || 1
    return rows.map((r, i) => ({ ...r, score: w[i] / z }))
  }

  /**
   * Leave-one-out over the taught templates: how far is each one from its own phrase (in) and from the
   * nearest other phrase (out)? The threshold sits above most "in" distances and below typical "out".
   */
  /**
   * Below 1, Mouna speaks only when closer than usual and sends the rest to the choices. Kannada, 8 taught classes:
   * at 0.7, untaught words spoken by mistake fall from 23% to 7% (encoder) or 9% (lip geometry);
   * deck/data/kannada-openset-plan{a,b}.json, strictness_curve.
   */
  strictness = 1

  calibrate(): { inDist: number[]; outDist: number[]; threshold: number } {
    const inDist: number[] = []
    const outDist: number[] = []
    for (const [phrase, ts] of this.templates) {
      if (ts.length < 2) continue
      for (const t of ts) {
        const r = this.rank(t, t)
        const own = r.find((x) => x.phrase === phrase)
        const other = r.find((x) => x.phrase !== phrase)
        if (own && Number.isFinite(own.distance)) inDist.push(own.distance)
        if (other && Number.isFinite(other.distance)) outDist.push(other.distance)
      }
    }
    if (inDist.length >= 3) {
      const hiIn = percentile(inDist, 0.9) * 1.25
      const midOut = outDist.length ? (percentile(inDist, 0.9) + percentile(outDist, 0.5)) / 2 : Infinity
      this.threshold = Math.min(hiIn, midOut)
    }
    return { inDist, outDist, threshold: this.threshold }
  }

  classify(seq: Seq): Decision {
    const t0 = performance.now()
    const ranked = this.rank(seq)
    const ms = performance.now() - t0
    const [best, second] = ranked
    if (!best || !Number.isFinite(best.distance)) {
      return { kind: 'reject', ranked, margin: 0, threshold: this.threshold * this.strictness, ms }
    }
    const margin = second && Number.isFinite(second.distance) ? second.distance / Math.max(best.distance, 1e-9) : Infinity
    let kind: DecisionKind = 'reject'
    if (best.distance <= this.threshold * this.strictness && margin >= this.cfg.minMargin) kind = 'accept'
    else if (best.distance <= this.threshold * this.strictness * this.cfg.unsureFactor) kind = 'unsure'
    return { kind, ranked, margin, threshold: this.threshold * this.strictness, ms }
  }
}
