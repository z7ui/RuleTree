package com.z7ui.ruletree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 定时闹钟到点。跑完这条规则立刻重排下一次，不然只会响一次。 */
public class TimeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String rid = intent.getStringExtra("rid");
        if (rid == null) return;

        Model.Rule rule = RuleStore.find(context, rid);
        if (rule == null) {
            Logx.i(context, "定时到点，但规则已经不在了：" + rid);
            return;
        }
        if (!rule.enabled) {
            Logx.i(context, "定时到点，规则已停用，跳过：" + rule.name);
            return;
        }

        Logx.i(context, "定时到点：" + rule.name);
        // 动作里可能有等待和二次确认，别占着广播线程
        Engine.runAsync(context, rule);
        TimeScheduler.sync(context);
    }
}
