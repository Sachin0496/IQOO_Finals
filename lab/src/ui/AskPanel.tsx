import { useEffect, useRef, useState } from 'react'
import { answerPhrase, AskSession, type AskNode } from '../core/ask'
import { LANGS, type Lang, type Phrase } from '../core/phrases'
import { link } from '../core/link'
import { speak } from '../audio/speech'
import { engine, usePersisted } from '../state'

const SCAN_MS = 5000

interface Props {
  /** the language the person understands: questions are asked in it */
  patientLang: Lang
  /** the answer, and how many questions it took */
  onDone: (p: Phrase, asked: number) => void
  onCancel: () => void
}

/**
 * Twenty questions on a fixed tree. Yes: nod, blink twice, or tap. No: shake, tap, or (with auto-scan) wait.
 * Each question is read aloud in the person's language and mirrored to a paired caregiver phone.
 */
export function AskPanel({ patientLang, onDone, onCancel }: Props) {
  const session = useRef(new AskSession())
  const [, setTick] = useState(0)
  const [heard, setHeard] = useState('')
  const [autoScan, setAutoScan] = usePersisted('askAutoScan', true)
  const s = session.current
  const node = s.current
  const latin = patientLang === 'en'

  const act = (a: 'yes' | 'no' | 'back', how = 'tap') => {
    if (a === 'yes') s.yes()
    else if (a === 'no') s.no()
    else s.back()
    setHeard(`${how} · ${a}`)
    if (s.result) {
      link.send({ type: 'ask', ask: null, at: Date.now() })
      onDone(answerPhrase(s.result), s.asked)
    } else setTick((t) => t + 1)
  }
  const actRef = useRef(act)
  actRef.current = act

  // read each question aloud and show it on the caregiver's phone
  useEffect(() => {
    if (!node) return
    speak(node.ask[patientLang], patientLang)
    link.send({ type: 'ask', ask: node.ask, at: Date.now() })
  }, [node, patientLang])

  // answers from the face: nod or double blink = yes, shake = no
  // re-armed for every question, so the movement that answered one cannot also answer the next
  useEffect(() => engine.listenForAnswers((a, how) => actRef.current(a, how), 900), [node])

  // keys for people who can press one: Y, N, B
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLInputElement
      if (t?.tagName === 'TEXTAREA' || (t?.tagName === 'INPUT' && t.type !== 'checkbox')) return
      const k = e.key.toLowerCase()
      if (k === 'y') actRef.current('yes', 'key')
      if (k === 'n') actRef.current('no', 'key')
      if (k === 'b') actRef.current('back', 'key')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // auto-scan: silence is a no, so a person with only one signal (two blinks) can still choose
  useEffect(() => {
    if (!autoScan || !node) return
    const t = setTimeout(() => actRef.current('no', 'wait'), SCAN_MS)
    return () => clearTimeout(t)
  }, [autoScan, node])

  if (!node) return null
  const path = s.path
  return (
    <div className="ask">
      <span className="label" style={{ color: 'var(--turmeric)' }}>
        Ask mode · nod or blink twice for yes · shake for no{autoScan ? ' · or wait' : ''}
      </span>
      {path.length > 0 && <span className="label crumbs">{path.map((n) => n.ask[patientLang]).join(' › ')}</span>}
      <div key={node.id} className={`out${latin ? ' latin' : ''}`}>
        {node.ask[patientLang]}
      </div>
      {!latin && <span className="label">{node.ask.en}</span>}
      {autoScan && <span key={`${node.id}${s.asked}`} className="scan" style={{ animationDuration: `${SCAN_MS}ms` }} />}
      <ol className="ask-options" aria-label="Questions in this group">
        {s.options.map((o: AskNode, i) => (
          <li key={o.id} className={i === s.position ? 'on' : i < s.position ? 'past' : ''}>
            {o.ask[patientLang]}
            {o.children && <span className="more"> ›</span>}
          </li>
        ))}
      </ol>
      <div className="row">
        <button className="btn solid" onClick={() => act('yes')}>
          Yes
        </button>
        <button className="btn" onClick={() => act('no')}>
          No
        </button>
        {path.length > 0 && (
          <button className="btn" onClick={() => act('back')}>
            Back
          </button>
        )}
        <button className="ghost" onClick={onCancel}>
          Stop
        </button>
        <label className="handsfree label" style={{ marginTop: 0 }}>
          <input type="checkbox" checked={autoScan} onChange={(e) => setAutoScan(e.target.checked)} /> auto-scan
        </label>
      </div>
      <span className="label num">
        question {s.asked} · asked in {LANGS[patientLang].name}
        {heard && ` · last answer: ${heard}`}
      </span>
    </div>
  )
}
