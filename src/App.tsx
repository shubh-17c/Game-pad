import { useEffect, useRef, useState } from 'react'
import { hidRegister, hidSend, hidStatus, isNativeHid, onHidEvent, type PadState } from './hidGamepad'

const B = { A: 1 << 0, B: 1 << 1, X: 1 << 2, Y: 1 << 3, LB: 1 << 4, RB: 1 << 5, LT: 1 << 6, RT: 1 << 7, Back: 1 << 8, Start: 1 << 9, L3: 1 << 10, R3: 1 << 11, Guide: 1 << 12 }

function Stick({ x, y, onChange }: { x: number; y: number; onChange: (x: number, y: number) => void }) {
  const baseRef = useRef<HTMLDivElement>(null)
  const dragging = useRef(false)
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
  const kx = ((x - 128) / 127) * R
  const ky = ((y - 128) / 127) * R
  return (
    <div
      ref={baseRef}
      onPointerDown={(e) => { dragging.current = true; (e.target as HTMLElement).setPointerCapture?.(e.pointerId); setFromEvent(e.clientX, e.clientY) }}
      onPointerMove={(e) => { if (dragging.current) setFromEvent(e.clientX, e.clientY) }}
      onPointerUp={() => { dragging.current = false; onChange(128, 128) }}
      onPointerCancel={() => { dragging.current = false; onChange(128, 128) }}
      style={{ width: 92, height: 92, borderRadius: '50%', background: '#1f2937', position: 'relative', touchAction: 'none', userSelect: 'none', cursor: 'grab', flexShrink: 0 }}
    >
      <div style={{ position: 'absolute', left: `calc(50% + ${kx}px)`, top: `calc(50% + ${ky}px)`, width: 38, height: 38, borderRadius: '50%', background: '#6b7280', border: '2px solid #9ca3af', transform: 'translate(-50%,-50%)' }} />
    </div>
  )
}

function PadBtn({ label, color, active, onDown, onUp, size = 46 }: {
  label: string; color: string; active: boolean; onDown: () => void; onUp: () => void; size?: number
}) {
  return (
    <button
      type="button"
      onPointerDown={(e) => { e.preventDefault(); onDown() }}
      onPointerUp={onUp}
      onPointerLeave={onUp}
      onPointerCancel={onUp}
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
  const [connected, setConnected] = useState(false)
  const [status, setStatus] = useState('Not connected')
  const lastSend = useRef(0)

  useEffect(() => {
    onHidEvent('connection', (e) => {
      const c = Boolean(e['connected'])
      setConnected(c)
      setStatus(c ? `Connected ${String(e['host'] || '')}` : 'Not connected')
    })
    void refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!isNativeHid) return
    const now = performance.now()
    if (now - lastSend.current < 16) {
      const t = setTimeout(() => { lastSend.current = performance.now(); void hidSend(pad) }, 16)
      return () => clearTimeout(t)
    }
    lastSend.current = now
    void hidSend(pad)
  }, [pad])

  const refresh = async () => {
    if (!isNativeHid) { setStatus('Web fallback'); return }
    try {
      const s = await hidStatus() as { connected: boolean; host?: string }
      setConnected(s.connected)
      setStatus(s.connected ? `Connected ${s.host || ''}` : 'Not connected')
    } catch { /* keep */ }
  }

  const connect = async () => setStatus(await hidRegister())
  const press = (m: number) => setPad((p) => ({ ...p, buttons: p.buttons | m }))
  const release = (m: number) => setPad((p) => ({ ...p, buttons: p.buttons & ~m }))
  const hatHold = (h: number) => setPad((p) => ({ ...p, hat: h }))
  const hatRelease = () => setPad((p) => ({ ...p, hat: 8 }))
  const on = (m: number) => (pad.buttons & m) !== 0

  return (
    <div className="phone" style={{ maxWidth: 860, width: '100%', minHeight: '100dvh', padding: 12, position: 'relative', textAlign: 'center' }}>
      {/* corner connect */}
      <button
        type="button"
        onClick={connect}
        title={status}
        style={{
          position: 'absolute', top: 10, right: 10, zIndex: 5,
          display: 'flex', alignItems: 'center', gap: 6,
          fontSize: 13, padding: '6px 12px', borderRadius: 999,
          background: connected ? '#16a34a' : '#4f46e5', color: 'white', border: 'none',
        }}
      >
        <span style={{ width: 8, height: 8, borderRadius: '50%', background: connected ? '#bbf7d0' : '#c7d2fe', display: 'inline-block' }} />
        {connected ? 'Connected' : 'Connect'}
      </button>

      <div style={{ background: '#111827', borderRadius: 18, padding: '12px 14px', color: 'white', marginTop: 34 }}>
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

          {/* center status dot */}
          <div style={{ fontSize: 11, opacity: 0.6, maxWidth: 110, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={status}>
            {isNativeHid ? status : 'web fallback'}
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
