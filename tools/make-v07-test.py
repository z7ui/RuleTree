#!/usr/bin/env python3
"""生成 v0.7 的测试规则：一堆新动作串成一条，定时触发，跑完看日志。

用法：python make-v07-test.py 21:05
"""
import json
import sys

hhmm = sys.argv[1] if len(sys.argv) > 1 else "07:00"


def act(name, **params):
    return {"type": "action", "action": name, "params": params}


def d(ms):
    return act("delay", ms=ms)


seq = [
    act("notify", title="开始", text="0.7 新动作测试"),
    act("copy_text", text="规则树 0.7"),
    act("vibrate", ms=200),
    act("media_volume", value=40),
    act("volume_ring", value=60),
    act("dnd_on"), d(800), act("dnd_off"),
    act("torch_on"), d(800), act("torch_off"),
    act("media_next"), d(300),
    act("tts", text="测试通过"),
    act("http_request", url="https://www.z7ui.cn/", body=""),
    act("g_home"), d(500),
    act("stop"),
    act("notify", title="不该出现", text="stop 没生效"),
]

rules = [
    {
        "id": "test-v07-0001",
        "name": "测试：0.7 新动作",
        "enabled": True,
        "createdAt": 0,
        "updatedAt": 0,
        "trigger": {"type": "time_daily", "params": {"hhmm": hhmm, "days": ""}},
        "tree": {"type": "seq", "children": seq},
    },
    {
        "id": "test-v07-0002",
        "name": "测试：新条件描述",
        "enabled": False,
        "createdAt": 0,
        "updatedAt": 0,
        "trigger": {"type": "manual", "params": {}},
        "tree": {
            "type": "if",
            "condition": {
                "type": "group", "op": "all", "negate": False, "params": {},
                "children": [
                    {"type": "screen_on", "params": {}, "negate": False},
                    {"type": "ringer_normal", "params": {}, "negate": False},
                    {"type": "day_of_week", "params": {"days": "1,3,5"}, "negate": False},
                    {"type": "battery_above", "params": {"pct": 20}, "negate": False},
                    {"type": "dnd_on", "params": {}, "negate": True},
                ],
            },
            "then": [act("notify", title="条件都成立", text="")],
            "else": [act("notify", title="有条件不成立", text="")],
        },
    },
]

print(json.dumps({"version": 1, "rules": rules}, ensure_ascii=False, indent=2))
