/**
 * Turning a recorded clip into a sequence the recogniser compares.
 * The Python harness implements the same steps (harness/mouna_harness/sequence.py); keep them in sync.
 */

export const TARGET_FPS = 25

/** Linear resampling of per-frame vectors onto a uniform 25 fps grid. */
export function resample(t: number[], frames: Float32Array[], fps = TARGET_FPS): Float32Array[] {
  if (frames.length < 2) return frames.map((f) => f.slice())
  const step = 1000 / fps
  const out: Float32Array[] = []
  const dim = frames[0].length
  let j = 0
  for (let tt = t[0]; tt <= t[t.length - 1]; tt += step) {
    while (j < t.length - 2 && t[j + 1] < tt) j++
    const span = t[j + 1] - t[j]
    const a = span > 0 ? Math.min(1, Math.max(0, (tt - t[j]) / span)) : 0
    const f = new Float32Array(dim)
    for (let d = 0; d < dim; d++) f[d] = frames[j][d] * (1 - a) + frames[j + 1][d] * a
    out.push(f)
  }
  return out
}

/** Subtract the clip's mean lip shape so resting shape and small framing shifts cancel. */
export function centre(frames: Float32Array[]): Float32Array[] {
  if (frames.length === 0) return []
  const dim = frames[0].length
  const mean = new Float32Array(dim)
  for (const f of frames) for (let d = 0; d < dim; d++) mean[d] += f[d] / frames.length
  return frames.map((f) => f.map((v, d) => v - mean[d]))
}

/** Kannada: 71.0% -> 75.3% top-1; weights 6-10 plateau at 75.3-75.7%. */
export const VELOCITY_WEIGHT = 6

/** Append frame-to-frame lip velocity: how the mouth moves, not only where it is. */
export function withVelocity(frames: Float32Array[], weight = VELOCITY_WEIGHT): Float32Array[] {
  return frames.map((f, i) => {
    const out = new Float32Array(f.length * 2)
    out.set(f)
    if (i > 0) for (let d = 0; d < f.length; d++) out[f.length + d] = weight * (f[d] - frames[i - 1][d])
    return out
  })
}

export const toSequence = (t: number[], frames: Float32Array[]): Float32Array[] => withVelocity(centre(resample(t, frames)))
