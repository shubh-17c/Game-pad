package com.openwork.mobile;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;

import com.getcapacitor.BridgeActivity;
import com.openwork.mobile.hid.HidGamepadPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(HidGamepadPlugin.class);
        super.onCreate(savedInstanceState);
        lockToAppFeel();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void lockToAppFeel() {
        hideSystemUI();
        // Gamepads don't sleep mid-game: hold the screen on (Web WakeLock is unreliable in WebViews).
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception ignored) {}
        // WebView must behave like native UI: no pinch/double-tap zoom, no wide-viewport scaling.
        try {
            if (bridge != null && bridge.getWebView() != null) {
                WebSettings s = bridge.getWebView().getSettings();
                s.setBuiltInZoomControls(false);
                s.setDisplayZoomControls(false);
                s.setSupportZoom(false);
                s.setLoadWithOverviewMode(true);
                s.setUseWideViewPort(true);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUI() {
        try {
            View decor = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            decor.setSystemUiVisibility(flags);
        } catch (Exception ignored) {}
    }
}
