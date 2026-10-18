package com.z7ui.ruletree;

import android.content.Context;

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

    /** 一次执行的状态。目前只有一个标记：这一支是否被取消。 */
    private static class Ctx {
        boolean aborted;
    }

    /** 触发器入口。watch 是本次事件的具体值：connected / disconnected。 */
    public static void fire(final Context c, final String triggerType, final String watch) {
        WORKER.execute(() -> fireNow(c.getApplicationContext(), triggerType, watch));
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
        try {
            Logx.i(c, "── 开始执行「" + r.name + "」");
            long t = System.currentTimeMillis();
            Ctx ctx = new Ctx();
            runNode(c, r.tree, ctx);
            Logx.i(c, "── 「" + r.name + "」" + (ctx.aborted ? "被取消" : "完成")
                    + "，耗时 " + (System.currentTimeMillis() - t) + "ms");
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
        boolean ok = Actions.run(c, n.action, n.params);
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
