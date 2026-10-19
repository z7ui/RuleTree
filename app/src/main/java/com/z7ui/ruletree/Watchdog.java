package com.z7ui.ruletree;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 看门狗。每 15 分钟醒一次，把监听服务按规则状态重新同步一遍。
 *
 * 为什么需要：WatchService 只有 START_STICKY，被 MIUI 清掉之后系统不一定拉回来，
 * 而 Wi-Fi / 电源 / 蓝牙这些触发全靠它。没有这个兜底，服务死了是完全静默的。
 *
 * 开销可以忽略：一次闹钟 + 一次 sync，没规则时 sync 会直接停服务。
 */
public class Watchdog extends BroadcastReceiver {

    public static final String ACTION = "com.z7ui.ruletree.WATCHDOG";
    private static final long INTERVAL = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        Logx.i(context, "看门狗醒了，按规则情况重新同步监听");
        WatchService.sync(context);
        schedule(context);
    }

    /** 排下一次。用 setAndAllowWhileIdle，不需要精确闹钟权限。 */
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL, pending(c));
        } catch (Exception e) {
            Logx.i(c, "看门狗排不上: " + e);
        }
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, Watchdog.class);
        i.setAction(ACTION);
        return PendingIntent.getBroadcast(c, 0x5D06, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
