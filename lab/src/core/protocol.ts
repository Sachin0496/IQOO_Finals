/**
 * In-browser version of the spike protocol (docs/protocol.md), for a quick read on the spot.
 * The harness computes the official numbers from exported files.
 */
import type { Clip } from './clip'
import { FewShotDTW } from './recognizer'
import { toSequence } from './sequence'

export const TEACH_REPS = 3
export const PROTOCOL_REPS = 5

/**
 * Step 2 of the Finale protocol: ten everyday words that are not among the protocol phrases, mouthed once per session in the
 * person's own language. The harness uses half of them as "none of these" examples and tests the other half as
 * untaught words, then swaps (harness/mouna_harness/silent.py).
 */
export const UNTAUGHT_WORDS = ['tea', 'phone', 'doctor', 'hungry', 'sleep', 'light', 'blanket', 'son', 'home', 'pillow']

export interface SplitScore {
  n: number
  top1: number
  top3: number
  accepted: number
  acceptedCorrect: number
}

export interface ProtocolScore {
  sameSession: SplitScore | null
  nextSession: SplitScore | null
  threshold: number
}

const seqOf = (c: Clip) => toSequence(c.t, c.features)

function score(r: FewShotDTW, clips: Clip[]): SplitScore | null {
  if (!clips.length) return null
  const s: SplitScore = { n: clips.length, top1: 0, top3: 0, accepted: 0, acceptedCorrect: 0 }
  for (const c of clips) {
    const d = r.classify(seqOf(c))
    const top = d.ranked.slice(0, 3).map((x) => x.phrase)
    if (top[0] === c.phrase) s.top1++
    if (top.includes(c.phrase!)) s.top3++
    if (d.kind === 'accept') {
      s.accepted++
      if (top[0] === c.phrase) s.acceptedCorrect++
    }
  }
  return s
}

/** Teach on `teachSession` reps 1-3; test on its reps 4-5 and on every rep of `nextSession`. */
export function scoreProtocol(clips: Clip[], teachSession: string, nextSession: string | null): ProtocolScore | null {
  const usable = clips.filter((c) => c.kind === 'protocol' && c.phrase && c.issues.length === 0 && c.features.length > 1)
  const teach = usable.filter((c) => c.session === teachSession && c.rep <= TEACH_REPS)
  if (!teach.length) return null
  const r = new FewShotDTW()
  for (const c of teach) r.add(c.phrase!, seqOf(c))
  const { threshold } = r.calibrate()
  return {
    sameSession: score(r, usable.filter((c) => c.session === teachSession && c.rep > TEACH_REPS)),
    nextSession: nextSession ? score(r, usable.filter((c) => c.session === nextSession)) : null,
    threshold,
  }
}
