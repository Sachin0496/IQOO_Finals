// Copies MediaPipe's wasm runtime and fetches the face landmarker model into public/,
// so the Lab is served entirely from this machine (no CDN at runtime).
import { copyFile, mkdir, readdir, stat, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const wasmSrc = join(root, 'node_modules/@mediapipe/tasks-vision/wasm')
const wasmDst = join(root, 'public/wasm')
const modelDst = join(root, 'public/models/face_landmarker.task')
const MODEL_URL = 'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task'

await mkdir(wasmDst, { recursive: true })
for (const f of await readdir(wasmSrc)) await copyFile(join(wasmSrc, f), join(wasmDst, f))

const exists = await stat(modelDst).then((s) => s.size > 0, () => false)
if (!exists) {
  await mkdir(join(root, 'public/models'), { recursive: true })
  const res = await fetch(MODEL_URL)
  if (!res.ok) throw new Error(`model download failed: ${res.status}`)
  await writeFile(modelDst, Buffer.from(await res.arrayBuffer()))
}
console.log('vendor: wasm + face_landmarker.task ready')

// The lip encoder (int8, fixed 48-frame window) is optional: built by encoder/ (python -m mouna_encoder convert +
// quantisation) and copied here when present. Without it the Lab uses the lip-geometry recogniser only.
const encoderSrc = join(root, process.env.MOUNA_NO_ENCODER ? '/nonexistent' : '../encoder/weights/encoder_static48.int8.onnx')
const encoderDst = join(root, 'public/models/encoder.int8.onnx')
if (await stat(encoderSrc).then(() => true, () => false)) {
  await copyFile(encoderSrc, encoderDst)
  console.log('vendor: lip encoder copied')
}
