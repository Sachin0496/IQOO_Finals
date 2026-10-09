/**
 * Personal switch: "your yes" is whatever this person can repeat (an eyebrow, a half smile, a cheek puff, a look up).
 * Taught from a few seconds of normal face (including mouthing) and three cued examples, over MediaPipe's
 * blendshape scores. Mouna keeps the channels that move most for this person relative to their own resting noise
 * and that stay quiet while they mouth, so a one-sided smile after a stroke works as well as a symmetric one.
 */

export interface SwitchChannel {
  index: number
  name: string
  /** Resting mean of this channel. */
  rest: number
  /** Typical signed change at the peak of the taught movement. */
  peak: number
  /** |peak| in units of the resting noise. */
  z: number
}

export interface SwitchModel {
  channels: SwitchChannel[]
}

export interface SwitchConfig {
  /** A channel must move at least this many resting standard deviations... */
  minZ: number
  /** ...and at least this many times its own largest wobble at rest (99th percentile), so speech does not press it. */
  minOverRest: number
  maxChannels: number
  /** Activation (0 at rest, 1 at the taught movement) to press, and to release. */
  on: number
  off: number
  holdMs: number
  refractoryMs: number
}

export const DEFAULT_SWITCH: SwitchConfig = { minZ: 4, minOverRest: 1.5, maxChannels: 3, on: 0.6, off: 0.3, holdMs: 120, refractoryMs: 900 }

type Frames = ArrayLike<ArrayLike<number>>

const SIGMA_FLOOR = 0.01

function quantile(xs: number[], q: number): number {
  const s = [...xs].sort((a, b) => a - b)
  return s.length ? s[Math.min(s.length - 1, Math.max(0, Math.round(q * (s.length - 1))))] : 0
}

const median = (xs: number[]) => quantile(xs, 0.5)

/**
 * rest: frames of normal face, ideally including some mouthing (2 s or more). moves: one window of frames per cued
 * example (3 recommended). names: blendshape category names, for the "Mouna watches" line.
 */
export function teachSwitch(rest: Frames, moves: Frames[], names: string[] = [], cfg: SwitchConfig = DEFAULT_SWITCH): SwitchModel | { error: string } {
  if (rest.length < 30) return { error: 'Need a few seconds of your normal face first.' }
  if (moves.length < 2) return { error: 'Show the movement at least twice.' }
  const dim = rest[0].length
  const found: SwitchChannel[] = []
  for (let c = 0; c < dim; c++) {
    const r = Array.from(rest, (f) => f[c])
    const mu = r.reduce((a, b) => a + b, 0) / r.length
    const sigma = Math.max(SIGMA_FLOOR, Math.sqrt(r.reduce((a, b) => a + (b - mu) ** 2, 0) / r.length))
    const wobble = quantile(r.map((x) => Math.abs(x - mu)), 0.99)
    // signed peak of each example: the larger excursion from rest, up or down
    const peaks = moves.map((m) => {
      let hi = -Infinity
      let lo = Infinity
      for (let t = 0; t < m.length; t++) {
        hi = Math.max(hi, m[t][c] - mu)
        lo = Math.min(lo, m[t][c] - mu)
      }
      return Math.abs(hi) >= Math.abs(lo) ? hi : lo
    })
    const peak = median(peaks)
    const sameWay = peaks.filter((p) => Math.sign(p) === Math.sign(peak) && Math.abs(p) >= cfg.minZ * sigma).length
    const z = Math.abs(peak) / sigma
    if (z >= cfg.minZ && Math.abs(peak) >= cfg.minOverRest * wobble && sameWay >= Math.ceil(moves.length * 0.66)) {
      found.push({ index: c, name: names[c] ?? `channel ${c}`, rest: mu, peak, z })
    }
  }
  if (!found.length) return { error: 'Mouna could not see that movement clearly. Try a bigger one, or another one.' }
  found.sort((a, b) => b.z - a.z)
  return { channels: found.slice(0, cfg.maxChannels) }
}

/** 0 at rest, 1 at the taught movement (mean over the chosen channels, each clamped to [-1, 2]). */
export function activation(model: SwitchModel, f: ArrayLike<number>): number {
  let s = 0
  for (const ch of model.channels) s += Math.max(-1, Math.min(2, (f[ch.index] - ch.rest) / ch.peak))
  return model.channels.length ? s / model.channels.length : 0
}

export class PersonalSwitch {
  readonly cfg: SwitchConfig
  readonly model: SwitchModel
  private aboveSince = -1
  private latched = false
  private quietUntil = -Infinity
  level = 0

  constructor(model: SwitchModel, cfg: SwitchConfig = DEFAULT_SWITCH) {
    this.model = model
    this.cfg = cfg
  }

  /** Feed one frame of blendshape scores. Returns true once per press, on the frame it is recognised. */
  push(f: ArrayLike<number>, tMs: number): boolean {
    const c = this.cfg
    this.level = activation(this.model, f)
    if (this.level < c.off) {
      this.latched = false
      this.aboveSince = -1
      return false
    }
    if (this.latched || tMs < this.quietUntil || this.level < c.on) return false
    if (this.aboveSince < 0) this.aboveSince = tMs
    if (tMs - this.aboveSince < c.holdMs) return false
    this.latched = true
    this.quietUntil = tMs + c.refractoryMs
    return true
  }
}
