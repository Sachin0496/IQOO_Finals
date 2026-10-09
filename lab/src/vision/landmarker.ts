import { FaceLandmarker, FilesetResolver } from '@mediapipe/tasks-vision'

export type Delegate = 'GPU' | 'CPU'

const BASE = import.meta.env.BASE_URL

/**
 * Loads MediaPipe Face Landmarker from files vendored into /public (scripts/vendor.mjs),
 * so the Lab runs with the network off once it has been served. GPU first, CPU if that fails.
 */
export async function createLandmarker(prefer: Delegate = 'GPU'): Promise<{ landmarker: FaceLandmarker; delegate: Delegate }> {
  const fileset = await FilesetResolver.forVisionTasks(`${BASE}wasm`)
  const order: Delegate[] = prefer === 'GPU' ? ['GPU', 'CPU'] : ['CPU']
  let lastError: unknown
  for (const delegate of order) {
    try {
      const landmarker = await FaceLandmarker.createFromOptions(fileset, {
        baseOptions: { modelAssetPath: `${BASE}models/face_landmarker.task`, delegate },
        runningMode: 'VIDEO',
        numFaces: 1,
        outputFaceBlendshapes: true, // 52 expression scores: the personal switch (core/switch.ts)
        minFaceDetectionConfidence: 0.5,
        minFacePresenceConfidence: 0.5,
        minTrackingConfidence: 0.5,
      })
      return { landmarker, delegate }
    } catch (e) {
      lastError = e
    }
  }
  throw lastError
}
