package com.z7ui.ruletree;

import android.content.Context;

import java.util.List;

/**
 * 引擎：触发器命中 → 跑对应规则的树。
 * 树的递归结构全在这里，节点类型只有三种：action / if / seq。
 */
public class Engine {

    /** 触发器入口。watch 是本次事件的具体值：connected / disconnected。 */
    public static void fire(Context c, String triggerType, String watch) {
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
        Logx.i(c, "── 开始执行「" + r.name + "」");
        long t = System.currentTimeMillis();
        runNode(c, r.tree);
        Logx.i(c, "── 「" + r.name + "」完成，耗时 " + (System.currentTimeMillis() - t) + "ms");
    }

    private static void runNode(Context c, Model.Node n) {
        if (n == null) return;
        switch (n.type) {
            case "action":
                boolean ok = Actions.run(c, n.action, n.params);
                Logx.i(c, (ok ? "✓ " : "✗ ") + Model.Node.describeAction(n.action, n.params));
                break;
            case "if":
                boolean branch = Conditions.eval(c, n.condition);
                List<Model.Node> list = branch ? n.then : n.otherwise;
                Logx.i(c, "走 " + (branch ? "then（" : "else（") + list.size() + " 步）");
                runNodes(c, list);
                break;
            case "seq":
                runNodes(c, n.children);
                break;
            default:
                Logx.i(c, "未知节点类型，跳过: " + n.type);
        }
    }

    private static void runNodes(Context c, List<Model.Node> list) {
        if (list == null) return;
        for (Model.Node n : list) runNode(c, n);
    }
}
