package dev.pideck.mobile;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 自定义插件必须在 super.onCreate 之前注册，否则 JS 侧调用会报 "not implemented"
        registerPlugin(NavigationBarPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
