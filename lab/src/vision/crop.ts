import type { FaceFrame } from '../core/lips'

/** Side of the square grayscale mouth crop (LRW convention; the encoder centre-crops 88 from it). */
export const CROP = 96
/** Crop side in units of inter-ocular distance. */
export const CROP_IOD = 1.1

/**
 * Cuts an upright, scale-normalised grayscale mouth crop straight from the video frame.
 * One affine draw per frame; the pixels go to the dataset and, later, to the encoder.
 */
export class MouthCropper {
  readonly canvas: OffscreenCanvas | HTMLCanvasElement
  private ctx: OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D

  constructor() {
    this.canvas = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(CROP, CROP) : Object.assign(document.createElement('canvas'), { width: CROP, height: CROP })
    const ctx = this.canvas.getContext('2d', { willReadFrequently: true })
    if (!ctx) throw new Error('2D canvas unavailable')
    this.ctx = ctx as OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D
  }

  /** Draws the crop and returns it as one byte per pixel. */
  crop(video: HTMLVideoElement, f: FaceFrame): Uint8Array {
    const s = CROP / (CROP_IOD * f.iod)
    const ctx = this.ctx
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.translate(CROP / 2, CROP / 2)
    ctx.scale(s, s)
    ctx.rotate(-f.roll)
    ctx.translate(-f.center.x, -f.center.y)
    ctx.drawImage(video, 0, 0)
    const rgba = ctx.getImageData(0, 0, CROP, CROP).data
    const gray = new Uint8Array(CROP * CROP)
    for (let i = 0, p = 0; i < gray.length; i++, p += 4) {
      gray[i] = (rgba[p] * 77 + rgba[p + 1] * 150 + rgba[p + 2] * 29) >> 8
    }
    return gray
  }
}
