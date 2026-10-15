import { Capacitor, registerPlugin } from '@capacitor/core'

export interface PadState {
  buttons: number // 16-bit bitmask: bit0=A, bit1=B, bit2=X, bit3=Y, bit4=LB, bit5=RB, bit6=Back, bit7=Start, bit8=L3, bit9=R3, bit10=Guide
  lx: number // 0..255 (128 center)
  ly: number
  rx: number
  ry: number
  hat: number // 0..7 d-pad, 8 = neutral
}

export interface HidStatus {
  supported: boolean
  proxy: boolean
  registered: boolean
  connected: boolean
  host?: string
  address?: string
  reportBytes: number
}

interface HidPlugin {
  register(): Promise<{ registering: boolean; name: string } | { proxy: string }>
  unregister(): Promise<{ registered: boolean }>
  sendPadState(state: PadState): Promise<{ sent: boolean }>
  getStatus(): Promise<HidStatus>
  addListener(event: 'connection' | 'registration' | 'rumble' | 'proxy', cb: (e: Record<string, unknown>) => void): Promise<{ remove: () => void }>
}

const NativeHid = Capacitor.isNativePlatform()
  ? registerPlugin<HidPlugin>('HidGamepad')
  : null

export const isNativeHid = NativeHid !== null

export async function hidRegister(): Promise<string> {
  if (!NativeHid) return 'Web preview: native HID needs Android build — UI sends locally only.'
  try {
    const r = await NativeHid.register()
    if ('proxy' in r) return 'Starting Bluetooth HID proxy… tap Register again.'
    return `Registering as "${(r as { name: string }).name}" — pair it in PC/Android Bluetooth settings.`
  } catch (e) {
    return `Register failed: ${(e as Error).message || e}`
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

export async function hidStatus(): Promise<HidStatus | { note: string }> {
  if (!NativeHid) return { note: 'web-fallback' } as unknown as HidStatus
  return NativeHid.getStatus()
}

export function onHidEvent(
  event: 'connection' | 'rumble',
  cb: (e: Record<string, unknown>) => void
): void {
  if (!NativeHid) return
  void NativeHid.addListener(event, cb)
}
