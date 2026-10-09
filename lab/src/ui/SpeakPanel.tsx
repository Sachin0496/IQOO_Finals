import { useEffect, useMemo, useRef, useState } from 'react'
import type { Clip } from '../core/clip'
import { ISSUE_TEXT } from '../core/gate'
import { LANGS, PHRASES, phraseById, speakable, type Lang, type Phrase } from '../core/phrases'
import type { Decision, FewShotDTW } from '../core/recognizer'
import { ContextPrior, type Transitions } from '../core/context'
import { toSequence } from '../core/sequence'
import { alarm, sayPhrase, voicePack, type VoiceEntry } from '../audio/speech'
import { playBankedVoice } from '../audio/bank'
import { bankedPhrases } from '../store/db'
import { link } from '../core/link'
import { encoderAvailable } from '../vision/encoder'
import { saveClip } from '../store/db'
import { engine, finalizeClip, usePersisted, type EncoderState, type Who } from '../state'
import { PushToTalk } from './Instruments'
import { AskPanel } from './AskPanel'
import { plainWords, readBlocks, type Reading } from '../core/blocks'

/** Threshold scale for "Careful": the measured point where untaught words are rarely spoken (see recognizer.ts). */
const CAREFUL = 0.7

type View =
  | { kind: 'empty' }
  | { kind: 'said'; phrase: string; d?: Decision; urgent: boolean; clip?: Clip; asked?: number }
  | { kind: 'confirm'; phrase: string; d: Decision; clip: Clip }
  | { kind: 'correct'; wrong: string; d: Decision; clip: Clip }
  | { kind: 'unsure'; d: Decision; clip: Clip }
  | { kind: 'reject'; reason: string }
  | { kind: 'offer' }
  | { kind: 'ask' }
  | { kind: 'row'; row: Decision[] }
  | { kind: 'sentence'; reading: Reading; choices: boolean }

interface Props {
  who: Who
  recognizer: FewShotDTW
  outputLang: Lang
  setOutputLang: (l: Lang) => void
  reload: () => void
  onMatch: (ms: number) => void
  recogniser: 'geometry' | 'encoder'
  setRecogniser: (r: 'geometry' | 'encoder') => void
  encoderState: EncoderState
}

export function SpeakPanel({ who, recognizer, outputLang, setOutputLang, reload, onMatch, recogniser, setRecogniser, encoderState }: Props) {
  const [view, setView] = useState<View>({ kind: 'empty' })
  const [history, setHistory] = useState<{ phrase: string; at: Date }[]>([])
  const [voices, setVoices] = useState<VoiceEntry[]>([])
  const [voiceId, setVoiceId] = usePersisted('voice', 'kavitha')
  const [careful, setCareful] = usePersisted('careful', false)
  const [build, setBuild] = usePersisted('buildSentence', false)
  const [banked, setBanked] = useState<Set<string>>(new Set())
  const [hasEncoder, setHasEncoder] = useState(false)
  useEffect(() => {
    encoderAvailable().then((ok) => {
      setHasEncoder(ok)
      if (!ok && recogniser === 'encoder') setRecogniser('geometry')
    })
  }, [recogniser, setRecogniser])
  useEffect(() => {
    voicePack().then(setVoices)
    bankedPhrases(who.participant).then(setBanked)
  }, [who.participant])
  // what this person tends to say next; reorders the "not sure" choices, never speaks on its own
  const [transitions, setTransitions] = usePersisted<Transitions>(`context.${who.participant}`, {})
  const prior = useMemo(() => new ContextPrior(structuredClone(transitions)), [transitions])
  const prev = history[0]?.phrase ?? null
  // learning meter: what happened on the last 20 tries
  type Outcome = 'right' | 'wrong' | 'asked'
  const [outcomes, setOutcomes] = usePersisted<Outcome[]>(`outcomes.${who.participant}`, [])
  const record = (o: Outcome) => setOutcomes([...outcomes, o].slice(-20))
  const [learned, setLearned] = useState('')
  // two misses in a row: offer Ask mode, answerable with the face alone
  const misses = useRef(0)
  const taught = recognizer.phrases.length
  const voice = voiceId === 'mine' && banked.size ? 'mine' : voices.some((v) => v.id === voiceId) ? voiceId : 'device'
  const fallback = voices[0]?.id ?? 'device'
  /** "My voice" plays the person's own recording when one exists for this phrase and language. */
  const sayIt = async (phrase: string) => {
    const { text, lang } = speakable(phraseById(phrase), outputLang)
    if (voice === 'mine' && (await playBankedVoice(who.participant, lang, phrase))) return
    return sayPhrase(phrase, text, lang, voice === 'mine' ? fallback : voice)
  }

  const say = (phrase: string, d: Decision | undefined, urgent = false, clip?: Clip) => {
    void sayIt(phrase)
    if (urgent) alarm()
    const p = phraseById(phrase)
    // reaches a paired caregiver phone, if any; anything beyond the built-in pack carries its text, as that phone may not know it
    const own = !PHRASES.some((x) => x.id === phrase)
    link.send({ type: 'phrase', phrase, urgent, at: Date.now(), custom: own ? { text: p.text, lang: p.custom?.lang ?? 'en', given: p.custom?.given ?? ['en', 'ta', 'kn', 'hi'] } : undefined })
    if (d) {
      prior.observe(prev, phrase)
      setTransitions(structuredClone(prior.transitions))
    }
    setView({ kind: 'said', phrase, d, urgent, clip })
    setHistory((h) => [{ phrase, at: new Date() }, ...h].slice(0, 5))
  }

  const onClip = async () => {
    const b = engine.endClip()
    if (!b) return
    const clip = finalizeClip(b, who, 'teach', null, 0)
    if (clip.issues.length) return setView({ kind: 'reject', reason: ISSUE_TEXT[clip.issues[0]] })
    if (view.kind === 'ask' || view.kind === 'offer' || view.kind === 'sentence') return
    // plan A (encoder) when it is ready, else plan B (lip geometry); same safety rule either way
    const enc = encoderState.status === 'ready' && clip.crops ? encoderState : null
    recognizer.strictness = careful ? CAREFUL : 1
    if (enc) enc.head.strictness = careful ? CAREFUL : 1
    const d = enc
      ? await enc.encoder.embed(clip.crops!, clip.t).then(({ embedding, ms }) => enc.head.classify(embedding, ms))
      : recognizer.classify(toSequence(clip.t, clip.features))
    onMatch(d.ms)
    if (build) return addBlock(d)
    const best = d.ranked[0]?.phrase
    setLearned('')
    misses.current = d.kind === 'reject' ? misses.current + 1 : 0
    if (d.kind === 'accept' && best) {
      record('right') // provisional: "Wrong?" flips it
      if (phraseById(best).urgent) setView({ kind: 'confirm', phrase: best, d, clip })
      else say(best, d, false, clip)
    } else {
      record('asked')
      if (d.kind === 'unsure') setView({ kind: 'unsure', d, clip })
      else if (misses.current >= 2) setView({ kind: 'offer' })
      else setView({ kind: 'reject', reason: 'That did not look like a phrase I was taught.' })
    }
  }

  /** A tap on a candidate speaks it and keeps the clip as a new teaching sample. */
  const choose = async (phrase: string, d: Decision, clip: Clip) => {
    say(phrase, d, phraseById(phrase).urgent)
    const n = recognizer.count(phrase) + 1
    await saveClip({ ...clip, phrase, rep: n })
    setLearned(`Learned from you · ${phraseById(phrase).text.en} now has ${n} samples`)
    reload()
  }

  /** The spoken phrase was wrong: mark it, then let the person pick the right one (which teaches Mouna). */
  const wrong = (phrase: string, d: Decision, clip: Clip) => {
    setOutcomes([...outcomes.slice(0, -1), 'wrong'])
    setView({ kind: 'correct', wrong: phrase, d, clip })
  }

  /** Ask mode finished: the person chose it question by question, so even an urgent answer needs no extra confirm. */
  const answered = (p: Phrase, asked: number) => {
    misses.current = 0
    say(p.id, undefined, p.urgent)
    setView({ kind: 'said', phrase: p.id, urgent: p.urgent, asked })
  }
  const startAsk = () => setView({ kind: 'ask' })

  /** Build a sentence: each clip is one block; after the second, read the pair against the sentence list. */
  const addBlock = (d: Decision) => {
    if (!d.ranked.length) return
    const row = [...(view.kind === 'row' ? view.row : []), d]
    if (row.length < 2) return setView({ kind: 'row', row })
    setView({ kind: 'sentence', reading: readBlocks(row), choices: false })
  }
  /** Every sentence is confirmed first: the list repairs blocks, so the person must see what it chose. */
  const sayChosen = (p: Phrase) => say(p.id, undefined, p.urgent)

  // the proposed sentence is answerable by nod, blink or shake
  useEffect(() => {
    if (view.kind !== 'sentence' || view.choices) return
    const best = view.reading.ranked[0]?.sentence.phrase
    return engine.listenForAnswers((a) => (a === 'yes' && best ? sayChosen(best) : setView({ ...view, choices: true })))
    // re-arm only when a new reading appears, so the grace period is not reset by unrelated renders
  }, [view.kind === 'sentence' ? view.reading : null, view.kind === 'sentence' && view.choices])
  const stopAsk = () => {
    link.send({ type: 'ask', ask: null, at: Date.now() })
    setView({ kind: 'empty' })
  }

  // the offer itself is answerable by nod, blink or shake
  useEffect(() => {
    if (view.kind !== 'offer') return
    return engine.listenForAnswers((a) => setView(a === 'yes' ? { kind: 'ask' } : { kind: 'empty' }))
  }, [view.kind])

  const latin = outputLang === 'en'
  return (
    <>
      <div className="panel">
        <header>
          <h1>
            Mouth it. <em>Mouna</em> says it.
          </h1>
          <p>Hold, mouth a taught phrase, release. It speaks only when it is sure; otherwise it asks.</p>
        </header>

        <div className="row">
          <span className="label">Caregiver hears</span>
          <div className="seg script" role="group" aria-label="Output language">
            {(Object.keys(LANGS) as Lang[]).map((l) => (
              <button key={l} aria-pressed={l === outputLang} onClick={() => setOutputLang(l)}>
                {LANGS[l].native}
              </button>
            ))}
          </div>
        </div>

        {voices.length > 0 && (
          <div className="row">
            <span className="label">Voice</span>
            <div className="seg" role="group" aria-label="Voice">
              {banked.size > 0 && (
                <button aria-pressed={voice === 'mine'} onClick={() => setVoiceId('mine')}>
                  My voice · {banked.size}
                </button>
              )}
              {voices.map((v) => (
                <button key={v.id} aria-pressed={voice === v.id} onClick={() => setVoiceId(v.id)}>
                  {v.label}
                </button>
              ))}
              <button aria-pressed={voice === 'device'} onClick={() => setVoiceId('device')}>
                Device
              </button>
            </div>
          </div>
        )}

        <div className="row">
          <span className="label">Mouth</span>
          <div className="seg" role="group" aria-label="What to mouth">
            <button aria-pressed={!build} onClick={() => (setBuild(false), setView({ kind: 'empty' }))}>
              Whole phrases
            </button>
            <button aria-pressed={build} onClick={() => (setBuild(true), setView({ kind: 'empty' }))}>
              Build a sentence
            </button>
          </div>
          {build && <span className="label">two taught blocks make one of 23 sentences (Teach › Blocks)</span>}
        </div>

        <div className="row">
          <span className="label">Speaks</span>
          <div className="seg" role="group" aria-label="When to speak">
            <button aria-pressed={!careful} onClick={() => setCareful(false)}>
              When sure
            </button>
            <button aria-pressed={careful} onClick={() => setCareful(true)}>
              Careful
            </button>
          </div>
          {careful && <span className="label">fewer wrong words, more choices to tap</span>}
        </div>

        {hasEncoder && (
        <div className="row">
          <span className="label">Recogniser</span>
          <div className="seg" role="group" aria-label="Recogniser">
            <button aria-pressed={recogniser === 'geometry'} onClick={() => setRecogniser('geometry')}>
              Lip geometry · instant
            </button>
            <button aria-pressed={recogniser === 'encoder'} onClick={() => setRecogniser('encoder')}>
              Encoder · more accurate
            </button>
          </div>
          <span className="label">
            {encoderState.status === 'loading' && 'loading the 86 MB encoder…'}
            {encoderState.status === 'embedding' && `learning your clips ${encoderState.done}/${encoderState.total}`}
            {encoderState.status === 'ready' && `ready · ${encoderState.encoder.backend}`}
            {encoderState.status === 'unavailable' && 'encoder not installed on this site'}
            {encoderState.status === 'error' && `encoder error: ${encoderState.message}`}
          </span>
        </div>
        )}

        <section className={`heard${view.kind === 'said' && view.urgent ? ' urgent' : ''}`} aria-live="assertive">
          {view.kind === 'empty' && (
            <div className="empty">
              {taught < 2 ? 'Teach at least two phrases first.' : build ? 'Mouth two blocks, one at a time: water · want.' : 'Waiting for lips.'}
            </div>
          )}

          {view.kind === 'said' && (
            <>
              <span className="label">{view.urgent ? 'Urgent · alert sent' : 'Heard'}</span>
              <div key={`${view.phrase}${history.length}`} className={`out${latin ? ' latin' : ''}`}>
                {phraseById(view.phrase).text[outputLang]}
              </div>
              {view.d && <Meta d={view.d} />}
              {view.asked !== undefined && (
                <span className="meta label num">
                  {view.phrase === 'ask_none' ? 'none of the questions fit' : 'found by Ask mode'} · {view.asked} questions
                </span>
              )}
              {view.clip && view.d && (
                <button className="ghost" style={{ justifySelf: 'start' }} onClick={() => wrong(view.phrase, view.d!, view.clip!)}>
                  Wrong? Pick the right phrase
                </button>
              )}
            </>
          )}

          {view.kind === 'correct' && (
            <>
              <div className="empty" style={{ color: 'var(--bone)' }}>
                Which one did you mean?
              </div>
              <div className="cands">
                {view.d.ranked
                  .filter((r) => r.phrase !== view.wrong)
                  .slice(0, 6)
                  .map((r) => (
                    <button key={r.phrase} className="cand" onClick={() => choose(r.phrase, view.d, view.clip)}>
                      <span className="t">{phraseById(r.phrase).text[outputLang]}</span>
                    </button>
                  ))}
              </div>
            </>
          )}

          {view.kind === 'confirm' && (
            <>
              <span className="label" style={{ color: 'var(--kumkum)' }}>
                Urgent phrase · confirm before alerting
              </span>
              <div className={`out${latin ? ' latin' : ''}`}>{phraseById(view.phrase).text[outputLang]}</div>
              <div className="row">
                <button className="btn solid" onClick={() => say(view.phrase, view.d, true, view.clip)}>
                  Yes, alert
                </button>
                <button className="btn" onClick={() => setView({ kind: 'empty' })}>
                  No
                </button>
              </div>
            </>
          )}

          {view.kind === 'unsure' && (
            <>
              <div className="empty" style={{ color: 'var(--bone)' }}>
                Not sure. Which one?
              </div>
              <div className="cands">
                {prior.rerank(view.d.ranked.slice(0, 5), prev, new Date().getHours()).slice(0, 3).map((r) => (
                  <button key={r.phrase} className="cand" onClick={() => choose(r.phrase, view.d, view.clip)}>
                    <span className="t">{phraseById(r.phrase).text[outputLang]}</span>
                    {r.boosted && <span className="label" style={{ color: 'var(--turmeric)' }}>likely next</span>}
                    <span className="bar">
                      <i style={{ width: `${Math.round(r.score * 100)}%` }} />
                    </span>
                  </button>
                ))}
              </div>
              <span className="label">A tap speaks it and teaches Mouna this sample.</span>
            </>
          )}

          {view.kind === 'reject' && (
            <>
              <div className="empty" style={{ color: 'var(--bone)' }}>
                Not sure. Please repeat.
              </div>
              <span className="label">{view.reason}</span>
            </>
          )}

          {view.kind === 'offer' && (
            <>
              <div className="empty" style={{ color: 'var(--bone)' }}>
                Something I was not taught? I can ask you questions.
              </div>
              <span className="label">Nod or blink twice for yes · shake for no</span>
              <div className="row">
                <button className="btn solid" onClick={startAsk}>
                  Yes, ask me
                </button>
                <button className="btn" onClick={() => setView({ kind: 'empty' })}>
                  No
                </button>
              </div>
            </>
          )}

          {view.kind === 'row' && (
            <>
              <span className="label">Block {view.row.length} of 2 · now the next one</span>
              <div className="blocks">
                {view.row.map((d, i) => (
                  <span key={i} className="block">
                    {phraseById(d.ranked[0].phrase).text[who.spokenLang]}
                  </span>
                ))}
                <span className="block next">…</span>
              </div>
              <button className="ghost" style={{ justifySelf: 'start' }} onClick={() => setView({ kind: 'empty' })}>
                Start again
              </button>
            </>
          )}

          {view.kind === 'sentence' && <SentenceView view={view} outputLang={outputLang} spokenLang={who.spokenLang} onSay={sayChosen} onChoices={() => setView({ ...view, choices: true })} onAsk={startAsk} />}

          {view.kind === 'ask' && <AskPanel patientLang={who.spokenLang} onDone={answered} onCancel={stopAsk} />}
        </section>

        {view.kind !== 'ask' && (
          <div className="row">
            <button className="ghost" onClick={startAsk}>
              Something else? Ask me questions
            </button>
          </div>
        )}

        {(outcomes.length > 0 || learned) && (
          <section className="learning" aria-label="Learning from corrections">
            <span className="label">
              Last {outcomes.length} · {outcomes.filter((o) => o === 'right').length} right · {outcomes.filter((o) => o === 'wrong').length} wrong ·{' '}
              {outcomes.filter((o) => o === 'asked').length} asked
            </span>
            <div className="ticks">
              {outcomes.map((o, i) => (
                <i key={i} className={o} />
              ))}
            </div>
            {learned && <span className="feedback good">{learned}</span>}
          </section>
        )}

        {history.length > 0 && (
          <section className="history" aria-label="Last five phrases">
            <span className="label" style={{ paddingBottom: 8 }}>
              Last five
            </span>
            {history.map((h, i) => (
              <div key={i}>
                <span className="label num">{h.at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
                <span className="t">{phraseById(h.phrase).text[outputLang]}</span>
                <button className="ghost" onClick={() => sayIt(h.phrase)}>
                  repeat
                </button>
              </div>
            ))}
          </section>
        )}
      </div>
      <PushToTalk label="Hold and mouth" disabled={taught < 2} onClip={onClip} />
    </>
  )
}

interface SentenceProps {
  view: { reading: Reading; choices: boolean }
  outputLang: Lang
  spokenLang: Lang
  onSay: (p: Phrase) => void
  onChoices: () => void
  onAsk: () => void
}

/** The sentence the blocks point to, to confirm; or the other listed sentences and the plain words. */
function SentenceView({ view, outputLang, spokenLang, onSay, onChoices, onAsk }: SentenceProps) {
  const { reading } = view
  const best = reading.ranked[0]?.sentence.phrase
  const words = plainWords(reading.words)
  const latin = outputLang === 'en'
  if (!view.choices && best)
    return (
      <>
        <span className="label" style={{ color: 'var(--turmeric)' }}>
          {reading.sure ? 'Did you mean' : 'Not sure. Did you mean'} · nod or blink twice to say it · shake for other choices
        </span>
        <div className={`out${latin ? ' latin' : ''}`}>{best.text[outputLang]}</div>
        {outputLang !== spokenLang && <span className="label">{best.text[spokenLang]}</span>}
        <div className="row">
          <button className="btn solid" onClick={() => onSay(best)}>
            Yes, say it
          </button>
          <button className="btn" onClick={onChoices}>
            Other choices
          </button>
        </div>
      </>
    )
  return (
    <>
      <div className="empty" style={{ color: 'var(--bone)' }}>
        Which one?
      </div>
      <div className="cands">
        {reading.ranked.slice(best ? 1 : 0, 4).map(({ sentence }) => (
          <button key={sentence.id} className="cand" onClick={() => onSay(sentence.phrase)}>
            <span className="t">{sentence.phrase.text[outputLang]}</span>
          </button>
        ))}
        <button className="cand" onClick={() => onSay(words)}>
          <span className="t">{words.text[outputLang]}</span>
          <span className="label">just the words</span>
        </button>
      </div>
      <button className="ghost" style={{ justifySelf: 'start' }} onClick={onAsk}>
        None of these: ask me questions
      </button>
    </>
  )
}

function Meta({ d }: { d: Decision }) {
  const best = d.ranked[0]
  const lead = Number.isFinite(d.margin) ? d.margin.toFixed(2) : '∞'
  return (
    <span className="meta label num">
      {phraseById(best.phrase).custom ? 'your phrase' : best.phrase} · d {best.distance.toFixed(3)} / {d.threshold.toFixed(3)} · lead {lead}× · {d.ms.toFixed(1)} ms
    </span>
  )
}
