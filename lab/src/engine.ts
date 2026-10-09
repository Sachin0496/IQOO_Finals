/**
 * The frame loop: camera -> landmarks -> aligned lips -> activity gate -> (recording) clip.
 * Canvases read `live` every animation frame; React reads a throttled `Snapshot`.
 */
import type { FaceLandmarker, NormalizedLandmark } from '@mediapipe/tasks-vision'
import { ActivityGate } from './core/gate'
import { DoubleBlink, eyeOpenness } from './core/blink'
import { HeadGesture, pitchProxy } from './core/nod'
import { irisPosition } from './core/gaze'
import { ClipBuilder } from './core/clip'
import { analyseFace, INNER, lipMotion, OUTER, type FaceFrame, type Pt } from './core/lips'
import { openCamera, openFile } from './vision/camera'
import { MouthCropper } from './vision/crop'
import { createLandmarker, type Delegate } from './vision/landmarker'

export interface Snapshot {
  status: 'idle' | 'loading' | 'running' | 'error'
  error: string | null
  delegate: Delegate | null
  fps: number
  landmarkMs: number
  face: boolean
  iod: number
  yaw: number
  gateOpen: boolean
  recording: boolean
  recFrames: number
  videoW: number
  videoH: number
}

/** Rolling traces for the lip waveform, newest last. */
export const TRACE_LEN = 180

export interface Live {
  outer: Pt[]
  inner: Pt[]
  frame: FaceFrame | null
  aperture: Float32Array
  score: Float32Array
  open: Uint8Array
  rec: Uint8Array
}

export type AutoClipHandler = (clip: ClipBuilder) => void

/** Per-frame signals for the personal switch and look-to-choose (SwitchPanel). */
export interface FaceSignals {
  t: number
  /** MediaPipe blendshape scores, 52 values, same order as `names`. */
  blend: Float32Array
  names: string[]
  /** core/gaze.ts irisPosition. */
  iris: number
  /** The activity gate thinks the lips are moving. */
  mouthing: boolean
}

/** Nod and shake count only after the lips have been still this long. */
const STILL_MS = 700

const ema = (prev: number, next: number, k = 0.1) => (prev === 0 ? next : prev + k * (next - prev))

export class Engine {
  readonly video = document.createElement('video')
  readonly cropper = new MouthCropper()
  readonly gate = new ActivityGate()
  readonly live: Live = {
    outer: [],
    inner: [],
    frame: null,
    aperture: new Float32Array(TRACE_LEN),
    score: new Float32Array(TRACE_LEN),
    open: new Uint8Array(TRACE_LEN),
    rec: new Uint8Array(TRACE_LEN),
  }

  private snap: Snapshot = {
    status: 'idle',
    error: null,
    delegate: null,
    fps: 0,
    landmarkMs: 0,
    face: false,
    iod: 0,
    yaw: 0,
    gateOpen: false,
    recording: false,
    recFrames: 0,
    videoW: 0,
    videoH: 0,
  }
  private listeners = new Set<() => void>()
  private landmarker: FaceLandmarker | null = null
  private stream: MediaStream | null = null
  private prevFeatures: Float32Array | null = null
  private lastTs = 0
  private lastEmit = 0
  private running = false
  private builder: ClipBuilder | null = null
  private autoBuilder: ClipBuilder | null = null

  /** When set, the gate segments clips by itself (used for the false-trigger test). */
  onAutoClip: AutoClipHandler | null = null

  /** When set, receives blendshapes and iris position every frame with a face. */
  onFace: ((f: FaceSignals) => void) | null = null
  private blendNames: string[] = []

  /**
   * Blink-to-talk. With `handsFree` on, a double blink starts a clip and the clip ends by itself once the lips
   * have moved and stopped (or after 4.5 s, or after 2.5 s of no lip movement).
   */
  readonly blink = new DoubleBlink()
  handsFree = false
  onHandsFreeStart: (() => void) | null = null
  onHandsFreeEnd: (() => void) | null = null
  private hf: { startedAt: number; sawOpen: boolean } | null = null

  /**
   * Ask mode answers. While set, a double blink or a nod means yes and a shake means no, and a double blink no
   * longer starts a hands-free clip.
   */
  readonly gesture = new HeadGesture()
  private lipsMovedAt = -Infinity
  onAnswer: ((a: 'yes' | 'no', how: 'blink' | 'nod' | 'shake') => void) | null = null

  /**
   * Listen for answers from now on, ignoring the first `graceMs`: the head and eyes are still settling from the
   * mouthing that led to the question, and that movement must not count as a nod or a double blink.
   * Returns the function that stops listening.
   */
  listenForAnswers(fn: (a: 'yes' | 'no', how: 'blink' | 'nod' | 'shake') => void, graceMs = 1200): () => void {
    const from = performance.now() + graceMs
    const handler = (a: 'yes' | 'no', how: 'blink' | 'nod' | 'shake') => {
      if (performance.now() >= from) fn(a, how)
    }
    this.onAnswer = handler
    return () => {
      if (this.onAnswer === handler) this.onAnswer = null
    }
  }

  subscribe = (fn: () => void) => {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }
  getSnapshot = () => this.snap

  private set(patch: Partial<Snapshot>, force = false) {
    this.snap = { ...this.snap, ...patch }
    const now = performance.now()
    if (force || now - this.lastEmit > 120) {
      this.lastEmit = now
      this.listeners.forEach((l) => l())
    }
  }

  /** Starts on the front camera, or on a video file when one is given. */
  async start(prefer: Delegate = 'GPU', file?: File): Promise<void> {
    if (this.running) return
    this.set({ status: 'loading', error: null }, true)
    try {
      if (file) await openFile(this.video, file)
      else this.stream = await openCamera(this.video)
      const { landmarker, delegate } = await createLandmarker(prefer)
      this.landmarker?.close()
      this.landmarker = landmarker
      this.running = true
      this.set({ status: 'running', delegate, videoW: this.video.videoWidth, videoH: this.video.videoHeight }, true)
      this.schedule()
    } catch (e) {
      this.stream?.getTracks().forEach((t) => t.stop())
      this.set({ status: 'error', error: describe(e) }, true)
    }
  }

  stop(): void {
    this.running = false
    this.stream?.getTracks().forEach((t) => t.stop())
    this.landmarker?.close()
    this.landmarker = null
    this.set({ status: 'idle' }, true)
  }

  beginClip(): void {
    this.builder = new ClipBuilder()
    this.set({ recording: true, recFrames: 0 }, true)
  }

  /** Returns the finished builder, or null if nothing was recording. */
  endClip(): ClipBuilder | null {
    const b = this.builder
    this.builder = null
    this.set({ recording: false }, true)
    return b
  }

  private schedule() {
    if (!this.running) return
    const v = this.video as HTMLVideoElement & { requestVideoFrameCallback?: (cb: () => void) => number }
    if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(() => this.tick())
    else requestAnimationFrame(() => this.tick())
  }

  private tick() {
    if (!this.running || !this.landmarker) return
    const now = performance.now()
    if (this.video.readyState >= 2 && now > this.lastTs) {
      const t0 = performance.now()
      const res = this.landmarker.detectForVideo(this.video, now)
      const lmMs = performance.now() - t0
      const fps = this.lastTs ? 1000 / (now - this.lastTs) : 0
      this.lastTs = now
      this.process(now, res.faceLandmarks[0], lmMs, fps)
      const cats = res.faceBlendshapes?.[0]?.categories
      const lm = res.faceLandmarks[0]
      if (this.onFace && cats && lm) {
        if (this.blendNames.length !== cats.length) this.blendNames = cats.map((c) => c.categoryName)
        const blend = Float32Array.from(cats, (c) => c.score)
        this.onFace({ t: now, blend, names: this.blendNames, iris: irisPosition(lm, this.video.videoWidth, this.video.videoHeight), mouthing: this.gate.open })
      }
    }
    this.schedule()
  }

  private process(now: number, lm: NormalizedLandmark[] | undefined, lmMs: number, fps: number) {
    const w = this.video.videoWidth
    const h = this.video.videoHeight
    const L = this.live
    let frame: FaceFrame | null = null
    let motion = 0

    if (lm) {
      frame = analyseFace(lm, w, h)
      motion = this.prevFeatures ? lipMotion(frame.features, this.prevFeatures) : 0
      this.prevFeatures = frame.features
      L.outer = OUTER.map((i) => ({ x: lm[i].x * w, y: lm[i].y * h }))
      L.inner = INNER.map((i) => ({ x: lm[i].x * w, y: lm[i].y * h }))
    } else {
      this.prevFeatures = null
      L.outer = []
      L.inner = []
    }
    L.frame = frame

    const wasOpen = this.gate.open
    const open = frame ? this.gate.push(motion, frame.aperture) : false
    if (lm) this.handsFreeStep(now, eyeOpenness(lm, w, h), open)
    if (open) this.lipsMovedAt = now
    if (lm && frame) {
      const g = this.gesture.push(frame.yaw, pitchProxy(lm, w, h), now)
      // an answer is given with a still mouth: head movement while mouthing (or within a moment of it) is speech
      if (g && !this.builder && now - this.lipsMovedAt > STILL_MS) this.onAnswer?.(g === 'nod' ? 'yes' : 'no', g)
    }
    const crop = frame ? this.cropper.crop(this.video, frame) : undefined

    const sample = {
      t: now,
      face: !!frame,
      features: frame?.features,
      aperture: frame?.aperture,
      iod: frame?.iod,
      yaw: frame?.yaw,
      motion,
      crop,
    }
    this.builder?.push(sample)
    this.autoSegment(wasOpen, open, sample)

    shift(L.aperture, frame?.aperture ?? 0)
    shift(L.score, this.gate.score)
    shift(L.open, open ? 1 : 0)
    shift(L.rec, this.builder ? 1 : 0)

    this.set({
      fps: ema(this.snap.fps, fps),
      landmarkMs: ema(this.snap.landmarkMs, lmMs),
      face: !!frame,
      iod: frame?.iod ?? 0,
      yaw: frame?.yaw ?? 0,
      gateOpen: open,
      recFrames: this.builder?.length ?? 0,
    })
  }

  private handsFreeStep(now: number, openness: number, gateOpen: boolean) {
    const doubleBlink = this.blink.push(openness, now)
    if (doubleBlink && this.onAnswer) return this.onAnswer('yes', 'blink')
    if (!this.handsFree) return
    if (doubleBlink && !this.builder && !this.hf) {
      this.hf = { startedAt: now, sawOpen: false }
      this.onHandsFreeStart?.()
      return
    }
    if (!this.hf) return
    if (gateOpen) this.hf.sawOpen = true
    const elapsed = now - this.hf.startedAt
    if ((this.hf.sawOpen && !gateOpen) || elapsed > 4500 || (!this.hf.sawOpen && elapsed > 2500)) {
      this.hf = null
      this.onHandsFreeEnd?.()
    }
  }

  private autoSegment(wasOpen: boolean, open: boolean, sample: Parameters<ClipBuilder['push']>[0]) {
    if (!this.onAutoClip || this.builder) {
      this.autoBuilder = null
      return
    }
    if (!wasOpen && open) this.autoBuilder = new ClipBuilder()
    this.autoBuilder?.push(sample)
    if (wasOpen && !open && this.autoBuilder) {
      const b = this.autoBuilder
      this.autoBuilder = null
      this.onAutoClip(b)
    }
  }
}

function describe(e: unknown): string {
  const name = e instanceof DOMException ? e.name : ''
  if (name === 'NotAllowedError') return 'Camera permission was refused. Allow the camera for this page and try again.'
  if (name === 'NotFoundError') return 'No camera found on this device.'
  if (name === 'NotReadableError') return 'The camera is busy in another app.'
  if (!window.isSecureContext) return 'The camera needs HTTPS or localhost. On a phone, use npm run dev:lan.'
  return e instanceof Error ? e.message : String(e)
}

function shift(buf: Float32Array | Uint8Array, v: number) {
  buf.copyWithin(0, 1)
  buf[buf.length - 1] = v
}
