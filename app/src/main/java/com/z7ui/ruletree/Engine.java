package com.z7ui.ruletree;

import android.content.Context;

import org.json.JSONObject;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 引擎：触发器命中 → 跑对应规则的树。
 * 树的递归结构全在这里，节点类型只有三种：action / if / seq。
 *
 * 动作里有 sleep（等待）和 latch（二次确认），所有规则都丢到单线程队列里跑，
 * 一个慢任务不会拖住主线程，也不会和另一个规则的执行混在一起。
 */
public class Engine {

    /** 线程池而不是单线程：一条规则卡在二次确认里，不该把别的规则一起堵住。 */
    private static final ExecutorService WORKER = Executors.newCachedThreadPool();

    /** 正在跑的规则 id。二次确认可能停十几秒，期间同一个规则又被触发就别再排一份。 */
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    /** 一次执行的状态。aborted = 被取消，failed = 有动作返回失败。 */
    private static class Ctx {
        boolean aborted;
        boolean failed;
    }

    /** 触发器入口。watch 是本次事件的具体值：connected / disconnected。 */
    public static void fire(final Context c, final String triggerType, final String watch) {
        WORKER.execute(() -> fireNow(c.getApplicationContext(), triggerType, watch));
    }

    /** 通知触发：参数里可以限定应用包名和关键词，空表示不限。 */
    public static void fireNotification(final Context c, final String pkg,
                                        final String title, final String text) {
        final Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            Logx.i(app, "收到通知 [" + pkg + "] " + title);
            String blob = (title == null ? "" : title) + " " + (text == null ? "" : text);
            for (Model.Rule r : RuleStore.load(app)) {
                if (!r.enabled || !"notification".equals(r.trigger.type)) continue;
                JSONObject p = r.trigger.params;
                String wantPkg = p.optString("pkg", "");
                if (!wantPkg.isEmpty() && !wantPkg.equals(pkg)) continue;
                String kw = p.optString("keyword", "");
                if (!kw.isEmpty() && !blob.contains(kw)) continue;
                Logx.i(app, "规则命中：" + r.name);
                run(app, r);
            }
        });
    }

    /** 蓝牙触发：参数里可以限定设备 MAC，空表示任意设备。 */
    public static void fireBluetooth(final Context c, final String watch, final String mac) {
        final Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            Logx.i(app, "蓝牙" + ("connected".equals(watch) ? "已连接" : "已断开")
                    + (mac == null || mac.isEmpty() ? "" : "（" + mac + "）"));
            int hit = 0;
            for (Model.Rule r : RuleStore.load(app)) {
                if (!r.enabled || !"bluetooth_state".equals(r.trigger.type)) continue;
                String w = r.trigger.watch();
                if (!"any".equals(w) && !w.equals(watch)) continue;
                String wantMac = r.trigger.params.optString("mac", "");
                if (!wantMac.isEmpty() && (mac == null || !wantMac.equalsIgnoreCase(mac))) continue;
                Logx.i(app, "规则命中：" + r.name);
                run(app, r);
                hit++;
            }
            if (hit == 0) Logx.i(app, "没有匹配的蓝牙规则");
        });
    }

    /** 给广播接收器用：动作里可能有等待，别在接收器线程里跑。 */
    public static void runAsync(final Context c, final Model.Rule r) {
        WORKER.execute(() -> run(c.getApplicationContext(), r));
    }

    private static void fireNow(Context c, String triggerType, String watch) {
        Logx.i(c, "触发器 " + triggerType + "（" + watch + "）");
        List<Model.Rule> rules = RuleStore.load(c);
        int hit = 0;
        for (Model.Rule r : rules) {
            if (!r.enabled) continue;
            if (!r.trigger.type.equals(triggerType)) continue;
            String w = r.trigger.watch();
            if (!"any".equals(w) && !w.equals(watch)) continue;
            Logx.i(c, "规则命中：" + r.name);
            run(c, r);
            hit++;
        }
        if (hit == 0) Logx.i(c, "没有匹配的规则");
    }

    /** 手动跑某一条规则，比如编辑页的「测试运行」。 */
    public static void run(Context c, Model.Rule r) {
        if (!RUNNING.add(r.id)) {
            Logx.i(c, "「" + r.name + "」还在执行，这次触发跳过");
            return;
        }
        long t = System.currentTimeMillis();
        Ctx ctx = new Ctx();
        try {
            Logx.i(c, "── 开始执行「" + r.name + "」");
            runNode(c, r.tree, ctx);
            long cost = System.currentTimeMillis() - t;
            boolean ok = !ctx.failed && !ctx.aborted;
            String note = ctx.failed ? "有动作失败" : (ctx.aborted ? "被取消" : "");
            RunLog.record(c, r.id, ok, cost, note);
            Logx.i(c, "── 「" + r.name + "」" + (ctx.aborted ? "被取消" : "完成")
                    + "，耗时 " + cost + "ms");
        } catch (Throwable e) {
            RunLog.record(c, r.id, false, System.currentTimeMillis() - t, "执行异常：" + e);
            Logx.i(c, "── 「" + r.name + "」异常终止: " + e);
        } finally {
            RUNNING.remove(r.id);
        }
    }

    private static void runNode(Context c, Model.Node n, Ctx ctx) {
        if (n == null || ctx.aborted) return;
        switch (n.type) {
            case "action":
                handleAction(c, n, ctx);
                break;
            case "if":
                boolean branch = Conditions.eval(c, n.condition);
                List<Model.Node> list = branch ? n.then : n.otherwise;
                Logx.i(c, "走 " + (branch ? "then（" : "else（") + list.size() + " 步）");
                runNodes(c, list, ctx);
                break;
            case "seq":
                runNodes(c, n.children, ctx);
                break;
            default:
                Logx.i(c, "未知节点类型，跳过: " + n.type);
        }
    }

    private static void handleAction(Context c, Model.Node n, Ctx ctx) {
        // 二次确认不算普通动作：它要把执行停住，等到用户点或者超时
        if ("confirm".equals(n.action)) {
            String text = n.params.optString("text", "继续执行后面的动作？");
            int sec = n.params.optInt("sec", 15);
            boolean ok = ConfirmGate.ask(c, text, sec);
            Logx.i(c, (ok ? "✓ 确认通过" : "✗ 未确认，取消剩余动作") + "：" + text);
            if (!ok) ctx.aborted = true;
            return;
        }
        // 主动喊停：这一支后面的动作都不做了
        if ("stop".equals(n.action)) {
            Logx.i(c, "■ 到这里停止，这一支剩下的动作都不执行");
            ctx.aborted = true;
            return;
        }
        boolean ok = Actions.run(c, n.action, n.params);
        if (!ok) ctx.failed = true;
        Logx.i(c, (ok ? "✓ " : "✗ ") + Model.Node.describeAction(n.action, n.params));
    }

    private static void runNodes(Context c, List<Model.Node> list, Ctx ctx) {
        if (list == null) return;
        for (Model.Node n : list) {
            if (ctx.aborted) return;
            runNode(c, n, ctx);
        }
    }
}
