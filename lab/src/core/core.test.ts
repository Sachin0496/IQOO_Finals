import { describe, expect, it } from 'vitest'
import { analyseFace, FEATURE_DIM, LIPS, type Pt } from './lips'
import { ActivityGate, checkQuality, DEFAULT_LIMITS } from './gate'
import { dtw } from './dtw'
import { centre, resample, toSequence } from './sequence'
import { FewShotDTW, type Seq } from './recognizer'
import { DoubleBlink } from './blink'
import { ContextPrior } from './context'
import { encoderInput } from './encoderInput'
import { PrototypeHead } from './prototype'
import { phraseById, setCustomPhrases, speakable } from './phrases'
import { HeadGesture, pitchProxy } from './nod'
import { answerPhrase, AskSession, ASK_TREE } from './ask'
import { blockId, plainWords, readBlocks, SENTENCES } from './blocks'
import type { Decision } from './recognizer'

/** A fake 478-point face: eyes on a line, lips on an ellipse whose opening we control. */
function fakeFace(open: number, opts: { dx?: number; scale?: number; roll?: number } = {}): Pt[] {
  const { dx = 0, scale = 1, roll = 0 } = opts
  const pts: Pt[] = Array.from({ length: 478 }, () => ({ x: 0, y: 0 }))
  const put = (i: number, x: number, y: number) => {
    const c = Math.cos(roll)
    const s = Math.sin(roll)
    pts[i] = { x: 0.5 + dx + scale * (x * c - y * s), y: 0.5 + scale * (x * s + y * c) }
  }
  put(33, -0.1, -0.15)
  put(263, 0.1, -0.15)
  put(1, 0, -0.05)
  LIPS.forEach((idx, k) => {
    const ring = k < 20 ? 1 : 0.6
    const a = (2 * Math.PI * (k % 20)) / 20
    put(idx, 0.06 * ring * Math.cos(a), 0.1 + (0.02 + open) * ring * Math.sin(a))
  })
  put(61, -0.06, 0.1)
  put(291, 0.06, 0.1)
  put(13, 0, 0.1 - open * 0.6)
  put(14, 0, 0.1 + open * 0.6)
  return pts
}

describe('lips', () => {
  it('is invariant to translation, scale and roll', () => {
    const a = analyseFace(fakeFace(0.02), 1000, 1000)
    const b = analyseFace(fakeFace(0.02, { dx: 0.1, scale: 0.7, roll: 0.3 }), 1000, 1000)
    expect(a.features.length).toBe(FEATURE_DIM)
    for (let i = 0; i < FEATURE_DIM; i++) expect(b.features[i]).toBeCloseTo(a.features[i], 4)
    expect(b.roll).toBeCloseTo(0.3, 4)
    expect(Math.abs(a.yaw)).toBeLessThan(1)
  })

  it('reports a larger aperture when the mouth opens', () => {
    expect(analyseFace(fakeFace(0.04), 640, 480).aperture).toBeGreaterThan(analyseFace(fakeFace(0), 640, 480).aperture)
  })
})

describe('gate', () => {
  it('stays closed at rest, opens on motion, closes after the hangover', () => {
    const g = new ActivityGate()
    for (let i = 0; i < 30; i++) expect(g.push(0.001, 0.05)).toBe(false)
    let opened = false
    for (let i = 0; i < 10; i++) opened = g.push(0.03, 0.2) || opened
    expect(opened).toBe(true)
    let closedAt = -1
    for (let i = 0; i < 40 && closedAt < 0; i++) if (!g.push(0, 0.05)) closedAt = i
    expect(closedAt).toBeGreaterThanOrEqual(g.cfg.hangover - 1)
  })

  it('names every quality issue', () => {
    const issues = checkQuality({ durationMs: 100, frames: 3, faceRatio: 0.5, medianIod: 20, maxAbsYaw: 40, motionEnergy: 0 })
    expect(issues).toEqual(['too_short', 'face_lost', 'too_far', 'head_turned', 'lips_still'])
    const ok = checkQuality({ durationMs: 1500, frames: 40, faceRatio: 1, medianIod: 120, maxAbsYaw: 5, motionEnergy: 1 })
    expect(ok).toEqual([])
    expect(DEFAULT_LIMITS.maxYawDeg).toBe(25)
  })
})

/** A synthetic "phrase": a 1-D trajectory embedded in 4 dims, with optional time stretch and noise. */
function synth(freqs: number[], frames: number, noise = 0, seed = 1): Seq {
  let s = seed
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647 - 0.5) * 2
  return Array.from({ length: frames }, (_, i) => {
    const u = i / frames
    return Float32Array.from(freqs.map((f, d) => Math.sin(2 * Math.PI * f * u + d) + noise * rnd()))
  })
}

describe('dtw', () => {
  it('is zero for identical sequences and small for a time-stretched copy', () => {
    const a = synth([1, 2, 3, 1.5], 50)
    expect(dtw(a, a)).toBe(0)
    const stretched = synth([1, 2, 3, 1.5], 65)
    const other = synth([3, 1, 0.5, 2.5], 50)
    expect(dtw(a, stretched)).toBeLessThan(dtw(a, other) / 3)
  })

  it('returns Infinity for empty input', () => {
    expect(dtw([], synth([1], 5))).toBe(Infinity)
  })
})

describe('sequence', () => {
  it('resamples to 25 fps and removes the mean', () => {
    const t = Array.from({ length: 61 }, (_, i) => i * (1000 / 30))
    const frames = t.map((ms) => Float32Array.from([ms / 1000 + 5]))
    const r = resample(t, frames)
    expect(r.length).toBe(51)
    expect(r[25][0]).toBeCloseTo(6, 3)
    const c = centre(r)
    expect(c.reduce((a, f) => a + f[0], 0)).toBeCloseTo(0, 3)
    expect(toSequence(t, frames).length).toBe(51)
  })
})

describe('FewShotDTW', () => {
  const phrases: Record<string, number[]> = {
    water: [1, 2, 3, 1.5],
    pain: [3, 1, 0.5, 2.5],
    nurse: [2, 2.5, 1, 3.5],
  }

  function taught(): FewShotDTW {
    const r = new FewShotDTW()
    let seed = 1
    for (const [id, f] of Object.entries(phrases))
      for (const len of [48, 52, 56]) r.add(id, synth(f, len, 0.05, seed++))
    r.calibrate()
    return r
  }

  it('accepts each taught phrase from a fresh repetition', () => {
    const r = taught()
    let seed = 100
    for (const [id, f] of Object.entries(phrases)) {
      const d = r.classify(synth(f, 50, 0.05, seed++))
      expect(d.ranked[0].phrase).toBe(id)
      expect(d.kind).toBe('accept')
    }
  })

  it('does not accept something it was never taught', () => {
    const d = taught().classify(synth([0.3, 4, 4, 0.2], 50, 0.05, 7))
    expect(d.kind).not.toBe('accept')
  })

  it('calibrates a finite threshold from leave-one-out distances', () => {
    const { inDist, outDist, threshold } = taught().calibrate()
    expect(inDist.length).toBe(9)
    expect(outDist.length).toBe(9)
    expect(threshold).toBeGreaterThan(Math.max(...inDist) * 0.5)
    expect(threshold).toBeLessThan(Math.min(...outDist))
  })
})

describe('parity with harness/mouna_harness/heads.py', () => {
  it('computes the same DTW distance as the Python harness', () => {
    const a = [[0, 0], [1, 0.5], [2, 1], [1.5, 0.2]].map((r) => Float32Array.from(r))
    const b = [[0, 0.1], [2, 0.9], [1.4, 0.3]].map((r) => Float32Array.from(r))
    expect(dtw(a, b)).toBeCloseTo(0.202636, 5)
  })
})

describe('parity: sequence', () => {
  it('builds the same velocity-augmented sequence as the Python harness', () => {
    const t = [0, 40, 80, 120]
    const f = [[0, 1], [0.5, 1.5], [1.5, 1], [1, 0]].map((r) => Float32Array.from(r))
    const s = toSequence(t, f)
    expect(s.length).toBe(4)
    expect(s[0].length).toBe(4)
    expect(s.reduce((a, r) => a + r.reduce((b, v) => b + Math.abs(v), 0), 0)).toBeCloseTo(27.750000, 4)
  })
})


describe('blink-to-talk', () => {
  /** Openness trace at 30 fps: 0.3 open, 0.05 closed during the given [startMs, endMs) windows. */
  function run(closedWindows: [number, number][], totalMs = 4000) {
    const d = new DoubleBlink()
    const hits: number[] = []
    for (let t = 0; t < totalMs; t += 33) {
      const closed = closedWindows.some(([a, b]) => t >= a && t < b)
      if (d.push(closed ? 0.05 : 0.3, t)) hits.push(t)
    }
    return hits
  }

  it('fires on two quick blinks', () => {
    expect(run([[500, 650], [900, 1050]]).length).toBe(1)
  })

  it('ignores a single blink and slow, natural blinking', () => {
    expect(run([[500, 650]])).toEqual([])
    expect(run([[500, 650], [2500, 2650]])).toEqual([])
  })

  it('ignores eyes closed for a long time', () => {
    expect(run([[500, 1500], [1700, 1800]])).toEqual([])
  })

  it('does not fire twice within the refractory period', () => {
    expect(run([[500, 600], [800, 900], [1100, 1200], [1400, 1500]]).length).toBe(1)
  })
})

describe('context re-ranking', () => {
  const cands = [
    { phrase: 'water', distance: 0.1, score: 0.4 },
    { phrase: 'medicine', distance: 0.11, score: 0.35 },
    { phrase: 'toilet', distance: 0.12, score: 0.25 },
  ]

  it('promotes what this person usually says next', () => {
    const c = new ContextPrior()
    for (let i = 0; i < 4; i++) c.observe('pain', 'medicine')
    expect(c.rerank(cands, 'pain', 12)[0].phrase).toBe('medicine')
  })

  it('leaves the lip order alone without context', () => {
    expect(new ContextPrior().rerank(cands, null, 12).map((r) => r.phrase)).toEqual(['water', 'medicine', 'toilet'])
  })

  it('keeps distances and renormalises scores', () => {
    const r = new ContextPrior().rerank(cands, 'pain', 23)
    expect(r.reduce((a, x) => a + x.score, 0)).toBeCloseTo(1, 6)
    expect(r.find((x) => x.phrase === 'water')!.distance).toBe(0.1)
  })
})

describe('encoder input (parity with encoder/mouna_encoder/preprocess.py)', () => {
  it('matches the Python preprocessing numbers', () => {
    const crops = Uint8Array.from({ length: 7 * 96 * 96 }, (_, i) => i % 251)
    const t = Array.from({ length: 7 }, (_, i) => i * 40)
    const x = encoderInput(crops, t, 6)
    expect(x.length).toBe(6 * 5 * 88 * 88)
    expect(x.reduce((a, v) => a + v, 0)).toBeCloseTo(91155.9531, 0)
    expect(x[((3 * 5 + 2) * 88 + 40) * 88 + 40]).toBeCloseTo(0.330392, 5)
    expect(x[(0 * 88 + 10) * 88 + 70]).toBe(0)
  })
})

describe('prototype head', () => {
  it('recognises a held-out sample of a taught phrase and rejects noise', () => {
    let s = 7
    const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647 - 0.5)
    const centre = { a: Float32Array.from({ length: 32 }, rnd), b: Float32Array.from({ length: 32 }, rnd), c: Float32Array.from({ length: 32 }, rnd) }
    const jitter = (v: Float32Array) => v.map((x) => x + 0.05 * rnd())
    const h = new PrototypeHead()
    for (const [p, v] of Object.entries(centre)) for (let i = 0; i < 3; i++) h.add(p, jitter(v))
    h.calibrate()
    const d = h.classify(jitter(centre.b))
    expect(d.ranked[0].phrase).toBe('b')
    expect(d.kind).toBe('accept')
  })
  it('careful mode turns a borderline accept into a choice', () => {
    const h = new PrototypeHead()
    h.add('a', Float32Array.from([1, 0, 0]))
    h.add('b', Float32Array.from([0, 1, 0]))
    h.threshold = 0.1
    const borderline = Float32Array.from([1, 0.35, 0]) // cosine distance ~0.056 from a
    expect(h.classify(borderline).kind).toBe('accept')
    h.strictness = 0.5
    expect(h.classify(borderline).kind).toBe('unsure')
    expect(h.classify(borderline).threshold).toBeCloseTo(0.05)
  })
})

describe('custom phrases', () => {
  it('speaks a missing translation in the language it was written in, never in another script', () => {
    setCustomPhrases([{ id: 'c-1', urgent: false, lang: 'ta', text: { ta: 'விளக்கை அணையுங்கள்', en: 'Turn off the light' } }])
    const p = phraseById('c-1')
    expect(p.custom?.given).toEqual(['en', 'ta'])
    expect(speakable(p, 'en')).toEqual({ text: 'Turn off the light', lang: 'en' })
    expect(speakable(p, 'kn')).toEqual({ text: 'விளக்கை அணையுங்கள்', lang: 'ta' })
    expect(speakable(phraseById('water'), 'kn').lang).toBe('kn')
  })

  it('survives a deleted phrase still referenced by old clips', () => {
    setCustomPhrases([])
    expect(phraseById('c-gone').text.en).toBe('(deleted phrase)')
  })
})

describe('nod and shake', () => {
  // 30 fps; pitch in eye distances, yaw in degrees, both relative to whatever pose the person rests in
  const run = (f: (t: number) => { yaw: number; pitch: number }, ms = 2000) => {
    const g = new HeadGesture()
    const out: string[] = []
    for (let t = 0; t <= ms; t += 33) {
      const { yaw, pitch } = f(t)
      const r = g.push(yaw + 3, pitch + 0.6, t)
      if (r) out.push(r)
    }
    return out
  }
  const bump = (t: number, start: number, len: number, amp: number) => (t > start && t < start + len ? amp * Math.sin((Math.PI * (t - start)) / len) : 0)

  it('a nod (down and back) is yes', () => {
    expect(run((t) => ({ yaw: 0, pitch: bump(t, 500, 500, 0.08) }))).toEqual(['nod'])
  })
  it('a shake (one way, then the other) is no', () => {
    expect(run((t) => ({ yaw: bump(t, 400, 400, 14) - bump(t, 800, 400, 14), pitch: 0 }))).toEqual(['shake'])
  })
  it('turning to look away once is neither', () => {
    expect(run((t) => ({ yaw: t > 500 ? 25 : 0, pitch: 0 }))).toEqual([])
  })
  it('small movements while mouthing are neither', () => {
    expect(run((t) => ({ yaw: 2 * Math.sin(t / 90), pitch: 0.015 * Math.sin(t / 70) }))).toEqual([])
  })
  it('pitch proxy grows as the nose moves down from the eye line', () => {
    const face = (noseY: number) => {
      const pts: Pt[] = Array.from({ length: 478 }, () => ({ x: 0.5, y: 0.5 }))
      pts[33] = { x: 0.4, y: 0.4 }
      pts[263] = { x: 0.6, y: 0.4 }
      pts[1] = { x: 0.5, y: noseY }
      return pts
    }
    expect(pitchProxy(face(0.52), 640, 480)).toBeGreaterThan(pitchProxy(face(0.5), 640, 480))
  })
})

describe('ask mode', () => {
  it('reaches a leaf with yes, yes', () => {
    const s = new AskSession()
    expect(s.current?.id).toBe('pain')
    s.yes()
    expect(s.current?.id).toBe('pain_head')
    s.no()
    s.yes()
    expect(s.result).toEqual({ kind: 'answer', node: ASK_TREE[0].children![1] })
    expect(answerPhrase(s.result!).text.en).toBe('My throat or neck hurts')
    expect(answerPhrase(s.result!).urgent).toBe(true)
  })
  it('a leaf that is a built-in phrase keeps its id, so its natural voice is used', () => {
    const s = new AskSession()
    s.no() // pain
    s.no() // breathing
    s.yes() // drink
    s.yes() // water
    expect(answerPhrase(s.result!).id).toBe('water')
  })
  it('no past the end of a group moves on to the next group', () => {
    const s = new AskSession()
    s.yes()
    for (let i = 0; i < ASK_TREE[0].children!.length; i++) s.no()
    expect(s.current?.id).toBe('breathing')
  })
  it('no to everything ends with "bring the letter board"', () => {
    const s = new AskSession()
    for (let i = 0; i < ASK_TREE.length; i++) s.no()
    expect(s.result).toEqual({ kind: 'none' })
    expect(answerPhrase(s.result!).text.kn).toContain('ಅಕ್ಷರ')
  })
  it('back undoes a group', () => {
    const s = new AskSession()
    s.no()
    s.yes()
    s.back()
    expect(s.current?.id).toBe('breathing')
  })
  it('every question and answer exists in all four languages', () => {
    const walk = (ns: typeof ASK_TREE): void =>
      ns.forEach((n) => {
        for (const l of ['en', 'ta', 'kn', 'hi'] as const) {
          expect(n.ask[l]).toBeTruthy()
          if (n.say) expect(n.say[l]).toBeTruthy()
        }
        if (n.phrase) expect(phraseById(n.phrase).text.en).not.toBe('(deleted phrase)')
        if (n.children) walk(n.children)
      })
    walk(ASK_TREE)
  })
})

describe('blocks to sentences', () => {
  const d = (dist: Record<string, number>, threshold = 0.3): Decision => ({
    kind: 'accept',
    ranked: Object.entries(dist)
      .map(([w, x]) => ({ phrase: blockId(w), distance: x, score: 0 }))
      .sort((a, b) => a.distance - b.distance),
    margin: 2,
    threshold,
    ms: 1,
  })
  it('reads a clear pair as its listed sentence', () => {
    const r = readBlocks([d({ water: 0.1, tea: 0.4, fan: 0.6 }), d({ want: 0.1, more: 0.5, off: 0.7 })])
    expect(r.ranked[0].sentence.phrase.text.en).toBe('I want water')
    expect(r.sure).toBe(true)
  })
  it('the list repairs a block whose own best guess is wrong', () => {
    // second block looks most like "off", but "water off" is not a sentence; "water want" is
    const r = readBlocks([d({ water: 0.1, fan: 0.5 }), d({ off: 0.2, want: 0.25, more: 0.6 })])
    expect(r.words).toEqual([blockId('water'), blockId('off')])
    expect(r.ranked[0].sentence.blocks).toEqual([blockId('water'), blockId('want')])
  })
  it('is not sure when a block is far from everything taught', () => {
    expect(readBlocks([d({ water: 0.1 }), d({ want: 0.9 })]).sure).toBe(false)
  })
  it('plain words keep each language, never an invented sentence', () => {
    const p = plainWords([blockId('fan'), blockId('enough')])
    expect(p.text.en).toBe('fan, enough')
    expect(p.text.kn).toBe('ಫ್ಯಾನ್, ಸಾಕು')
  })
  it('every listed sentence has all four languages', () => {
    for (const s of SENTENCES) for (const l of ['en', 'ta', 'kn', 'hi'] as const) expect(s.phrase.text[l]).toBeTruthy()
  })
})
