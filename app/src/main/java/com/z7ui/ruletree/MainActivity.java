package com.z7ui.ruletree;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private LinearLayout listContainer;
    private TextView emptyTip;
    private MaterialCardView permCard;
    private TextView tvPerm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setTitle(R.string.app_name);

        listContainer = findViewById(R.id.listContainer);
        emptyTip = findViewById(R.id.emptyTip);
        permCard = findViewById(R.id.permCard);
        tvPerm = findViewById(R.id.tvPerm);

        permCard.setOnClickListener(v ->
                startActivity(new Intent(this, PermissionsActivity.class)));

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> startActivity(new Intent(this, EditRuleActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderList();
        renderPerm();
        // 没有启用中的规则就不挂常驻通知
        WatchService.sync(this);
    }

    // ---------------- 菜单 ----------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "权限");
        menu.add(0, 2, 1, "添加示例规则");
        menu.add(0, 3, 2, "查看日志");
        menu.add(0, 4, 3, "清除日志");
        menu.add(0, 5, 4, "关于");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                startActivity(new Intent(this, PermissionsActivity.class));
                return true;
            case 2:
                addDemoRule();
                return true;
            case 3:
                showLog();
                return true;
            case 4:
                clearLog();
                return true;
            case 5:
                showAbout();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void addDemoRule() {
        for (Model.Rule r : RuleStore.load(this)) {
            if ("演示：回家静音".equals(r.name)) {
                Toast.makeText(this, "示例已经加过了", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Model.Rule r = new Model.Rule();
        r.name = "演示：回家静音";
        r.trigger.type = "wifi_state";
        try {
            r.trigger.params.put("watch", "any");
        } catch (Exception ignored) {
        }

        Model.Cond cond = new Model.Cond();
        cond.type = "wifi_connected";
        try {
            cond.params.put("ssid", "Home-5G");
            cond.params.put("matchAny", true);
        } catch (Exception ignored) {
        }

        Model.Node ifNode = new Model.Node();
        ifNode.type = "if";
        ifNode.condition = cond;
        ifNode.then.add(action("ringer_mode", "mode", "silent"));
        ifNode.otherwise.add(action("ringer_mode", "mode", "normal"));
        r.tree = ifNode;

        RuleStore.upsert(this, r);
        WatchService.sync(this);
        renderList();
        renderPerm();
        Toast.makeText(this, "示例已添加，切换 Wi-Fi 就能看到效果", Toast.LENGTH_SHORT).show();
    }

    private Model.Node action(String name, String key, String value) {
        Model.Node n = new Model.Node();
        n.type = "action";
        n.action = name;
        try {
            JSONObject p = new JSONObject();
            p.put(key, value);
            n.params = p;
        } catch (Exception ignored) {
        }
        return n;
    }

    private void showLog() {
        String all = Logx.read(this);
        String[] lines = all.split("\n");
        int from = Math.max(0, lines.length - 100);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append("\n");

        TextView tv = new TextView(this);
        tv.setPadding(48, 32, 48, 32);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setMovementMethod(new ScrollingMovementMethod());
        tv.setText(sb.length() == 0 ? "(暂无日志)" : sb.toString());

        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(tv)
                .setPositiveButton("关闭", null)
                .setNegativeButton("清除", (d, w) -> clearLog())
                .show();
    }

    private void clearLog() {
        java.io.File f = new java.io.File(getFilesDir(), "rt.log");
        long size = f.exists() ? f.length() : 0;
        if (size <= 0) {
            Toast.makeText(this, "没有日志可清除", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清除日志")
                .setMessage("共 " + (size / 1024) + " KB，确定删除？")
                .setPositiveButton("删除", (d, w) -> {
                    new java.io.File(getFilesDir(), "rt.old.log").delete();
                    f.delete();
                    Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAbout() {
        String body = "把「如果……则……否则……」做成可复用的规则。触发器和动作分开，条件成立走一边，不成立走另一边。\n\n"
                + "· 规则存成 JSON 树，支持任意层级嵌套\n"
                + "· 触发器：Wi-Fi、电源、蓝牙、收到通知、每天定时、手动\n"
                + "· 条件：连着指定 Wi-Fi、充电、电量、时间段、星期、屏幕与锁屏、免打扰、铃声模式、深色模式、蓝牙、耳机，都能取反，也能组合成「并且 / 或者」\n"
                + "· 动作：铃声与各路音量、免打扰、媒体键、手电筒、打开网址、复制、朗读、HTTP 请求、打开应用、发通知、等待、确认、等待解锁、返回/主屏/锁屏/点击/滑动\n\n"
                + "作者  Deepseek · B站 @z7ui\n"
                + "仓库  github.com/z7ui/RuleTree\n\n"
                + "版本  " + versionName();
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(body)
                .setPositiveButton("确定", null)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.1";
        }
    }

    // ---------------- 列表 ----------------

    private void renderList() {
        listContainer.removeAllViews();
        List<Model.Rule> rules = RuleStore.load(this);
        emptyTip.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final Model.Rule r : rules) {
            View item = inflater.inflate(R.layout.item_rule, listContainer, false);
            TextView name = item.findViewById(R.id.tvName);
            TextView trigger = item.findViewById(R.id.tvTrigger);
            TextView cond = item.findViewById(R.id.tvCond);
            TextView then = item.findViewById(R.id.tvThen);
            MaterialSwitch sw = item.findViewById(R.id.swEnabled);

            name.setText(r.name);
            trigger.setText("触发：" + r.trigger.describe());

            // 没有分支的规则存成 seq，卡片上就别显示「条件：?」了
            boolean branched = "if".equals(r.tree.type) && r.tree.condition != null;
            cond.setVisibility(branched ? View.VISIBLE : View.GONE);
            if (branched) cond.setText("条件：" + r.tree.condition.describe());

            List<Model.Node> main = branched ? r.tree.then : r.tree.children;
            then.setText(branched ? "则：" + summarize(main)
                    + (r.tree.otherwise.isEmpty() ? "" : "　否则：" + summarize(r.tree.otherwise))
                    : "做：" + summarize(main));

            sw.setChecked(r.enabled);
            sw.setOnCheckedChangeListener((b, checked) -> {
                r.enabled = checked;
                RuleStore.upsert(this, r);
                WatchService.sync(this);
            });

            item.findViewById(R.id.btnRun).setOnClickListener(v ->
                    TestRunner.ask(this, () -> r, x -> true));
            item.findViewById(R.id.btnEdit).setOnClickListener(v -> {
                Intent i = new Intent(this, EditRuleActivity.class);
                i.putExtra("rid", r.id);
                startActivity(i);
            });
            listContainer.addView(item);
        }
    }

    private String summarize(List<Model.Node> list) {
        if (list == null || list.isEmpty()) return "（空）";
        List<String> parts = new ArrayList<>();
        for (Model.Node n : list) parts.add(n.describe());
        return String.join("、", parts);
    }

    private void renderPerm() {
        boolean loc = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean dnd = nm != null && nm.isNotificationPolicyAccessGranted();
        boolean notif = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;

        List<String> missing = new ArrayList<>();
        if (!loc) missing.add("定位");
        if (!dnd) missing.add("勿扰访问");
        if (!notif) missing.add("通知");

        if (missing.isEmpty()) {
            tvPerm.setText("都齐了");
            tvPerm.setTextColor(ContextCompat.getColor(this, R.color.perm_ok));
        } else {
            tvPerm.setText("还差 " + missing.size() + " 项：" + String.join("、", missing));
            tvPerm.setTextColor(ContextCompat.getColor(this, R.color.perm_missing));
        }
    }
}
