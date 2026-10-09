/**
 * Export format `mouna-lab/1`, read by harness/mouna_harness/io.py.
 *
 *   <participant>_<session>.mouna.json   metadata, timestamps, lip features per frame
 *   <participant>_<session>.crops.bin    optional: raw 96x96 grayscale crops, concatenated;
 *                                        each clip records its byte offset and frame count
 */
import { LIPS } from '../core/lips'
import { CROP } from '../vision/crop'
import { getCrops, listClips } from './db'

const round = (v: number) => Math.round(v * 1e4) / 1e4

function download(name: string, blob: Blob): void {
  const url = URL.createObjectURL(blob)
  const a = Object.assign(document.createElement('a'), { href: url, download: name })
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export async function exportSession(participant: string, session: string, withCrops: boolean): Promise<number> {
  const clips = await listClips(participant, session)
  const base = `${participant}_${session}`
  const parts: Uint8Array[] = []
  let offset = 0

  const rows = []
  for (const c of clips) {
    let cropOffset: number | null = null
    if (withCrops) {
      const crops = await getCrops(c.id)
      if (crops) {
        cropOffset = offset
        parts.push(crops)
        offset += crops.length
      }
    }
    rows.push({
      id: c.id,
      kind: c.kind,
      phrase: c.phrase,
      rep: c.rep,
      spokenLang: c.spokenLang,
      createdAt: c.createdAt,
      t: c.t.map((v) => Math.round(v * 10) / 10),
      features: c.features.map((f) => Array.from(f, round)),
      aperture: c.aperture.map(round),
      stats: c.stats,
      issues: c.issues,
      cropOffset,
      cropFrames: cropOffset === null ? 0 : c.features.length,
    })
  }

  const doc = {
    format: 'mouna-lab/1',
    exportedAt: new Date().toISOString(),
    participant,
    session,
    featureDim: LIPS.length * 2,
    landmarks: LIPS,
    crop: withCrops && offset > 0 ? { size: CROP, file: `${base}.crops.bin` } : null,
    clips: rows,
  }
  download(`${base}.mouna.json`, new Blob([JSON.stringify(doc)], { type: 'application/json' }))
  if (withCrops && offset > 0) download(`${base}.crops.bin`, new Blob(parts as BlobPart[], { type: 'application/octet-stream' }))
  return clips.length
}
