package com.openwork.mobile.hid;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.Executor;

/**
 * HidGamepad — phone advertises as a Classic Bluetooth HID gamepad.
 * Closest to a real gamepad Android allows without console vendor auth.
 *
 * Hosts that accept generic HID (Windows / Linux / Android / Steam)
 * see "OpenWork Pad" as a Standard Gamepad.
 * Xbox / PlayStation / Switch require first-party auth chips and will NOT pair.
 */
@CapacitorPlugin(name = "HidGamepad")
public class HidGamepadPlugin extends Plugin {

    private static final int REQ_ENABLE_BT = 9001;
    private static final String CODE_BT_OFF = "BT_OFF";
    private static final String CODE_BT_NO_PERM = "BT_NO_PERM";
    private static final String CODE_BT_NO_PROXY = "BT_NO_PROXY";
    private static final String CODE_BT_BUSY = "BT_BUSY";

    // 7-byte input report (Report ID 1):
    // [btnLo, btnHi, LX, LY, RX, RY, hatNibble+pad]
    // LX/LY/RX/RY 0..255 (128 center). hat 0..7, 8 = neutral.
    private static final byte[] REPORT_MAP = new byte[]{
            (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x05, // Usage (Game Pad)
            (byte) 0xA1, (byte) 0x01, // Collection (Application)
            (byte) 0xA1, (byte) 0x00, // Collection (Physical)
            (byte) 0x85, (byte) 0x01, // Report ID (1)
            (byte) 0x05, (byte) 0x09, // Usage Page (Button)
            (byte) 0x19, (byte) 0x01, // Usage Minimum (1)
            (byte) 0x29, (byte) 0x10, // Usage Maximum (16)
            (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
            (byte) 0x25, (byte) 0x01, // Logical Maximum (1)
            (byte) 0x95, (byte) 0x10, // Report Count (16)
            (byte) 0x75, (byte) 0x01, // Report Size (1)
            (byte) 0x81, (byte) 0x02, // Input (Data,Var,Abs)
            (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x30, // Usage (X)  -> LX
            (byte) 0x09, (byte) 0x31, // Usage (Y)  -> LY
            (byte) 0x09, (byte) 0x32, // Usage (Z)  -> RX
            (byte) 0x09, (byte) 0x33, // Usage (Rx) -> RY
            (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
            (byte) 0x26, (byte) 0xFF, (byte) 0x00, // Logical Maximum (255)
            (byte) 0x95, (byte) 0x04, // Report Count (4)
            (byte) 0x75, (byte) 0x08, // Report Size (8)
            (byte) 0x81, (byte) 0x02, // Input (Data,Var,Abs)
            (byte) 0x09, (byte) 0x39, // Usage (Hat switch)
            (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
            (byte) 0x25, (byte) 0x07, // Logical Maximum (7)
            (byte) 0x95, (byte) 0x01, // Report Count (1)
            (byte) 0x75, (byte) 0x04, // Report Size (4)
            (byte) 0x81, (byte) 0x02, // Input
            (byte) 0x95, (byte) 0x01, // Report Count (1) padding
            (byte) 0x75, (byte) 0x04, // Report Size (4)
            (byte) 0x81, (byte) 0x01, // Input (Constant)
            (byte) 0xC0, // End Collection
            // Output report ID 2: rumble left/right 0..255 (vendor page so any host accepts it)
            (byte) 0x85, (byte) 0x02, // Report ID (2)
            (byte) 0x06, (byte) 0x00, (byte) 0xFF, // Usage Page (Vendor)
            (byte) 0x09, (byte) 0x01, // Usage (1)
            (byte) 0x15, (byte) 0x00,
            (byte) 0x26, (byte) 0xFF, (byte) 0x00,
            (byte) 0x95, (byte) 0x02, // 2 bytes
            (byte) 0x75, (byte) 0x08,
            (byte) 0x91, (byte) 0x02, // Output (Data,Var,Abs)
            (byte) 0xC0, // End Collection
            // ---- Mouse (Report ID 3): desktop cursor + clicks ----
            // 4-byte payload: [buttons, dx, dy, wheel]
            (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x02, // Usage (Mouse)
            (byte) 0xA1, (byte) 0x01, // Collection (Application)
            (byte) 0x85, (byte) 0x03, // Report ID (3)
            (byte) 0x09, (byte) 0x01, // Usage (Pointer)
            (byte) 0xA1, (byte) 0x00, // Collection (Physical)
            (byte) 0x05, (byte) 0x09, // Usage Page (Button)
            (byte) 0x19, (byte) 0x01, // Usage Minimum (1)
            (byte) 0x29, (byte) 0x03, // Usage Maximum (3)
            (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
            (byte) 0x25, (byte) 0x01, // Logical Maximum (1)
            (byte) 0x95, (byte) 0x03, // Report Count (3)
            (byte) 0x75, (byte) 0x01, // Report Size (1)
            (byte) 0x81, (byte) 0x02, // Input (Data,Var,Abs)
            (byte) 0x95, (byte) 0x01, // Report Count (1)
            (byte) 0x75, (byte) 0x05, // Report Size (5)
            (byte) 0x81, (byte) 0x01, // Input (Constant padding)
            (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x30, // Usage (X)
            (byte) 0x09, (byte) 0x31, // Usage (Y)
            (byte) 0x09, (byte) 0x38, // Usage (Wheel)
            (byte) 0x15, (byte) 0x81, // Logical Minimum (-127)
            (byte) 0x25, (byte) 0x7F, // Logical Maximum (127)
            (byte) 0x75, (byte) 0x08, // Report Size (8)
            (byte) 0x95, (byte) 0x03, // Report Count (3)
            (byte) 0x81, (byte) 0x06, // Input (Data,Var,Rel)
            (byte) 0xC0, // End Collection
            (byte) 0xC0 // End Collection
    };

    private BluetoothAdapter btAdapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice hostDevice;
    private boolean registered = false;
    private boolean proxyReady = false;
    private boolean proxyRequested = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = (BluetoothHidDevice) proxy;
                proxyReady = true;
                JSObject evt = new JSObject();
                evt.put("proxy", true);
                notifyListeners("proxy", evt);
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                proxyReady = false;
                proxyRequested = false;
                hidDevice = null;
                registered = false;
                hostDevice = null;
            }
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean reg) {
            registered = reg;
            JSObject evt = new JSObject();
            evt.put("registered", reg);
            notifyListeners("registration", evt);
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                hostDevice = device;
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device != null && device.equals(hostDevice)) {
                    hostDevice = null;
                }
            }
            JSObject evt = new JSObject();
            evt.put("connected", hostDevice != null);
            evt.put("state", state);
            if (device != null) {
                try { evt.put("host", device.getName() + " " + device.getAddress()); }
                catch (SecurityException se) { evt.put("host", device.getAddress()); }
            }
            notifyListeners("connection", evt);
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            // Host -> phone output report (rumble). Forward to web for vibration.
            JSObject evt = new JSObject();
            evt.put("type", type);
            evt.put("id", id);
            if (data != null && data.length >= 2) {
                evt.put("strong", data[0] & 0xFF);
                evt.put("weak", data[1] & 0xFF);
            }
            notifyListeners("rumble", evt);
        }
    };

    // ---------- helpers ----------

    private BluetoothAdapter adapterOrNull(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            call.reject("HID gamepad needs Android 9 or newer");
            return null;
        }
        Context ctx = getContext();
        BluetoothManager mgr = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mgr == null) {
            call.reject("This device has no Bluetooth");
            return null;
        }
        BluetoothAdapter adapter = mgr.getAdapter();
        if (adapter == null) {
            call.reject("Bluetooth adapter missing — is Bluetooth supported on this device?");
            return null;
        }
        btAdapter = adapter;
        return adapter;
    }

    private boolean hasBtPermissions() {
        Context ctx = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBtPermissions() {
        Activity act = getActivity();
        if (act == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(act, new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, 1001);
        }
    }

    private void requestProxy() {
        if (proxyRequested || proxyReady) return;
        proxyRequested = true;
        try {
            btAdapter.getProfileProxy(getContext(), profileListener, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException se) {
            proxyRequested = false;
        }
    }

    /** Poll until proxy ready (HID profile binds async), then run next. */
    private void waitForProxy(PluginCall call, Runnable next) {
        requestProxy();
        final long deadline = android.os.SystemClock.uptimeMillis() + 4000;
        final Handler h = main;
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (proxyReady && hidDevice != null) {
                next.run();
            } else if (android.os.SystemClock.uptimeMillis() >= deadline) {
                proxyRequested = false;
                call.reject("Bluetooth HID service didn't start — toggle Bluetooth off/on and tap Connect again.", CODE_BT_NO_PROXY);
            } else {
                h.postDelayed(poll[0], 250);
            }
        };
        h.post(poll[0]);
    }

    /** Poll until runtime permissions granted (user is in the system dialog), then run next. */
    private void waitForPermissions(PluginCall call, Runnable next) {
        if (hasBtPermissions()) {
            next.run();
            return;
        }
        requestBtPermissions();
        final long deadline = android.os.SystemClock.uptimeMillis() + 8000;
        final Handler h = main;
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (hasBtPermissions()) {
                next.run();
            } else if (android.os.SystemClock.uptimeMillis() >= deadline) {
                call.reject("Nearby-devices permission denied — allow it in Settings → Apps → OpenWork Pad → Permissions, then tap Connect.", CODE_BT_NO_PERM);
            } else {
                h.postDelayed(poll[0], 400);
            }
        };
        h.postDelayed(poll[0], 600);
    }

    private void doRegisterApp(PluginCall call) {
        if (registered && hidDevice != null) {
            JSObject ret = new JSObject();
            ret.put("registered", true);
            ret.put("name", "OpenWork Pad");
            call.resolve(ret);
            return;
        }
        try {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "OpenWork Pad",
                    "Capacitor HID Gamepad",
                    "OpenWork",
                    (byte) 0x04, // subclass: Joystick; report says Gamepad
                    REPORT_MAP
            );
            Executor exec = getContext().getMainExecutor();
            boolean ok = hidDevice.registerApp(sdp, null, null, exec, hidCallback);
            if (!ok) {
                call.reject("Phone refused HID registration (profile busy) — reboot Bluetooth: toggle it off/on, force-stop the app, then Connect again.", CODE_BT_BUSY);
                return;
            }
            // onAppStatusChanged confirms; resolve optimistically so UI can prompt pairing.
            JSObject ret = new JSObject();
            ret.put("registering", true);
            ret.put("name", "OpenWork Pad");
            call.resolve(ret);
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied mid-registration — allow Nearby devices, then tap Connect.", CODE_BT_NO_PERM);
        } catch (Exception e) {
            call.reject("Registration failed: " + e.getMessage());
        }
    }

    // ---------- plugin methods ----------

    @PluginMethod
    public void isEnabled(PluginCall call) {
        BluetoothAdapter adapter = adapterOrNull(call);
        if (adapter == null) return;
        JSObject ret = new JSObject();
        try {
            ret.put("enabled", adapter.isEnabled());
        } catch (SecurityException se) {
            ret.put("enabled", false);
        }
        call.resolve(ret);
    }

    /** Opens the system "Turn on Bluetooth?" dialog; auto-continues a pending register. */
    @PluginMethod
    public void requestEnable(PluginCall call) {
        BluetoothAdapter adapter = adapterOrNull(call);
        if (adapter == null) return;
        try {
            if (adapter.isEnabled()) {
                JSObject ret = new JSObject();
                ret.put("enabled", true);
                call.resolve(ret);
                return;
            }
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied — allow Nearby devices first.", CODE_BT_NO_PERM);
            return;
        }
        saveCall(call);
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        try {
            startActivityForResult(call, intent, REQ_ENABLE_BT);
        } catch (Exception e) {
            freeSavedCall();
            call.reject("Couldn't open Bluetooth settings: " + e.getMessage());
        }
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ENABLE_BT) return;
        PluginCall saved = getSavedCall();
        if (saved == null) return;
        if (resultCode == Activity.RESULT_OK) {
            // User enabled BT — continue the chain automatically, no extra tap.
            freeSavedCall();
            register(saved);
        } else {
            freeSavedCall();
            saved.reject("Bluetooth must be ON — tap Connect and accept the Bluetooth prompt.", CODE_BT_OFF);
        }
    }

    @PluginMethod
    public void register(PluginCall call) {
        BluetoothAdapter adapter = adapterOrNull(call);
        if (adapter == null) return;
        boolean on;
        try {
            on = adapter.isEnabled();
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied — allow Nearby devices, then tap Connect.", CODE_BT_NO_PERM);
            return;
        }
        if (!on) {
            call.reject("Bluetooth is OFF — tap Connect again and accept the prompt to turn it on.", CODE_BT_OFF);
            return;
        }
        waitForPermissions(call, () -> waitForProxy(call, () -> doRegisterApp(call)));
    }

    @PluginMethod
    public void getBonded(PluginCall call) {
        BluetoothAdapter adapter = adapterOrNull(call);
        if (adapter == null) return;
        if (!hasBtPermissions()) {
            call.reject("Bluetooth permission denied — allow Nearby devices.", CODE_BT_NO_PERM);
            return;
        }
        try {
            JSArray arr = new JSArray();
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                JSObject o = new JSObject();
                try { o.put("name", d.getName()); } catch (SecurityException se) { o.put("name", "paired device"); }
                o.put("address", d.getAddress());
                arr.put(o);
            }
            JSObject ret = new JSObject();
            ret.put("devices", arr);
            call.resolve(ret);
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied.", CODE_BT_NO_PERM);
        }
    }

    /** Initiate HID connection to a bonded host (auto-rejoin last host). */
    @PluginMethod
    public void connectTo(PluginCall call) {
        BluetoothAdapter adapter = adapterOrNull(call);
        if (adapter == null) return;
        if (!hasBtPermissions()) {
            call.reject("Bluetooth permission denied — allow Nearby devices.", CODE_BT_NO_PERM);
            return;
        }
        if (hidDevice == null || !registered) {
            call.reject("Register first — app auto-registers on launch.", CODE_BT_NO_PROXY);
            return;
        }
        String address = call.getString("address");
        if (address == null || address.isEmpty()) {
            call.reject("No host address given.");
            return;
        }
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(address);
            boolean ok = hidDevice.connect(dev);
            JSObject ret = new JSObject();
            ret.put("connecting", ok);
            call.resolve(ret);
            if (!ok) {
                JSObject evt = new JSObject();
                evt.put("connected", false);
                evt.put("note", "host rejected connect — open host Bluetooth settings once");
                notifyListeners("connection", evt);
            }
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied.", CODE_BT_NO_PERM);
        } catch (Exception e) {
            call.reject("Connect failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void unregister(PluginCall call) {
        try {
            if (hidDevice != null && registered) {
                hidDevice.unregisterApp();
            }
            registered = false;
            hostDevice = null;
            JSObject ret = new JSObject();
            ret.put("registered", false);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("unregister failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void sendPadState(PluginCall call) {
        if (hidDevice == null || !registered) {
            call.reject("Not registered — tap Connect first and wait for pairing.");
            return;
        }
        if (hostDevice == null) {
            call.reject("No host connected — pair 'OpenWork Pad' in the host's Bluetooth settings.");
            return;
        }
        try {
            int buttons = call.getInt("buttons", 0);
            int lx = clamp8(call.getInt("lx", 128));
            int ly = clamp8(call.getInt("ly", 128));
            int rx = clamp8(call.getInt("rx", 128));
            int ry = clamp8(call.getInt("ry", 128));
            int hat = call.getInt("hat", 8);
            if (hat < 0 || hat > 8) hat = 8;

            byte[] report = new byte[7];
            report[0] = (byte) (buttons & 0xFF);
            report[1] = (byte) ((buttons >> 8) & 0xFF);
            report[2] = (byte) lx;
            report[3] = (byte) ly;
            report[4] = (byte) rx;
            report[5] = (byte) ry;
            report[6] = (byte) (hat & 0x0F);

            boolean sent = hidDevice.sendReport(hostDevice, 1, report);
            JSObject ret = new JSObject();
            ret.put("sent", sent);
            call.resolve(ret);
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied: " + se.getMessage());
        } catch (Exception e) {
            call.reject("sendReport failed: " + e.getMessage());
        }
    }

    /**
     * Send gamepad (ID 1, 7 bytes) + mouse (ID 3, 4 bytes) together.
     * mBtn bit0=left, bit1=right, bit2=middle. dx/dy/wheel are signed -127..127.
     */
    @PluginMethod
    public void sendAll(PluginCall call) {
        if (hidDevice == null || !registered) {
            call.reject("Not registered — tap the pill first and wait for pairing.");
            return;
        }
        if (hostDevice == null) {
            call.reject("No host connected — pair 'OpenWork Pad' in the host's Bluetooth settings.");
            return;
        }
        try {
            int buttons = call.getInt("buttons", 0);
            int lx = clamp8(call.getInt("lx", 128));
            int ly = clamp8(call.getInt("ly", 128));
            int rx = clamp8(call.getInt("rx", 128));
            int ry = clamp8(call.getInt("ry", 128));
            int hat = call.getInt("hat", 8);
            if (hat < 0 || hat > 8) hat = 8;

            byte[] pad = new byte[7];
            pad[0] = (byte) (buttons & 0xFF);
            pad[1] = (byte) ((buttons >> 8) & 0xFF);
            pad[2] = (byte) lx;
            pad[3] = (byte) ly;
            pad[4] = (byte) rx;
            pad[5] = (byte) ry;
            pad[6] = (byte) (hat & 0x0F);
            hidDevice.sendReport(hostDevice, 1, pad);

            int mBtn = call.getInt("mBtn", 0) & 0x07;
            byte[] mouse = new byte[4];
            mouse[0] = (byte) mBtn;
            mouse[1] = (byte) clampSigned(call.getInt("dx", 0));
            mouse[2] = (byte) clampSigned(call.getInt("dy", 0));
            mouse[3] = (byte) clampSigned(call.getInt("wheel", 0));
            boolean sent = hidDevice.sendReport(hostDevice, 3, mouse);

            JSObject ret = new JSObject();
            ret.put("sent", sent);
            call.resolve(ret);
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied: " + se.getMessage());
        } catch (Exception e) {
            call.reject("sendReport failed: " + e.getMessage());
        }
    }

    private int clampSigned(int v) {
        if (v < -127) return -127;
        if (v > 127) return 127;
        return v;
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P);
        boolean on = false;
        try {
            BluetoothManager mgr = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = mgr != null ? mgr.getAdapter() : null;
            on = adapter != null && adapter.isEnabled();
        } catch (SecurityException ignored) {}
        ret.put("bluetoothOn", on);
        ret.put("proxy", proxyReady);
        ret.put("registered", registered);
        ret.put("connected", hostDevice != null);
        if (hostDevice != null) {
            try { ret.put("host", hostDevice.getName()); } catch (SecurityException se) { ret.put("host", "connected host"); }
            ret.put("address", hostDevice.getAddress());
        }
        ret.put("reportBytes", 7);
        call.resolve(ret);
    }

    private int clamp8(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    @Override
    protected void handleOnDestroy() {
        try {
            if (hidDevice != null && registered) hidDevice.unregisterApp();
            if (btAdapter != null) btAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
        } catch (Exception ignored) {}
    }
}
