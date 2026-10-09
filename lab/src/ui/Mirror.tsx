import { useEffect, useRef } from 'react'
import { CROP, CROP_IOD } from '../vision/crop'
import { engine, useSnapshot } from '../state'
import type { Pt } from '../core/lips'

function path(ctx: CanvasRenderingContext2D, pts: Pt[]) {
  ctx.beginPath()
  pts.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)))
  ctx.closePath()
}

/** Live camera, the lip contour the machine sees, and the exact crop it keeps. */
export function Mirror() {
  const snap = useSnapshot()
  const host = useRef<HTMLDivElement>(null)
  const overlay = useRef<HTMLCanvasElement>(null)
  const inset = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const v = engine.video
    v.setAttribute('aria-hidden', 'true')
    host.current?.prepend(v)
  }, [])

  useEffect(() => {
    let raf = 0
    const draw = () => {
      raf = requestAnimationFrame(draw)
      const c = overlay.current
      const ic = inset.current
      if (!c || !ic) return
      const { videoWidth: w, videoHeight: h } = engine.video
      if (!w) return
      if (c.width !== w) c.width = w
      if (c.height !== h) c.height = h
      const ctx = c.getContext('2d')!
      ctx.clearRect(0, 0, w, h)
      const { outer, inner, frame } = engine.live
      const recording = engine.getSnapshot().recording
      const accent = recording ? '#e4472b' : '#f0aa2e'

      if (frame) {
        // crop window, rotated with the head
        const side = CROP_IOD * frame.iod
        ctx.save()
        ctx.translate(frame.center.x, frame.center.y)
        ctx.rotate(frame.roll)
        ctx.strokeStyle = '#ede6d64d'
        ctx.setLineDash([3, 5])
        ctx.lineWidth = 1
        ctx.strokeRect(-side / 2, -side / 2, side, side)
        ctx.restore()
        ctx.setLineDash([])
      }
      if (outer.length) {
        ctx.lineJoin = 'round'
        ctx.shadowColor = accent
        ctx.shadowBlur = 14
        ctx.strokeStyle = accent
        ctx.lineWidth = 2.2
        path(ctx, outer)
        ctx.stroke()
        ctx.shadowBlur = 0
        ctx.strokeStyle = '#ede6d6b3'
        ctx.lineWidth = 1.2
        path(ctx, inner)
        ctx.stroke()
        ctx.fillStyle = accent
        for (const p of outer) ctx.fillRect(p.x - 1.2, p.y - 1.2, 2.4, 2.4)
      }

      const ictx = ic.getContext('2d')!
      if (frame) ictx.drawImage(engine.cropper.canvas, 0, 0)
    }
    draw()
    return () => cancelAnimationFrame(raf)
  }, [])

  const warn = snap.status === 'running' && !snap.face
  return (
    <div ref={host} className={`mirror${snap.recording ? ' recording' : ''}`}>
      <canvas ref={overlay} className="overlay" />
      <i className="tick tl" />
      <i className="tick tr" />
      <i className="tick bl" />
      <i className="tick br" />

      {snap.status === 'running' && (
        <>
          <div className="hud label">
            <span>{snap.recording ? `● rec ${snap.recFrames}` : 'live'}</span>
            <span className="num">
              {snap.videoW}×{snap.videoH}
            </span>
          </div>
          <div className={`hint${warn ? ' warn' : ''}`}>{warn ? 'No face. Sit 30–45 cm from the lens, face lit from the front.' : ''}</div>
        </>
      )}
      <div className="inset" hidden={snap.status !== 'running'}>
        <canvas ref={inset} width={CROP} height={CROP} />
        <span className="label">mouth · {CROP}² · gray</span>
      </div>

      {snap.status !== 'running' && (
        <div className="idle-cover">
          <div>
            <div className="big">{snap.status === 'loading' ? 'Opening the camera…' : snap.status === 'error' ? 'Camera unavailable' : 'Silence, watched.'}</div>
            <p>
              {snap.status === 'error'
                ? snap.error
                : 'The camera stays on this device. Mouna Lab keeps lip points and a small grey mouth crop, never the video.'}
            </p>
            {snap.status === 'idle' && (
              <ol className="steps">
                <li>Start the camera, sit 30–45 cm away, face lit from the front.</li>
                <li>Teach: hold Space, mouth a phrase silently, release. Three times each.</li>
                <li>Speak: mouth any taught phrase. Mouna says it, or asks if unsure.</li>
              </ol>
            )}
            {snap.status === 'idle' && (
              <div className="row" style={{ marginTop: 18, justifyContent: 'center', gap: 16 }}>
                <button className="btn solid" onClick={() => engine.start()}>
                  Start camera
                </button>
                <label className="ghost" style={{ cursor: 'pointer' }}>
                  or play a video file
                  <input
                    type="file"
                    accept="video/*"
                    hidden
                    onChange={(e) => {
                      const f = e.target.files?.[0]
                      if (f) void engine.start('GPU', f)
                    }}
                  />
                </label>
              </div>
            )}
            {snap.status === 'error' && (
              <div className="row" style={{ marginTop: 18, justifyContent: 'center', gap: 16 }}>
                <button className="btn" onClick={() => engine.start()}>
                  Try again
                </button>
                <button className="ghost" onClick={() => engine.start('CPU')}>
                  use CPU
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
