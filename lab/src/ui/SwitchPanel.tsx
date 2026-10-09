import { useEffect, useRef, useState } from 'react'
import { calibrateGaze, GazeSelector, type GazeModel, type Zone } from '../core/gaze'
import { PersonalSwitch, teachSwitch, type SwitchModel } from '../core/switch'
import { engine, type Who } from '../state'
import type { FaceSignals } from '../engine'

/**
 * Personal switch and look-to-choose, plus the E7 trials on a real face (PLAN.md): false presses while mouthing,
 * deliberate presses detected, and left/right picks. Results download as JSON for deck/data.
 */
type Phase =
  | { k: 'idle' }
  | { k: 'rest'; until: number }
  | { k: 'cue'; n: number; at: number } // the cue fires at `at`, the window runs 1.5 s after it
  | { k: 'gaze'; look: 'center' | 'left' | 'right'; at: number }
  | { k: 'falseTest'; until: number }
  | { k: 'hitTest' }
  | { k: 'gazeTest'; i: number; want: 'left' | 'right'; at: number }

const REST_MS = 4000
const CUE_LEAD_MS = 1200
const CUE_WINDOW_MS = 1500
const GAZE_MS = 2000
const FALSE_TEST_MS = 60000
const GAZE_TRIALS = 10
const GAZE_TIMEOUT_MS = 5000

interface Results {
  participant: string
  session: string
  switchChannels: string[]
  falsePresses?: { presses: number; seconds: number; mouthingSeconds: number }
  hits?: { presses: number; asked: number }
  gaze?: { right: number; wrong: number; timeout: number; trials: number; meanMs: number | null }
}

export function SwitchPanel({ who }: { who: Who }) {
  const [phase, setPhase] = useState<Phase>({ k: 'idle' })
  const [model, setModel] = useState<SwitchModel | null>(null)
  const [gaze, setGaze] = useState<GazeModel | null>(null)
  const [msg, setMsg] = useState('')
  const [level, setLevel] = useState(0)
  const [zone, setZone] = useState<Zone>('center')
  const [presses, setPresses] = useState(0)
  const [results, setResults] = useState<Results>({ participant: who.participant, session: who.session, switchChannels: [] })

  const buf = useRef({ rest: [] as Float32Array[], moves: [] as Float32Array[][], center: [] as number[], left: [] as number[], right: [] as number[], names: [] as string[] })
  const sw = useRef<PersonalSwitch | null>(null)
  const sel = useRef<GazeSelector | null>(null)
  const phaseRef = useRef(phase)
  phaseRef.current = phase
  const trial = useRef({ presses: 0, mouthingMs: 0, lastT: 0, right: 0, wrong: 0, timeout: 0, ms: [] as number[] })

  useEffect(() => {
    engine.onFace = (f: FaceSignals) => onFrame(f)
    return () => void (engine.onFace = null)
  })

  function onFrame(f: FaceSignals) {
    const p = phaseRef.current
    const b = buf.current
    b.names = f.names
    const dt = trial.current.lastT ? f.t - trial.current.lastT : 0
    trial.current.lastT = f.t
    if (p.k === 'rest') {
      b.rest.push(f.blend)
      if (f.t >= p.until) finishRest()
    } else if (p.k === 'cue') {
      if (f.t >= p.at) b.moves[p.n].push(f.blend)
      if (f.t >= p.at + CUE_WINDOW_MS) nextCue(p.n + 1, f.t)
    } else if (p.k === 'gaze') {
      if (f.t >= p.at + 600) b[p.look].push(f.iris) // let the eyes settle first
      if (f.t >= p.at + GAZE_MS) nextGaze(p.look, f.t)
    }
    const s = sw.current
    if (s) {
      const pressed = s.push(f.blend, f.t)
      setLevel(s.level)
      if (pressed) {
        setPresses((n) => n + 1)
        if (p.k === 'falseTest' || p.k === 'hitTest') trial.current.presses++
      }
    }
    if (p.k === 'falseTest') {
      if (f.mouthing) trial.current.mouthingMs += dt
      if (f.t >= p.until) finishFalseTest()
    }
    const g = sel.current
    if (g) {
      const pick = g.push(f.iris, f.t)
      setZone(g.zone)
      if (p.k === 'gazeTest') {
        if (pick) {
          trial.current[pick === p.want ? 'right' : 'wrong']++
          trial.current.ms.push(f.t - p.at)
          nextGazeTrial(p.i + 1, f.t)
        } else if (f.t - p.at > GAZE_TIMEOUT_MS) {
          trial.current.timeout++
          nextGazeTrial(p.i + 1, f.t)
        }
      }
    }
  }

  // ----- teaching the switch -----
  const startRest = () => {
    buf.current.rest = []
    buf.current.moves = [[], [], []]
    setMsg('Normal face. Mouth a few words, blink, look around.')
    setPhase({ k: 'rest', until: performance.now() + REST_MS })
  }
  const finishRest = () => nextCue(0, performance.now())
  const nextCue = (n: number, now: number) => {
    if (n < 3) {
      setMsg(`Get ready… then do your movement (${n + 1} of 3)`)
      setPhase({ k: 'cue', n, at: now + CUE_LEAD_MS })
      return
    }
    const m = teachSwitch(buf.current.rest, buf.current.moves, buf.current.names)
    setPhase({ k: 'idle' })
    if ('error' in m) return setMsg(m.error)
    setModel(m)
    sw.current = new PersonalSwitch(m)
    setPresses(0)
    const names = m.channels.map((c) => c.name)
    setResults((r) => ({ ...r, switchChannels: names }))
    setMsg(`Learned. Mouna watches: ${names.join(', ')}`)
  }

  // ----- calibrating gaze -----
  const startGaze = () => {
    Object.assign(buf.current, { center: [], left: [], right: [] })
    setMsg('Look at the middle of the screen')
    setPhase({ k: 'gaze', look: 'center', at: performance.now() })
  }
  const nextGaze = (done: 'center' | 'left' | 'right', now: number) => {
    const order = ['center', 'left', 'right'] as const
    const i = order.indexOf(done)
    if (i < 2) {
      setMsg(`Look at the ${order[i + 1]} picture (eyes only, if you can)`)
      setPhase({ k: 'gaze', look: order[i + 1], at: now })
      return
    }
    setPhase({ k: 'idle' })
    const g = calibrateGaze(buf.current.center, buf.current.left, buf.current.right)
    if ('error' in g) return setMsg(g.error)
    setGaze(g)
    sel.current = new GazeSelector(g)
    setMsg('Gaze ready. Look at a picture and hold it to choose.')
  }

  // ----- E7 trials -----
  const startFalseTest = () => {
    Object.assign(trial.current, { presses: 0, mouthingMs: 0 })
    setMsg('Mouth phrases normally for a minute. Do NOT do your movement.')
    setPhase({ k: 'falseTest', until: performance.now() + FALSE_TEST_MS })
  }
  const finishFalseTest = () => {
    const t = trial.current
    setResults((r) => ({ ...r, falsePresses: { presses: t.presses, seconds: FALSE_TEST_MS / 1000, mouthingSeconds: Math.round(t.mouthingMs / 100) / 10 } }))
    setMsg(`False presses while mouthing: ${t.presses} in ${FALSE_TEST_MS / 1000} s`)
    setPhase({ k: 'idle' })
  }
  const startHitTest = () => {
    trial.current.presses = 0
    setMsg('Do your movement 10 times, a second or two apart. Press Done after the tenth.')
    setPhase({ k: 'hitTest' })
  }
  const finishHitTest = () => {
    const n = trial.current.presses
    setResults((r) => ({ ...r, hits: { presses: n, asked: 10 } }))
    setMsg(`Detected ${n} of 10`)
    setPhase({ k: 'idle' })
  }
  const startGazeTest = () => {
    Object.assign(trial.current, { right: 0, wrong: 0, timeout: 0, ms: [] })
    nextGazeTrial(0, performance.now())
  }
  const nextGazeTrial = (i: number, now: number) => {
    if (i >= GAZE_TRIALS) {
      const t = trial.current
      const meanMs = t.ms.length ? Math.round(t.ms.reduce((a, b) => a + b, 0) / t.ms.length) : null
      setResults((r) => ({ ...r, gaze: { right: t.right, wrong: t.wrong, timeout: t.timeout, trials: GAZE_TRIALS, meanMs } }))
      setMsg(`Gaze: ${t.right} right, ${t.wrong} wrong, ${t.timeout} timed out`)
      setPhase({ k: 'idle' })
      return
    }
    const want = Math.random() < 0.5 ? 'left' : 'right'
    setMsg(`Trial ${i + 1} of ${GAZE_TRIALS}: look at the ${want} picture`)
    setPhase({ k: 'gazeTest', i, want, at: now + 300 })
  }

  const download = () => {
    const blob = new Blob([JSON.stringify({ format: 'mouna-switch/1', generated: new Date().toISOString(), ...results }, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${who.participant}_${who.session}.switch.json`
    a.click()
    URL.revokeObjectURL(a.href)
  }

  const busy = phase.k !== 'idle' && phase.k !== 'hitTest'
  const want = phase.k === 'gazeTest' ? phase.want : null
  return (
    <div className="panel">
      <header>
        <h1>
          Your <em>switch</em>, your eyes.
        </h1>
        <p>Teach any movement you can repeat as your yes: an eyebrow, a half smile, a cheek puff. Then choose between two pictures by looking.</p>
      </header>

      <section className="prompt" aria-live="polite">
        <span className="label">{model ? `switch · ${presses} presses` : 'switch · not taught'}</span>
        <div className="say" style={{ fontSize: '1.4rem' }}>
          {msg || 'Start with your normal face.'}
        </div>
        {model && (
          <div style={{ height: 10, background: 'var(--line, #ddd)', borderRadius: 5, overflow: 'hidden' }} aria-label="switch level">
            <div style={{ width: `${Math.max(0, Math.min(1, level / 1.2)) * 100}%`, height: '100%', background: level >= 0.6 ? 'var(--good, #2a7)' : 'var(--ink, #555)' }} />
          </div>
        )}
      </section>

      <div className="row">
        <button className="btn" onClick={startRest} disabled={busy}>
          {model ? 'Re-teach switch' : 'Teach my switch'}
        </button>
        <button className="btn" onClick={startGaze} disabled={busy}>
          {gaze ? 'Re-calibrate eyes' : 'Calibrate eyes'}
        </button>
      </div>

      {gaze && (
        <div className="row" style={{ justifyContent: 'space-between' }} aria-label="two pictures">
          {(['left', 'right'] as const).map((side) => (
            <div
              key={side}
              style={{
                flex: 1,
                height: 110,
                display: 'grid',
                placeItems: 'center',
                border: `3px solid ${zone === side ? 'var(--good, #2a7)' : 'var(--line, #ccc)'}`,
                borderRadius: 12,
                fontWeight: want === side ? 700 : 400,
              }}
            >
              {side === 'left' ? '◀ left' : 'right ▶'}
            </div>
          ))}
        </div>
      )}

      <section style={{ display: 'grid', gap: 8 }}>
        <span className="label">E7 trials · real face</span>
        <div className="row">
          <button className="ghost" onClick={startFalseTest} disabled={!model || busy}>
            False presses (1 min mouthing)
          </button>
          {phase.k === 'hitTest' ? (
            <button className="btn" onClick={finishHitTest}>
              Done ({trial.current.presses})
            </button>
          ) : (
            <button className="ghost" onClick={startHitTest} disabled={!model || busy}>
              Detected of 10
            </button>
          )}
          <button className="ghost" onClick={startGazeTest} disabled={!gaze || busy}>
            Gaze, 10 trials
          </button>
          <button className="ghost" onClick={download} disabled={!results.falsePresses && !results.hits && !results.gaze}>
            Download results
          </button>
        </div>
        <table className="plain num">
          <tbody>
            <tr>
              <td>false presses</td>
              <td>{results.falsePresses ? `${results.falsePresses.presses} in ${results.falsePresses.seconds} s (${results.falsePresses.mouthingSeconds} s mouthing)` : '—'}</td>
            </tr>
            <tr>
              <td>detected</td>
              <td>{results.hits ? `${results.hits.presses} / ${results.hits.asked}` : '—'}</td>
            </tr>
            <tr>
              <td>gaze</td>
              <td>{results.gaze ? `${results.gaze.right} right · ${results.gaze.wrong} wrong · ${results.gaze.timeout} timeout · ${results.gaze.meanMs ?? '—'} ms` : '—'}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
  )
}
