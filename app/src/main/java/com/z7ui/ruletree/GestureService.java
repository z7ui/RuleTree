package com.z7ui.ruletree;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    /** 当前前台应用的包名，靠窗口事件更新。只留包名，不留任何界面内容。 */
    private static volatile String lastPkg;

    public static boolean available() {
        return instance != null;
    }

    public static String foregroundPackage() {
        return lastPkg;
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

    /**
     * 回放录下来的一段手势。每步之间按录制时的间隔停一下，
     * 派发之后也要等一下，否则手势还没画完下一步就叠上去了。
     */
    public static boolean play(Context c, JSONArray steps) {
        GestureService s = instance;
        if (s == null) {
            Logx.i(c, "无障碍服务没开，手势序列跳过");
            return false;
        }
        if (steps == null || steps.length() == 0) return false;
        boolean all = true;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject st = steps.optJSONObject(i);
            if (st == null) continue;
            long delay = Math.max(0, Math.min(10000, st.optLong("delay", 0)));
            if (delay > 0) sleep(delay);
            long dur;
            boolean ok;
            if ("swipe".equals(st.optString("type"))) {
                dur = Math.max(50, Math.min(3000, st.optLong("ms", 300)));
                Path p = new Path();
                p.moveTo(st.optInt("x1"), st.optInt("y1"));
                p.lineTo(st.optInt("x2"), st.optInt("y2"));
                ok = s.dispatch(p, 0, dur);
            } else {
                dur = 60;
                Path p = new Path();
                p.moveTo(st.optInt("x"), st.optInt("y"));
                ok = s.dispatch(p, 0, dur);
            }
            all &= ok;
            Logx.i(s, "第 " + (i + 1) + "/" + steps.length() + " 步 → " + ok);
            sleep(dur + 60);
        }
        return all;
    }

    /** 截屏。Android 11 起无障碍才有这个能力，存到相册 Pictures/RuleTree。 */
    public static boolean screenshot(Context c) {
        final GestureService s = instance;
        if (s == null) {
            Logx.i(c, "无障碍服务没开，截屏跳过");
            return false;
        }
        if (Build.VERSION.SDK_INT < 30) {
            Logx.i(c, "系统低于 Android 11，无障碍截不了屏");
            return false;
        }
        final Context app = c.getApplicationContext();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, app.getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult r) {
                            try {
                                ok[0] = save(app, r.getHardwareBuffer());
                            } catch (Exception e) {
                                Logx.i(app, "截图保存失败: " + e);
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Logx.i(app, "截图失败，错误码 " + errorCode);
                            latch.countDown();
                        }
                    });
            latch.await(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            Logx.i(app, "截屏异常: " + e);
        }
        return ok[0];
    }

    private static boolean save(Context c, HardwareBuffer buf) throws Exception {
        Bitmap hw = Bitmap.wrapHardwareBuffer(buf, null);
        if (hw == null) return false;
        Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        if (soft == null) return false;

        String name = "RuleTree-" + System.currentTimeMillis() + ".png";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RuleTree");
        ContentResolver cr = c.getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return false;
        try (OutputStream out = cr.openOutputStream(uri)) {
            soft.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        soft.recycle();
        Logx.i(c, "截图已存相册：Pictures/RuleTree/" + name);
        return true;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
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
        // 只取包名：用来判断「当前应用是」这个条件，以及「打开某应用」这个触发器。
        // 界面内容一概不读。
        if (event == null) return;
        try {
            CharSequence p = event.getPackageName();
            if (p == null || p.length() == 0) return;
            String pkg = p.toString();
            String old = lastPkg;
            lastPkg = pkg;
            if (old != null && !old.equals(pkg)) {
                Engine.fireApp(this, pkg);
            }
        } catch (Exception ignored) {
        }
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
