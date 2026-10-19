package com.z7ui.ruletree;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

    /** 录手势时临时记住要挂到哪一组动作里 */
    private List<Model.Node> macroTarget;

    private final ActivityResultLauncher<Intent> macroLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                String json = res.getData().getStringExtra("steps");
                if (json == null || json.isEmpty() || macroTarget == null) return;
                Model.Node n = new Model.Node();
                n.type = "action";
                n.action = "gesture_seq";
                try {
                    n.params.put("steps", new org.json.JSONArray(json));
                } catch (Exception ignored) {
                }
                macroTarget.add(n);
                macroTarget = null;
                render();
            });

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
        findViewById(R.id.btnTest).setOnClickListener(v ->
                TestRunner.ask(this, this::build, this::persistForTest));
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
                "连上蓝牙设备时", "断开蓝牙设备时", "蓝牙连接变化时",
                "每天固定时间",
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
                            case 7:
                                pickBluetooth("connected");
                                return;
                            case 8:
                                pickBluetooth("disconnected");
                                return;
                            case 9:
                                pickBluetooth("any");
                                return;
                            case 10:
                                pickTime();
                                return;
                            default:
                                t.type = "manual";
                                break;
                        }
                    } catch (Exception ignored) {
                    }
                    rule.trigger = t;
                    render();
                    WatchService.sync(this);
                })
                .show();
    }

    /** 蓝牙触发：列已配对的设备，也可以不限。 */
    private void pickBluetooth(final String watch) {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setTitle("需要蓝牙权限")
                    .setMessage("Android 12 起，读设备名和地址要单独授权。\n不给也能用「任意设备」，只是分不清是哪一台。")
                    .setPositiveButton("去授权", (d, w) ->
                            startActivity(new Intent(this, PermissionsActivity.class)))
                    .setNeutralButton("任意设备", (d, w) -> applyBluetooth(watch, "", ""))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        final List<String> labels = new ArrayList<>();
        final List<String> macs = new ArrayList<>();
        labels.add("任意蓝牙设备");
        macs.add("");
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            Set<BluetoothDevice> bonded = ad == null ? null : ad.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String name = d.getName();
                    labels.add(name == null || name.isEmpty() ? d.getAddress() : name);
                    macs.add(d.getAddress());
                }
            }
        } catch (Exception e) {
            Logx.i(this, "读取已配对设备失败: " + e);
        }
        if (labels.size() == 1) labels.add("（没有已配对的设备，先随便选着）");

        new AlertDialog.Builder(this)
                .setTitle("哪台设备")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= macs.size()) return;
                    String label = which == 0 ? "" : labels.get(which);
                    applyBluetooth(watch, macs.get(which), label);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyBluetooth(String watch, String mac, String label) {
        Model.Trigger t = new Model.Trigger();
        t.type = "bluetooth_state";
        try {
            t.params.put("watch", watch);
            t.params.put("mac", mac);
            t.params.put("label", label);
        } catch (Exception ignored) {
        }
        rule.trigger = t;
        render();
    }

    /** 每天固定时间：先选时刻，再选星期，都不选就是每天。 */
    private void pickTime() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        new android.app.TimePickerDialog(this, (view, h, m) ->
                askWeekdays(String.format(java.util.Locale.CHINA, "%02d:%02d", h, m)),
                now.get(java.util.Calendar.HOUR_OF_DAY),
                now.get(java.util.Calendar.MINUTE), true).show();
    }

    private void askWeekdays(final String hhmm) {
        final String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        final boolean[] checked = new boolean[7];
        new AlertDialog.Builder(this)
                .setTitle(hhmm + " 哪几天跑")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setMessage("都不勾就是每天。")
                .setPositiveButton("确定", (d, w) -> {
                    StringBuilder days = new StringBuilder();
                    for (int i = 0; i < 7; i++) {
                        if (!checked[i]) continue;
                        if (days.length() > 0) days.append(",");
                        days.append(i + 1);
                    }
                    Model.Trigger t = new Model.Trigger();
                    t.type = "time_daily";
                    try {
                        t.params.put("hhmm", hhmm);
                        t.params.put("days", days.toString());
                    } catch (Exception ignored) {
                    }
                    rule.trigger = t;
                    render();
                })
                .setNegativeButton("取消", null)
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

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(20), dp(4));

        final TextView mode = new TextView(this);
        mode.setTextSize(15);
        mode.setTextColor(ContextCompat.getColor(this, R.color.perm_ok));
        mode.setPadding(0, dp(8), 0, dp(2));
        mode.setOnClickListener(v -> {
            group.op = "any".equals(group.op) ? "all" : "any";
            render();
        });
        root.addView(mode);

        final TextView tip = new TextView(this);
        tip.setText("点文字改这一条，拖左边的 ≡ 调顺序");
        tip.setTextSize(12);
        tip.setPadding(0, 0, 0, dp(4));
        root.addView(tip);

        final TextView empty = new TextView(this);
        empty.setText("还没有条件，点下面的按钮加一条");
        empty.setTextSize(13);
        empty.setPadding(0, dp(4), 0, dp(8));
        root.addView(empty);

        final CondAdapter adapter = new CondAdapter(group);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        // 弹窗里不再套 ScrollView，列表自己滚，否则拖动时手势会被外层抢走
        rv.setNestedScrollingEnabled(false);
        LinearLayout.LayoutParams rvlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260));
        rv.setLayoutParams(rvlp);
        root.addView(rv);

        final ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView r,
                                  @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                // 老版本 RecyclerView 没有 getBindingAdapterPosition，用这个通用的
                int f = from.getAdapterPosition();
                int t = to.getAdapterPosition();
                if (f < 0 || t < 0 || f == t) return false;
                Collections.swap(group.children, f, t);
                adapter.notifyItemMoved(f, t);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
            }

            @Override
            public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(r, vh);
                // 顺序变了，主界面那句摘要也要跟着变
                render();
            }
        });
        helper.attachToRecyclerView(rv);
        adapter.helper = helper;

        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                boolean any = "any".equals(group.op);
                mode.setText(any ? "满足任意一条（或者）　点这里切换"
                        : "满足全部条件（并且）　点这里切换");
                empty.setVisibility(group.children.isEmpty() ? View.VISIBLE : View.GONE);
                rv.setVisibility(group.children.isEmpty() ? View.GONE : View.VISIBLE);
                adapter.notifyDataSetChanged();
            }
        };

        TextView add = new TextView(this);
        add.setText("＋ 添加条件");
        add.setTextSize(14);
        add.setTextColor(ContextCompat.getColor(this, R.color.perm_ok));
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
        root.addView(add);

        groupRefreshers.add(fill);
        fill.run();

        new AlertDialog.Builder(this)
                .setTitle("组合条件")
                .setView(root)
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

    /** 组合条件里的一行：左边把手用来拖，中间文字点开改，右边删除。 */
    private class CondAdapter extends RecyclerView.Adapter<CondAdapter.CondVH> {

        final Model.Cond group;
        ItemTouchHelper helper;

        CondAdapter(Model.Cond g) {
            group = g;
        }

        @NonNull
        @Override
        public CondVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(EditRuleActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));

            TextView handle = new TextView(EditRuleActivity.this);
            handle.setText("≡");
            handle.setTextSize(18);
            handle.setPadding(dp(2), dp(2), dp(12), dp(2));
            row.addView(handle);

            TextView text = new TextView(EditRuleActivity.this);
            text.setTextSize(15);
            text.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text);

            TextView del = new TextView(EditRuleActivity.this);
            del.setText("删除");
            del.setTextSize(13);
            del.setPadding(dp(10), dp(6), dp(2), dp(6));
            row.addView(del);

            return new CondVH(row, handle, text, del);
        }

        @Override
        public void onBindViewHolder(@NonNull final CondVH h, int position) {
            final Model.Cond child = group.children.get(position);
            h.text.setText((position + 1) + ". " + child.describe());
            h.text.setOnClickListener(v -> editChild(group, child));
            h.del.setOnClickListener(v -> {
                group.children.remove(child);
                render();
            });
            h.handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && helper != null) {
                    helper.startDrag(h);
                }
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return group.children.size();
        }

        class CondVH extends RecyclerView.ViewHolder {
            final TextView handle;
            final TextView text;
            final TextView del;

            CondVH(View itemView, TextView handle, TextView text, TextView del) {
                super(itemView);
                this.handle = handle;
                this.text = text;
                this.del = del;
            }
        }
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
                "连着蓝牙设备",
                "正在充电",
                "电量低于某个百分比",
                "电量高于某个百分比",
                "时间在一个区间内",
                "星期几",
                "屏幕亮着",
                "处于锁屏",
                "免打扰开着",
                "铃声是静音",
                "铃声是振动",
                "铃声正常响",
                "深色模式开着",
                "插着有线耳机",
                "当前应用是（填包名）",
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
                            applyCond(cond("bt_connected"));
                            break;
                        case 4:
                            applyCond(cond("charging"));
                            break;
                        case 5:
                            askNumber("电量低于多少（%）", 20, v ->
                                    applyCond(cond("battery_below", "pct", v)), true);
                            break;
                        case 6:
                            askNumber("电量高于多少（%）", 80, v ->
                                    applyCond(cond("battery_above", "pct", v)), true);
                            break;
                        case 7:
                            askTimeRange();
                            break;
                        case 8:
                            askDays();
                            break;
                        case 9:
                            applyCond(cond("screen_on"));
                            break;
                        case 10:
                            applyCond(cond("keyguard_locked"));
                            break;
                        case 11:
                            applyCond(cond("dnd_on"));
                            break;
                        case 12:
                            applyCond(cond("ringer_silent"));
                            break;
                        case 13:
                            applyCond(cond("ringer_vibrate"));
                            break;
                        case 14:
                            applyCond(cond("ringer_normal"));
                            break;
                        case 15:
                            applyCond(cond("dark_mode"));
                            break;
                        case 16:
                            applyCond(cond("headset"));
                            break;
                        case 17:
                            askText("哪个应用在前台", "填包名，比如 com.tencent.mm", "", v -> {
                                if (v.isEmpty()) return;
                                Model.Cond c = cond("app_foreground", "pkg", v);
                                try {
                                    c.params.put("label", v);
                                } catch (Exception ignored) {
                                }
                                applyCond(c);
                            });
                            break;
                        case 18:
                            startGroup();
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 星期几：多选，周一到周日。 */
    private void askDays() {
        final String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        final boolean[] picked = new boolean[7];
        new AlertDialog.Builder(this)
                .setTitle("哪几天算成立")
                .setMultiChoiceItems(names, picked, (d, which, checked) -> picked[which] = checked)
                .setPositiveButton("确定", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 7; i++) {
                        if (picked[i]) {
                            if (sb.length() > 0) sb.append(",");
                            sb.append(i + 1);
                        }
                    }
                    if (sb.length() == 0) {
                        Toast.makeText(this, "一天都没选，按每天都算", Toast.LENGTH_SHORT).show();
                    }
                    applyCond(cond("day_of_week", "days", sb.toString()));
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

    private interface OnText {
        void on(String v);
    }

    /** 通用文本输入框，包名、网址这类都走它。 */
    private void askText(String title, String hint, String def, final OnText cb) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(def);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> cb.on(et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
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

    /**
     * 选动作。动作多了之后一层平铺太长，改成先选分类，再选具体动作。
     * 分类和动作都来自 Capabilities.ACTIONS，加新动作不用改这里。
     */
    private void pickAction(final List<Model.Node> target) {
        final List<String> groups = Capabilities.actionGroups();
        new AlertDialog.Builder(this)
                .setTitle("添加动作")
                .setItems(groups.toArray(new String[0]),
                        (d, which) -> pickActionIn(groups.get(which), target))
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickActionIn(String group, final List<Model.Node> target) {
        final List<Capabilities.Def> defs = Capabilities.actionsIn(group);
        String[] labels = new String[defs.size()];
        for (int i = 0; i < defs.size(); i++) labels[i] = defs.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle(group)
                .setItems(labels, (d, which) -> addAction(defs.get(which), target))
                .setNegativeButton("返回", (d, w) -> pickAction(target))
                .show();
    }

    private void addAction(final Capabilities.Def def, final List<Model.Node> target) {
        switch (def.key) {
            case "open_app":
                pickApp(target);
                return;
            case "notify":
                askNotify(target);
                return;
            case "confirm":
                askConfirm(target);
                return;
            case "delay":
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
                return;
            case "gesture_seq":
                macroTarget = target;
                macroLauncher.launch(new Intent(this, MacroActivity.class));
                return;
            case "ringer_mode": {
                final String[] modes = {"静音", "振动", "正常响铃"};
                final String[] keys = {"silent", "vibrate", "normal"};
                new AlertDialog.Builder(this)
                        .setTitle("铃声切到")
                        .setItems(modes, (d, which) -> {
                            target.add(act("ringer_mode", "mode", keys[which]));
                            render();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
            default:
                break;
        }

        if (def.args.isEmpty()) {
            Model.Node n = new Model.Node();
            n.type = "action";
            n.action = def.key;
            n.params = Capabilities.defaults(def);
            target.add(n);
            render();
            return;
        }
        askParams(def, target);
    }

    /** 按 Capabilities 里的参数定义生成输入控件，一行一个参数；choice 类型给下拉框。 */
    private void askParams(final Capabilities.Def def, final List<Model.Node> target) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final List<EditText> inputs = new ArrayList<>();
        final List<android.widget.Spinner> spinners = new ArrayList<>();

        for (Capabilities.Arg arg : def.args) {
            TextView label = new TextView(this);
            label.setText(arg.label);
            label.setTextSize(13);
            label.setPadding(0, dp(6), 0, 0);
            box.addView(label);

            if (arg.isChoice()) {
                android.widget.Spinner sp = new android.widget.Spinner(this);
                String[] keys = arg.choices();
                List<String> labels = new ArrayList<>();
                for (String k : keys) labels.add(arg.choiceLabel(k));
                sp.setAdapter(new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, labels));
                for (int i = 0; i < keys.length; i++) {
                    if (keys[i].equals(arg.def)) sp.setSelection(i);
                }
                box.addView(sp);
                spinners.add(sp);
                inputs.add(null);
            } else {
                EditText et = new EditText(this);
                et.setHint(arg.label);
                et.setText(arg.def);
                if (arg.isInt()) et.setInputType(InputType.TYPE_CLASS_NUMBER);
                box.addView(et);
                spinners.add(null);
                inputs.add(et);
            }
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(def.label)
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    Model.Node n = new Model.Node();
                    n.type = "action";
                    n.action = def.key;
                    try {
                        for (int i = 0; i < def.args.size(); i++) {
                            Capabilities.Arg arg = def.args.get(i);
                            if (arg.isChoice()) {
                                int sel = spinners.get(i).getSelectedItemPosition();
                                String[] keys = arg.choices();
                                n.params.put(arg.key, keys[Math.max(0, Math.min(sel, keys.length - 1))]);
                                continue;
                            }
                            String v = inputs.get(i).getText().toString().trim();
                            if (arg.isInt()) {
                                int num;
                                try {
                                    num = Integer.parseInt(v);
                                } catch (Exception e) {
                                    num = 0;
                                }
                                n.params.put(arg.key, num);
                            } else {
                                n.params.put(arg.key, v);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    target.add(n);
                    render();
                })
                .setNegativeButton("取消", null);
        if (def.hint != null && !def.hint.isEmpty()) b.setMessage(def.hint);
        b.show();
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

    /**
     * 延迟测试前的落盘。名字和动作不做强校验：测试就是想随手试一下，
     * 空名字给个默认名，动作空着测试跑起来什么也不做而已。
     */
    private boolean persistForTest(Model.Rule r) {
        if (thenList.isEmpty() && elseList.isEmpty()) {
            Toast.makeText(this, "先加一个动作再测", Toast.LENGTH_SHORT).show();
            return false;
        }
        RuleStore.upsert(this, r);
        WatchService.sync(this);
        Logx.i(this, "测试前已保存规则 " + r.name + " (" + r.id + ")");
        return true;
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
