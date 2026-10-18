package com.z7ui.ruletree;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;

import java.util.Calendar;

/** 条件求值。返回前统一处理 negate，各类型只算原始真假。 */
public class Conditions {

    public static boolean eval(Context c, Model.Cond cond) {
        boolean v;
        try {
            v = raw(c, cond);
        } catch (Throwable t) {
            Logx.i(c, "条件求值异常 " + cond.type + ": " + t);
            v = false;
        }
        boolean r = cond.negate ? !v : v;
        Logx.i(c, "条件[" + cond.describe() + "] = " + r);
        return r;
    }

    private static boolean raw(Context c, Model.Cond cond) {
        switch (cond.type) {
            case "wifi_connected":
                return wifiConnected(c, cond.params);
            case "charging":
                return isCharging(c);
            case "battery_below":
                return batteryLevel(c) < cond.params.optInt("pct", 20);
            case "time_between":
                return timeBetween(cond.params);
            case "always":
            default:
                return true;
        }
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

    private static int minutes(String hhmm) {
        try {
            String[] parts = hhmm.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }
}
