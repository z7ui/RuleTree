package com.z7ui.ruletree;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import java.util.Calendar;

/**
 * 日出日落时刻。用常见的简化算法（赤纬 + 时角 + 时差），误差几分钟，
 * 对一个「天黑了就开灯」的规则够用。
 *
 * 位置取系统最后已知位置；没权限或没定位过，退回 06:00 / 18:00。
 */
public class Sun {

    /** 返回 {日出, 日落} 的毫秒时间戳，用的是今天。定位不到返回 null。 */
    public static long[] today(Context c) {
        double[] ll = lastLocation(c);
        double lat, lon;
        if (ll == null) {
            Logx.i(c, "拿不到位置，日出日落按 06:00 / 18:00 估");
            lat = Double.NaN;
            lon = 0;
        } else {
            lat = ll[0];
            lon = ll[1];
        }

        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 12);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long noon = day.getTimeInMillis();

        if (Double.isNaN(lat)) {
            Calendar r = Calendar.getInstance();
            r.set(Calendar.HOUR_OF_DAY, 6);
            r.set(Calendar.MINUTE, 0);
            Calendar s = Calendar.getInstance();
            s.set(Calendar.HOUR_OF_DAY, 18);
            s.set(Calendar.MINUTE, 0);
            return new long[]{r.getTimeInMillis(), s.getTimeInMillis()};
        }

        int n = day.get(Calendar.DAY_OF_YEAR);
        double rad = Math.PI / 180.0;
        double decl = 23.45 * Math.sin(2 * Math.PI * (284 + n) / 365.0) * rad;
        double latR = lat * rad;

        double cosH = -Math.tan(latR) * Math.tan(decl);
        if (cosH > 1 || cosH < -1) {
            Logx.i(c, "这个纬度今天没有日出或日落（极昼极夜），跳过");
            return null;
        }
        double hDeg = Math.acos(cosH) / rad;           // 时角，单位度

        double b = 2 * Math.PI * (n - 81) / 364.0;
        double eot = 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b); // 分钟
        double tz = Calendar.getInstance().getTimeZone().getOffset(noon) / 3600000.0;

        // 地方太阳时换算成当地钟表时间（小时）
        double solarNoon = 12 - lon / 15.0 - eot / 60.0 + tz;
        double rise = solarNoon - hDeg / 15.0;
        double set = solarNoon + hDeg / 15.0;

        return new long[]{atHour(noon, rise), atHour(noon, set)};
    }

    private static long atHour(long noonMs, double hour) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(noonMs);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() + Math.round(hour * 3600000L);
    }

    private static double[] lastLocation(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l;
                try {
                    l = lm.getLastKnownLocation(p);
                } catch (SecurityException e) {
                    continue;
                }
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            if (best == null) return null;
            return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (Exception e) {
            return null;
        }
    }
}
