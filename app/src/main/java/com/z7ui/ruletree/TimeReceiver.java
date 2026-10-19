package com.z7ui.ruletree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 闹钟到点。两个来源共用一个接收器：
 * 日常定时（TIME_FIRE）跑完要重排下一次；
 * 测试模式（TEST_FIRE）只跑一次，不碰定时表。
 */
public class TimeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String rid = intent.getStringExtra("rid");
        if (rid == null) return;
        boolean isTest = TimeScheduler.ACTION_TEST.equals(intent.getAction());

        Model.Rule rule = RuleStore.find(context, rid);
        if (rule == null) {
            Logx.i(context, (isTest ? "测试到点" : "定时到点") + "，但规则已经不在了：" + rid);
            return;
        }
        // 测试模式特意不检查 enabled：停用的规则也要能试跑一次
        if (!rule.enabled && !isTest) {
            Logx.i(context, "定时到点，规则已停用，跳过：" + rule.name);
            return;
        }

        Logx.i(context, (isTest ? "测试触发：" : "定时到点：") + rule.name);
        // 动作里可能有等待和二次确认，别占着广播线程
        Engine.runAsync(context, rule);
        if (!isTest) TimeScheduler.sync(context);
    }
}
