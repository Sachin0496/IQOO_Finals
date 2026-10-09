/**
 * Look to choose: two big pictures, left and right; the person looks at one. Only two zones are needed, so the
 * iris position between the eye corners is enough (no fine gaze estimation), calibrated per person in a few seconds.
 */
import type { Pt } from './lips'

/** Iris centres and eye corners in MediaPipe's 478-point face mesh. */
const EYES = [
  { iris: 468, a: 33, b: 133 },
  { iris: 473, a: 362, b: 263 },
]

/**
 * Where the irises sit between the eye corners, averaged over both eyes: 0 at the image-left corner, 1 at the
 * image-right corner. Projected on the corner-to-corner line, so head roll does not move it.
 */
export function irisPosition(lm: ArrayLike<Pt>, w: number, h: number): number {
  let s = 0
  for (const e of EYES) {
    let a = lm[e.a]
    let b = lm[e.b]
    if (a.x > b.x) [a, b] = [b, a]
    const ax = a.x * w
    const ay = a.y * h
    const vx = b.x * w - ax
    const vy = b.y * h - ay
    const len2 = vx * vx + vy * vy
    s += len2 > 0 ? ((lm[e.iris].x * w - ax) * vx + (lm[e.iris].y * h - ay) * vy) / len2 : 0.5
  }
  return s / EYES.length
}

export interface GazeModel {
  center: number
  left: number
  right: number
}

/** Smallest centre-to-side change worth trusting (fraction of the eye width). */
export const MIN_GAZE_SPAN = 0.03

/** Positions recorded while the person looks at the centre, the left picture and the right picture. */
export function calibrateGaze(center: number[], left: number[], right: number[]): GazeModel | { error: string } {
  const med = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)]
  if (center.length < 5 || left.length < 5 || right.length < 5) return { error: 'Look at each picture for a moment.' }
  const m = { center: med(center), left: med(left), right: med(right) }
  if (Math.sign(m.left - m.center) === Math.sign(m.right - m.center)) return { error: 'Left and right looked the same. Try again, moving only the eyes.' }
  if (Math.abs(m.left - m.center) < MIN_GAZE_SPAN || Math.abs(m.right - m.center) < MIN_GAZE_SPAN)
    return { error: 'The eyes moved too little to tell left from right. Move the phone closer.' }
  return m
}

export type Zone = 'left' | 'center' | 'right'

export interface GazeConfig {
  /** A side is entered past this fraction of the way from centre to that side, and left again below `exit`. */
  enter: number
  exit: number
  /** Looking at one side this long selects it. */
  dwellMs: number
  refractoryMs: number
}

export const DEFAULT_GAZE: GazeConfig = { enter: 0.55, exit: 0.35, dwellMs: 800, refractoryMs: 1200 }

export class GazeSelector {
  readonly cfg: GazeConfig
  readonly model: GazeModel
  zone: Zone = 'center'
  private since = 0
  private quietUntil = -Infinity

  constructor(model: GazeModel, cfg: GazeConfig = DEFAULT_GAZE) {
    this.model = model
    this.cfg = cfg
  }

  /** How far towards each side, 0 at centre and 1 at the calibrated side. */
  progress(pos: number): { left: number; right: number } {
    const m = this.model
    return { left: (pos - m.center) / (m.left - m.center), right: (pos - m.center) / (m.right - m.center) }
  }

  /** Feed one frame. Returns the side on the frame it is selected by dwell. */
  push(pos: number, tMs: number): 'left' | 'right' | null {
    const p = this.progress(pos)
    const c = this.cfg
    let next: Zone = this.zone
    if (this.zone === 'center') next = p.left >= c.enter ? 'left' : p.right >= c.enter ? 'right' : 'center'
    else if (p[this.zone] < c.exit) next = 'center'
    if (next !== this.zone) {
      this.zone = next
      this.since = tMs
    }
    if (this.zone === 'center' || tMs < this.quietUntil || tMs - this.since < c.dwellMs) return null
    this.quietUntil = tMs + c.refractoryMs
    this.since = tMs + c.refractoryMs // a held look selects again only after the refractory time and a full dwell
    return this.zone
  }
}
