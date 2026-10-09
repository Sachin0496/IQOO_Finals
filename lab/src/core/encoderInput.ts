/**
 * Mouth crops -> encoder input, identical to encoder/mouna_encoder/preprocess.py + model.stack_frames
 * (a parity test pins the numbers). Every clip is stretched to a fixed 48-frame window: the static shape the
 * browser and the NPU compile once, and on Kannada it is also the most accurate (86.2% top-1).
 */

export const STATIC_FRAMES = 48
const IN = 96
const ROI = 128
const OUT = 88
const OFF = (ROI - OUT) / 2

/** np.searchsorted(t, v, side="left") */
function searchsorted(t: number[], v: number): number {
  let lo = 0
  let hi = t.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (t[mid] < v) lo = mid + 1
    else hi = mid
  }
  return lo
}

/** Source coordinate for torch's bilinear resize with align_corners=False. */
function axis(n: number): { i0: Int32Array; i1: Int32Array; w: Float32Array } {
  const scale = IN / ROI
  const i0 = new Int32Array(n)
  const i1 = new Int32Array(n)
  const w = new Float32Array(n)
  for (let o = 0; o < n; o++) {
    const src = Math.max(0, scale * (o + OFF + 0.5) - 0.5) // OFF: the centre crop, folded in
    const a = Math.min(Math.floor(src), IN - 1)
    i0[o] = a
    i1[o] = Math.min(a + 1, IN - 1)
    w[o] = src - a
  }
  return { i0, i1, w }
}
const AX = axis(OUT)

/** One 96x96 grey crop -> the 88x88 centre of its 128x128 bilinear upscale, in [0, 1]. */
function frame88(crops: Uint8Array, index: number, out: Float32Array, offset: number): void {
  const base = index * IN * IN
  for (let y = 0; y < OUT; y++) {
    const r0 = base + AX.i0[y] * IN
    const r1 = base + AX.i1[y] * IN
    const wy = AX.w[y]
    for (let x = 0; x < OUT; x++) {
      const c0 = AX.i0[x]
      const c1 = AX.i1[x]
      const wx = AX.w[x]
      const top = crops[r0 + c0] * (1 - wx) + crops[r0 + c1] * wx
      const bottom = crops[r1 + c0] * (1 - wx) + crops[r1 + c1] * wx
      out[offset + y * OUT + x] = (top * (1 - wy) + bottom * wy) / 255
    }
  }
}

/**
 * @param crops concatenated 96x96 grey frames, as the Lab records them
 * @param t     timestamps (ms) of those frames
 * @returns (frames, 5, 88, 88) float32: each frame with its two neighbours either side, zero beyond the ends
 */
export function encoderInput(crops: Uint8Array, t: number[], frames = STATIC_FRAMES): Float32Array {
  const plane = OUT * OUT
  const picked = new Float32Array(frames * plane)
  for (let k = 0; k < frames; k++) {
    const v = frames === 1 ? t[0] : t[0] + ((t[t.length - 1] - t[0]) * k) / (frames - 1)
    const idx = Math.min(searchsorted(t, v), t.length - 1)
    frame88(crops, idx, picked, k * plane)
  }
  const out = new Float32Array(frames * 5 * plane)
  for (let f = 0; f < frames; f++) {
    for (let c = 0; c < 5; c++) {
      const src = f + c - 2
      if (src < 0 || src >= frames) continue
      out.set(picked.subarray(src * plane, (src + 1) * plane), (f * 5 + c) * plane)
    }
  }
  return out
}
