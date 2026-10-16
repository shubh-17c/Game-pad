# OpenWork Pad 🎮

Your phone, as a real Bluetooth gamepad. A Vite + React + TypeScript + Capacitor app
(`com.openwork.mobile`) that advertises itself as a **Classic Bluetooth HID gamepad**
and streams sticks + buttons to any host that accepts generic HID (PC, Android, Steam).

## How it works

```
Touch UI (React) → HidGamepad plugin (Capacitor) → BluetoothHidDevice (Android) → Host
```

- The UI builds a 16-bit button mask + 4 stick axes + hat switch.
- The native plugin packs them into a **7-byte HID input report (ID 1)** and calls
  `BluetoothHidDevice.sendReport()` on every change (~60 Hz throttled).
- Host → phone **output report (ID 2)** carries rumble levels, shown in-app + phone vibrate.

Report map: Usage Gamepad, 16 buttons, X/Y/Z/Rx 0–255 (128 = center), hat 0–7
(8 = neutral), vendor output for rumble.

## Controls

| Input | HID mapping |
|---|---|
| Left / Right stick (drag, springs back) | LX/LY, RX/RY axes |
| A, B, X, Y | Buttons 0–3 |
| LB, RB, LT, RT | Buttons 4–7 |
| Back, Start, Guide | Buttons 8, 9, 12 |
| L3, R3 (stick click) | Buttons 10–11 |
| D-pad | Hat switch |

All buttons are momentary (press-and-hold), like hardware.

## Quick start

### Web / UI preview (no HID, layout only)

```bash
npm install
npm run dev -- --host --port 5173   # live, HMR
# or
npm run build && npm run preview -- --host --port 4173
```

Open the shown `http://<lan-ip>:<port>` on your phone (same Wi-Fi).
The UI runs in fallback mode — fully interactive, just no Bluetooth advertising.

### Android build (real HID gamepad)

Prereqs: Android SDK (platform + build-tools 34), JDK 17+, phone with Android 9+.

```bash
npm run build
npx cap sync android
cd android && ./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and Run ▶️ to your phone.
Tip for iteration: `npx cap run android --live-reload --host=<your-lan-ip>`.

### Pairing

1. Install the APK, grant **Nearby devices** permission, turn Bluetooth on.
2. Tap **Connect** (top-right corner) — phone registers as `OpenWork Pad`.
3. On the host (PC/Android): Bluetooth settings → pair `OpenWork Pad`.
4. Dot turns green. Move sticks / hold buttons — the host sees a standard gamepad
   (verify at `gamepad-tester.com`).

## Permissions (Android)

`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`
(+ `ACCESS_FINE_LOCATION` pre-Android 12 on some OEMs). Declared in
`android/app/src/main/AndroidManifest.xml`; requested at runtime on first Register.

## Key files

- `src/App.tsx` — landscape gamepad UI (sticks, D-pad, all keys, corner Connect)
- `src/hidGamepad.ts` — Capacitor bridge with web fallback
- `android/app/src/main/java/com/openwork/mobile/hid/HidGamepadPlugin.java` — HID device plugin
- `android/app/src/main/java/com/openwork/mobile/MainActivity.java` — plugin registration

## Limitations

- Generic HID only. **Xbox / PlayStation / Switch require first-party auth chips**
  and will refuse pairing — PC, Linux, Android, Steam work.
- HID device role needs **Android 9+**; iOS cannot advertise as HID.
- Web Bluetooth (central-only) can't advertise — native build required for HID.

## Known issues

Open (under investigation):
- **D-pad on some Android hosts**: hat directions can trigger host-UI edge behavior
  (e.g. exit/select in launchers) instead of game-style navigation, while face buttons
  map correctly. Suspected host focus handling, not our report data (hat values verified
  pure: single values 0/2/4/6, neutral 8). Planned: optional D-pad-as-buttons mode.
- **Browser Gamepad API ritual**: Chrome/Firefox hide controllers until a button is
  pressed with the tester tab focused. Always press A on the pad after opening
  `gamepad-tester.com`; `navigator.getGamepads()` stays empty otherwise.
- **Host descriptor cache**: after any descriptor change you MUST forget the bond
  (`bluetoothctl remove <MAC>`) and re-pair, or the host keeps parsing the old layout.

Fixed in current build (kept here for reference):
- Stick drift / radio spam at rest → hysteresis deadzone (settle < 6, break > 10) +
  byte-level send dedup: hands-off = one neutral report, then silence.
- Phantom multi-taps → pointer capture + one-press-per-finger guard on all buttons.
- Stick hijack by second finger → first-finger ownership until release.
- Forced diagonals → cardinal lock (2.5× dominant-axis rule); true diagonals still pass.
- Sleep disconnects → native `FLAG_KEEP_SCREEN_ON` + persistent 5s rejoin loop.
- Register took 3 taps with cryptic errors → one-tap chain (BT enable → perms →
  proxy → register) with specific failure reasons.
- App looked like a zoomable webpage → locked landscape, immersive fullscreen,
  WebView + viewport zoom disabled, fixed non-scrolling layout.

## Roadmap

- [x] Lock landscape in the APK (`screenOrientation`)
- [x] One-tap connect-and-play (auto-register, remembered host, wake lock)
- [x] Composite mouse (cursor + clicks + wheel) alongside gamepad
- [x] Drift-free free state (hysteresis + dedup + stuck-input watchdog)
- [ ] D-pad-as-buttons mode for picky hosts
- [ ] Pressure-sensitive triggers (dedicated HID bytes)
- [ ] Battery level reporting to host
- [ ] Release-signed AAB for Play publishing
