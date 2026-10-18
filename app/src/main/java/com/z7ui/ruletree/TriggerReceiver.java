package com.z7ui.ruletree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;

/**
 * 系统事件入口。只做两件事：判断事件类型，把 watch 值交给引擎。
 * 判断逻辑一律不写在这里，全在树里，这样触发器可以复用。
 */
public class TriggerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiManager.NETWORK_STATE_CHANGED_ACTION: {
                NetworkInfo ni = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (ni == null) return;
                boolean connected = ni.isConnected()
                        && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
                Logx.i(context, "收到 Wi-Fi 状态变化: " + ni.getState());
                Engine.fire(context, "wifi_state", connected ? "connected" : "disconnected");
                break;
            }
            case Intent.ACTION_POWER_CONNECTED:
                Engine.fire(context, "power_state", "connected");
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                Engine.fire(context, "power_state", "disconnected");
                break;
            default:
                break;
        }
    }
}
