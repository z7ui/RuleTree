package com.z7ui.ruletree;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 执行前的二次确认。
 *
 * 引擎跑到 confirm 节点时会停在这里等：发一条带「执行 / 算了」两个按钮的通知，
 * 最多等 timeout 秒。点了执行就往下走，超时或者点算了就把这一支的剩余动作全部取消。
 *
 * 之所以用通知而不是弹窗：规则大多在后台被触发，弹 Activity 会打断正在用的应用。
 */
public class ConfirmGate {

    private static final String CHANNEL = "confirm";
    private static final int NOTI_ID = 3001;
    private static final String ACTION_CONFIRM = "com.z7ui.ruletree.CONFIRM";

    private static final Map<String, CountDownLatch> WAITERS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> RESULTS = new ConcurrentHashMap<>();

    /** 返回 true 表示用户点了执行。没有通知权限时按通过处理，否则规则永远跑不动。 */
    public static boolean ask(Context c, String text, int timeoutSec) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) {
            Logx.i(c, "没有通知权限，确认步骤跳过（按通过处理）");
            return true;
        }

        String token = UUID.randomUUID().toString();
        CountDownLatch latch = new CountDownLatch(1);
        WAITERS.put(token, latch);
        Logx.i(c, "等待确认，token=" + token + "，最多 " + timeoutSec + " 秒");
        post(c, nm, token, text);

        boolean resolved = false;
        try {
            resolved = latch.await(Math.max(3, timeoutSec), TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        WAITERS.remove(token);
        Boolean result = RESULTS.remove(token);
        nm.cancel(NOTI_ID);

        boolean ok = resolved && Boolean.TRUE.equals(result);
        Logx.i(c, ok ? "二次确认：已确认" : "二次确认：未确认，取消剩余动作");
        return ok;
    }

    /** 由 ConfirmReceiver 调用。token 对不上就忽略，避免别人乱发广播打断规则。 */
    public static void resolve(String token, boolean ok) {
        CountDownLatch latch = WAITERS.get(token);
        if (latch == null) return;
        RESULTS.put(token, ok);
        latch.countDown();
    }

    private static void post(Context c, NotificationManager nm, String token, String text) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "执行确认",
                    NotificationManager.IMPORTANCE_HIGH));
        }

        Intent yes = new Intent(c, ConfirmReceiver.class).setAction(ACTION_CONFIRM);
        yes.putExtra("token", token);
        PendingIntent piYes = PendingIntent.getBroadcast(c, token.hashCode(), yes,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent no = new Intent(c, ConfirmReceiver.class).setAction(ACTION_CONFIRM);
        no.putExtra("token", token);
        no.putExtra("deny", true);
        PendingIntent piNo = PendingIntent.getBroadcast(c, token.hashCode() + 1, no,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("规则要执行了")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOngoing(false)
                .addAction(0, "执行", piYes)
                .addAction(0, "算了", piNo);

        nm.notify(NOTI_ID, b.build());
    }
}
