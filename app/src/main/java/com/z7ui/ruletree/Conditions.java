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
            case "time_between":
                return timeBetween(cond.params);
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

    private static int minutes(String hhmm) {
        try {
            String[] parts = hhmm.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }
}
