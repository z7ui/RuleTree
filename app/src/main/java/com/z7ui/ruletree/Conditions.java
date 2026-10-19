package com.z7ui.ruletree;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.PowerManager;

import java.util.Calendar;

/** 条件求值。返回前统一处理 negate，各类型只算原始真假。 */
public class Conditions {

    private static final int MAX_DEPTH = 8;

    public static boolean eval(Context c, Model.Cond cond) {
        return eval(c, cond, 0);
    }

    private static boolean eval(Context c, Model.Cond cond, int depth) {
        if (cond == null) return true;
        if (depth > MAX_DEPTH) {
            Logx.i(c, "条件嵌套超过 " + MAX_DEPTH + " 层，按不成立处理");
            return false;
        }
        boolean v;
        try {
            v = raw(c, cond, depth);
        } catch (Throwable t) {
            Logx.i(c, "条件求值异常 " + cond.type + ": " + t);
            v = false;
        }
        boolean r = cond.negate ? !v : v;
        Logx.i(c, "条件[" + cond.describe() + "] = " + r);
        return r;
    }

    private static boolean raw(Context c, Model.Cond cond, int depth) {
        if (cond.isGroup()) {
            return group(c, cond, depth);
        }
        switch (cond.type) {
            case "wifi_connected":
                return wifiConnected(c, cond.params);
            case "charging":
                return isCharging(c);
            case "battery_below":
                return batteryLevel(c) < cond.params.optInt("pct", 20);
            case "battery_above":
                return batteryLevel(c) > cond.params.optInt("pct", 80);
            case "time_between":
                return timeBetween(cond.params);
            case "day_of_week":
                return dayOfWeek(cond.params.optString("days", ""));
            case "screen_on": {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isInteractive();
            }
            case "keyguard_locked": {
                KeyguardManager km =
                        (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
                return km != null && km.isKeyguardLocked();
            }
            case "dnd_on": {
                NotificationManager nm =
                        (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null
                        && nm.getCurrentInterruptionFilter()
                        != NotificationManager.INTERRUPTION_FILTER_ALL;
            }
            case "ringer_silent":
                return ringerMode(c) == AudioManager.RINGER_MODE_SILENT;
            case "ringer_vibrate":
                return ringerMode(c) == AudioManager.RINGER_MODE_VIBRATE;
            case "ringer_normal":
                return ringerMode(c) == AudioManager.RINGER_MODE_NORMAL;
            case "dark_mode":
                return (c.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            case "bt_connected":
                return btConnected(c);
            case "headset": {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                return am != null && am.isWiredHeadsetOn();
            }
            case "always":
            default:
                return true;
        }
    }

    /**
     * 条件组。all 遇到第一个 false 就停，any 遇到第一个 true 就停。
     * 空组按成立处理：一条规则只写了动作、没写条件，本来就该跑。
     */
    private static boolean group(Context c, Model.Cond g, int depth) {
        if (g.children == null || g.children.isEmpty()) return true;
        boolean all = !"any".equals(g.op);
        for (Model.Cond child : g.children) {
            boolean v = eval(c, child, depth + 1);
            if (all && !v) return false;
            if (!all && v) return true;
        }
        return all;
    }

    private static boolean wifiConnected(Context c, org.json.JSONObject p) {
        String ssid = p.optString("ssid", "");
        boolean matchAny = p.optBoolean("matchAny", ssid.isEmpty());

        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        boolean connected = ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
        if (!connected) return false;
        if (matchAny) return true;

        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        if (info == null) return false;
        String cur = info.getSSID();
        if (cur == null) return false;
        cur = cur.replace("\"", "");
        return cur.equals(ssid);
    }

    private static boolean isCharging(Context c) {
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return false;
        return bm.isCharging();
    }

    private static int batteryLevel(Context c) {
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return -1;
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private static boolean timeBetween(org.json.JSONObject p) {
        int start = minutes(p.optString("start", "00:00"));
        int end = minutes(p.optString("end", "23:59"));
        Calendar now = Calendar.getInstance();
        int cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (start <= end) return cur >= start && cur <= end;
        // 跨零点：22:00-06:00
        return cur >= start || cur <= end;
    }

    /** days 是 ISO 星期，"1,3,5" 表示周一二三……周一=1，周日=7；空串表示每天都算成立。 */
    private static boolean dayOfWeek(String days) {
        if (days == null || days.isEmpty()) return true;
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        int iso = dow == Calendar.SUNDAY ? 7 : dow - 1;
        for (String p : days.split(",")) {
            try {
                if (Integer.parseInt(p.trim()) == iso) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static int ringerMode(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        return am == null ? -1 : am.getRingerMode();
    }

    /**
     * 有没有连着蓝牙设备。用 getConnectedDevices，比查广播可靠。
     * Android 12 起要 BLUETOOTH_CONNECT，没授权会抛异常，按「没连」处理。
     */
    private static boolean btConnected(Context c) {
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return false;
            BluetoothAdapter ad = bm.getAdapter();
            if (ad == null || !ad.isEnabled()) return false;
            return !bm.getConnectedDevices(BluetoothProfile.GATT).isEmpty()
                    || !bm.getConnectedDevices(BluetoothProfile.GATT_SERVER).isEmpty();
        } catch (Exception e) {
            Logx.i(c, "读蓝牙连接状态失败（多半是缺 BLUETOOTH_CONNECT）: " + e);
            return false;
        }
    }

    private static int minutes(String hhmm) {
        try {
            String[] parts = hhmm.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }
}
