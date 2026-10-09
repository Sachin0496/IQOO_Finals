/**
 * Blink-to-talk: a hands-free trigger for people who cannot press a button (ICU, stroke, weak hands).
 * Two deliberate blinks in quick succession start listening; natural blinks rarely pair this fast.
 */
import type { Pt } from './lips'

/** Eye landmarks: corners and two upper/lower pairs, per eye. */
const RIGHT = { outer: 33, inner: 133, up: [159, 158], down: [145, 153] }
const LEFT = { outer: 263, inner: 362, up: [386, 385], down: [374, 380] }

const dist = (a: Pt, b: Pt, w: number, h: number) => Math.hypot((a.x - b.x) * w, (a.y - b.y) * h)

function aspect(lm: ArrayLike<Pt>, e: typeof RIGHT, w: number, h: number): number {
  const width = dist(lm[e.outer], lm[e.inner], w, h)
  const height = (dist(lm[e.up[0]], lm[e.down[0]], w, h) + dist(lm[e.up[1]], lm[e.down[1]], w, h)) / 2
  return width > 0 ? height / width : 0
}

/** Mean eye aspect ratio of both eyes: about 0.25-0.35 open, below 0.15 closed. */
export const eyeOpenness = (lm: ArrayLike<Pt>, w: number, h: number) => (aspect(lm, RIGHT, w, h) + aspect(lm, LEFT, w, h)) / 2

export interface BlinkConfig {
  /** Closed when openness falls below this fraction of the person's open-eye baseline. */
  closedRatio: number
  minBlinkMs: number
  maxBlinkMs: number
  /** Second blink must start within this long after the first ends. */
  maxGapMs: number
  /** Ignore everything for this long after a trigger. */
  refractoryMs: number
}

export const DEFAULT_BLINK: BlinkConfig = { closedRatio: 0.55, minBlinkMs: 40, maxBlinkMs: 500, maxGapMs: 1000, refractoryMs: 1500 }

export class DoubleBlink {
  readonly cfg: BlinkConfig
  private baseline = -1
  private closedAt = -1
  private lastBlinkEnd = -Infinity
  private quietUntil = -Infinity

  constructor(cfg: BlinkConfig = DEFAULT_BLINK) {
    this.cfg = cfg
  }

  get closed(): boolean {
    return this.closedAt >= 0
  }

  /** Feed one frame. Returns true on the frame a double blink completes. */
  push(openness: number, tMs: number): boolean {
    const c = this.cfg
    if (this.baseline < 0) this.baseline = openness
    const isClosed = openness < this.baseline * c.closedRatio
    if (!isClosed && openness > this.baseline * 0.7) this.baseline += 0.05 * (openness - this.baseline)

    if (tMs < this.quietUntil) {
      this.closedAt = -1
      return false
    }
    if (isClosed && this.closedAt < 0) this.closedAt = tMs
    if (isClosed || this.closedAt < 0) return false

    // eyes just reopened: was that a blink?
    const length = tMs - this.closedAt
    this.closedAt = -1
    if (length < c.minBlinkMs || length > c.maxBlinkMs) return false
    const start = tMs - length
    if (start - this.lastBlinkEnd <= c.maxGapMs) {
      this.lastBlinkEnd = -Infinity
      this.quietUntil = tMs + c.refractoryMs
      return true
    }
    this.lastBlinkEnd = tMs
    return false
  }
}
