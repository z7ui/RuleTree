#!/usr/bin/env python3
"""生成一份测试用 rules.json，推到手机上验证 v0.6 的定时 / 蓝牙 / 组合条件。

用法：python make-test-rules.py <HH:MM>（定时规则的时间点）
"""
import json
import sys

hhmm = sys.argv[1] if len(sys.argv) > 1 else "07:00"


def action(name, **params):
    return {"type": "action", "action": name, "params": params}


def leaf(t, **params):
    return {"type": t, "params": params, "negate": False, "op": "all"}


def group(op, children):
    return {"type": "group", "op": op, "negate": False, "params": {}, "children": children}


rules = [
    {
        "id": "test-time-0001",
        "name": "测试：定时" + hhmm,
        "enabled": True,
        "createdAt": 0,
        "updatedAt": 0,
        "trigger": {"type": "time_daily", "params": {"hhmm": hhmm, "days": ""}},
        "tree": {
            "type": "seq",
            "children": [action("notify", title="定时到了", text=hhmm)],
        },
    },
    {
        "id": "test-bt-0002",
        "name": "测试：蓝牙连上任意设备",
        "enabled": True,
        "createdAt": 0,
        "updatedAt": 0,
        "trigger": {"type": "bluetooth_state", "params": {"watch": "connected", "mac": "", "label": ""}},
        "tree": {
            "type": "seq",
            "children": [action("notify", title="蓝牙连上了", text="来自规则树")],
        },
    },
    {
        "id": "test-group-0003",
        "name": "测试：组合条件顺序",
        "enabled": False,
        "createdAt": 0,
        "updatedAt": 0,
        "trigger": {"type": "manual", "params": {}},
        "tree": {
            "type": "if",
            "condition": group("all", [
                leaf("charging"),
                leaf("battery_below", pct=80),
                leaf("time_between", start="00:00", end="23:59"),
            ]),
            "then": [action("notify", title="组合成立", text="并且")],
            "else": [action("notify", title="组合不成立", text="或者")],
        },
    },
]

print(json.dumps({"version": 1, "rules": rules}, ensure_ascii=False, indent=2))
