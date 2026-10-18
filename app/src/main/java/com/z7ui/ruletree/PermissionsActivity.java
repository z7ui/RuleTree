package com.z7ui.ruletree;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

/** 权限单独一页：每项写清用途、当前状态，点一下就跳到对应设置。 */
public class PermissionsActivity extends AppCompatActivity {

    private LinearLayout container;
    private TextView tvSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.container);
        tvSummary = findViewById(R.id.tvSummary);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        render();
    }

    private void render() {
        container.removeAllViews();

        boolean loc = has(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean notif = Build.VERSION.SDK_INT < 33
                || has("android.permission.POST_NOTIFICATIONS");
        boolean dnd = dndGranted();
        boolean battery = ignoringBattery();

        int missing = (loc ? 0 : 1) + (notif ? 0 : 1) + (dnd ? 0 : 1) + (battery ? 0 : 1);
        tvSummary.setText(missing == 0
                ? "都齐了。铃声和音量能正常改，也能读 Wi-Fi 名字。"
                : "还差 " + missing + " 项。带「必须」的两项不给，规则基本没法用。下面每项点一下都能跳到对应设置。");

        addRow("定位", "必须。读 Wi-Fi 名称要用它（Android 从 8.1 起把 SSID 归到定位里）。\n不给的话只能判断「连没连 Wi-Fi」，判不了具体是哪个网络。",
                loc, v -> requestLocation());

        addRow("通知", "发通知这个动作要用，前台服务的常驻提示也走这里。\nAndroid 13 以上需要单独授权。",
                notif, v -> requestNotif());

        boolean listener = NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(getPackageName());
        addRow("通知使用权", "用「收到通知」触发规则时要开。不开的话这类触发器永远不响，\n别的功能不受影响，所以没算进上面的统计。",
                listener, v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        addRow("勿扰访问", "必须。改铃声模式和媒体音量都要它，没有的话动作会被系统静默忽略。",
                dnd, v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));

        addRow("电池优化", "建议开。设成「无限制」后，监听服务不容易被系统清掉。",
                battery, v -> openBattery());

        addRow("自启动", "小米特有，没法检测状态。不开的话重启后监听服务可能起不来。",
                null, v -> openAutoStart());
    }

    private void addRow(String title, String desc, Boolean ok, View.OnClickListener click) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        card.setRadius(dp(14));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(click);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setLineSpacing(dp(2), 1f);
        d.setPadding(0, dp(4), 0, 0);
        texts.addView(d);

        row.addView(texts);

        TextView state = new TextView(this);
        state.setTextSize(13);
        state.setPadding(dp(12), 0, 0, 0);
        if (ok == null) {
            state.setText("需手动");
            state.setTextColor(ContextCompat.getColor(this, R.color.perm_unknown));
        } else if (ok) {
            state.setText("已授权");
            state.setTextColor(ContextCompat.getColor(this, R.color.perm_ok));
        } else {
            state.setText("未授权");
            state.setTextColor(ContextCompat.getColor(this, R.color.perm_missing));
        }
        row.addView(state);

        card.addView(row);
        container.addView(card);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------------- 状态 ----------------

    private boolean has(String perm) {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean dndGranted() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    private boolean ignoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // ---------------- 跳转 ----------------

    private void requestLocation() {
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "已经授权了", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 30);
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (has("android.permission.POST_NOTIFICATIONS")) {
                Toast.makeText(this, "已经授权了", Toast.LENGTH_SHORT).show();
                return;
            }
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 31);
        } else {
            Toast.makeText(this, "这个系统版本不需要单独授权", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBattery() {
        if (ignoringBattery()) {
            Toast.makeText(this, "已经是无限制了", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        if (!startSafely(direct)) {
            startSafely(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void openAutoStart() {
        Intent mi = new Intent();
        mi.setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (startSafely(mi)) return;
        startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private boolean startSafely(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
