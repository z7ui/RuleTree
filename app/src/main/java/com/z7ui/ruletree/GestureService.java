package com.z7ui.ruletree;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务。只做两件事：系统级导航（返回/主屏/最近任务/锁屏），和合成手势（点、滑）。
 *
 * 这是第三方应用唯一能"动别人的界面"的合法通道，触控宏也建在它上面。
 * 服务没开时所有动作直接返回 false，日志里写清楚原因，不会静默失败。
 */
public class GestureService extends AccessibilityService {

    public static final int GLOBAL_BACK = AccessibilityService.GLOBAL_ACTION_BACK;
    public static final int GLOBAL_HOME = AccessibilityService.GLOBAL_ACTION_HOME;
    public static final int GLOBAL_RECENTS = AccessibilityService.GLOBAL_ACTION_RECENTS;
    public static final int GLOBAL_NOTIFICATIONS = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
    public static final int GLOBAL_QUICK_SETTINGS = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
    /** API 28 才有「锁屏」这个全局动作 */
    public static final int GLOBAL_LOCK = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;

    private static GestureService instance;

    public static boolean available() {
        return instance != null;
    }

    public static boolean global(int action) {
        GestureService s = instance;
        if (s == null) {
            Logx.i(s == null ? null : s, "无障碍服务没开，手势动作跳过");
            return false;
        }
        boolean ok = s.performGlobalAction(action);
        Logx.i(s, "全局动作 " + action + " → " + ok);
        return ok;
    }

    public static boolean tap(int x, int y) {
        GestureService s = instance;
        if (s == null) {
            Logx.i(null, "无障碍服务没开，点击跳过");
            return false;
        }
        Path p = new Path();
        p.moveTo(x, y);
        boolean ok = s.dispatch(p, 0, 60);
        Logx.i(s, "点击 (" + x + "," + y + ") → " + ok);
        return ok;
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        GestureService s = instance;
        if (s == null) {
            Logx.i(null, "无障碍服务没开，滑动跳过");
            return false;
        }
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        boolean ok = s.dispatch(p, 0, Math.max(50, Math.min(3000, ms)));
        Logx.i(s, "滑动 (" + x1 + "," + y1 + ")→(" + x2 + "," + y2 + ") → " + ok);
        return ok;
    }

    private boolean dispatch(Path path, long startMs, long durationMs) {
        try {
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, startMs, durationMs);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            Logx.i(this, "手势派发失败: " + e);
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Logx.i(this, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要监听事件，手势和全局动作都不依赖事件流
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Logx.i(this, "无障碍服务已断开");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
