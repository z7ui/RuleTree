package com.z7ui.ruletree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接「执行 / 算了」两个按钮。
 *
 * 这个接收器是导出的，但真正的通行证是 token：只有 ConfirmGate 生成、
 * 塞进通知按钮里的那串随机值才能放行，别人乱发广播没有 token，什么也做不了。
 */
public class ConfirmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String token = intent.getStringExtra("token");
        if (token == null || token.isEmpty()) return;

        boolean deny = intent.getBooleanExtra("deny", false);
        if (deny) Logx.i(context, "用户点了「算了」");
        ConfirmGate.resolve(token, !deny);
    }
}
