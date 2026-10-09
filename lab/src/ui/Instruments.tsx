import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { engine, usePersisted, useSnapshot } from '../state'
import { TRACE_LEN } from '../engine'

/** Lip aperture drawn like an audio waveform: the sound a silent mouth would have made. */
export function Wave() {
  const ref = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    let raf = 0
    const draw = () => {
      raf = requestAnimationFrame(draw)
      const c = ref.current
      if (!c) return
      const dpr = devicePixelRatio || 1
      const W = Math.round(c.clientWidth * dpr)
      const H = Math.round(c.clientHeight * dpr)
      if (c.width !== W) c.width = W
      if (c.height !== H) c.height = H
      const ctx = c.getContext('2d')!
      ctx.clearRect(0, 0, W, H)
      const { aperture, open, rec } = engine.live
      const step = W / TRACE_LEN
      const mid = H / 2

      for (let i = 0; i < TRACE_LEN; i++) {
        if (open[i]) {
          ctx.fillStyle = '#f0aa2e1f'
          ctx.fillRect(i * step, 0, step + 0.5, H)
        }
        if (rec[i]) {
          ctx.fillStyle = '#e4472b'
          ctx.fillRect(i * step, H - 2 * dpr, step + 0.5, 2 * dpr)
        }
      }
      ctx.fillStyle = '#2c2924'
      ctx.fillRect(0, mid, W, 1)

      const barW = Math.max(1, step * 0.55)
      for (let i = 0; i < TRACE_LEN; i++) {
        const a = Math.min(1, aperture[i] * 2.4)
        const hh = Math.max(1, a * (H * 0.46))
        ctx.fillStyle = open[i] ? '#f0aa2e' : '#ede6d6' + (i > TRACE_LEN - 4 ? 'ff' : '8c')
        ctx.fillRect(i * step, mid - hh, barW, hh * 2)
      }
    }
    draw()
    return () => cancelAnimationFrame(raf)
  }, [])

  return (
    <section className="wave" aria-label="Lip aperture over the last six seconds">
      <header>
        <span className="label">Lip aperture · last 6 s</span>
        <span className="label">
          <span style={{ color: 'var(--turmeric)' }}>■</span> gate open&nbsp;&nbsp;
          <span style={{ color: 'var(--kumkum)' }}>▬</span> recording
        </span>
      </header>
      <canvas ref={ref} />
    </section>
  )
}

function useOnline() {
  const [online, setOnline] = useState(navigator.onLine)
  useEffect(() => {
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    addEventListener('online', on)
    addEventListener('offline', off)
    return () => {
      removeEventListener('online', on)
      removeEventListener('offline', off)
    }
  }, [])
  return online
}

export function Telemetry({ matchMs }: { matchMs: number | null }) {
  const s = useSnapshot()
  const online = useOnline()
  const live = s.status === 'running'
  const cells: [string, string, '' | 'good' | 'bad'][] = [
    ['fps', live ? s.fps.toFixed(1) : '—', !live ? '' : s.fps >= 24 ? 'good' : 'bad'],
    ['landmarks', live ? `${s.landmarkMs.toFixed(1)} ms` : '—', ''],
    ['match', matchMs === null ? '—' : `${matchMs.toFixed(1)} ms`, ''],
    ['eye span', live && s.face ? `${Math.round(s.iod)} px` : '—', !live || !s.face ? '' : s.iod >= 55 ? 'good' : 'bad'],
    ['yaw', live && s.face ? `${Math.round(s.yaw)}°` : '—', !live || !s.face ? '' : Math.abs(s.yaw) <= 25 ? '' : 'bad'],
    ['gate', live ? (s.gateOpen ? 'open' : 'shut') : '—', s.gateOpen ? 'good' : ''],
    ['compute', s.delegate ?? '—', ''],
    ['network', online ? 'online' : 'offline', online ? '' : 'good'],
  ]
  return (
    <section className="telemetry" aria-label="Telemetry">
      {cells.map(([k, v, cls]) => (
        <div key={k}>
          <span className="label">{k}</span>
          <strong className={cls}>{v}</strong>
        </div>
      ))}
    </section>
  )
}

/**
 * Hold to record. Pointer or Space. The fill sweeps for 2.7 s, the length of a typical phrase.
 */
export function PushToTalk({ label, disabled, onClip, onStart }: { label: string; disabled?: boolean; onClip: () => void; onStart?: () => void }) {
  const snap = useSnapshot()
  const [down, setDown] = useState(false)
  const ready = snap.status === 'running' && !disabled

  const press = () => {
    if (!ready || down) return
    setDown(true)
    engine.beginClip()
    onStart?.()
  }
  const release = () => {
    if (!down) return
    setDown(false)
    onClip()
  }

  const pressRef = useRef(press)
  const releaseRef = useRef(release)
  useLayoutEffect(() => {
    pressRef.current = press
    releaseRef.current = release
  })

  const [handsFree, setHandsFree] = usePersisted('handsFree', false)
  useEffect(() => {
    engine.handsFree = handsFree && ready
    engine.onHandsFreeStart = () => pressRef.current()
    engine.onHandsFreeEnd = () => releaseRef.current()
    return () => {
      engine.handsFree = false
      engine.onHandsFreeStart = null
      engine.onHandsFreeEnd = null
    }
  }, [handsFree, ready])

  useEffect(() => {
    const isTyping = (e: KeyboardEvent) => e.target instanceof HTMLInputElement
    const kd = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !e.repeat && !isTyping(e)) {
        e.preventDefault()
        pressRef.current()
      }
    }
    const ku = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !isTyping(e)) {
        e.preventDefault()
        releaseRef.current()
      }
    }
    addEventListener('keydown', kd)
    addEventListener('keyup', ku)
    return () => {
      removeEventListener('keydown', kd)
      removeEventListener('keyup', ku)
    }
  }, [])

  return (
    <div className="ptt-wrap">
      <button
        className={`ptt${down ? ' down' : ''}`}
        disabled={!ready}
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId)
          press()
        }}
        onPointerUp={release}
        onPointerCancel={release}
        onContextMenu={(e) => e.preventDefault()}
      >
        <span className="fill" />
        <span className="what">{down ? 'Mouthing…' : handsFree ? 'Blink twice, then mouth' : label}</span>
        <span className="key label">{down ? `${snap.recFrames} frames` : handsFree ? 'hands-free · or hold' : 'hold · or space'}</span>
      </button>
      <label className="handsfree label">
        <input type="checkbox" checked={handsFree} onChange={(e) => setHandsFree(e.target.checked)} />
        hands-free: blink twice to start
      </label>
    </div>
  )
}

export function Ring({ value, of }: { value: number; of: number }) {
  const r = 11
  const c = 2 * Math.PI * r
  const frac = Math.min(1, value / of)
  return (
    <svg className="ring" width="28" height="28" viewBox="0 0 28 28" aria-label={`${value} of ${of}`}>
      <circle className="bg" cx="14" cy="14" r={r} />
      <circle className="fg" cx="14" cy="14" r={r} strokeDasharray={c} strokeDashoffset={c * (1 - frac)} transform="rotate(-90 14 14)" />
    </svg>
  )
}
