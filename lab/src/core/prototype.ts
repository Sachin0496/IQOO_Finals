/**
 * Plan A head: one prototype (mean embedding) per phrase, cosine distance. Mirrors PrototypeHead in
 * harness/mouna_harness/heads.py and uses the same decision rule as the lip-geometry recogniser.
 */
import { DEFAULT_RECOGNIZER, type Decision, type DecisionKind, type Ranked, type RecognizerConfig } from './recognizer'

const normalise = (v: Float32Array): Float32Array => {
  let n = 0
  for (const x of v) n += x * x
  n = Math.sqrt(n) + 1e-9
  return v.map((x) => x / n)
}
const dot = (a: Float32Array, b: Float32Array) => {
  let s = 0
  for (let i = 0; i < a.length; i++) s += a[i] * b[i]
  return s
}
const percentile = (xs: number[], p: number) => [...xs].sort((a, b) => a - b)[Math.min(xs.length - 1, Math.max(0, Math.round(p * (xs.length - 1))))]

export class PrototypeHead {
  readonly cfg: RecognizerConfig
  private samples = new Map<string, Float32Array[]>()
  threshold = 0.5

  constructor(cfg: RecognizerConfig = DEFAULT_RECOGNIZER) {
    this.cfg = cfg
  }

  add(phrase: string, embedding: Float32Array): void {
    const list = this.samples.get(phrase) ?? []
    list.push(normalise(embedding))
    this.samples.set(phrase, list)
  }

  count(phrase: string): number {
    return this.samples.get(phrase)?.length ?? 0
  }

  get phrases(): string[] {
    return [...this.samples.keys()]
  }

  private prototypes(skipPhrase?: string, skipIndex = -1): Map<string, Float32Array> {
    const out = new Map<string, Float32Array>()
    for (const [p, xs] of this.samples) {
      const keep = xs.filter((_, i) => !(p === skipPhrase && i === skipIndex))
      if (!keep.length) continue
      const mean = new Float32Array(keep[0].length)
      for (const x of keep) for (let i = 0; i < x.length; i++) mean[i] += x[i] / keep.length
      out.set(p, normalise(mean))
    }
    return out
  }

  private rank(e: Float32Array, protos: Map<string, Float32Array>): Ranked[] {
    const u = normalise(e)
    const rows = [...protos].map(([phrase, v]) => ({ phrase, distance: 1 - dot(u, v) })).sort((a, b) => a.distance - b.distance)
    const tau = Math.max(1e-6, (rows[0]?.distance ?? 1) * 0.25)
    const w = rows.map((r) => Math.exp(-(r.distance - rows[0].distance) / tau))
    const z = w.reduce((a, b) => a + b, 0) || 1
    return rows.map((r, i) => ({ ...r, score: w[i] / z }))
  }

  /**
   * Below 1, Mouna speaks only when closer than usual and sends the rest to the choices. Kannada, 8 taught classes:
   * at 0.7, untaught words spoken by mistake fall from 23% to 7% (encoder) or 9% (lip geometry);
   * deck/data/kannada-openset-plan{a,b}.json, strictness_curve.
   */
  strictness = 1

  calibrate(): number {
    const inside: number[] = []
    const outside: number[] = []
    for (const [p, xs] of this.samples) {
      if (xs.length < 2) continue
      xs.forEach((x, i) => {
        const r = this.rank(x, this.prototypes(p, i))
        const own = r.find((q) => q.phrase === p)
        if (own) inside.push(own.distance)
        for (const q of r.slice(0, 2)) if (q.phrase !== p) outside.push(q.distance)
      })
    }
    if (inside.length >= 3) {
      const p90 = percentile(inside, 0.9)
      const mid = outside.length ? (p90 + percentile(outside, 0.5)) / 2 : Infinity
      this.threshold = Math.min(p90 * 1.25, mid)
    }
    return this.threshold
  }

  classify(e: Float32Array, ms = 0): Decision {
    const ranked = this.rank(e, this.prototypes())
    const [best, second] = ranked
    if (!best) return { kind: 'reject', ranked, margin: 0, threshold: this.threshold * this.strictness, ms }
    const margin = second ? second.distance / Math.max(best.distance, 1e-9) : Infinity
    let kind: DecisionKind = 'reject'
    if (best.distance <= this.threshold * this.strictness && margin >= this.cfg.minMargin) kind = 'accept'
    else if (best.distance <= this.threshold * this.strictness * this.cfg.unsureFactor) kind = 'unsure'
    return { kind, ranked, margin, threshold: this.threshold * this.strictness, ms }
  }
}
