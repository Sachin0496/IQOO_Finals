/**
 * Lip geometry from MediaPipe Face Mesh landmarks (478 points).
 *
 * Every frame is reduced to the 40 lip points, expressed in a face-aligned frame:
 * origin at the mouth centre, x along the eye line, unit = inter-ocular distance.
 * That removes head translation, roll and distance to the camera.
 */

export interface Pt {
  x: number
  y: number
}

/** Outer lip ring, clockwise from the right mouth corner (subject's right, image left). */
export const OUTER = [61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146]
/** Inner lip ring, same order. */
export const INNER = [78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95]
export const LIPS = [...OUTER, ...INNER]
export const FEATURE_DIM = LIPS.length * 2

const EYE_R = 33
const EYE_L = 263
const NOSE_TIP = 1
const INNER_TOP = 13
const INNER_BOTTOM = 14
const CORNER_R = 61
const CORNER_L = 291

export interface FaceFrame {
  /** 40 lip points, aligned and scaled (length FEATURE_DIM, interleaved x,y). */
  features: Float32Array
  /** Inner-lip opening / mouth width. 0 when closed. */
  aperture: number
  /** Mouth centre in pixels. */
  center: Pt
  /** Eye-line angle in radians. */
  roll: number
  /** Inter-ocular distance in pixels. */
  iod: number
  /** Rough yaw estimate in degrees from nose offset against the eye midpoint. */
  yaw: number
}

const dist = (a: Pt, b: Pt) => Math.hypot(a.x - b.x, a.y - b.y)

/**
 * @param lm normalised landmarks from MediaPipe (x, y in 0..1)
 * @param w  video width in pixels
 * @param h  video height in pixels
 */
export function analyseFace(lm: ArrayLike<Pt>, w: number, h: number): FaceFrame {
  const px = (i: number): Pt => ({ x: lm[i].x * w, y: lm[i].y * h })

  const eyeR = px(EYE_R)
  const eyeL = px(EYE_L)
  const iod = dist(eyeR, eyeL)
  const roll = Math.atan2(eyeL.y - eyeR.y, eyeL.x - eyeR.x)
  const cos = Math.cos(-roll)
  const sin = Math.sin(-roll)

  let cx = 0
  let cy = 0
  for (const i of OUTER) {
    cx += lm[i].x * w
    cy += lm[i].y * h
  }
  const center = { x: cx / OUTER.length, y: cy / OUTER.length }

  const features = new Float32Array(FEATURE_DIM)
  LIPS.forEach((idx, k) => {
    const dx = lm[idx].x * w - center.x
    const dy = lm[idx].y * h - center.y
    features[2 * k] = (dx * cos - dy * sin) / iod
    features[2 * k + 1] = (dx * sin + dy * cos) / iod
  })

  const width = dist(px(CORNER_R), px(CORNER_L))
  const aperture = width > 0 ? dist(px(INNER_TOP), px(INNER_BOTTOM)) / width : 0

  const eyeMid = { x: (eyeR.x + eyeL.x) / 2, y: (eyeR.y + eyeL.y) / 2 }
  const nose = px(NOSE_TIP)
  // Nose offset along the eye line, relative to half the eye distance; ~0 when frontal.
  const along = ((nose.x - eyeMid.x) * Math.cos(roll) + (nose.y - eyeMid.y) * Math.sin(roll)) / (iod / 2)
  const yaw = (Math.asin(Math.max(-1, Math.min(1, along))) * 180) / Math.PI

  return { features, aperture, center, roll, iod, yaw }
}

/** Mean absolute per-point displacement between two aligned frames (lip speed, iod units). */
export function lipMotion(a: Float32Array, b: Float32Array): number {
  let s = 0
  for (let i = 0; i < a.length; i += 2) s += Math.hypot(a[i] - b[i], a[i + 1] - b[i + 1])
  return s / (a.length / 2)
}
