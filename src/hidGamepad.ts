import { Capacitor, registerPlugin } from '@capacitor/core'

export interface PadState {
  buttons: number // 16-bit bitmask
  lx: number // 0..255 (128 center)
  ly: number
  rx: number
  ry: number
  hat: number // 0..7 d-pad, 8 = neutral
}

export interface HidStatus {
  supported: boolean
  bluetoothOn: boolean
  proxy: boolean
  registered: boolean
  connected: boolean
  host?: string
  address?: string
  reportBytes: number
}

export type ConnPhase = 'idle' | 'joining' | 'playing' | 'retry'

interface HidPlugin {
  isEnabled(): Promise<{ enabled: boolean }>
  requestEnable(): Promise<{ enabled: boolean }>
  register(): Promise<Record<string, unknown>>
  unregister(): Promise<{ registered: boolean }>
  getBonded(): Promise<{ devices: { name: string; address: string }[] }>
  connectTo(opts: { address: string }): Promise<{ connecting: boolean }>
  sendPadState(state: PadState): Promise<{ sent: boolean }>
  sendAll(state: PadState & MouseDelta): Promise<{ sent: boolean }>
  getStatus(): Promise<HidStatus>
  addListener(event: 'connection' | 'registration' | 'rumble' | 'proxy', cb: (e: Record<string, unknown>) => void): Promise<{ remove: () => void }>
}

interface PluginError {
  message?: string
  code?: string
}

const NativeHid = Capacitor.isNativePlatform()
  ? registerPlugin<HidPlugin>('HidGamepad')
  : null

export const isNativeHid = NativeHid !== null

const LAST_HOST_KEY = 'openwork-pad:last-host'

const errCode = (e: unknown): string | undefined => (e as PluginError)?.code
const errMsg = (e: unknown): string => (e as PluginError)?.message || String(e)

export const getLastHost = (): string | null => {
  try { return localStorage.getItem(LAST_HOST_KEY) } catch { return null }
}
export const saveLastHost = (address: string): void => {
  try { localStorage.setItem(LAST_HOST_KEY, address) } catch { /* noop */ }
}
export const forgetLastHost = (): void => {
  try { localStorage.removeItem(LAST_HOST_KEY) } catch { /* noop */ }
}

/** One-tap connect: enables BT if off, waits for perms/proxy, registers. */
export async function hidConnect(): Promise<string> {
  if (!NativeHid) return 'Web preview: install the APK on Android 9+ for real HID.'
  try {
    const r = await NativeHid.register()
    if (r['registered'] === true) return 'Already registered as "OpenWork Pad" — pair it from the host Bluetooth settings.'
    return 'Registering as "OpenWork Pad" — pair it from the host Bluetooth settings.'
  } catch (e) {
    if (errCode(e) === 'BT_OFF') {
      try {
        await NativeHid.requestEnable()
        return 'Bluetooth on — finishing registration, pair "OpenWork Pad" from the host.'
      } catch (e2) {
        return `Connect failed: ${errMsg(e2)}`
      }
    }
    return `Connect failed: ${errMsg(e)}`
  }
}

/**
 * Connect-and-play: register, then rejoin the remembered host (or first
 * bonded host). Reports phase changes via onPhase. New hosts pair from host
 * settings — the connection event saves them for next launch.
 */
export async function hidAutoStart(onPhase: (phase: ConnPhase, note: string) => void): Promise<void> {
  if (!NativeHid) {
    onPhase('idle', 'Web preview: install the APK on Android 9+ for real HID.')
    return
  }
  onPhase('joining', 'Waking up gamepad…')
  const msg = await hidConnect()
  if (msg.startsWith('Connect failed')) {
    onPhase('idle', msg)
    return
  }
  const target = getLastHost()
  if (target) {
    onPhase('joining', 'Rejoining last host…')
    try {
      await NativeHid.connectTo({ address: target })
      return // connection event flips phase to playing
    } catch {
      // fall through to bonded scan
    }
  }
  try {
    const bonded = await NativeHid.getBonded()
    const first = bonded.devices[0]
    if (first) {
      onPhase('joining', `Rejoining ${first.name}…`)
      await NativeHid.connectTo({ address: first.address })
      return
    }
  } catch {
    // no bonded hosts yet — user pairs from host settings
  }
  onPhase('joining', 'Registered — pair "OpenWork Pad" from host settings.')
}

/** Keep the screen awake while gaming (real gamepads don't sleep). */
export function keepAwake(): void {
  if (typeof navigator === 'undefined') return
  const nav = navigator as Navigator & {
    wakeLock?: { request: (t: string) => Promise<{ addEventListener: (e: string, f: () => void) => void }> }
  }
  if (!nav.wakeLock) return
  let lock: { addEventListener: (e: string, f: () => void) => void } | null = null
  const ask = () => {
    nav.wakeLock?.request('screen').then((l) => {
      lock = l
      lock.addEventListener('release', () => { lock = null })
    }).catch(() => { /* denied or unsupported — game still works */ })
  }
  ask()
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && !lock) ask()
  })
}

export interface MouseDelta {
  mBtn: number // bit0=left, bit1=right, bit2=middle
  dx: number // -127..127 relative
  dy: number
  wheel: number
}

/**
 * Derive desktop-mouse movement from the pad: right stick = cursor
 * (deadzone + quadratic curve + fractional accumulation for smooth slow
 * moves), A = left click, B = right click, D-pad up/down = wheel.
 */
export function deriveMouse(pad: PadState, acc: { x: number; y: number }): MouseDelta {
  const dz = 10
  const max = 22
  const curve = (d: number): number => {
    const a = Math.abs(d)
    if (a <= dz) return 0
    const t = (a - dz) / (127 - dz)
    return Math.sign(d) * t * t * max
  }
  acc.x += curve(pad.rx - 128)
  acc.y += curve(pad.ry - 128)
  const dx = Math.trunc(acc.x)
  const dy = Math.trunc(acc.y)
  acc.x -= dx
  acc.y -= dy
  const mBtn = (pad.buttons & 1 ? 1 : 0) | (pad.buttons & 2 ? 2 : 0)
  const wheel = pad.hat === 0 ? 1 : pad.hat === 4 ? -1 : 0
  return { mBtn, dx, dy, wheel }
}

export async function hidSendAll(state: PadState, mouse: MouseDelta): Promise<boolean> {
  if (!NativeHid) return false
  try {
    const r = await NativeHid.sendAll({ ...state, ...mouse })
    return r.sent
  } catch {
    return false
  }
}

export async function hidSend(state: PadState): Promise<boolean> {
  if (!NativeHid) return false
  try {
    const r = await NativeHid.sendPadState(state)
    return r.sent
  } catch {
    return false
  }
}

export async function hidStatus(): Promise<HidStatus | null> {
  if (!NativeHid) return null
  try {
    return await NativeHid.getStatus()
  } catch {
    return null
  }
}

export function onHidEvent(
  event: 'connection' | 'registration' | 'rumble' | 'proxy',
  cb: (e: Record<string, unknown>) => void
): void {
  if (!NativeHid) return
  void NativeHid.addListener(event, cb)
}
