package com.z7ui.ruletree;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「每天固定时间」的触发器靠闹钟实现，不走轮询。
 *
 * 每次规则变动都整体重排：先把库里所有规则的闹钟取消，再给启用的定时规则各排一个。
 * 这样不用维护「哪条规则改过时间」的差异，代价是每次重排要遍历一遍规则，规则量小，无所谓。
 */
public class TimeScheduler {

    /** 日常定时到点 */
    public static final String ACTION_FIRE = "com.z7ui.ruletree.TIME_FIRE";
    /** 测试模式到点，跑完不重排 */
    public static final String ACTION_TEST = "com.z7ui.ruletree.TEST_FIRE";

    /** 没有精确闹钟权限时的窗口宽度，到点误差最多这么多。 */
    private static final long INEXACT_WINDOW = 10 * 60 * 1000L;

    /** 规则一改就整体重排。删规则时记得先 cancel 再 sync，不然旧闹钟会留在系统里。 */
    public static void sync(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        List<Model.Rule> rules = RuleStore.load(c);
        for (Model.Rule r : rules) {
            try {
                am.cancel(pending(c, r.id));
            } catch (Exception ignored) {
            }
        }

        int count = 0;
        for (Model.Rule r : rules) {
            if (!r.enabled) continue;
            String type = r.trigger.type;
            if (!"time_daily".equals(type) && !"time_monthly".equals(type)
                    && !"sun_event".equals(type)) continue;
            long at = nextAt(c, r.trigger);
            if (at <= 0) {
                Logx.i(c, "定时「" + r.name + "」算不出下次时间，跳过");
                continue;
            }
            schedule(c, am, r.id, at);
            Logx.i(c, "定时「" + r.name + "」下次 " + fmt(at));
            count++;
        }
        if (count == 0) Logx.i(c, "没有启用的定时规则");
    }

    public static void cancel(Context c, String ruleId) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(pending(c, ruleId));
        } catch (Exception ignored) {
        }
    }

    /**
     * 测试模式：x 秒后跑一次。
     * 先取消同一条规则上一次没跑的测试，免得连点几下堆出一串。
     * 没有精确闹钟权限时用 setAndAllowWhileIdle，误差通常只有几秒，比 10 分钟窗口靠谱。
     */
    public static void test(Context c, String ruleId, int sec) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, ruleId, ACTION_TEST, testCode(ruleId));
        try {
            am.cancel(pi);
        } catch (Exception ignored) {
        }
        long at = System.currentTimeMillis() + Math.max(1, sec) * 1000L;
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            Logx.i(c, "测试闹钟已排：" + sec + " 秒后" + (exact ? "" : "（无精确闹钟权限）"));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            Logx.i(c, "精确闹钟被拒，测试改用普通闹钟: " + e);
        }
    }

    /** 测试用另一个 requestCode，跟日常定时互不影响。 */
    private static int testCode(String rid) {
        return rid.hashCode() ^ 0x5EED;
    }

    private static void schedule(Context c, AlarmManager am, String rid, long at) {
        PendingIntent pi = pending(c, rid);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, INEXACT_WINDOW, pi);
                Logx.i(c, "没有精确闹钟权限，这次按 10 分钟窗口排");
            }
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, INEXACT_WINDOW, pi);
            Logx.i(c, "排精确闹钟被拒，退化成窗口: " + e);
        }
    }

    private static PendingIntent pending(Context c, String rid) {
        // 每条规则一个 requestCode，规则 id 是 UUID，hash 撞车概率可以忽略
        return pending(c, rid, ACTION_FIRE, rid.hashCode());
    }

    private static PendingIntent pending(Context c, String rid, String action, int code) {
        Intent i = new Intent(c, TimeReceiver.class);
        i.setAction(action);
        i.putExtra("rid", rid);
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 下一次该响的时间戳。算不出来返回 0。 */
    public static long nextAt(Context c, Model.Trigger t) {
        if ("sun_event".equals(t.type)) return nextSun(c, t);
        return nextAt(t);
    }

    /**
     * 日出或日落。今天已经过了就加 24 小时——日出时刻一天只差几十秒，
     * 不值得再算一遍明天，等它响过之后 sync 会重排。
     */
    private static long nextSun(Context c, Model.Trigger t) {
        long[] s = Sun.today(c);
        if (s == null) return 0;
        boolean sunrise = !"sunset".equals(t.params.optString("event", "sunrise"));
        long at = sunrise ? s[0] : s[1];
        int offset = Math.max(-120, Math.min(120, t.params.optInt("offset", 0)));
        at += offset * 60000L;
        if (at <= System.currentTimeMillis()) at += 24 * 3600 * 1000L;
        return at;
    }

    public static long nextAt(Model.Trigger t) {
        if ("time_monthly".equals(t.type)) return nextMonthly(t);
        int hhmm = minutes(t.params.optString("hhmm", "07:00"));
        if (hhmm < 0) return 0;
        boolean[] days = parseDays(t.params.optString("days", ""));
        boolean everyDay = true;
        for (boolean d : days) if (d) everyDay = false;

        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hhmm / 60);
        c.set(Calendar.MINUTE, hhmm % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (everyDay) return c.getTimeInMillis();

        // 往后找 7 天，总有一个匹配
        for (int i = 0; i < 8; i++) {
            if (days[isoDay(c) - 1]) return c.getTimeInMillis();
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return 0;
    }

    /** 每月某日。月份没有那一天（比如 2 月 30 号）就跳到下个月。 */
    private static long nextMonthly(Model.Trigger t) {
        int hhmm = minutes(t.params.optString("hhmm", "07:00"));
        if (hhmm < 0) return 0;
        int day = Math.max(1, Math.min(31, t.params.optInt("day", 1)));

        Calendar base = Calendar.getInstance();
        base.set(Calendar.DAY_OF_MONTH, 1);
        base.set(Calendar.HOUR_OF_DAY, hhmm / 60);
        base.set(Calendar.MINUTE, hhmm % 60);
        base.set(Calendar.SECOND, 0);
        base.set(Calendar.MILLISECOND, 0);

        for (int i = 0; i < 14; i++) {
            int max = base.getActualMaximum(Calendar.DAY_OF_MONTH);
            if (day <= max) {
                Calendar hit = (Calendar) base.clone();
                hit.set(Calendar.DAY_OF_MONTH, day);
                if (hit.getTimeInMillis() > System.currentTimeMillis()) {
                    return hit.getTimeInMillis();
                }
            }
            base.add(Calendar.MONTH, 1);
        }
        return 0;
    }

    private static int isoDay(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SUNDAY ? 7 : dow - 1;
    }

    private static boolean[] parseDays(String s) {
        boolean[] out = new boolean[7];
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(",")) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 1 && v <= 7) out[v - 1] = true;
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static int minutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmt(long at) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(at));
    }
}
