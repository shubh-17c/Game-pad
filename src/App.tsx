import { useEffect, useRef, useState } from 'react'
import { deriveMouse, hidAutoStart, hidSend, hidSendAll, isNativeHid, keepAwake, onHidEvent, saveLastHost, type ConnPhase, type PadState } from './hidGamepad'

const B = { A: 1 << 0, B: 1 << 1, X: 1 << 2, Y: 1 << 3, LB: 1 << 4, RB: 1 << 5, LT: 1 << 6, RT: 1 << 7, Back: 1 << 8, Start: 1 << 9, L3: 1 << 10, R3: 1 << 11, Guide: 1 << 12 }

// fingers physically down right now (any control) — watchdog must not steal held inputs
const touchRefs = { fingers: 0 }
const NEUTRAL: PadState = { buttons: 0, lx: 128, ly: 128, rx: 128, ry: 128, hat: 8 }

function Stick({ x, y, onChange }: { x: number; y: number; onChange: (x: number, y: number) => void }) {
  const baseRef = useRef<HTMLDivElement>(null)
  const owner = useRef<number | null>(null) // first finger owns the stick until release
  const R = 32
  const setFromEvent = (clientX: number, clientY: number) => {
    const el = baseRef.current
    if (!el) return
    const r = el.getBoundingClientRect()
    const cx = r.left + r.width / 2
    const cy = r.top + r.height / 2
    let dx = clientX - cx
    let dy = clientY - cy
    const len = Math.hypot(dx, dy)
    if (len > R) { dx = (dx / len) * R; dy = (dy / len) * R }
    onChange(Math.round(128 + (dx / R) * 127), Math.round(128 + (dy / R) * 127))
  }
  const end = (id: number) => {
    if (owner.current !== id) return
    owner.current = null
    touchRefs.fingers = Math.max(0, touchRefs.fingers - 1)
    onChange(128, 128)
  }
  const kx = ((x - 128) / 127) * R
  const ky = ((y - 128) / 127) * R
  return (
    <div
      ref={baseRef}
      onPointerDown={(e) => {
        if (owner.current !== null) return // second finger can't hijack
        owner.current = e.pointerId
        touchRefs.fingers += 1
        try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId) } catch { /* noop */ }
        setFromEvent(e.clientX, e.clientY)
      }}
      onPointerMove={(e) => { if (owner.current === e.pointerId) setFromEvent(e.clientX, e.clientY) }}
      onPointerUp={(e) => end(e.pointerId)}
      onPointerCancel={(e) => end(e.pointerId)}
      style={{ width: 92, height: 92, borderRadius: '50%', background: '#1f2937', position: 'relative', touchAction: 'none', userSelect: 'none', cursor: 'grab', flexShrink: 0 }}
    >
      <div style={{ position: 'absolute', left: `calc(50% + ${kx}px)`, top: `calc(50% + ${ky}px)`, width: 38, height: 38, borderRadius: '50%', background: '#6b7280', border: '2px solid #9ca3af', transform: 'translate(-50%,-50%)', pointerEvents: 'none' }} />
    </div>
  )
}

function PadBtn({ label, color, active, onDown, onUp, size = 46 }: {
  label: string; color: string; active: boolean; onDown: () => void; onUp: () => void; size?: number
}) {
  const pid = useRef<number | null>(null)
  const drop = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (pid.current !== e.pointerId) return
    pid.current = null
    touchRefs.fingers = Math.max(0, touchRefs.fingers - 1)
    onUp()
  }
  const down = (e: React.PointerEvent<HTMLButtonElement>) => {
    e.preventDefault()
    if (pid.current !== null) return // already held — no double-fire
    pid.current = e.pointerId
    touchRefs.fingers += 1
    try { e.currentTarget.setPointerCapture(e.pointerId) } catch { /* noop */ }
    onDown()
  }
  const end = (e: React.PointerEvent<HTMLButtonElement>) => drop(e)
  const leave = (e: React.PointerEvent<HTMLButtonElement>) => {
    // finger slid off but still down (captured) = keep holding; truly gone = release
    if (pid.current !== e.pointerId) return
    try {
      if (e.currentTarget.hasPointerCapture(e.pointerId)) return
    } catch { /* noop */ }
    drop(e)
  }
  return (
    <button
      type="button"
      onPointerDown={down}
      onPointerUp={end}
      onPointerLeave={leave}
      onPointerCancel={end}
      onContextMenu={(e) => e.preventDefault()}
      style={{
        width: size, height: size, borderRadius: '50%', fontSize: 14, fontWeight: 700,
        background: active ? '#16a34a' : color, color: 'white',
        border: active ? '2px solid #14532d' : '2px solid rgba(0,0,0,0.25)',
        touchAction: 'none', userSelect: 'none', padding: 0, flexShrink: 0,
      }}
    >
      {label}
    </button>
  )
}

export default function App() {
  const [pad, setPad] = useState<PadState>({ buttons: 0, lx: 128, ly: 128, rx: 128, ry: 128, hat: 8 })
  const [phase, setPhase] = useState<ConnPhase>('idle')
  const [status, setStatus] = useState('Starting…')
  const [mouseMode, setMouseMode] = useState(true) // right stick = cursor, A/B = clicks
  const [yInvert, setYInvert] = useState(false) // some hosts flip Y — toggle if up/down feel wrong
  const lastSend = useRef(0)
  const acc = useRef({ x: 0, y: 0 })
  const lastSent = useRef('') // byte-signature of last transmitted report
  const lastInput = useRef(Date.now())
  const centered = useRef({ lx: true, ly: true, rx: true, ry: true })
  const retries = useRef(0)
  const phaseRef = useRef<ConnPhase>('idle')

  const note = (p: ConnPhase, n: string) => { phaseRef.current = p; setPhase(p); setStatus(n) }
  const start = () => { retries.current = 0; void hidAutoStart(note) }

  useEffect(() => {
    keepAwake()
    onHidEvent('connection', (e) => {
      if (Boolean(e['connected'])) {
        retries.current = 0
        const addr = e['address']
        if (typeof addr === 'string' && addr) saveLastHost(addr)
        note('playing', `Playing on ${String(e['host'] || 'host')}`)
      } else if (phaseRef.current === 'playing' || phaseRef.current === 'retry') {
        // link dropped mid-game — rejoin like a real pad, for as long as it takes
        retries.current += 1
        note('retry', `Reconnecting… (${retries.current})`)
        setTimeout(() => { void hidAutoStart(note) }, 5000)
      } else {
        note('idle', String(e['note'] || 'Not connected — tap the pill.'))
      }
    })
    start()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!isNativeHid) return
    const now = performance.now()
    // deadzone WITH hysteresis: settle below 6, break out above 10 — resting
    // fingers can't dither on the boundary and spam the radio
    const snap = (v: number, k: 'lx' | 'ly' | 'rx' | 'ry'): number => {
      const d = Math.abs(v - 128)
      if (centered.current[k]) {
        if (d > 10) { centered.current[k] = false; return v }
        return 128
      }
      if (d <= 6) { centered.current[k] = true; return 128 }
      return v
    }
    const flip = (v: number): number => (yInvert ? 256 - v : v)
    // cardinal lock: a mostly-straight push reads as pure up/down/left/right, never forced diagonal
    const lock = (x: number, y: number): [number, number] => {
      const dx = x - 128
      const dy = y - 128
      if (Math.abs(dx) > 2.5 * Math.abs(dy)) return [x, 128]
      if (Math.abs(dy) > 2.5 * Math.abs(dx)) return [128, y]
      return [x, y]
    }
    const [lx, ly] = lock(snap(pad.lx, 'lx'), flip(snap(pad.ly, 'ly')))
    const [rx, ry] = lock(snap(pad.rx, 'rx'), flip(snap(pad.ry, 'ry')))
    const out: PadState = { ...pad, lx, ly, rx, ry }
    const tick = () => {
      lastSend.current = performance.now()
      lastInput.current = Date.now()
      const m = mouseMode ? deriveMouse(out, acc.current) : null
      const key = JSON.stringify(m ? { ...out, ...m } : out)
      if (key === lastSent.current) return // free state: byte-identical = radio silence
      lastSent.current = key
      if (m) void hidSendAll(out, m)
      else void hidSend(out)
    }
    if (now - lastSend.current < 16) {
      const t = setTimeout(tick, 16)
      return () => clearTimeout(t)
    }
    tick()
  }, [pad, mouseMode, yInvert])

  useEffect(() => {
    // stuck-input watchdog: a lost pointer-up must never hold a button forever.
    // fingers down = held on purpose, never steal. none down + stale = force free state once.
    const t = setInterval(() => {
      if (touchRefs.fingers > 0) return
      if (Date.now() - lastInput.current <= 250) return
      setPad((p) => {
        if (p.buttons === 0 && p.lx === 128 && p.ly === 128 && p.rx === 128 && p.ry === 128 && p.hat === 8) return p
        acc.current = { x: 0, y: 0 }
        centered.current = { lx: true, ly: true, rx: true, ry: true }
        return { ...NEUTRAL }
      })
    }, 250)
    const release = () => {
      acc.current = { x: 0, y: 0 }
      centered.current = { lx: true, ly: true, rx: true, ry: true }
      touchRefs.fingers = 0
      setPad({ ...NEUTRAL })
    }
    const onHide = () => { if (document.visibilityState === 'hidden') release() }
    document.addEventListener('visibilitychange', onHide)
    window.addEventListener('blur', release)
    return () => { clearInterval(t); document.removeEventListener('visibilitychange', onHide); window.removeEventListener('blur', release) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const pill = phase === 'playing'
    ? { bg: '#16a34a', dot: '#bbf7d0', label: 'Playing' }
    : phase === 'joining' || phase === 'retry'
      ? { bg: '#d97706', dot: '#fde68a', label: 'Joining…' }
      : { bg: '#4f46e5', dot: '#c7d2fe', label: 'Play' }

  const press = (m: number) => setPad((p) => ({ ...p, buttons: p.buttons | m }))
  const release = (m: number) => setPad((p) => ({ ...p, buttons: p.buttons & ~m }))
  const hatHold = (h: number) => setPad((p) => ({ ...p, hat: h }))
  const hatRelease = () => setPad((p) => ({ ...p, hat: 8 }))
  const on = (m: number) => (pad.buttons & m) !== 0

  return (
    <div className="phone" style={{ maxWidth: 860, width: '100%', height: '100dvh', overflow: 'hidden', padding: '8px 12px', position: 'relative', textAlign: 'center', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
      {/* corner status pill — tap only to retry */}
      <button
        type="button"
        onClick={start}
        title={status}
        style={{
          position: 'absolute', top: 10, right: 10, zIndex: 5,
          display: 'flex', alignItems: 'center', gap: 6,
          fontSize: 13, padding: '6px 12px', borderRadius: 999,
          background: pill.bg, color: 'white', border: 'none',
        }}
      >
        <span style={{ width: 8, height: 8, borderRadius: '50%', background: pill.dot, display: 'inline-block' }} />
        {pill.label}
      </button>

      <div style={{ background: '#111827', borderRadius: 18, padding: '10px 14px', color: 'white' }}>
        {/* top shoulder row */}
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
          <div style={{ display: 'flex', gap: 6 }}>
            <PadBtn label="LB" color="#4f46e5" size={42} active={on(B.LB)} onDown={() => press(B.LB)} onUp={() => release(B.LB)} />
            <PadBtn label="LT" color="#7c3aed" size={42} active={on(B.LT)} onDown={() => press(B.LT)} onUp={() => release(B.LT)} />
          </div>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            <PadBtn label="◀" color="#374151" size={34} active={on(B.Back)} onDown={() => press(B.Back)} onUp={() => release(B.Back)} />
            <PadBtn label="●" color="#059669" size={34} active={on(B.Guide)} onDown={() => press(B.Guide)} onUp={() => release(B.Guide)} />
            <PadBtn label="▶" color="#374151" size={34} active={on(B.Start)} onDown={() => press(B.Start)} onUp={() => release(B.Start)} />
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <PadBtn label="RT" color="#7c3aed" size={42} active={on(B.RT)} onDown={() => press(B.RT)} onUp={() => release(B.RT)} />
            <PadBtn label="RB" color="#4f46e5" size={42} active={on(B.RB)} onDown={() => press(B.RB)} onUp={() => release(B.RB)} />
          </div>
        </div>

        {/* landscape main row: left | center | right */}
        <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
          {/* left: stick + dpad */}
          <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', gap: 12 }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
              <Stick x={pad.lx} y={pad.ly} onChange={(x, y) => setPad((p) => ({ ...p, lx: x, ly: y }))} />
              <PadBtn label="L3" color="#4b5563" size={34} active={on(B.L3)} onDown={() => press(B.L3)} onUp={() => release(B.L3)} />
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 38px)', gap: 3 }}>
              <span />
              <PadBtn label="▲" color="#374151" size={38} active={pad.hat === 0} onDown={() => hatHold(0)} onUp={hatRelease} />
              <span />
              <PadBtn label="◀" color="#374151" size={38} active={pad.hat === 6} onDown={() => hatHold(6)} onUp={hatRelease} />
              <span style={{ width: 38, height: 38 }} />
              <PadBtn label="▶" color="#374151" size={38} active={pad.hat === 2} onDown={() => hatHold(2)} onUp={hatRelease} />
              <span />
              <PadBtn label="▼" color="#374151" size={38} active={pad.hat === 4} onDown={() => hatHold(4)} onUp={hatRelease} />
              <span />
            </div>
          </div>

          {/* center status + mouse toggle */}
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
            <div style={{ fontSize: 11, opacity: 0.6, maxWidth: 110, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={status}>
              {isNativeHid ? status : 'web fallback'}
            </div>
            <button
              type="button"
              onClick={() => setMouseMode((v) => !v)}
              title={mouseMode ? 'Mouse on: right stick moves cursor, A/B click' : 'Mouse off: pure gamepad'}
              style={{ fontSize: 11, padding: '3px 10px', borderRadius: 999, border: 'none', background: mouseMode ? '#0ea5e9' : '#374151', color: 'white' }}
            >
              🖱 {mouseMode ? 'on' : 'off'}
            </button>
            <button
              type="button"
              onClick={() => setYInvert((v) => !v)}
              title="Flip up/down if a host inverts Y"
              style={{ fontSize: 11, padding: '3px 10px', borderRadius: 999, border: 'none', background: yInvert ? '#d97706' : '#374151', color: 'white' }}
            >
              Y⇅ {yInvert ? 'flip' : 'std'}
            </button>
          </div>

          {/* right: ABXY + stick */}
          <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', gap: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 44px)', gap: 3, alignItems: 'center' }}>
              <span />
              <PadBtn label="Y" color="#eab308" size={44} active={on(B.Y)} onDown={() => press(B.Y)} onUp={() => release(B.Y)} />
              <span />
              <PadBtn label="X" color="#2563eb" size={44} active={on(B.X)} onDown={() => press(B.X)} onUp={() => release(B.X)} />
              <span />
              <PadBtn label="B" color="#dc2626" size={44} active={on(B.B)} onDown={() => press(B.B)} onUp={() => release(B.B)} />
              <span />
              <PadBtn label="A" color="#16a34a" size={44} active={on(B.A)} onDown={() => press(B.A)} onUp={() => release(B.A)} />
              <span />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
              <Stick x={pad.rx} y={pad.ry} onChange={(x, y) => setPad((p) => ({ ...p, rx: x, ry: y }))} />
              <PadBtn label="R3" color="#4b5563" size={34} active={on(B.R3)} onDown={() => press(B.R3)} onUp={() => release(B.R3)} />
            </div>
          </div>
        </div>
      </div>
      <p className="hint" style={{ margin: '6px 0 0' }}>Rotate phone to landscape · 0x{pad.buttons.toString(16).padStart(4, '0')}</p>
    </div>
  )
}
