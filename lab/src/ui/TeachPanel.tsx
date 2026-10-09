import { useEffect, useMemo, useRef, useState } from 'react'
import type { Clip } from '../core/clip'
import { ISSUE_TEXT } from '../core/gate'
import { DEMO_SET, LANGS, PHRASES, PROTOCOL_SET, QUICK_SET, phraseById, type CustomPhrase, type Lang } from '../core/phrases'
import { deleteClip, saveClip } from '../store/db'
import { engine, finalizeClip, usePersisted, type Who } from '../state'
import { bankVoice, VoiceRecorder } from '../audio/bank'
import { PushToTalk, Ring } from './Instruments'
import { BLOCK_IDS } from '../core/blocks'

export const REPS_TO_TEACH = 3

const BUILT_IN: Record<string, { name: string; ids: string[] }> = {
  quick: { name: 'Quick · 3', ids: QUICK_SET },
  demo: { name: 'Demo · 5', ids: DEMO_SET },
  protocol: { name: 'Protocol · 8', ids: PROTOCOL_SET },
  all: { name: `All · ${PHRASES.length}`, ids: PHRASES.map((p) => p.id) },
  // single words that combine into sentences in Speak > Build a sentence
  blocks: { name: `Blocks · ${BLOCK_IDS.length}`, ids: BLOCK_IDS },
}

interface Props {
  who: Who
  clips: Clip[]
  reload: () => void
  setSpokenLang: (l: Lang) => void
  onReady: () => void
  custom: CustomPhrase[]
  setCustom: (list: CustomPhrase[]) => void
}

export function TeachPanel({ who, clips, reload, setSpokenLang, onReady, custom, setCustom }: Props) {
  const SETS: Record<string, { name: string; ids: string[] }> = { ...BUILT_IN, mine: { name: `Mine · ${custom.length}`, ids: custom.map((c) => c.id) } }
  const [setId, setSetId] = useState<string>('quick')
  const [adding, setAdding] = useState(false)
  const ids = SETS[setId]?.ids ?? QUICK_SET
  const counts = useMemo(() => {
    const m = new Map<string, number>()
    for (const c of clips) if (c.kind === 'teach' && c.phrase && c.issues.length === 0) m.set(c.phrase, (m.get(c.phrase) ?? 0) + 1)
    return m
  }, [clips])

  const firstUnfinished = ids.find((id) => (counts.get(id) ?? 0) < REPS_TO_TEACH)
  const [picked, setPicked] = useState<string | null>(null)
  const current: string | undefined = picked && ids.includes(picked) ? picked : (firstUnfinished ?? ids[0])
  const phrase = current ? phraseById(current) : null
  const have = counts.get(current) ?? 0
  const allDone = !firstUnfinished
  const [feedback, setFeedback] = useState<{ text: string; tone: '' | 'good' | 'bad' }>({ text: '', tone: '' })

  // Voice banking: say the phrase aloud while teaching; the recording becomes "My voice" in Speak.
  const [banking, setBanking] = usePersisted('voiceBanking', false)
  const recorder = useRef<VoiceRecorder | null>(null)
  useEffect(() => () => recorder.current?.close(), [])
  const onStart = () => {
    if (!banking) return
    recorder.current ??= new VoiceRecorder()
    recorder.current.start().catch(() => setFeedback({ text: 'Microphone unavailable; teaching lips only.', tone: 'bad' }))
  }

  const onClip = async () => {
    const b = engine.endClip()
    const audio = banking && recorder.current ? await recorder.current.stop() : null
    if (!b || !current || !phrase) return
    const clip = finalizeClip(b, who, 'teach', current, have + 1)
    if (clip.issues.length) {
      setFeedback({ text: ISSUE_TEXT[clip.issues[0]], tone: 'bad' })
      return
    }
    await saveClip(clip)
    if (audio) await bankVoice(who.participant, who.spokenLang, current, audio)
    setFeedback({
      text: `Kept · ${phrase.text[who.spokenLang]} · ${have + 1} of ${REPS_TO_TEACH} · ${clip.features.length} frames${audio ? ' · your voice saved' : ''}`,
      tone: 'good',
    })
    if (have + 1 >= REPS_TO_TEACH) setPicked(null)
    reload()
  }

  const forget = async (id: string) => {
    for (const c of clips) if (c.kind === 'teach' && c.phrase === id) await deleteClip(c.id)
    setPicked(id)
    reload()
  }

  /** Removes one of the person's own phrases and its teaching clips. */
  const remove = async (id: string) => {
    if (!confirm('Delete this phrase and what Mouna learned for it?')) return
    for (const c of clips) if (c.phrase === id) await deleteClip(c.id)
    setCustom(custom.filter((c) => c.id !== id))
    setPicked(null)
    reload()
  }

  const add = (c: CustomPhrase) => {
    setCustom([...custom, c])
    setAdding(false)
    setSetId('mine')
    setPicked(c.id)
    setFeedback({ text: 'New phrase added. Mouth it three times to teach it.', tone: 'good' })
  }

  const script = who.spokenLang !== 'en'
  return (
    <>
      <div className="panel">
        <header>
          <h1>
            Teach <em>it</em> your lips.
          </h1>
          <p>Mouth each phrase three times, silently, as if whispering. Three phrases take about twenty-five seconds.</p>
        </header>

        <div className="row" style={{ justifyContent: 'space-between' }}>
          <div className="row">
            <div className="seg" role="group" aria-label="Phrase set">
              {Object.entries(SETS).map(([k, s]) => (
                <button key={k} aria-pressed={k === setId} onClick={() => setSetId(k)}>
                  {s.name}
                </button>
              ))}
            </div>
            <button className="btn" onClick={() => setAdding((a) => !a)}>
              {adding ? 'Cancel' : '+ New phrase'}
            </button>
          </div>
          <div className="row">
            <span className="label">I mouth in</span>
            <div className="seg script" role="group" aria-label="Language you mouth in">
              {(Object.keys(LANGS) as Lang[]).map((l) => (
                <button key={l} aria-pressed={l === who.spokenLang} onClick={() => setSpokenLang(l)}>
                  {LANGS[l].native}
                </button>
              ))}
            </div>
          </div>
        </div>

        {adding && <NewPhrase lang={who.spokenLang} onAdd={add} />}

        <section className="prompt" aria-live="polite">
          {!phrase ? (
            <div className="say" style={{ fontSize: 28 }}>
              No phrases of your own yet. Press “+ New phrase”.
            </div>
          ) : (
            <>
          <span className="label">{allDone ? 'All taught' : `Now mouth · ${have + 1} of ${REPS_TO_TEACH}`}</span>
          <div className={`say${script ? ' script' : ''}`}>{phrase.text[who.spokenLang]}</div>
          {script && !phrase.custom && <div className="gloss">{phrase.text.en}</div>}
          <div className="dots">
            {Array.from({ length: REPS_TO_TEACH }, (_, i) => (
              <i key={i} className={i < have ? 'done' : i === have ? 'next' : ''} />
            ))}
          </div>
            </>
          )}
          <div className={`feedback ${feedback.tone}`}>{feedback.text}</div>
        </section>

        <div className="list">
          {ids.map((id) => {
            const p = phraseById(id)
            const n = counts.get(id) ?? 0
            return (
              <div key={id} aria-current={id === current}>
                <Ring value={n} of={REPS_TO_TEACH} />
                <button className="t" onClick={() => setPicked(id)} style={{ textAlign: 'left' }}>
                  {p.custom ? p.text[p.custom.lang] : p.text.en}
                  <small>
                    {p.custom
                      ? `your phrase · ${p.custom.given.map((l) => LANGS[l].native).join(' · ')}`
                      : `${p.text.ta} · ${p.text.kn} · ${p.text.hi}`}
                  </small>
                </button>
                <span className="urgent-tag">
                  {p.urgent ? 'URGENT ' : ''}
                  {p.custom && (
                    <button className="ghost" onClick={() => remove(id)}>
                      delete
                    </button>
                  )}
                </span>
                {n > 0 ? (
                  <button className="ghost" onClick={() => forget(id)}>
                    teach again
                  </button>
                ) : (
                  <span className="label num">0/{REPS_TO_TEACH}</span>
                )}
              </div>
            )
          })}
        </div>

        {allDone && (
          <div className="row">
            <button className="btn solid" onClick={onReady}>
              Ready. Go to Speak →
            </button>
          </div>
        )}
      </div>
      <label className="handsfree label" style={{ marginTop: -8 }}>
        <input type="checkbox" checked={banking} onChange={(e) => setBanking(e.target.checked)} />
        bank my voice: say the phrase aloud while teaching (before surgery)
      </label>
      <PushToTalk
        label={allDone ? 'Add another sample' : banking ? 'Hold and say it aloud' : 'Hold to teach'}
        disabled={!phrase}
        onClip={onClip}
        onStart={onStart}
      />
    </>
  )
}

/** Create a phrase: the person's own words in the language they mouth in, plus any translations they type. */
function NewPhrase({ lang, onAdd }: { lang: Lang; onAdd: (c: CustomPhrase) => void }) {
  const [text, setText] = useState<Partial<Record<Lang, string>>>({})
  const [urgent, setUrgent] = useState(false)
  const [more, setMore] = useState(false)
  const others = (Object.keys(LANGS) as Lang[]).filter((l) => l !== lang)
  const ok = (text[lang]?.trim().length ?? 0) >= 2
  return (
    <section className="newphrase" aria-label="New phrase">
      <label>
        <span className="label">Your phrase in {LANGS[lang].native}</span>
        <input
          autoFocus
          maxLength={80}
          value={text[lang] ?? ''}
          onChange={(e) => setText({ ...text, [lang]: e.target.value })}
          placeholder="e.g. Please turn off the light"
        />
      </label>
      {more &&
        others.map((l) => (
          <label key={l}>
            <span className="label">{LANGS[l].native} (optional, typed by someone who speaks it)</span>
            <input maxLength={80} value={text[l] ?? ''} onChange={(e) => setText({ ...text, [l]: e.target.value })} />
          </label>
        ))}
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div className="row">
          <label className="row label" style={{ cursor: 'pointer' }}>
            <input type="checkbox" checked={urgent} onChange={(e) => setUrgent(e.target.checked)} /> urgent
          </label>
          {!more && (
            <button className="ghost" onClick={() => setMore(true)}>
              add translations
            </button>
          )}
        </div>
        <button className="btn solid" disabled={!ok} onClick={() => onAdd({ id: `c-${Date.now().toString(36)}`, urgent, lang, text })}>
          Add phrase
        </button>
      </div>
      <span className="label" style={{ textTransform: 'none', letterSpacing: 0 }}>
        Without a translation, the caregiver hears it in {LANGS[lang].native}. Mouna never machine-translates.
      </span>
    </section>
  )
}
