import type { ClipStats, QualityIssue } from './gate'
import type { Lang } from './phrases'

/**
 * teach     taught in Teach mode, or a Speak-mode result the user corrected with a tap
 * protocol  recorded by the protocol grid; the harness splits teach/test by session and rep
 * idle      a gate trigger while the person was not speaking (false-trigger test)
 * idle_span no frames; stats.durationMs is how long the idle test ran
 * untaught  a word outside the protocol phrases (protocol step 2): "none of these" examples and open-set tests
 */
export type ClipKind = 'teach' | 'protocol' | 'idle' | 'idle_span' | 'untaught'

/** One push-to-talk recording. Frames without a detected face are counted in stats but not stored. */
export interface Clip {
  id: string
  participant: string
  session: string
  kind: ClipKind
  /** Phrase id; null for idle or for an unlabelled live test. */
  phrase: string | null
  /** 1-based repetition within (participant, session, phrase). */
  rep: number
  spokenLang: Lang
  createdAt: number
  /** ms since the first stored frame. */
  t: number[]
  features: Float32Array[]
  aperture: number[]
  stats: ClipStats
  issues: QualityIssue[]
  /** Concatenated CROP x CROP grayscale frames, one per stored frame. */
  crops: Uint8Array | null
}

export interface ClipBuilderFrame {
  t: number
  face: boolean
  features?: Float32Array
  aperture?: number
  iod?: number
  yaw?: number
  motion?: number
  crop?: Uint8Array
}

const median = (xs: number[]) => {
  if (!xs.length) return 0
  const s = [...xs].sort((a, b) => a - b)
  return s[Math.floor(s.length / 2)]
}

/** Accumulates frames while the button is held, then summarises them. */
export class ClipBuilder {
  private frames: ClipBuilderFrame[] = []
  readonly startedAt = performance.now()

  push(f: ClipBuilderFrame): void {
    this.frames.push(f)
  }

  get length(): number {
    return this.frames.length
  }

  finish(): Omit<Clip, 'id' | 'participant' | 'session' | 'kind' | 'phrase' | 'rep' | 'spokenLang' | 'createdAt' | 'issues'> {
    const withFace = this.frames.filter((f) => f.face && f.features)
    const t0 = withFace[0]?.t ?? 0
    const first = this.frames[0]?.t ?? 0
    const last = this.frames[this.frames.length - 1]?.t ?? 0
    const cropFrames = withFace.filter((f) => f.crop)
    let crops: Uint8Array | null = null
    if (cropFrames.length === withFace.length && cropFrames.length > 0) {
      const size = cropFrames[0].crop!.length
      crops = new Uint8Array(size * cropFrames.length)
      cropFrames.forEach((f, i) => crops!.set(f.crop!, i * size))
    }
    const stats: ClipStats = {
      durationMs: last - first,
      frames: this.frames.length,
      faceRatio: this.frames.length ? withFace.length / this.frames.length : 0,
      medianIod: median(withFace.map((f) => f.iod ?? 0)),
      maxAbsYaw: Math.max(0, ...withFace.map((f) => Math.abs(f.yaw ?? 0))),
      motionEnergy: withFace.reduce((a, f) => a + (f.motion ?? 0), 0),
    }
    return {
      t: withFace.map((f) => f.t - t0),
      features: withFace.map((f) => f.features!),
      aperture: withFace.map((f) => f.aperture ?? 0),
      stats,
      crops,
    }
  }
}
