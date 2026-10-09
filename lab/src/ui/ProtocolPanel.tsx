import { useEffect, useMemo, useRef, useState } from 'react'
import type { Clip } from '../core/clip'
import { ClipBuilder } from '../core/clip'
import { ISSUE_TEXT } from '../core/gate'
import { PROTOCOL_SET, phraseById } from '../core/phrases'
import { PROTOCOL_REPS, scoreProtocol, TEACH_REPS, UNTAUGHT_WORDS, type SplitScore } from '../core/protocol'
import type { FewShotDTW } from '../core/recognizer'
import { toSequence } from '../core/sequence'
import { deleteClip, saveClip } from '../store/db'
import { engine, finalizeClip, type Who } from '../state'
import { PushToTalk } from './Instruments'

interface Props {
  who: Who
  clips: Clip[]
  recognizer: FewShotDTW
  reload: () => void
}

const pct = (n: number, d: number) => (d ? Math.round((100 * n) / d) : 0)

export function ProtocolPanel({ who, clips, recognizer, reload }: Props) {
  const mine = useMemo(() => clips.filter((c) => c.kind === 'protocol' && c.session === who.session && c.issues.length === 0), [clips, who.session])
  const have = (phrase: string, rep: number) => mine.some((c) => c.phrase === phrase && c.rep === rep)
  const untaught = useMemo(() => clips.filter((c) => c.kind === 'untaught' && c.session === who.session && c.issues.length === 0), [clips, who.session])

  // Round-robin: every phrase once, then every phrase again, so fatigue spreads evenly.
  let next: { phrase: string; rep: number } | null = null
  outer: for (let rep = 1; rep <= PROTOCOL_REPS; rep++)
    for (const phrase of PROTOCOL_SET)
      if (!have(phrase, rep)) {
        next = { phrase, rep }
        break outer
      }

  // Step 2, after the grid: each untaught word once.
  const nextWord = next ? null : (UNTAUGHT_WORDS.find((w) => !untaught.some((c) => c.phrase === w)) ?? null)

  const [feedback, setFeedback] = useState<{ text: string; tone: '' | 'good' | 'bad' }>({ text: '', tone: '' })
  const score = useMemo(() => scoreProtocol(clips, 's1', clips.some((c) => c.session === 's2') ? 's2' : null), [clips])

  const onClip = async () => {
    const b = engine.endClip()
    if (!b || (!next && !nextWord)) return
    const clip = next ? finalizeClip(b, who, 'protocol', next.phrase, next.rep) : finalizeClip(b, who, 'untaught', nextWord, 1)
    if (clip.issues.length) return setFeedback({ text: ISSUE_TEXT[clip.issues[0]], tone: 'bad' })
    await saveClip(clip)
    setFeedback({ text: next ? `Kept · ${phraseById(next.phrase).text.en} · rep ${next.rep}` : `Kept · untaught · ${nextWord}`, tone: 'good' })
    reload()
  }

  const undo = async () => {
    const last = [...mine, ...untaught].sort((a, b) => a.createdAt - b.createdAt).pop()
    if (!last) return
    await deleteClip(last.id)
    setFeedback({ text: `Removed · ${last.kind === 'untaught' ? last.phrase : phraseById(last.phrase!).text.en} · rep ${last.rep}`, tone: '' })
    reload()
  }

  const script = who.spokenLang !== 'en'
  const total = PROTOCOL_SET.length * PROTOCOL_REPS
  return (
    <>
      <div className="panel">
        <header>
          <h1>
            The <em>spike</em> protocol.
          </h1>
          <p>
            8 phrases × 5 mouthings, then {UNTAUGHT_WORDS.length} untaught words once each, session <b>{who.session}</b>. Record <b>s1</b> in the
            evening and <b>s2</b> the next morning without re-teaching. Reps 1–{TEACH_REPS} of s1 teach; everything else tests.
          </p>
        </header>

        <section className="prompt" aria-live="polite">
          <span className="label">
            {next
              ? `Mouth · rep ${next.rep} of ${PROTOCOL_REPS} · ${mine.length}/${total}`
              : nextWord
                ? `Step 2 · untaught word ${untaught.length + 1} of ${UNTAUGHT_WORDS.length} · mouth it in your own language`
                : `Session ${who.session} complete · ${total}/${total} + ${UNTAUGHT_WORDS.length} untaught`}
          </span>
          {next && (
            <>
              <div className={`say${script ? ' script' : ''}`}>{phraseById(next.phrase).text[who.spokenLang]}</div>
              {script && <div className="gloss">{phraseById(next.phrase).text.en}</div>}
            </>
          )}
          {!next && nextWord && <div className="say">{nextWord}</div>}
          <div className={`feedback ${feedback.tone}`}>{feedback.text}</div>
        </section>

        <div className="grid" role="table" aria-label="Protocol progress">
          <span className="label name">phrase</span>
          {Array.from({ length: PROTOCOL_REPS }, (_, i) => (
            <span key={i} className="label">
              {i + 1}
            </span>
          ))}
          {PROTOCOL_SET.map((id) => (
            <Row key={id} id={id} have={have} next={next} teachSession={who.session === 's1'} />
          ))}
        </div>

        <div className="row">
          <button className="ghost" onClick={undo} disabled={!mine.length}>
            undo last
          </button>
        </div>

        <Scorecard same={score?.sameSession ?? null} next={score?.nextSession ?? null} />
        <IdleTest who={who} recognizer={recognizer} reload={reload} />
      </div>
      <PushToTalk label={next || nextWord ? 'Hold and mouth' : 'Session complete'} disabled={!next && !nextWord} onClip={onClip} />
    </>
  )
}

function Row({ id, have, next, teachSession }: { id: string; have: (p: string, r: number) => boolean; next: { phrase: string; rep: number } | null; teachSession: boolean }) {
  return (
    <>
      <span className="name">{phraseById(id).text.en}</span>
      {Array.from({ length: PROTOCOL_REPS }, (_, i) => {
        const rep = i + 1
        const cls = ['cell', have(id, rep) ? 'done' : '', next?.phrase === id && next.rep === rep ? 'cur' : '', teachSession && rep <= TEACH_REPS ? 'teach' : '']
        return <span key={rep} className={cls.join(' ')} />
      })}
    </>
  )
}

function Scorecard({ same, next }: { same: SplitScore | null; next: SplitScore | null }) {
  const cell = (title: string, s: SplitScore | null, pass: number) => (
    <div>
      <span className="label">{title}</span>
      <strong className={s ? (pct(s.top1, s.n) >= pass ? 'pass' : 'fail') : ''}>{s ? `${pct(s.top1, s.n)}%` : '—'}</strong>
      <span className="label num">
        {s ? `top-3 ${pct(s.top3, s.n)}% · spoke ${s.accepted}/${s.n} · ${s.acceptedCorrect} right · n ${s.n}` : `pass line ${pass}%`}
      </span>
    </div>
  )
  return (
    <section className="scorecard" aria-label="Protocol score, plan B">
      {cell('Same session · top-1', same, 95)}
      {cell('Next morning · top-1', next, 90)}
    </section>
  )
}

/** Ten minutes of not speaking. Every gate trigger is saved; we also count how many would have been spoken. */
function IdleTest({ who, recognizer, reload }: { who: Who; recognizer: FewShotDTW; reload: () => void }) {
  const [startedAt, setStartedAt] = useState<number | null>(null)
  const [now, setNow] = useState(Date.now())
  const [triggers, setTriggers] = useState(0)
  const [falseSpeaks, setFalseSpeaks] = useState(0)
  const rep = useRef(0)

  useEffect(() => {
    if (startedAt === null) return
    const id = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(id)
  }, [startedAt])

  useEffect(() => () => void (engine.onAutoClip = null), [])

  const start = () => {
    rep.current = 0
    setTriggers(0)
    setFalseSpeaks(0)
    setStartedAt(Date.now())
    engine.onAutoClip = (b: ClipBuilder) => {
      const clip = finalizeClip(b, who, 'idle', null, ++rep.current)
      setTriggers((n) => n + 1)
      if (clip.features.length > 1 && recognizer.classify(toSequence(clip.t, clip.features)).kind === 'accept') setFalseSpeaks((n) => n + 1)
      void saveClip(clip)
    }
  }

  const stop = async () => {
    engine.onAutoClip = null
    if (startedAt === null) return
    const span = finalizeClip(new ClipBuilder(), who, 'idle_span', null, 0)
    span.stats.durationMs = Date.now() - startedAt
    span.issues = []
    await saveClip(span)
    setStartedAt(null)
    reload()
  }

  const mins = startedAt === null ? 0 : (now - startedAt) / 60000
  const rate = (n: number) => (mins > 0.05 ? (n / mins).toFixed(2) : '—')
  return (
    <section style={{ display: 'grid', gap: 10 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <span className="label">False-trigger test · neutral, swallow, yawn, smile, talk off camera</span>
        {startedAt === null ? (
          <button className="btn" onClick={start}>
            Start idle test
          </button>
        ) : (
          <button className="btn danger" onClick={stop}>
            Stop · {Math.floor(mins)}:{String(Math.floor((mins * 60) % 60)).padStart(2, '0')}
          </button>
        )}
      </div>
      {startedAt !== null && (
        <table className="plain num">
          <tbody>
            <tr>
              <td>gate triggers</td>
              <td>{triggers}</td>
              <td>{rate(triggers)} / min</td>
            </tr>
            <tr>
              <td>would have spoken</td>
              <td>{falseSpeaks}</td>
              <td>{rate(falseSpeaks)} / min</td>
            </tr>
          </tbody>
        </table>
      )}
    </section>
  )
}
