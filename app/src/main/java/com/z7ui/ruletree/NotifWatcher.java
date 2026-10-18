package com.z7ui.ruletree;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 通知触发器。
 *
 * 需要用户在「设置 → 通知与状态栏 → 通知使用权」里打开，权限页有直达入口。
 * 这里只负责把通知的关键信息转给引擎，判断哪个规则关心的活交给 Engine.fireNotification。
 */
public class NotifWatcher extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        String pkg = sbn.getPackageName();
        if (getPackageName().equals(pkg)) return;   // 自己的通知不触发，免得绕回来

        Bundle ex = sbn.getNotification().extras;
        String title = str(ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT));
        if (text.isEmpty() && ex != null) {
            text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        }

        Engine.fireNotification(this, pkg, title, text);
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
