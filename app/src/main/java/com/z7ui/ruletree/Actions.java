package com.z7ui.ruletree;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

/** 动作执行。全部同步、无网络、几毫秒完事，所以敢直接在 receiver 里跑。 */
public class Actions {

    private static final String CHANNEL = "rule_notify";

    public static boolean run(Context c, String action, JSONObject params) {
        try {
            switch (action == null ? "" : action) {
                case "ringer_mode":
                    return ringerMode(c, params.optString("mode", "silent"));
                case "media_volume":
                    return mediaVolume(c, params.optInt("value", 50));
                case "open_app":
                    return openApp(c, params.optString("pkg", ""));
                case "notify":
                    notify(c, params.optString("title", "规则树"), params.optString("text", ""));
                    return true;
                case "delay": {
                    long ms = Math.max(0, Math.min(30000, params.optLong("ms", 1000)));
                    Logx.i(c, "等待 " + ms + "ms");
                    Thread.sleep(ms);
                    return true;
                }
                default:
                    Logx.i(c, "未知动作，跳过: " + action);
                    return false;
            }
        } catch (Throwable t) {
            Logx.i(c, "动作执行异常 " + action + ": " + t);
            return false;
        }
    }

    private static boolean ringerMode(Context c, String mode) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
            Logx.i(c, "铃声模式需要勿扰访问权限，未授权，动作被忽略");
            return false;
        }
        int target;
        if ("silent".equals(mode)) target = AudioManager.RINGER_MODE_SILENT;
        else if ("vibrate".equals(mode)) target = AudioManager.RINGER_MODE_VIBRATE;
        else target = AudioManager.RINGER_MODE_NORMAL;
        am.setRingerMode(target);
        Logx.i(c, "铃声模式已设为 " + mode + "（当前=" + am.getRingerMode() + "）");
        return true;
    }

    private static boolean mediaVolume(Context c, int pct) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
            Logx.i(c, "音量调整需要勿扰访问权限，未授权，动作被忽略");
            return false;
        }
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = Math.round(max * Math.max(0, Math.min(100, pct)) / 100f);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        Logx.i(c, "媒体音量已设为 " + pct + "%（" + v + "/" + max + "）");
        return true;
    }

    private static boolean openApp(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Logx.i(c, "找不到应用的启动入口: " + pkg);
            return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        Logx.i(c, "已打开应用 " + pkg);
        return true;
    }

    private static void notify(Context c, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "规则通知",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        PendingIntent pi = PendingIntent.getActivity(c, 0,
                open != null ? open : new Intent(), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify((int) System.currentTimeMillis(), b.build());
        Logx.i(c, "已发通知: " + title);
    }
}
