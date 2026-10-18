package com.z7ui.ruletree;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 数据模型。字段和 JSON 格式以项目根的 DATA-MODEL.md 为准，
 * 想改格式先看那份文档的兼容性策略。
 */
public class Model {

    // ---------------- 规则 ----------------

    public static class Rule {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public boolean enabled = true;
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = System.currentTimeMillis();
        public Trigger trigger = new Trigger();
        public Node tree = new Node();

        public static Rule fromJson(JSONObject o) throws JSONException {
            Rule r = new Rule();
            r.id = o.optString("id", r.id);
            r.name = o.optString("name", "");
            r.enabled = o.optBoolean("enabled", true);
            r.createdAt = o.optLong("createdAt", System.currentTimeMillis());
            r.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
            r.trigger = Trigger.fromJson(o.optJSONObject("trigger"));
            r.tree = Node.fromJson(o.optJSONObject("tree"));
            return r;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("enabled", enabled);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            o.put("trigger", trigger.toJson());
            o.put("tree", tree.toJson());
            return o;
        }
    }

    // ---------------- 触发器 ----------------

    public static class Trigger {
        public String type = "manual";
        public JSONObject params = new JSONObject();

        public String watch() {
            return params.optString("watch", "any");
        }

        public static Trigger fromJson(JSONObject o) throws JSONException {
            Trigger t = new Trigger();
            if (o == null) return t;
            t.type = o.optString("type", "manual");
            t.params = o.optJSONObject("params") != null ? o.optJSONObject("params") : new JSONObject();
            return t;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("params", params);
            return o;
        }

        public String describe() {
            switch (type) {
                case "wifi_state": {
                    String w = watch();
                    String v = "connected".equals(w) ? "连上 Wi-Fi 时"
                            : "disconnected".equals(w) ? "断开 Wi-Fi 时" : "Wi-Fi 状态变化时";
                    return v;
                }
                case "power_state": {
                    String w = watch();
                    String v = "connected".equals(w) ? "插上电源时"
                            : "disconnected".equals(w) ? "拔掉电源时" : "电源状态变化时";
                    return v;
                }
                default:
                    return "手动运行";
            }
        }
    }

    // ---------------- 条件 ----------------

    public static class Cond {
        public String type = "always";
        public JSONObject params = new JSONObject();
        public boolean negate = false;

        public static Cond fromJson(JSONObject o) throws JSONException {
            Cond c = new Cond();
            if (o == null) return c;
            c.type = o.optString("type", "always");
            c.params = o.optJSONObject("params") != null ? o.optJSONObject("params") : new JSONObject();
            c.negate = o.optBoolean("negate", false);
            return c;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("params", params);
            o.put("negate", negate);
            return o;
        }

        public String describe() {
            String base;
            switch (type) {
                case "wifi_connected": {
                    boolean any = params.optBoolean("matchAny", false);
                    String ssid = params.optString("ssid", "");
                    base = any || ssid.isEmpty() ? "连着 Wi-Fi" : "连着 Wi-Fi「" + ssid + "」";
                    break;
                }
                case "charging":
                    base = "正在充电";
                    break;
                case "battery_below":
                    base = "电量低于 " + params.optInt("pct", 20) + "%";
                    break;
                case "time_between": {
                    String s = params.optString("start", "00:00");
                    String e = params.optString("end", "23:59");
                    base = "时间在 " + s + " 到 " + e + " 之间";
                    break;
                }
                default:
                    base = "总是成立";
            }
            return negate ? "不满足「" + base + "」" : base;
        }
    }

    // ---------------- 树节点 ----------------

    public static class Node {
        /** action | if | seq */
        public String type = "action";
        /** action 节点：动作名 */
        public String action = "";
        public JSONObject params = new JSONObject();
        /** if 节点 */
        public Cond condition;
        public List<Node> then = new ArrayList<>();
        public List<Node> otherwise = new ArrayList<>();
        /** seq 节点 */
        public List<Node> children = new ArrayList<>();

        public static Node fromJson(JSONObject o) throws JSONException {
            Node n = new Node();
            if (o == null) return n;
            n.type = o.optString("type", "action");
            n.action = o.optString("action", "");
            n.params = o.optJSONObject("params") != null ? o.optJSONObject("params") : new JSONObject();
            n.condition = Cond.fromJson(o.optJSONObject("condition"));
            n.then = nodeList(o.optJSONArray("then"));
            n.otherwise = nodeList(o.optJSONArray("else"));
            n.children = nodeList(o.optJSONArray("children"));
            return n;
        }

        private static List<Node> nodeList(JSONArray a) throws JSONException {
            List<Node> out = new ArrayList<>();
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                out.add(Node.fromJson(a.optJSONObject(i)));
            }
            return out;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type);
            if ("action".equals(type)) {
                o.put("action", action);
                o.put("params", params);
            } else if ("if".equals(type)) {
                o.put("condition", condition.toJson());
                o.put("then", nodeArray(then));
                o.put("else", nodeArray(otherwise));
            } else if ("seq".equals(type)) {
                o.put("children", nodeArray(children));
            }
            return o;
        }

        private static JSONArray nodeArray(List<Node> list) throws JSONException {
            JSONArray a = new JSONArray();
            for (Node n : list) a.put(n.toJson());
            return a;
        }

        public String describe() {
            if ("action".equals(type)) {
                return describeAction(action, params);
            }
            if ("if".equals(type)) {
                String c = condition == null ? "?" : condition.describe();
                String a = then.isEmpty() ? "什么也不做"
                        : then.get(0).describe() + (then.size() > 1 ? "等 " + then.size() + " 步" : "");
                String b = otherwise.isEmpty() ? "什么也不做"
                        : otherwise.get(0).describe() + (otherwise.size() > 1 ? "等 " + otherwise.size() + " 步" : "");
                return "如果" + c + "，则" + a + "，否则" + b;
            }
            return "按顺序执行 " + children.size() + " 步";
        }

        public static String describeAction(String action, JSONObject p) {
            switch (action == null ? "" : action) {
                case "ringer_mode": {
                    String m = p.optString("mode", "silent");
                    String v = "silent".equals(m) ? "静音" : "vibrate".equals(m) ? "振动" : "正常响铃";
                    return "铃声切到" + v;
                }
                case "media_volume":
                    return "媒体音量设为 " + p.optInt("value", 50) + "%";
                case "open_app":
                    return "打开 " + p.optString("label", p.optString("pkg", "?"));
                case "notify":
                    return "发通知：" + p.optString("title", "提示");
                case "delay":
                    return "等待 " + Math.round(p.optLong("ms", 1000) / 1000.0) + " 秒";
                case "confirm":
                    return "执行前确认：" + p.optString("text", "继续？")
                            + "（" + p.optInt("sec", 15) + " 秒内）";
                default:
                    return "未知动作 " + action;
            }
        }
    }
}
