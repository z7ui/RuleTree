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
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页。树是递归的，界面也递归：任意一层都能加「动作」或再嵌一个「条件分支」。
 * 存储格式见 DATA-MODEL.md，节点只有 action / if / seq 三种。
 */
public class EditRuleActivity extends AppCompatActivity {

    private Model.Rule rule;
    private boolean isNew;

    private TextView tvName, tvTrigger, tvCond;
    private LinearLayout thenContainer, elseContainer;

    private Model.Cond cond = new Model.Cond();
    private final List<Model.Node> thenList = new ArrayList<>();
    private final List<Model.Node> elseList = new ArrayList<>();

    /** 当前正在编辑的条件归属。get() 用来判断「这一格现在是不是组合条件」，可为 null。 */
    private abstract class CondTarget {
        abstract Model.Cond get();

        abstract void set(Model.Cond c);
    }

    private CondTarget condTarget;

    /** 打开着的组合条件弹窗，主界面重绘时一并刷新它们的内容。 */
    private final List<Runnable> groupRefreshers = new ArrayList<>();

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
        condTarget = new CondTarget() {
            @Override
            Model.Cond get() {
                return cond;
            }

            @Override
            void set(Model.Cond c) {
                cond = c;
            }
        };
        findViewById(R.id.rowCond).setOnClickListener(v -> openConditionEditor());
        findViewById(R.id.btnAddThen).setOnClickListener(v ->
                addChooser(thenList, "条件成立时"));
        findViewById(R.id.btnAddElse).setOnClickListener(v ->
                addChooser(elseList, "条件不成立时"));
        findViewById(R.id.btnTest).setOnClickListener(v -> {
            final Model.Rule probe = build();
            Toast.makeText(this, "开始执行…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                Engine.run(getApplicationContext(), probe);
                runOnUiThread(() -> Toast.makeText(this, "执行完了，主界面菜单能看日志",
                        Toast.LENGTH_SHORT).show());
            }).start();
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnDelete).setVisibility(isNew ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());

        render();
    }

    // ---------------- 载入与渲染 ----------------

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
        renderRows(thenContainer, thenList, 0);
        renderRows(elseContainer, elseList, 0);
        // 组合条件的弹窗还开着的话，把里面的行也重画一遍
        for (Runnable r : new ArrayList<>(groupRefreshers)) r.run();
    }

    /** 递归渲染一组节点。if 节点会把它的两个分支也画出来，缩进一层。 */
    private void renderRows(LinearLayout container, final List<Model.Node> list, final int depth) {
        container.removeAllViews();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(depth == 0 ? "（空，什么也不做）" : "（空）");
            empty.setTextSize(13);
            empty.setPadding(dp(8 + depth * 12), dp(8), 0, dp(8));
            container.addView(empty);
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            final Model.Node node = list.get(i);
            final int index = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8 + depth * 12), dp(10), 0, dp(10));

            TextView text = new TextView(this);
            String prefix = (i + 1) + ". ";
            text.setText(prefix + node.describe());
            text.setTextSize(15);
            text.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text);

            if ("if".equals(node.type)) {
                TextView edit = new TextView(this);
                edit.setText("改条件");
                edit.setTextSize(13);
                edit.setTextColor(ContextCompat.getColor(this, R.color.perm_ok));
                edit.setPadding(dp(10), dp(6), dp(6), dp(6));
                edit.setOnClickListener(v -> {
                    condTarget = new CondTarget() {
                        @Override
                        Model.Cond get() {
                            return node.condition;
                        }

                        @Override
                        void set(Model.Cond c) {
                            node.condition = c;
                        }
                    };
                    openConditionEditor();
                });
                row.addView(edit);
            }

            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextSize(13);
            del.setPadding(dp(10), dp(6), dp(6), dp(6));
            del.setOnClickListener(v -> {
                list.remove(index);
                render();
            });
            row.addView(del);

            container.addView(row);

            // 嵌套分支：把两边的列表继续画出来，并各带一个添加按钮
            if ("if".equals(node.type)) {
                if (node.condition == null) node.condition = always();

                TextView thenLabel = new TextView(this);
                thenLabel.setText("成立时");
                thenLabel.setTextSize(12);
                thenLabel.setPadding(dp(20 + depth * 12), dp(4), 0, dp(2));
                container.addView(thenLabel);
                container.addView(branchBox(node.then, depth + 1));

                TextView elseLabel = new TextView(this);
                elseLabel.setText("不成立时");
                elseLabel.setTextSize(12);
                elseLabel.setPadding(dp(20 + depth * 12), dp(4), 0, dp(2));
                container.addView(elseLabel);
                container.addView(branchBox(node.otherwise, depth + 1));
            }
        }
    }

    /** 一个分支 = 子列表渲染 + 一行「+」按钮 */
    private LinearLayout branchBox(final List<Model.Node> list, final int depth) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rows);
        renderRows(rows, list, depth);

        TextView add = new TextView(this);
        add.setText("＋ 添加");
        add.setTextSize(13);
        add.setTextColor(ContextCompat.getColor(this, R.color.perm_ok));
        add.setPadding(dp(8 + depth * 12), dp(6), 0, dp(10));
        add.setOnClickListener(v -> addChooser(list, "这一层"));
        box.addView(add);

        return box;
    }

    /** 加东西：动作还是再嵌一层分支 */
    private void addChooser(final List<Model.Node> list, String where) {
        final String[] items = {"动作", "条件分支（再加一层如果）"};
        new AlertDialog.Builder(this)
                .setTitle("往「" + where + "」里加")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickAction(list);
                    } else {
                        Model.Node branch = new Model.Node();
                        branch.type = "if";
                        branch.condition = always();
                        list.add(branch);
                        render();
                        Toast.makeText(this, "已加一层分支，点「改条件」设置判断", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
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
                "收到通知时",
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
                            case 6:
                                pickNotificationTrigger();
                                return;
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

    /** 通知触发：先选应用（可以任意），再填关键词（可以空）。 */
    private void pickNotificationTrigger() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);

        final List<String> labels = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        labels.add("任意应用");
        pkgs.add("");
        for (ResolveInfo ri : ris) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || pkgs.contains(p)) continue;
            labels.add(ri.loadLabel(pm).toString());
            pkgs.add(p);
        }

        new AlertDialog.Builder(this)
                .setTitle("监听哪个应用的通知")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    final String pkg = pkgs.get(which);
                    final String label = which == 0 ? "" : labels.get(which);
                    askKeyword(pkg, label);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askKeyword(final String pkg, final String label) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("内容包含这些字才触发，可留空");
        new AlertDialog.Builder(this)
                .setTitle("关键词（可选）")
                .setMessage("留空表示只要收到通知就触发。")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    Model.Trigger t = new Model.Trigger();
                    t.type = "notification";
                    try {
                        t.params.put("pkg", pkg);
                        t.params.put("label", label);
                        t.params.put("keyword", et.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    rule.trigger = t;
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 条件 ----------------

    /** 点条件那一行。已经是组合的话先问一句，别让人一点就把整组弄丢。 */
    private void openConditionEditor() {
        Model.Cond cur = condTarget == null ? null : condTarget.get();
        if (cur == null || !cur.isGroup()) {
            pickCondition();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("判断条件")
                .setItems(new String[]{"编辑这个组合", "换成单个条件"}, (d, which) -> {
                    if (which == 0) editGroup(cur, null);
                    else pickCondition();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 新建一个空组合。点「完成」才挂上去，取消就丢掉。 */
    private void startGroup() {
        // 记住开弹窗时是谁在接这个条件。组里的「添加条件」会改 condTarget，
        // 不记住的话「完成」时会把新组塞进自己肚子里。
        final CondTarget attachTo = condTarget;
        Model.Cond g = new Model.Cond();
        g.type = "group";
        g.op = "all";
        editGroup(g, attachTo);
    }

    /**
     * 组合条件编辑器。嵌套靠弹窗叠弹窗：组里点某一条可以就地改，
     * 点「添加条件」再选「组合」，就会在这上面再叠一层。
     *
     * @param attachTo 完成时把这个组挂到哪儿；就地编辑已有组时传 null
     */
    private void editGroup(final Model.Cond group, final CondTarget attachTo) {
        final boolean isNewGroup = group.children.isEmpty();

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));

        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                box.removeAllViews();

                TextView mode = new TextView(EditRuleActivity.this);
                boolean any = "any".equals(group.op);
                mode.setText(any ? "满足任意一条（或者）　点这里切换"
                        : "满足全部条件（并且）　点这里切换");
                mode.setTextSize(15);
                mode.setTextColor(ContextCompat.getColor(EditRuleActivity.this, R.color.perm_ok));
                mode.setPadding(0, dp(10), 0, dp(12));
                mode.setOnClickListener(v -> {
                    group.op = "any".equals(group.op) ? "all" : "any";
                    render();
                });
                box.addView(mode);

                if (group.children.isEmpty()) {
                    TextView empty = new TextView(EditRuleActivity.this);
                    empty.setText("还没有条件，点下面的按钮加一条");
                    empty.setTextSize(13);
                    empty.setPadding(0, dp(2), 0, dp(10));
                    box.addView(empty);
                }

                for (int i = 0; i < group.children.size(); i++) {
                    final Model.Cond child = group.children.get(i);

                    LinearLayout row = new LinearLayout(EditRuleActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(6), 0, dp(6));

                    TextView text = new TextView(EditRuleActivity.this);
                    text.setText((i + 1) + ". " + child.describe());
                    text.setTextSize(15);
                    text.setLayoutParams(new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    text.setOnClickListener(v -> editChild(group, child));
                    row.addView(text);

                    TextView del = new TextView(EditRuleActivity.this);
                    del.setText("删除");
                    del.setTextSize(13);
                    del.setPadding(dp(10), dp(6), dp(2), dp(6));
                    del.setOnClickListener(v -> {
                        group.children.remove(child);
                        render();
                    });
                    row.addView(del);

                    box.addView(row);
                }

                TextView add = new TextView(EditRuleActivity.this);
                add.setText("＋ 添加条件");
                add.setTextSize(14);
                add.setTextColor(ContextCompat.getColor(EditRuleActivity.this, R.color.perm_ok));
                add.setPadding(0, dp(10), 0, dp(4));
                add.setOnClickListener(v -> {
                    condTarget = new CondTarget() {
                        @Override
                        Model.Cond get() {
                            return null;
                        }

                        @Override
                        void set(Model.Cond c) {
                            group.children.add(c);
                        }
                    };
                    pickCondition();
                });
                box.addView(add);
            }
        };

        groupRefreshers.add(fill);
        fill.run();

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("组合条件")
                .setView(sv)
                .setPositiveButton("完成", (d, w) -> {
                    groupRefreshers.remove(fill);
                    if (group.children.isEmpty()) {
                        Toast.makeText(this, "至少要有一个条件，这次不算", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (attachTo != null) attachTo.set(group);
                    render();
                    // 新建的组才问取反，改已有的组再问一次会让人以为点错了
                    if (isNewGroup) askNegate(group);
                })
                .setNegativeButton("取消", (d, w) -> groupRefreshers.remove(fill))
                .show();
    }

    /** 点组合里的某一条：本身是组合就继续往下编，是叶子就重新选一遍。 */
    private void editChild(final Model.Cond group, final Model.Cond child) {
        if (child.isGroup()) {
            editGroup(child, null);
            return;
        }
        condTarget = new CondTarget() {
            @Override
            Model.Cond get() {
                return child;
            }

            @Override
            void set(Model.Cond c) {
                int i = group.children.indexOf(child);
                if (i >= 0) group.children.set(i, c);
                else group.children.add(c);
            }
        };
        pickCondition();
    }

    private void pickCondition() {
        final String[] labels = {
                "总是成立（无条件）",
                "连着 Wi-Fi（任意）",
                "连着指定 Wi-Fi",
                "正在充电",
                "电量低于某个百分比",
                "时间在一个区间内",
                "组合条件（并且 / 或者）"
        };
        new AlertDialog.Builder(this)
                .setTitle("判断条件")
                .setItems(labels, (d, which) -> {
                    switch (which) {
                        case 0:
                            applyCond(always());
                            break;
                        case 1:
                            applyCond(cond("wifi_connected", "matchAny", true));
                            break;
                        case 2:
                            pickSsid();
                            break;
                        case 3:
                            applyCond(cond("charging"));
                            break;
                        case 4:
                            askNumber("电量低于多少（%）", 20, v ->
                                    applyCond(cond("battery_below", "pct", v)), true);
                            break;
                        case 5:
                            askTimeRange();
                            break;
                        case 6:
                            startGroup();
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

    private Model.Cond cond(String type, String key, Object value) {
        Model.Cond c = cond(type);
        try {
            c.params.put(key, value);
        } catch (Exception ignored) {
        }
        return c;
    }

    private void applyCond(Model.Cond c) {
        applyCond(c, true);
    }

    private void applyCond(Model.Cond c, boolean withNegate) {
        if (condTarget == null) {
            condTarget = new CondTarget() {
                @Override
                Model.Cond get() {
                    return cond;
                }

                @Override
                void set(Model.Cond x) {
                    cond = x;
                }
            };
        }
        condTarget.set(c);
        render();
        if (withNegate) askNegate(c);
    }

    private void askNegate(final Model.Cond target) {
        new AlertDialog.Builder(this)
                .setTitle("要取反吗")
                .setMessage("取反后表示「" + target.describe() + "」不成立时才走这一边。")
                .setPositiveButton("取反", (d, w) -> {
                    target.negate = !target.negate;
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
        Model.Cond c = cond("wifi_connected");
        try {
            c.params.put("ssid", ssid);
            c.params.put("matchAny", false);
        } catch (Exception ignored) {
        }
        applyCond(c);
    }

    private void askTimeRange() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);

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
                    Model.Cond c = cond("time_between");
                    try {
                        c.params.put("start", start.getText().toString().trim());
                        c.params.put("end", end.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    applyCond(c);
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
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 动作 ----------------

    private void pickAction(final List<Model.Node> target) {
        final String[] labels = {
                "铃声：静音", "铃声：振动", "铃声：正常",
                "媒体音量", "打开应用", "发通知", "等待一会儿", "执行前确认"
        };
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
                        case 7:
                            askConfirm(target);
                            break;
                        case 6:
                            askNumber("等待多少秒", 5, v -> {
                                Model.Node n = new Model.Node();
                                n.type = "action";
                                n.action = "delay";
                                try {
                                    n.params.put("ms", Math.max(1, v) * 1000);
                                } catch (Exception ignored) {
                                }
                                target.add(n);
                                render();
                            }, false);
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

    /** 执行前确认：发一条带按钮的通知，最多等 N 秒。 */
    private void askConfirm(final List<Model.Node> target) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);

        final EditText text = new EditText(this);
        text.setHint("通知上显示的问题");
        text.setText("继续执行后面的动作？");
        final EditText sec = new EditText(this);
        sec.setHint("最多等几秒");
        sec.setInputType(InputType.TYPE_CLASS_NUMBER);
        sec.setText("15");
        box.addView(text);
        box.addView(sec);

        new AlertDialog.Builder(this)
                .setTitle("执行前确认")
                .setMessage("到这一步会发通知，你点「执行」才继续；超时或点「算了」就取消这一支剩下的动作。")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    Model.Node n = new Model.Node();
                    n.type = "action";
                    n.action = "confirm";
                    try {
                        n.params.put("text", text.getText().toString());
                        n.params.put("sec", Math.max(3, Integer.parseInt(sec.getText().toString().trim())));
                    } catch (Exception ignored) {
                    }
                    target.add(n);
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askNotify(final List<Model.Node> target) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
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
