import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import QRCode from 'qrcode'
import { link, type LinkMessage } from '../core/link'
import { LANGS, phraseById, speakable, type Lang, type Phrase } from '../core/phrases'
import { alarm, sayPhrase } from '../audio/speech'

/** The phrase a message refers to: built in, or carried inside the message for the person's own phrases. */
function phraseOf(m: Extract<LinkMessage, { type: 'phrase' }>): Phrase {
  if (!m.custom) return phraseById(m.phrase)
  return { id: m.phrase, urgent: m.urgent, text: m.custom.text as Phrase['text'], custom: { lang: m.custom.lang as Lang, given: m.custom.given as Lang[] } }
}

const useLink = () => useSyncExternalStore(link.subscribe, () => `${link.status}|${link.role}`)

/** Shows a pairing code as a QR code plus a copyable string. */
function CodeCard({ code, caption }: { code: string; caption: string }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    if (canvas.current) void QRCode.toCanvas(canvas.current, code, { margin: 1, width: 220, color: { dark: '#0e0d0b', light: '#ede6d6' } })
  }, [code])
  return (
    <div className="codecard" data-code={code}>
      <canvas ref={canvas} aria-label="Pairing QR code" />
      <div style={{ display: 'grid', gap: 8 }}>
        <span className="label">{caption}</span>
        <button className="btn" onClick={() => navigator.clipboard?.writeText(code)}>
          Copy code
        </button>
      </div>
    </div>
  )
}

/** Paste a code, or scan it with the camera where the browser can read QR codes. */
function CodeInput({ label, onCode }: { label: string; onCode: (code: string) => void }) {
  const [text, setText] = useState('')
  const [scanning, setScanning] = useState(false)
  const video = useRef<HTMLVideoElement>(null)
  const canScan = 'BarcodeDetector' in window

  useEffect(() => {
    if (!scanning) return
    let stop = false
    let stream: MediaStream | null = null
    ;(async () => {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
      video.current!.srcObject = stream
      await video.current!.play()
      const detector = new (window as unknown as { BarcodeDetector: new (o: object) => { detect: (v: HTMLVideoElement) => Promise<{ rawValue: string }[]> } }).BarcodeDetector({ formats: ['qr_code'] })
      while (!stop) {
        const found = await detector.detect(video.current!).catch(() => [])
        if (found[0]) {
          setScanning(false)
          onCode(found[0].rawValue)
          break
        }
        await new Promise((r) => setTimeout(r, 250))
      }
    })()
    return () => {
      stop = true
      stream?.getTracks().forEach((t) => t.stop())
    }
  }, [scanning, onCode])

  return (
    <div style={{ display: 'grid', gap: 8 }}>
      <span className="label">{label}</span>
      {scanning ? (
        <video ref={video} muted playsInline style={{ width: 240, border: '1px solid var(--rule-2)' }} />
      ) : (
        <textarea className="codein" rows={3} value={text} onChange={(e) => setText(e.target.value)} placeholder="Paste the code here" />
      )}
      <div className="row">
        <button className="btn solid" disabled={!text.trim()} onClick={() => onCode(text)}>
          Use code
        </button>
        {canScan && (
          <button className="btn" onClick={() => setScanning((s) => !s)}>
            {scanning ? 'Stop scanning' : 'Scan QR'}
          </button>
        )}
      </div>
    </div>
  )
}

export function LinkPanel({ caregiverLang, setCaregiverLang }: { caregiverLang: Lang; setCaregiverLang: (l: Lang) => void }) {
  useLink()
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [inbox, setInbox] = useState<Extract<LinkMessage, { type: 'phrase' }>[]>([])
  const [acked, setAcked] = useState<string | null>(null)
  // Ask mode running on the patient's device: the question on screen now
  const [asking, setAsking] = useState<Record<string, string> | null>(null)

  // caregiver: show, speak and (for urgent phrases) alarm on every message; patient: hear "coming"
  useEffect(() => {
    link.onMessage = (m) => {
      if (m.type === 'ask') return setAsking(m.ask)
      if (m.type === 'phrase') {
        setAsking(null)
        setInbox((x) => [m, ...x].slice(0, 8))
        const { text, lang } = speakable(phraseOf(m), caregiverLang)
        void sayPhrase(m.phrase, text, lang, 'kavitha')
        if (m.urgent) {
          alarm()
          navigator.vibrate?.([300, 150, 300, 150, 600])
        }
      } else setAcked(m.phrase)
    }
  }, [caregiverLang])

  const run = (f: () => Promise<unknown>) => () => {
    setError('')
    f().catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }

  const connected = link.status === 'connected'
  const latest = inbox[0]
  return (
    <div className="panel">
      <header>
        <h1>
          Call the <em>nurse</em>.
        </h1>
        <p>
          Pair a caregiver's phone once. After that, every phrase Mouna speaks also appears on that phone; urgent ones ring it. Direct over the same
          Wi-Fi: no server, no internet.
        </p>
      </header>

      {link.status === 'idle' || link.status === 'closed' ? (
        <div className="row" style={{ gap: 12 }}>
          <button className="btn solid" onClick={run(async () => setCode(await link.offer()))}>
            This is the patient's device
          </button>
          <button
            className="btn"
            onClick={() => {
              link.role = 'caregiver'
              setCode('')
              link.emit()
            }}
          >
            This is the caregiver's phone
          </button>
          {link.status === 'closed' && <span className="feedback bad">Link closed. Pair again.</span>}
        </div>
      ) : null}

      {link.role === 'patient' && !connected && code && (
        <>
          <CodeCard code={code} caption="1 · Caregiver scans or pastes this" />
          <CodeInput label="2 · Then enter the caregiver's reply" onCode={(c) => run(() => link.complete(c))()} />
        </>
      )}

      {link.role === 'caregiver' && !connected && !code && (
        <CodeInput label="1 · Scan or paste the code shown on the patient's device" onCode={(c) => run(async () => setCode(await link.answer(c)))()} />
      )}
      {link.role === 'caregiver' && !connected && code && <CodeCard code={code} caption="2 · Patient's device scans or pastes this reply" />}

      {connected && link.role === 'patient' && (
        <section className="heard">
          <span className="label" style={{ color: 'var(--leaf)' }}>
            Linked to the caregiver's phone
          </span>
          <div className="empty" style={{ color: 'var(--bone)' }}>
            {acked ? `${phraseById(acked).text.en}: the nurse is coming.` : 'Phrases you speak now also reach the caregiver.'}
          </div>
        </section>
      )}

      {connected && link.role === 'caregiver' && (
        <>
          <div className="row">
            <span className="label">I read</span>
            <div className="seg script" role="group" aria-label="Caregiver language">
              {(Object.keys(LANGS) as Lang[]).map((l) => (
                <button key={l} aria-pressed={l === caregiverLang} onClick={() => setCaregiverLang(l)}>
                  {LANGS[l].native}
                </button>
              ))}
            </div>
          </div>
          <section className={`heard${latest?.urgent && !asking ? ' urgent' : ''}`} aria-live="assertive">
            {asking ? (
              <>
                <span className="label" style={{ color: 'var(--turmeric)' }}>
                  Wants to say something else · answering questions on their screen
                </span>
                <div className={`out${caregiverLang === 'en' ? ' latin' : ''}`}>{asking[caregiverLang] ?? asking.en}</div>
                <span className="label">Wait for the answer, or help: nod and blink mean yes.</span>
              </>
            ) : latest ? (
              <>
                <span className="label">
                  {latest.urgent ? 'Urgent · ' : ''}
                  {new Date(latest.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
                <div className={`out${caregiverLang === 'en' ? ' latin' : ''}`}>{phraseOf(latest).text[caregiverLang]}</div>
                <div className="row">
                  <button className="btn solid" onClick={() => link.send({ type: 'ack', phrase: latest.phrase, at: Date.now() })}>
                    Coming
                  </button>
                </div>
              </>
            ) : (
              <div className="empty">Linked. Waiting for the patient.</div>
            )}
          </section>
        </>
      )}

      {link.status !== 'idle' && (
        <div className="row">
          <button className="ghost" onClick={() => (link.close(), setCode(''), setInbox([]), setAcked(null))}>
            disconnect
          </button>
        </div>
      )}
      {error && <div className="feedback bad">{error}</div>}
    </div>
  )
}
