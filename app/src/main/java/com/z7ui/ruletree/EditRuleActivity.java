package com.z7ui.ruletree;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页。第一版的界面只暴露「一个条件 + 两组动作」，
 * 但底下存的是任意嵌套的树，以后要加嵌套分支只改这里，不动存储。
 */
public class EditRuleActivity extends AppCompatActivity {

    private static final int ID_ACTION_RINGER = 0;
    private static final int ID_ACTION_VOLUME = 1;
    private static final int ID_ACTION_OPEN = 2;
    private static final int ID_ACTION_NOTIFY = 3;

    private Model.Rule rule;
    private boolean isNew;

    private TextView tvName, tvTrigger, tvCond;
    private LinearLayout thenContainer, elseContainer;

    private Model.Cond cond = new Model.Cond();
    private final List<Model.Node> thenList = new ArrayList<>();
    private final List<Model.Node> elseList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        String rid = getIntent().getStringExtra("rid");
        if (rid != null) rule = RuleStore.find(this, rid);
        isNew = rule == null;
        if (rule == null) rule = new Model.Rule();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setTitle(isNew ? "新建规则" : "编辑规则");
        toolbar.setNavigationOnClickListener(v -> finish());

        tvName = findViewById(R.id.tvName);
        tvTrigger = findViewById(R.id.tvTrigger);
        tvCond = findViewById(R.id.tvCond);
        thenContainer = findViewById(R.id.thenContainer);
        elseContainer = findViewById(R.id.elseContainer);

        loadFromTree();

        findViewById(R.id.rowName).setOnClickListener(v -> editName());
        findViewById(R.id.rowTrigger).setOnClickListener(v -> pickTrigger());
        findViewById(R.id.rowCond).setOnClickListener(v -> pickCondition());
        findViewById(R.id.btnAddThen).setOnClickListener(v -> pickAction(thenList));
        findViewById(R.id.btnAddElse).setOnClickListener(v -> pickAction(elseList));
        findViewById(R.id.btnTest).setOnClickListener(v -> {
            Model.Rule probe = build();
            Engine.run(this, probe);
            Toast.makeText(this, "已执行，去主界面「查看日志」看结果", Toast.LENGTH_LONG).show();
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnDelete).setVisibility(isNew ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());

        render();
    }

    /** 把树读回编辑器的三个字段。seq 视为「无条件，顺序执行」。 */
    private void loadFromTree() {
        Model.Node t = rule.tree;
        if (t != null && "if".equals(t.type)) {
            cond = t.condition == null ? always() : t.condition;
            thenList.addAll(t.then);
            elseList.addAll(t.otherwise);
        } else if (t != null && "seq".equals(t.type)) {
            cond = always();
            thenList.addAll(t.children);
        } else {
            cond = always();
        }
    }

    private Model.Cond always() {
        Model.Cond c = new Model.Cond();
        c.type = "always";
        return c;
    }

    private void render() {
        tvName.setText(rule.name == null || rule.name.isEmpty() ? "（未命名）" : rule.name);
        tvTrigger.setText(rule.trigger.describe());
        tvCond.setText(cond.describe());
        renderRows(thenContainer, thenList);
        renderRows(elseContainer, elseList);
    }

    private void renderRows(LinearLayout container, final List<Model.Node> list) {
        container.removeAllViews();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（空，什么也不做）");
            empty.setTextSize(13);
            empty.setPadding(8, 16, 8, 16);
            container.addView(empty);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(8, 12, 8, 12);

            TextView text = new TextView(this);
            text.setText((i + 1) + ". " + list.get(i).describe());
            text.setTextSize(15);
            text.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text);

            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextSize(13);
            del.setPadding(16, 8, 8, 8);
            del.setOnClickListener(v -> {
                list.remove(index);
                render();
            });
            row.addView(del);

            container.addView(row);
        }
    }

    // ---------------- 名称 ----------------

    private void editName() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(rule.name);
        et.setHint("例如：回家静音");
        new AlertDialog.Builder(this)
                .setTitle("规则名称")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    rule.name = et.getText().toString().trim();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 触发器 ----------------

    private void pickTrigger() {
        final String[] labels = {
                "Wi-Fi 连上时", "Wi-Fi 断开时", "Wi-Fi 状态变化时",
                "插上电源时", "拔掉电源时", "电源状态变化时",
                "手动运行"
        };
        new AlertDialog.Builder(this)
                .setTitle("什么时候运行")
                .setItems(labels, (d, which) -> {
                    Model.Trigger t = new Model.Trigger();
                    try {
                        switch (which) {
                            case 0:
                                t.type = "wifi_state";
                                t.params.put("watch", "connected");
                                break;
                            case 1:
                                t.type = "wifi_state";
                                t.params.put("watch", "disconnected");
                                break;
                            case 2:
                                t.type = "wifi_state";
                                t.params.put("watch", "any");
                                break;
                            case 3:
                                t.type = "power_state";
                                t.params.put("watch", "connected");
                                break;
                            case 4:
                                t.type = "power_state";
                                t.params.put("watch", "disconnected");
                                break;
                            case 5:
                                t.type = "power_state";
                                t.params.put("watch", "any");
                                break;
                            default:
                                t.type = "manual";
                                break;
                        }
                    } catch (Exception ignored) {
                    }
                    rule.trigger = t;
                    render();
                })
                .show();
    }

    // ---------------- 条件 ----------------

    private void pickCondition() {
        final String[] labels = {
                "总是成立（无条件）",
                "连着 Wi-Fi（任意）",
                "连着指定 Wi-Fi",
                "正在充电",
                "电量低于某个百分比",
                "时间在一个区间内"
        };
        new AlertDialog.Builder(this)
                .setTitle("判断条件")
                .setItems(labels, (d, which) -> {
                    switch (which) {
                        case 0:
                            cond = always();
                            render();
                            break;
                        case 1:
                            cond = cond("wifi_connected");
                            try {
                                cond.params.put("matchAny", true);
                            } catch (Exception ignored) {
                            }
                            askNegate();
                            break;
                        case 2:
                            pickSsid();
                            break;
                        case 3:
                            cond = cond("charging");
                            askNegate();
                            break;
                        case 4:
                            askNumber("电量低于多少（%）", 20,
                                    v -> {
                                        cond = cond("battery_below");
                                        try {
                                            cond.params.put("pct", v);
                                        } catch (Exception ignored) {
                                        }
                                    }, true);
                            break;
                        case 5:
                            askTimeRange();
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Model.Cond cond(String type) {
        Model.Cond c = new Model.Cond();
        c.type = type;
        return c;
    }

    private void askNegate() {
        final Model.Cond target = cond;
        new AlertDialog.Builder(this)
                .setTitle("要取反吗")
                .setMessage("取反后表示「不满足：" + target.describe() + "」")
                .setPositiveButton("取反", (d, w) -> {
                    target.negate = true;
                    render();
                })
                .setNegativeButton("不取反", (d, w) -> render())
                .show();
    }

    /** 选 Wi-Fi：列当前连接的和扫到的，实在没有就手动输。 */
    private void pickSsid() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setTitle("需要定位权限")
                    .setMessage("Android 把 Wi-Fi 名字归到定位权限里了，不给的话读不到列表。\n也可以先给权限，回来再点一次。")
                    .setPositiveButton("去授权", (d, w) ->
                            startActivity(new Intent(this, PermissionsActivity.class)))
                    .setNegativeButton("手动输入", (d, w) -> manualSsid())
                    .show();
            return;
        }

        List<String> ssids = new ArrayList<>();
        String current = null;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            try {
                if (wm.getConnectionInfo() != null) {
                    current = wm.getConnectionInfo().getSSID();
                    if (current != null) current = current.replace("\"", "");
                    if ("<unknown ssid>".equals(current) || "0x".equals(current)) current = null;
                }
            } catch (Exception ignored) {
            }
            try {
                List<ScanResult> results = wm.getScanResults();
                if (results != null) {
                    for (ScanResult r : results) {
                        if (r.SSID == null || r.SSID.isEmpty()) continue;
                        if (!ssids.contains(r.SSID)) ssids.add(r.SSID);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        List<String> labels = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        if (current != null) {
            labels.add("当前连着：" + current);
            values.add(current);
        }
        for (String s : ssids) {
            if (current != null && current.equals(s)) continue;
            labels.add(s);
            values.add(s);
        }
        labels.add("手动输入…");

        new AlertDialog.Builder(this)
                .setTitle("选择 Wi-Fi")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == labels.size() - 1) {
                        manualSsid();
                        return;
                    }
                    applySsid(values.get(which));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void manualSsid() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("Wi-Fi 名称，和系统里显示的一致");
        new AlertDialog.Builder(this)
                .setTitle("手动输入")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> applySsid(et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void applySsid(String ssid) {
        cond = cond("wifi_connected");
        try {
            cond.params.put("ssid", ssid);
            cond.params.put("matchAny", false);
        } catch (Exception ignored) {
        }
        askNegate();
    }

    private void askTimeRange() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);

        EditText start = new EditText(this);
        start.setHint("开始，例如 22:00");
        start.setText("22:00");
        EditText end = new EditText(this);
        end.setHint("结束，例如 07:00");
        end.setText("07:00");
        box.addView(start);
        box.addView(end);

        new AlertDialog.Builder(this)
                .setTitle("时间段")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    cond = cond("time_between");
                    try {
                        cond.params.put("start", start.getText().toString().trim());
                        cond.params.put("end", end.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    askNegate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface OnNumber {
        void on(int v);
    }

    private void askNumber(String title, int def, final OnNumber cb, final boolean thenNegate) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(def));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    int v;
                    try {
                        v = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        v = def;
                    }
                    cb.on(v);
                    if (thenNegate) askNegate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 动作 ----------------

    private void pickAction(final List<Model.Node> target) {
        final String[] labels = {"铃声：静音", "铃声：振动", "铃声：正常", "媒体音量", "打开应用", "发通知"};
        new AlertDialog.Builder(this)
                .setTitle("添加动作")
                .setItems(labels, (d, which) -> {
                    switch (which) {
                        case 0:
                            target.add(act("ringer_mode", "mode", "silent"));
                            render();
                            break;
                        case 1:
                            target.add(act("ringer_mode", "mode", "vibrate"));
                            render();
                            break;
                        case 2:
                            target.add(act("ringer_mode", "mode", "normal"));
                            render();
                            break;
                        case 3:
                            askNumber("媒体音量（0-100）", 50, v -> {
                                Model.Node n = new Model.Node();
                                n.type = "action";
                                n.action = "media_volume";
                                try {
                                    n.params.put("value", v);
                                } catch (Exception ignored) {
                                }
                                target.add(n);
                                render();
                            }, false);
                            break;
                        case 4:
                            pickApp(target);
                            break;
                        case 5:
                            askNotify(target);
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private Model.Node act(String action, String key, String value) {
        Model.Node n = new Model.Node();
        n.type = "action";
        n.action = action;
        try {
            n.params.put(key, value);
        } catch (Exception ignored) {
        }
        return n;
    }

    private void askNotify(final List<Model.Node> target) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);
        final EditText title = new EditText(this);
        title.setHint("标题");
        title.setText("规则已执行");
        final EditText text = new EditText(this);
        text.setHint("内容");
        box.addView(title);
        box.addView(text);

        new AlertDialog.Builder(this)
                .setTitle("发通知")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    Model.Node n = new Model.Node();
                    n.type = "action";
                    n.action = "notify";
                    try {
                        n.params.put("title", title.getText().toString());
                        n.params.put("text", text.getText().toString());
                    } catch (Exception ignored) {
                    }
                    target.add(n);
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickApp(final List<Model.Node> target) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        final List<String> labels = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        for (ResolveInfo ri : ris) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || pkgs.contains(p)) continue;
            labels.add(ri.loadLabel(pm).toString());
            pkgs.add(p);
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "没有可选的应用", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择应用")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    Model.Node n = new Model.Node();
                    n.type = "action";
                    n.action = "open_app";
                    try {
                        n.params.put("pkg", pkgs.get(which));
                        n.params.put("label", labels.get(which));
                    } catch (Exception ignored) {
                    }
                    target.add(n);
                    render();
                })
                .show();
    }

    // ---------------- 保存 ----------------

    private Model.Rule build() {
        Model.Rule r = new Model.Rule();
        r.id = rule.id;
        r.createdAt = rule.createdAt;
        r.updatedAt = System.currentTimeMillis();
        r.enabled = rule.enabled;
        r.trigger = rule.trigger;
        r.name = rule.name == null || rule.name.isEmpty() ? "未命名规则" : rule.name;

        if ("always".equals(cond.type) && elseList.isEmpty()) {
            Model.Node seq = new Model.Node();
            seq.type = "seq";
            seq.children.addAll(thenList);
            r.tree = seq;
        } else {
            Model.Node ifNode = new Model.Node();
            ifNode.type = "if";
            ifNode.condition = cond;
            ifNode.then.addAll(thenList);
            ifNode.otherwise.addAll(elseList);
            r.tree = ifNode;
        }
        return r;
    }

    private void save() {
        if (rule.name == null || rule.name.trim().isEmpty()) {
            Toast.makeText(this, "先给规则起个名字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (thenList.isEmpty() && elseList.isEmpty()) {
            Toast.makeText(this, "至少加一个动作", Toast.LENGTH_SHORT).show();
            return;
        }
        Model.Rule r = build();
        RuleStore.upsert(this, r);
        WatchService.sync(this);
        Logx.i(this, "已保存规则 " + r.name + " (" + r.id + ")");
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage("确定删除「" + rule.name + "」？")
                .setPositiveButton("删除", (d, w) -> {
                    RuleStore.remove(this, rule.id);
                    WatchService.sync(this);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
