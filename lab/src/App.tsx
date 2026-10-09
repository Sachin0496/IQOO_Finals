import { useMemo, useState } from 'react'
import { setCustomPhrases, type CustomPhrase, type Lang } from './core/phrases'
import { useClips, useEncoderRecognizer, usePersisted, useRecognizer, useSnapshot, type Who } from './state'
import { Mirror } from './ui/Mirror'
import { Telemetry, Wave } from './ui/Instruments'
import { TeachPanel } from './ui/TeachPanel'
import { SpeakPanel } from './ui/SpeakPanel'
import { ProtocolPanel } from './ui/ProtocolPanel'
import { DataPanel } from './ui/DataPanel'
import { LinkPanel } from './ui/LinkPanel'
import { SwitchPanel } from './ui/SwitchPanel'

const MODES = [
  ['teach', 'Teach'],
  ['speak', 'Speak'],
  ['link', 'Link'],
  ['switch', 'Switch'],
  ['protocol', 'Protocol'],
  ['data', 'Data'],
] as const
type Mode = (typeof MODES)[number][0]

const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '').slice(0, 16)

export default function App() {
  const snap = useSnapshot()
  const [mode, setMode] = usePersisted<Mode>('mode', 'teach')
  const [participant, setParticipant] = usePersisted('participant', 'p1')
  const [session, setSession] = usePersisted('session', 's1')
  const [spokenLang, setSpokenLang] = usePersisted<Lang>('spokenLang', 'en')
  const [outputLang, setOutputLang] = usePersisted<Lang>('outputLang', 'kn')
  const [consent, setConsent] = usePersisted('consent', false)
  const [matchMs, setMatchMs] = useState<number | null>(null)

  const who: Who = { participant: participant || 'p1', session: session || 's1', spokenLang }
  // the person's own phrases, per participant; registered before any panel renders
  const [customAll, setCustomAll] = usePersisted<Record<string, CustomPhrase[]>>('customPhrases', {})
  const custom = useMemo(() => customAll[who.participant] ?? [], [customAll, who.participant])
  useMemo(() => setCustomPhrases(custom), [custom])
  const setCustom = (list: CustomPhrase[]) => setCustomAll({ ...customAll, [who.participant]: list })

  const { clips, reload } = useClips(who.participant)
  const recognizer = useRecognizer(clips)
  const [recogniser, setRecogniser] = usePersisted<'geometry' | 'encoder'>('recogniser', 'geometry')
  const encoderState = useEncoderRecognizer(clips, mode === 'speak' && recogniser === 'encoder')

  return (
    <div className="app">
      <header className="masthead">
        <div className="wordmark">
          <b>
            mouna<i>.</i>
          </b>
          <span>மௌனம் · ಮೌನ · मौन · lab</span>
        </div>

        <nav className="modes" aria-label="Mode">
          {MODES.map(([id, name], i) => (
            <button key={id} aria-current={mode === id ? 'page' : undefined} onClick={() => setMode(id)}>
              <small className="num">0{i + 1}</small>
              <span>{name}</span>
            </button>
          ))}
        </nav>

        <div className="who">
          <label className="chip">
            <span className="k">who</span>
            <input value={participant} onChange={(e) => setParticipant(slug(e.target.value))} aria-label="Participant id" />
          </label>
          <label className="chip">
            <span className="k">session</span>
            <input value={session} onChange={(e) => setSession(slug(e.target.value))} aria-label="Session id" style={{ width: '4ch' }} />
          </label>
          <span className={`lamp${snap.recording ? ' rec' : snap.status === 'running' ? ' on' : ''}`} title={snap.status} />
        </div>
      </header>

      <main className="stage">
        <section className="left">
          <Mirror />
          <Wave />
          <Telemetry matchMs={matchMs} />
          <footer className="colophon">
            <span className="label">Feasibility spike · plan B recogniser</span>
            <p>
              Lip geometry from MediaPipe, matched by time-warping against your own samples, entirely in this browser. The phone build swaps in an
              open-source lip encoder on the NPU. Method after LipLearner (Su, Fang, Rekimoto, CHI 2023), cited.
            </p>
          </footer>
        </section>

        <section className="right">
          {mode === 'teach' && <TeachPanel who={who} clips={clips} reload={reload} setSpokenLang={setSpokenLang} onReady={() => setMode('speak')} custom={custom} setCustom={setCustom} />}
          {mode === 'speak' && (
            <SpeakPanel
              who={who}
              recognizer={recognizer}
              outputLang={outputLang}
              setOutputLang={setOutputLang}
              reload={reload}
              onMatch={setMatchMs}
              recogniser={recogniser}
              setRecogniser={setRecogniser}
              encoderState={encoderState}
            />
          )}
          {mode === 'protocol' && <ProtocolPanel who={who} clips={clips} recognizer={recognizer} reload={reload} />}
          {mode === 'switch' && <SwitchPanel who={who} />}
          {mode === 'link' && <LinkPanel caregiverLang={outputLang} setCaregiverLang={setOutputLang} />}
          {mode === 'data' && <DataPanel who={who} clips={clips} reload={reload} />}
        </section>
      </main>

      {!consent && (
        <div className="consent" role="dialog" aria-modal="true" aria-labelledby="consent-title">
          <div>
            <span className="label">Before we start</span>
            <h2 id="consent-title">Your face stays on this device.</h2>
            <p>
              Mouna Lab uses the front camera to follow your lips. It keeps lip points and small grey mouth crops in this browser so it can learn
              your phrases. If you choose voice banking, recordings of your voice stay here too. It never stores or uploads video.
              You can delete everything from the Data tab at any time.
            </p>
            <p>This is a research spike with healthy volunteers. It is a communication aid prototype, not a medical device.</p>
            <div className="row">
              <button className="btn solid" onClick={() => setConsent(true)}>
                I agree
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
