/**
 * Nod for yes, shake for no: answers that need no hands and no voice, for Ask mode.
 * Head pose comes from the face landmarks already tracked for the lips; nothing else is computed.
 */
import type { Pt } from './lips'

const EYE_R = 33
const EYE_L = 263
const NOSE_TIP = 1

/**
 * Head pitch proxy: how far the nose tip sits below the eye line, in eye distances. It rises when the head tips
 * down and falls when it tips up; only its swings matter, not its absolute value.
 */
export function pitchProxy(lm: ArrayLike<Pt>, w: number, h: number): number {
  const r = { x: lm[EYE_R].x * w, y: lm[EYE_R].y * h }
  const l = { x: lm[EYE_L].x * w, y: lm[EYE_L].y * h }
  const n = { x: lm[NOSE_TIP].x * w, y: lm[NOSE_TIP].y * h }
  const iod = Math.hypot(l.x - r.x, l.y - r.y)
  if (iod === 0) return 0
  const roll = Math.atan2(l.y - r.y, l.x - r.x)
  const mx = (r.x + l.x) / 2
  const my = (r.y + l.y) / 2
  return (-(n.x - mx) * Math.sin(roll) + (n.y - my) * Math.cos(roll)) / iod
}

export type Gesture = 'nod' | 'shake'

export interface GestureConfig {
  /** Swing needed on each side of the resting pose: degrees of yaw, eye distances of pitch. */
  yawDeg: number
  pitch: number
  /** A nod (down and back) or a shake (one way and the other) must happen within this window. */
  windowMs: number
  /** The other axis must stay below this fraction of its own threshold, so a diagonal wobble is neither. */
  crossRatio: number
  refractoryMs: number
}

export const DEFAULT_GESTURE: GestureConfig = { yawDeg: 8, pitch: 0.045, windowMs: 1400, crossRatio: 0.8, refractoryMs: 1500 }

interface Sample {
  t: number
  yaw: number
  pitch: number
}

export class HeadGesture {
  readonly cfg: GestureConfig
  private rest: { yaw: number; pitch: number } | null = null
  private buf: Sample[] = []
  private quietUntil = -Infinity

  constructor(cfg: GestureConfig = DEFAULT_GESTURE) {
    this.cfg = cfg
  }

  /** Feed one frame. Returns a gesture on the frame it completes. */
  push(yaw: number, pitch: number, t: number): Gesture | null {
    const c = this.cfg
    if (!this.rest) this.rest = { yaw, pitch }
    const dy = yaw - this.rest.yaw
    const dp = pitch - this.rest.pitch
    // the resting pose follows slow drift, but not the swings themselves
    if (Math.abs(dy) < c.yawDeg / 2 && Math.abs(dp) < c.pitch / 2) {
      this.rest.yaw += 0.05 * dy
      this.rest.pitch += 0.05 * dp
    }
    this.buf.push({ t, yaw: dy, pitch: dp })
    while (this.buf.length && t - this.buf[0].t > c.windowMs) this.buf.shift()
    if (t < this.quietUntil) return null

    const xs = (axis: 'yaw' | 'pitch') => this.buf.map((s) => s[axis])
    const range = (axis: 'yaw' | 'pitch') => Math.max(...xs(axis).map(Math.abs))
    let g: Gesture | null = null
    if (swungBothWays(xs('yaw'), c.yawDeg) && range('pitch') < c.pitch * 2) g = 'shake'
    else if (wentAndCameBack(xs('pitch'), c.pitch) && range('yaw') < c.yawDeg * c.crossRatio) g = 'nod'
    if (g) {
      this.quietUntil = t + c.refractoryMs
      this.buf = []
    }
    return g
  }
}

/** True when the signal went past the limit (either way) and then came back near rest. */
function wentAndCameBack(xs: number[], limit: number): boolean {
  const out = xs.findIndex((x) => Math.abs(x) > limit)
  return out >= 0 && xs.slice(out + 1).some((x) => Math.abs(x) < limit / 3)
}

/** True when the signal went past +limit and past -limit (in either order) within the buffer. */
function swungBothWays(xs: number[], limit: number): boolean {
  let up = -1
  let down = -1
  xs.forEach((x, i) => {
    if (x > limit && up < 0) up = i
    if (x < -limit && down < 0) down = i
  })
  return up >= 0 && down >= 0
}
