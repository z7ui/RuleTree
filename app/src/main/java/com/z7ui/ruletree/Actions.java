package com.z7ui.ruletree;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 动作执行。除 HTTP 和等待之外都是毫秒级，跑在引擎的工作线程里。 */
public class Actions {

    private static final String CHANNEL = "rule_notify";

    public static boolean run(Context c, String action, JSONObject params) {
        try {
            switch (action == null ? "" : action) {
                case "ringer_mode":
                    return ringerMode(c, params.optString("mode", "silent"));
                case "media_volume":
                    return volume(c, AudioManager.STREAM_MUSIC, params.optInt("value", 50));
                case "volume_ring":
                    return volume(c, AudioManager.STREAM_RING, params.optInt("value", 60));
                case "volume_alarm":
                    return volume(c, AudioManager.STREAM_ALARM, params.optInt("value", 60));
                case "volume_notification":
                    return volume(c, AudioManager.STREAM_NOTIFICATION, params.optInt("value", 60));
                case "dnd_on":
                    return dnd(c, true);
                case "dnd_off":
                    return dnd(c, false);
                case "vibrate":
                    return vibrate(c, params.optInt("ms", 300));
                case "media_play_pause":
                    return mediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                case "media_next":
                    return mediaKey(c, KeyEvent.KEYCODE_MEDIA_NEXT);
                case "media_prev":
                    return mediaKey(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                case "torch_on":
                    return torch(c, true);
                case "torch_off":
                    return torch(c, false);
                case "open_url":
                    return openUrl(c, params.optString("url", ""));
                case "copy_text":
                    return copyText(c, params.optString("text", ""));
                case "tts":
                    return Tts.speak(c, params.optString("text", ""));
                case "http_request":
                    return http(c, params.optString("url", ""), params.optString("body", ""));
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
                case "wait_unlock":
                    return waitUnlock(c, params.optInt("sec", 60));
                case "g_back":
                    return GestureService.global(GestureService.GLOBAL_BACK);
                case "g_home":
                    return GestureService.global(GestureService.GLOBAL_HOME);
                case "g_recents":
                    return GestureService.global(GestureService.GLOBAL_RECENTS);
                case "g_notifications":
                    return GestureService.global(GestureService.GLOBAL_NOTIFICATIONS);
                case "g_quick_settings":
                    return GestureService.global(GestureService.GLOBAL_QUICK_SETTINGS);
                case "g_lock":
                    return GestureService.global(GestureService.GLOBAL_LOCK);
                case "g_tap":
                    return GestureService.tap(params.optInt("x", 0), params.optInt("y", 0));
                case "g_swipe":
                    return GestureService.swipe(params.optInt("x1", 0), params.optInt("y1", 0),
                            params.optInt("x2", 0), params.optInt("y2", 0), 300);
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

    private static boolean volume(Context c, int stream, int pct) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
            Logx.i(c, "音量调整需要勿扰访问权限，未授权，动作被忽略");
            return false;
        }
        int max = am.getStreamMaxVolume(stream);
        int v = Math.round(max * Math.max(0, Math.min(100, pct)) / 100f);
        am.setStreamVolume(stream, v, 0);
        Logx.i(c, "音量流 " + stream + " 已设为 " + pct + "%（" + v + "/" + max + "）");
        return true;
    }

    /** 免打扰开关。setInterruptionFilter 同样要勿扰访问权限。 */
    private static boolean dnd(Context c, boolean on) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        if (!nm.isNotificationPolicyAccessGranted()) {
            Logx.i(c, "免打扰需要「勿扰访问」权限，未授权，动作被忽略");
            return false;
        }
        nm.setInterruptionFilter(on
                ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL);
        Logx.i(c, "免打扰已" + (on ? "打开" : "关闭"));
        return true;
    }

    private static boolean vibrate(Context c, int ms) {
        int d = Math.max(1, Math.min(5000, ms));
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm == null) return false;
            vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return false;
            v.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE));
        }
        Logx.i(c, "振动 " + d + "ms");
        return true;
    }

    private static boolean mediaKey(Context c, int code) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        Logx.i(c, "媒体键 " + code);
        return true;
    }

    /** 手电筒。找第一个带闪光灯的摄像头，不用 CAMERA 权限。 */
    private static boolean torch(Context c, boolean on) {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return false;
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cm.setTorchMode(id, on);
                    Logx.i(c, "手电筒已" + (on ? "打开" : "关闭") + "（摄像头 " + id + "）");
                    return true;
                }
            }
        } catch (Exception e) {
            Logx.i(c, "手电筒不可用: " + e);
        }
        Logx.i(c, "没找到带闪光灯的摄像头");
        return false;
    }

    private static boolean openUrl(Context c, String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http")) url = "https://" + url;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Logx.i(c, "没有能打开网址的应用: " + e);
            return false;
        }
        Logx.i(c, "已打开网址 " + url);
        return true;
    }

    private static boolean copyText(Context c, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return false;
        cm.setPrimaryClip(ClipData.newPlainText("规则树", text));
        Logx.i(c, "已复制到剪贴板：" + text);
        return true;
    }

    /**
     * 发 HTTP 请求。body 为空就是 GET。跑在引擎的线程池里，超时 10 秒。
     * 这条动作是接到外部世界的口子，Home Assistant、自己的服务器都靠它。
     */
    private static boolean http(Context c, String url, String body) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http")) url = "https://" + url;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod(body == null || body.isEmpty() ? "GET" : "POST");
            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            String resp = "";
            try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[1024];
                    int n = in.read(buf);
                    if (n > 0) resp = new String(buf, 0, Math.min(n, 200), StandardCharsets.UTF_8);
                }
            }
            Logx.i(c, "HTTP " + code + " " + url + (resp.isEmpty() ? "" : " → " + resp));
            return code >= 200 && code < 400;
        } catch (Exception e) {
            Logx.i(c, "HTTP 请求失败: " + e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 锁屏时先停住，解锁了再往下走。超时也继续，免得整条规则卡死。 */
    private static boolean waitUnlock(Context c, int sec) {
        android.app.KeyguardManager km =
                (android.app.KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        android.os.PowerManager pm =
                (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (km == null || pm == null) return false;
        long deadline = System.currentTimeMillis() + Math.max(1, sec) * 1000L;
        Logx.i(c, "等解锁，最多 " + sec + " 秒");
        while (System.currentTimeMillis() < deadline) {
            if (!km.isKeyguardLocked() && pm.isInteractive()) {
                Logx.i(c, "已解锁，继续");
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        Logx.i(c, "等到超时还没解锁，继续往下走");
        return false;
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
