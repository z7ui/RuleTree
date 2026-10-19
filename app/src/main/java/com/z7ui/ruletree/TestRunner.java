package com.z7ui.ruletree;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 测试运行。立刻跑只能验证动作本身，验证不了「切到后台还灵不灵」，
 * 所以这里给一个延迟入口：排一个 x 秒后的闹钟，人可以先退出去、锁屏，到点再看结果。
 *
 * 延迟跑要靠闹钟，闹钟到点得能从存储里把规则找回来，所以延迟跑之前必须先落盘。
 */
public class TestRunner {

    public interface RuleSource {
        Model.Rule current();
    }

    /** 返回 false 表示这次不跑（比如名字没填、动作是空的）。 */
    public interface Persist {
        boolean save(Model.Rule r);
    }

    private static final int[] PRESETS = {5, 10, 30, 60};

    public static void ask(final Activity a, final RuleSource src, final Persist persist) {
        final String[] items = {"立刻运行", "5 秒后", "10 秒后", "30 秒后", "60 秒后", "自定义秒数…"};
        new AlertDialog.Builder(a)
                .setTitle("测试运行")
                .setItems(items, (d, which) -> {
                    Model.Rule r = src.current();
                    if (r == null) return;
                    if (which == 0) {
                        runNow(a, r);
                    } else if (which == items.length - 1) {
                        askSeconds(a, src, persist);
                    } else {
                        schedule(a, r, persist, PRESETS[which - 1]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void runNow(final Activity a, final Model.Rule r) {
        Toast.makeText(a, "开始执行…", Toast.LENGTH_SHORT).show();
        final android.content.Context app = a.getApplicationContext();
        new Thread(() -> {
            Engine.run(app, r);
            a.runOnUiThread(() -> Toast.makeText(a, "执行完了，主界面菜单能看日志",
                    Toast.LENGTH_SHORT).show());
        }).start();
    }

    private static void askSeconds(final Activity a, final RuleSource src, final Persist persist) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("秒");
        input.setText("10");
        new AlertDialog.Builder(a)
                .setTitle("几秒后运行")
                .setMessage("到点就算应用在后台、屏幕锁着也会跑。")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    int sec;
                    try {
                        sec = Integer.parseInt(input.getText().toString().trim());
                    } catch (Exception e) {
                        sec = 10;
                    }
                    sec = Math.max(1, Math.min(3600, sec));
                    schedule(a, src.current(), persist, sec);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void schedule(Activity a, Model.Rule r, Persist persist, int sec) {
        if (r == null) return;
        if (!persist.save(r)) return;
        TimeScheduler.test(a, r.id, sec);
        Toast.makeText(a, sec + " 秒后运行，可以切走或锁屏，到点看通知和日志",
                Toast.LENGTH_LONG).show();
    }
}
