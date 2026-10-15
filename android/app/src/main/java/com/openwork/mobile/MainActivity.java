package com.openwork.mobile;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.openwork.mobile.hid.HidGamepadPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(HidGamepadPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
