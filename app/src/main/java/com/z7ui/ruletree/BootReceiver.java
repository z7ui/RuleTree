package com.z7ui.ruletree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后把监听服务拉回来。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Logx.i(context, "开机广播，按规则情况启动监听");
        WatchService.sync(context);
    }
}
