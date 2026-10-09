/**
 * Lip-activity gate: cheap, per-frame, decides whether the lips are "speaking".
 * Score = smoothed lip speed plus how far the aperture is from the resting aperture.
 * Hysteresis and a hangover stop it flickering between syllables.
 */

export interface GateConfig {
  /** Score above which the gate opens. */
  on: number
  /** Score below which the gate may close. */
  off: number
  /** Frames the score must stay below `off` before closing. */
  hangover: number
  /** EMA factor for the score. */
  smoothing: number
  /** EMA factor for the resting aperture (only updated while closed). */
  restRate: number
}

export const DEFAULT_GATE: GateConfig = { on: 0.06, off: 0.035, hangover: 9, smoothing: 0.35, restRate: 0.02 }

export class ActivityGate {
  readonly cfg: GateConfig
  score = 0
  open = false
  private rest = -1
  private quiet = 0

  constructor(cfg: GateConfig = DEFAULT_GATE) {
    this.cfg = cfg
  }

  /** @param motion lip speed in iod units per frame  @param aperture inner opening / width */
  push(motion: number, aperture: number): boolean {
    const { on, off, hangover, smoothing, restRate } = this.cfg
    if (this.rest < 0) this.rest = aperture
    const raw = motion * 10 + Math.abs(aperture - this.rest)
    this.score += smoothing * (raw - this.score)

    if (!this.open) {
      this.rest += restRate * (aperture - this.rest)
      if (this.score > on) {
        this.open = true
        this.quiet = 0
      }
    } else if (this.score < off) {
      if (++this.quiet >= hangover) this.open = false
    } else {
      this.quiet = 0
    }
    return this.open
  }

  reset(): void {
    this.score = 0
    this.open = false
    this.rest = -1
    this.quiet = 0
  }
}

export interface ClipStats {
  durationMs: number
  frames: number
  faceRatio: number
  medianIod: number
  maxAbsYaw: number
  motionEnergy: number
}

export interface QualityLimits {
  minDurationMs: number
  maxDurationMs: number
  minFaceRatio: number
  minIodPx: number
  maxYawDeg: number
  minMotionEnergy: number
}

export const DEFAULT_LIMITS: QualityLimits = {
  minDurationMs: 400,
  maxDurationMs: 4500,
  minFaceRatio: 0.9,
  minIodPx: 55,
  maxYawDeg: 25,
  minMotionEnergy: 0.12,
}

export type QualityIssue = 'too_short' | 'too_long' | 'face_lost' | 'too_far' | 'head_turned' | 'lips_still'

export const ISSUE_TEXT: Record<QualityIssue, string> = {
  too_short: 'Too short. Hold the button for the whole phrase.',
  too_long: 'Too long. Release after the lips close.',
  face_lost: 'Face lost. Keep the whole face in view.',
  too_far: 'Too far. Move the phone closer (30–45 cm).',
  head_turned: 'Head turned. Face the lens.',
  lips_still: 'Lips barely moved. Mouth the words clearly, as if whispering.',
}

export function checkQuality(s: ClipStats, lim: QualityLimits = DEFAULT_LIMITS): QualityIssue[] {
  const issues: QualityIssue[] = []
  if (s.durationMs < lim.minDurationMs) issues.push('too_short')
  if (s.durationMs > lim.maxDurationMs) issues.push('too_long')
  if (s.faceRatio < lim.minFaceRatio) issues.push('face_lost')
  if (s.medianIod < lim.minIodPx) issues.push('too_far')
  if (s.maxAbsYaw > lim.maxYawDeg) issues.push('head_turned')
  if (s.motionEnergy < lim.minMotionEnergy) issues.push('lips_still')
  return issues
}
