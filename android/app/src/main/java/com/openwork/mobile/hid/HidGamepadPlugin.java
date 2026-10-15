package com.openwork.mobile.hid;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
            (byte) 0xC0 // End Collection
    };

    private BluetoothAdapter btAdapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice hostDevice;
    private boolean registered = false;
    private boolean proxyReady = false;

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

    private boolean ensureProxy(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            call.reject("HID device role needs Android 9+");
            return false;
        }
        Context ctx = getContext();
        BluetoothManager mgr = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mgr == null) {
            call.reject("Bluetooth not available on this device");
            return false;
        }
        btAdapter = mgr.getAdapter();
        if (btAdapter == null) {
            call.reject("Bluetooth adapter off or missing");
            return false;
        }
        if (!hasBtPermissions()) {
            requestBtPermissions();
            call.reject("Bluetooth permission requested — allow Nearby devices, then tap again.");
            return false;
        }
        if (!proxyReady || hidDevice == null) {
            try {
                btAdapter.getProfileProxy(ctx, profileListener, BluetoothProfile.HID_DEVICE);
            } catch (SecurityException se) {
                call.reject("Bluetooth permission denied: " + se.getMessage());
                return false;
            }
            // Proxy connects async; caller retries register() on next tap.
            if (!proxyReady) {
                JSObject ret = new JSObject();
                ret.put("proxy", "connecting");
                call.resolve(ret);
                return false;
            }
        }
        return true;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getActivity() != null) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, 1001);
            // Fallback location for pre-31 scan path on some OEMs
            try {
                if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(getActivity(),
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1002);
                }
            } catch (Exception ignored) {}
        }
    }

    @PluginMethod
    public void register(PluginCall call) {
        if (!ensureProxy(call)) return;
        try {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "OpenWork Pad",
                    "Capacitor HID Gamepad",
                    "OpenWork",
                    (byte) 0x04, // subclass: Joystick; report says Gamepad
                    REPORT_MAP
            );
            Executor exec;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                exec = getContext().getMainExecutor();
            } else {
                call.reject("HID device role needs Android 9+");
                return;
            }
            boolean ok = hidDevice.registerApp(sdp, null, null, exec, hidCallback);
            if (!ok) {
                call.reject("registerApp failed — is Bluetooth on and HID profile free?");
                return;
            }
            // onAppStatusChanged confirms; optimistically resolve.
            JSObject ret = new JSObject();
            ret.put("registering", true);
            ret.put("name", "OpenWork Pad");
            call.resolve(ret);
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied: " + se.getMessage());
        } catch (Exception e) {
            call.reject("register failed: " + e.getMessage());
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
            call.reject("Not registered — tap Register as Gamepad first.");
            return;
        }
        if (hostDevice == null) {
            call.reject("No host connected — pair 'OpenWork Pad' in PC/Android Bluetooth settings.");
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
            report[6] = (byte) ((hat & 0x0F) | 0x00); // low nibble hat, high nibble padding 0

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

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P);
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
        new Handler(Looper.getMainLooper());
    }
}
