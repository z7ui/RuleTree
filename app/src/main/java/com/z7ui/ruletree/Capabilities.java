package com.z7ui.ruletree;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 能力清单。动作和条件的元数据集中在这一个地方：
 * 菜单按 group 分组生成，参数输入框按 args 生成，卡片上的描述按 fmt 生成。
 *
 * 加一个新动作 = 在这里加一条 + 在 Actions.run 里加一个 case，不用再改界面。
 */
public class Capabilities {

    /** 一个参数。spec 字符串格式：key|标签|类型|默认值|别名表，后两段可省。 */
    public static class Arg {
        public final String key;
        public final String label;
        public final String type;   // text / int
        public final String def;
        /** "silent=静音,vibrate=振动"，描述时把值翻成人话 */
        public final String alias;

        Arg(String spec) {
            String[] p = spec.split("\\|", -1);
            key = p.length > 0 ? p[0] : "";
            label = p.length > 1 ? p[1] : key;
            type = p.length > 2 ? p[2] : "text";
            def = p.length > 3 ? p[3] : "";
            alias = p.length > 4 ? p[4] : "";
        }

        public boolean isInt() {
            return "int".equals(type);
        }

        public String pretty(String v) {
            if (alias.isEmpty() || v == null) return v;
            for (String pair : alias.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(v)) return kv[1];
            }
            return v;
        }
    }

    public static class Def {
        public final String key;
        public final String label;
        public final String group;
        /** 描述模板，%s 按 args 顺序填 */
        public final String fmt;
        /** 参数对话框里的一行说明，可空 */
        public final String hint;
        /** 需要专门的选择界面（选应用、发通知…），通用参数框搞不定 */
        public final boolean custom;
        public final List<Arg> args = new ArrayList<>();

        Def(String spec, String label, String group, String fmt, String hint, boolean custom,
            String... args) {
            this.key = spec;
            this.label = label;
            this.group = group;
            this.fmt = fmt;
            this.hint = hint;
            this.custom = custom;
            for (String a : args) this.args.add(new Arg(a));
        }
    }

    // ---------------- 动作 ----------------

    public static final List<Def> ACTIONS = new ArrayList<>();

    private static void a(String key, String label, String group, String fmt, String hint,
                          boolean custom, String... args) {
        ACTIONS.add(new Def(key, label, group, fmt, hint, custom, args));
    }

    static {
        // 声音
        a("ringer_mode", "铃声模式", "声音", "铃声切到%s", null, false,
                "mode|模式|text|silent|silent=静音,vibrate=振动,normal=正常");
        a("media_volume", "媒体音量", "声音", "媒体音量设为 %s%%", null, false,
                "value|音量 0-100|int|50");
        a("volume_ring", "铃声音量", "声音", "铃声音量设为 %s%%", null, false,
                "value|音量 0-100|int|60");
        a("volume_alarm", "闹钟音量", "声音", "闹钟音量设为 %s%%", null, false,
                "value|音量 0-100|int|60");
        a("volume_notification", "通知音量", "声音", "通知音量设为 %s%%", null, false,
                "value|音量 0-100|int|60");
        a("dnd_on", "打开免打扰", "声音", "打开免打扰", null, false);
        a("dnd_off", "关闭免打扰", "声音", "关闭免打扰", null, false);
        a("vibrate", "振动一下", "声音", "振动 %s 毫秒", null, false,
                "ms|时长（毫秒）|int|300");
        a("media_play_pause", "播放 / 暂停", "声音", "播放或暂停媒体", null, false);
        a("media_next", "下一首", "声音", "切下一首", null, false);
        a("media_prev", "上一首", "声音", "切上一首", null, false);

        // 显示
        a("torch_on", "打开手电筒", "显示", "打开手电筒", null, false);
        a("torch_off", "关闭手电筒", "显示", "关闭手电筒", null, false);

        // 文字与网络
        a("open_url", "打开网址", "文字与网络", "打开网址 %s", "会在浏览器里打开", false,
                "url|网址|text|https://");
        a("copy_text", "复制到剪贴板", "文字与网络", "复制到剪贴板：%s", null, false,
                "text|要复制的文字|text|");
        a("tts", "朗读文本", "文字与网络", "朗读：%s", "用系统 TTS 念出来", false,
                "text|要朗读的文字|text|");
        a("http_request", "发 HTTP 请求", "文字与网络", "请求 %s",
                "不填内容就是 GET，填了就是 POST。返回码记进日志。", false,
                "url|网址|text|https://", "body|请求体（可空）|text|");

        // 应用
        a("open_app", "打开应用", "应用", "", null, true);

        // 手势：要开无障碍
        a("g_back", "返回键", "手势", "按返回键", "需要「无障碍」权限", false);
        a("g_home", "回到主屏", "手势", "回到主屏", "需要「无障碍」权限", false);
        a("g_recents", "最近任务", "手势", "打开最近任务", "需要「无障碍」权限", false);
        a("g_notifications", "下拉通知栏", "手势", "下拉通知栏", "需要「无障碍」权限", false);
        a("g_quick_settings", "快捷设置面板", "手势", "打开快捷设置", "需要「无障碍」权限", false);
        a("g_lock", "锁屏", "手势", "锁屏", "需要「无障碍」权限", false);
        a("g_tap", "点击坐标", "手势", "点击 (%s, %s)", "屏幕坐标，左上角是 0,0", false,
                "x|横坐标|int|540", "y|纵坐标|int|1200");
        a("g_swipe", "滑动", "手势", "从 (%s, %s) 滑到 (%s, %s)", "屏幕坐标，左上角是 0,0", false,
                "x1|起点 X|int|540", "y1|起点 Y|int|1800",
                "x2|终点 X|int|540", "y2|终点 Y|int|600");

        // 流程
        a("notify", "发通知", "流程", "", null, true);
        a("delay", "等待一会儿", "流程", "", null, true);
        a("confirm", "执行前确认", "流程", "", null, true);
        a("wait_unlock", "等到解锁", "流程", "等待解锁，最多 %s 秒",
                "锁屏时先停住，解锁后再继续", false, "sec|最多等几秒|int|60");
        a("stop", "停止这一支", "流程", "停止这一支剩下的动作", null, false);
    }

    // ---------------- 分组 ----------------

    public static List<String> actionGroups() {
        Set<String> g = new LinkedHashSet<>();
        for (Def d : ACTIONS) g.add(d.group);
        return new ArrayList<>(g);
    }

    public static List<Def> actionsIn(String group) {
        List<Def> out = new ArrayList<>();
        for (Def d : ACTIONS) if (d.group.equals(group)) out.add(d);
        return out;
    }

    public static Def findAction(String key) {
        for (Def d : ACTIONS) if (d.key.equals(key)) return d;
        return null;
    }

    /** 卡片上那句「打开微信」「媒体音量设为 50%」都从这儿出。 */
    public static String describeAction(String key, JSONObject p) {
        if (key == null) return "未知动作";
        if (p == null) p = new JSONObject();
        switch (key) {
            case "open_app": {
                String label = p.optString("label", "");
                return "打开 " + (label.isEmpty() ? p.optString("pkg", "?") : label);
            }
            case "notify":
                return "发通知：" + p.optString("title", "提示");
            case "delay": {
                long ms = p.optLong("ms", 0);
                if (ms <= 0) ms = p.optLong("sec", 1) * 1000;
                // 不到一秒的按毫秒写，不然四舍五入成「等待 0 秒」很怪
                return ms < 1000 ? "等待 " + ms + " 毫秒"
                        : "等待 " + Math.round(ms / 1000.0) + " 秒";
            }
            case "confirm":
                return "执行前确认：" + p.optString("text", "继续？")
                        + "（" + p.optInt("sec", 15) + " 秒内）";
            default:
                break;
        }
        Def d = findAction(key);
        if (d == null) return "未知动作 " + key;
        if (d.args.isEmpty() || d.fmt.isEmpty()) return d.label.isEmpty() ? key : d.label;
        Object[] vals = new Object[d.args.size()];
        for (int i = 0; i < d.args.size(); i++) {
            Arg arg = d.args.get(i);
            String v = p.optString(arg.key, arg.def);
            if (arg.isInt()) v = String.valueOf(p.optInt(arg.key, intOr(arg.def, 0)));
            vals[i] = arg.pretty(v);
        }
        try {
            return String.format(d.fmt, vals);
        } catch (Exception e) {
            return d.label;
        }
    }

    /** 按参数默认值生成一条动作节点的 params。 */
    public static JSONObject defaults(Def d) {
        JSONObject p = new JSONObject();
        try {
            for (Arg arg : d.args) {
                if (arg.isInt()) p.put(arg.key, intOr(arg.def, 0));
                else p.put(arg.key, arg.def);
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    private static int intOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ---------------- 条件的描述 ----------------

    /** 条件类型的显示名。求值和界面共用一个名字，避免两边写法不一致。 */
    public static String describeCond(String type, JSONObject p) {
        if (p == null) p = new JSONObject();
        switch (type) {
            case "wifi_connected": {
                boolean any = p.optBoolean("matchAny", false);
                String ssid = p.optString("ssid", "");
                return any || ssid.isEmpty() ? "连着 Wi-Fi" : "连着 Wi-Fi「" + ssid + "」";
            }
            case "charging":
                return "正在充电";
            case "battery_below":
                return "电量低于 " + p.optInt("pct", 20) + "%";
            case "battery_above":
                return "电量高于 " + p.optInt("pct", 80) + "%";
            case "time_between":
                return "时间在 " + p.optString("start", "00:00")
                        + " 到 " + p.optString("end", "23:59") + " 之间";
            case "day_of_week":
                return Model.Trigger.daysText(p.optString("days", ""));
            case "screen_on":
                return "屏幕亮着";
            case "keyguard_locked":
                return "处于锁屏";
            case "dnd_on":
                return "免打扰开着";
            case "ringer_silent":
                return "铃声是静音";
            case "ringer_vibrate":
                return "铃声是振动";
            case "ringer_normal":
                return "铃声正常响";
            case "dark_mode":
                return "深色模式开着";
            case "bt_connected":
                return "连着蓝牙设备";
            case "headset":
                return "插着有线耳机";
            case "always":
            default:
                return "总是成立";
        }
    }
}
