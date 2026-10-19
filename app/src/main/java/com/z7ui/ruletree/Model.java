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
                case "notification": {
                    String label = params.optString("label", "");
                    String kw = params.optString("keyword", "");
                    String v = label.isEmpty() ? "收到任意通知时" : "收到「" + label + "」的通知时";
                    if (!kw.isEmpty()) v += "，内容含「" + kw + "」";
                    return v;
                }
                case "bluetooth_state": {
                    String w = watch();
                    String v = "connected".equals(w) ? "连上蓝牙设备时"
                            : "disconnected".equals(w) ? "断开蓝牙设备时" : "蓝牙连接变化时";
                    String label = params.optString("label", "");
                    return label.isEmpty() ? v : v + "（" + label + "）";
                }
                case "time_daily": {
                    String hhmm = params.optString("hhmm", "07:00");
                    String days = params.optString("days", "");
                    return days.isEmpty() ? "每天 " + hhmm : daysText(days) + " " + hhmm;
                }
                default:
                    return "手动运行";
            }
        }

        /** 「1,3,5」→「每周一、三、五」。空串返回「每天」。 */
        public static String daysText(String days) {
            if (days == null || days.isEmpty()) return "每天";
            if ("1,2,3,4,5".equals(days)) return "工作日";
            if ("6,7".equals(days)) return "周末";
            String[] names = {"一", "二", "三", "四", "五", "六", "日"};
            StringBuilder sb = new StringBuilder("每周");
            boolean first = true;
            for (String p : days.split(",")) {
                try {
                    int v = Integer.parseInt(p.trim());
                    if (v < 1 || v > 7) continue;
                    if (!first) sb.append("、");
                    sb.append(names[v - 1]);
                    first = false;
                } catch (Exception ignored) {
                }
            }
            return first ? "每天" : sb.toString();
        }
    }

    // ---------------- 条件 ----------------

    public static class Cond {
        public String type = "always";
        public JSONObject params = new JSONObject();
        public boolean negate = false;

        /** 仅 type=group 时有效：all = 并且，any = 或者 */
        public String op = "all";
        /** 仅 type=group 时有效：组内的条件，元素可以是叶子，也可以是另一个组 */
        public List<Cond> children = new ArrayList<>();

        public boolean isGroup() {
            return "group".equals(type);
        }

        public static Cond fromJson(JSONObject o) throws JSONException {
            Cond c = new Cond();
            if (o == null) return c;
            c.type = o.optString("type", "always");
            c.params = o.optJSONObject("params") != null ? o.optJSONObject("params") : new JSONObject();
            c.negate = o.optBoolean("negate", false);
            c.op = o.optString("op", "all");
            JSONArray kids = o.optJSONArray("children");
            if (kids != null) {
                for (int i = 0; i < kids.length(); i++) {
                    c.children.add(Cond.fromJson(kids.optJSONObject(i)));
                }
            }
            return c;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("negate", negate);
            if (isGroup()) {
                o.put("op", op);
                JSONArray a = new JSONArray();
                for (Cond c : children) a.put(c.toJson());
                o.put("children", a);
            } else {
                o.put("params", params);
            }
            return o;
        }

        public String describe() {
            return describe(0);
        }

        /** 条件的中文描述。具体文案在 Capabilities 里，和界面共用一份。 */
        private String describe(int depth) {
            if (isGroup()) {
                String inner = groupDescribe(depth);
                return negate ? "不满足（" + inner + "）" : inner;
            }
            String base = Capabilities.describeCond(type, params);
            return negate ? "不满足「" + base + "」" : base;
        }

        private String groupDescribe(int depth) {
            if (children.isEmpty()) return "组合条件（还空着）";
            if (depth > 6) return "…";
            boolean any = "any".equals(op);
            StringBuilder sb = new StringBuilder(any ? "任一满足：" : "全部满足：");
            int shown = 0;
            for (Cond c : children) {
                if (shown >= 2) break;
                if (shown > 0) sb.append("、");
                sb.append(c.describe(depth + 1));
                shown++;
            }
            if (children.size() > 2) sb.append(" 等 ").append(children.size()).append(" 项");
            String s = sb.toString();
            // 卡片上的摘要，太长就截断，完整内容在编辑页看
            return s.length() > 60 ? s.substring(0, 58) + "…" : s;
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
            return Capabilities.describeAction(action, p);
        }
    }
}
