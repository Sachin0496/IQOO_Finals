import { useEffect, useMemo, useState } from 'react'
import type { Clip } from '../core/clip'
import { LANGS, type Lang } from '../core/phrases'
import { voiceReport, voicesReady } from '../audio/speech'
import { deleteEverything } from '../store/db'
import { exportSession } from '../store/export'
import type { Who } from '../state'

export function DataPanel({ who, clips, reload }: { who: Who; clips: Clip[]; reload: () => void }) {
  const [withCrops, setWithCrops] = useState(true)
  const [msg, setMsg] = useState('')
  const [voices, setVoices] = useState<ReturnType<typeof voiceReport> | null>(null)

  useEffect(() => {
    voicesReady().then(() => setVoices(voiceReport()))
  }, [])

  const bySession = useMemo(() => {
    const m = new Map<string, Record<string, number>>()
    for (const c of clips) {
      const row = m.get(c.session) ?? {}
      row[c.kind] = (row[c.kind] ?? 0) + 1
      m.set(c.session, row)
    }
    return [...m].sort(([a], [b]) => a.localeCompare(b))
  }, [clips])

  const exportIt = async (session: string) => {
    const n = await exportSession(who.participant, session, withCrops)
    setMsg(`Exported ${n} clips · ${who.participant}_${session}`)
  }

  const wipe = async () => {
    if (!confirm('Delete every clip recorded on this device? This cannot be undone.')) return
    await deleteEverything()
    setMsg('Everything deleted.')
    reload()
  }

  return (
    <div className="panel">
      <header>
        <h1>
          Data <em>stays</em> here.
        </h1>
        <p>
          Clips live in this browser only: lip points and 96×96 grey mouth crops, never video. Export a session for the harness, then delete.
        </p>
      </header>

      <table className="plain num">
        <thead>
          <tr>
            <th>{who.participant} · session</th>
            <th>teach</th>
            <th>protocol</th>
            <th>idle</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {bySession.length === 0 && (
            <tr>
              <td colSpan={5} style={{ color: 'var(--mute)' }}>
                Nothing recorded yet.
              </td>
            </tr>
          )}
          {bySession.map(([s, row]) => (
            <tr key={s}>
              <td>{s}</td>
              <td>{row.teach ?? 0}</td>
              <td>{row.protocol ?? 0}</td>
              <td>{row.idle ?? 0}</td>
              <td style={{ textAlign: 'right' }}>
                <button className="ghost" onClick={() => exportIt(s)}>
                  export
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <label className="row" style={{ cursor: 'pointer' }}>
        <input type="checkbox" checked={withCrops} onChange={(e) => setWithCrops(e.target.checked)} />
        <span>Include mouth crops (needed for the encoder, about 0.6 MB per clip)</span>
      </label>
      <div className="feedback good">{msg}</div>

      <section style={{ display: 'grid', gap: 10 }}>
        <span className="label">Voices on this device · spike test S5</span>
        <table className="plain">
          <tbody>
            {(Object.keys(LANGS) as Lang[]).map((l) => {
              const v = voices?.[l]
              return (
                <tr key={l}>
                  <td style={{ fontFamily: 'var(--script)' }}>{LANGS[l].native}</td>
                  <td className="label">{LANGS[l].bcp47}</td>
                  <td style={{ color: v?.available ? (v.offline ? 'var(--leaf)' : 'var(--turmeric)') : 'var(--kumkum)' }}>
                    {!voices ? '…' : !v?.available ? 'missing' : v.offline ? 'offline' : 'network voice'}
                  </td>
                  <td style={{ color: 'var(--mute)' }}>{v?.name ?? ''}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </section>

      <div className="row">
        <button className="btn danger" onClick={wipe}>
          Delete everything
        </button>
      </div>
    </div>
  )
}
