/**
 * The LipLearner encoder in the browser (ONNX Runtime Web): int8, fixed 48-frame window, 86 MB, served from this
 * site. WebGPU when the browser has it, WebAssembly otherwise; Vite bundles ORT's runtime files with the app.
 * Optional: absent model -> lip geometry only.
 */
import * as ort from 'onnxruntime-web/webgpu'
import { encoderInput, STATIC_FRAMES } from '../core/encoderInput'

const BASE = import.meta.env.BASE_URL
const MODEL = `${BASE}models/encoder.int8.onnx`

export interface Encoder {
  backend: string
  embed(crops: Uint8Array, t: number[]): Promise<{ embedding: Float32Array; ms: number }>
}

let loading: Promise<Encoder | null> | null = null
let present: Promise<boolean> | null = null

/** Whether this site ships the encoder model (it is optional; the hosted Lab may run lip geometry only). */
export function encoderAvailable(): Promise<boolean> {
  present ??= fetch(MODEL, { method: 'HEAD' })
    .then((r) => r.ok && (r.headers.get('content-type') ?? '').indexOf('text/html') < 0)
    .catch(() => false)
  return present
}

export function loadEncoder(): Promise<Encoder | null> {
  loading ??= (async () => {
    if (!(await encoderAvailable())) return null
    ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 1) : 1
    let session: ort.InferenceSession | null = null
    let backend = 'wasm'
    if ('gpu' in navigator) {
      session = await ort.InferenceSession.create(MODEL, { executionProviders: ['webgpu'] }).catch(() => null)
      if (session) backend = 'webgpu'
    }
    session ??= await ort.InferenceSession.create(MODEL, { executionProviders: ['wasm'] })
    const s = session
    return {
      backend,
      async embed(crops, t) {
        const t0 = performance.now()
        const input = new ort.Tensor('float32', encoderInput(crops, t), [STATIC_FRAMES, 5, 88, 88])
        const out = await s.run({ frames: input })
        const embedding = Float32Array.from(out.embedding.data as Float32Array)
        return { embedding, ms: performance.now() - t0 }
      },
    }
  })()
  return loading
}
