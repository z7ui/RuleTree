package com.z7ui.ruletree;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则存储。格式见 DATA-MODEL.md：
 * files/rules.json，{"version":1,"rules":[...]}，原子写入。
 */
public class RuleStore {

    public static final int VERSION = 1;
    private static final String FILE = "rules.json";
    private static final String BAK = "rules.bak.json";

    public static List<Model.Rule> load(Context c) {
        List<Model.Rule> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) f = new File(c.getFilesDir(), BAK);
        if (!f.exists()) return out;
        try {
            JSONObject root = new JSONObject(read(f));
            int v = root.optInt("version", 1);
            if (v != VERSION) root = migrate(c, root, v);
            JSONArray a = root.optJSONArray("rules");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                out.add(Model.Rule.fromJson(a.optJSONObject(i)));
            }
        } catch (Exception e) {
            Logx.i(c, "规则读取失败，已按空处理: " + e);
        }
        return out;
    }

    public static void save(Context c, List<Model.Rule> rules) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            JSONArray a = new JSONArray();
            for (Model.Rule r : rules) a.put(r.toJson());
            root.put("rules", a);
            atomicWrite(file(c), root.toString(2));
        } catch (Exception e) {
            Logx.i(c, "规则保存失败: " + e);
        }
    }

    public static void upsert(Context c, Model.Rule r) {
        List<Model.Rule> list = load(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(r.id)) {
                list.set(i, r);
                found = true;
                break;
            }
        }
        if (!found) list.add(r);
        save(c, list);
    }

    public static void remove(Context c, String id) {
        List<Model.Rule> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        save(c, list);
    }

    public static Model.Rule find(Context c, String id) {
        for (Model.Rule r : load(c)) {
            if (r.id.equals(id)) return r;
        }
        return null;
    }

    // ---------------- 内部 ----------------

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    private static String read(File f) throws IOException {
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(all, StandardCharsets.UTF_8);
    }

    private static void atomicWrite(File f, String s) throws IOException {
        // 覆盖前留一份备份：手上只有一份数据，误写空就全没了
        if (f.exists() && f.length() > 0) {
            File bak = new File(f.getParentFile(), BAK);
            try {
                java.nio.file.Files.copy(f.toPath(), bak.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
        }
        File tmp = new File(f.getParentFile(), FILE + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            // 部分文件系统跨文件改名不行，退回直接写
            java.nio.file.Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
            tmp.delete();
        }
    }

    /** 以后格式升级在这里加分支。v1 没有旧版本要迁。 */
    private static JSONObject migrate(Context c, JSONObject root, int fromVersion) {
        Logx.i(c, "规则格式从 v" + fromVersion + " 迁移到 v" + VERSION);
        return root;
    }

    /** 供调试/导出用 */
    public static String rawJson(Context c) {
        File f = file(c);
        if (!f.exists()) return "{}";
        try {
            return read(f);
        } catch (IOException e) {
            return "{}";
        }
    }

    /** 复制一条规则，名字加「副本」，id 换新的，插在原规则后面。 */
    public static Model.Rule duplicate(Context c, String id) {
        List<Model.Rule> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            Model.Rule src = list.get(i);
            if (!src.id.equals(id)) continue;
            try {
                Model.Rule copy = Model.Rule.fromJson(new JSONObject(src.toJson().toString()));
                copy.id = java.util.UUID.randomUUID().toString();
                copy.name = src.name + " 副本";
                copy.createdAt = System.currentTimeMillis();
                copy.updatedAt = copy.createdAt;
                list.add(i + 1, copy);
                save(c, list);
                return copy;
            } catch (Exception e) {
                Logx.i(c, "复制规则失败: " + e);
                return null;
            }
        }
        return null;
    }

    /** 只在同一分组内上下移动，避免把规则挪到别的组里。返回是否真的动了。 */
    public static boolean move(Context c, String id, int delta) {
        List<Model.Rule> list = load(c);
        int from = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                from = i;
                break;
            }
        }
        if (from < 0) return false;
        String g = list.get(from).group == null ? "" : list.get(from).group;
        int to = from + delta;
        while (to >= 0 && to < list.size()) {
            String g2 = list.get(to).group == null ? "" : list.get(to).group;
            if (g2.equals(g)) break;
            to += delta;
        }
        if (to < 0 || to >= list.size()) return false;
        java.util.Collections.swap(list, from, to);
        save(c, list);
        return true;
    }

    /**
     * 导入规则。校验到能读、有树才算数；id 撞了就重新分配，不会覆盖本地规则。
     * 返回导入的条数。
     */
    public static int importFrom(Context c, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("rules");
        if (arr == null) throw new Exception("这不是规则文件：缺少 rules 数组");
        if (arr.length() > 500) throw new Exception("一次最多导入 500 条");

        List<Model.Rule> cur = load(c);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Model.Rule r : cur) ids.add(r.id);

        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.optJSONObject("tree") == null) continue;
            Model.Rule r;
            try {
                r = Model.Rule.fromJson(o);
            } catch (Exception e) {
                continue;
            }
            if (r.id == null || r.id.isEmpty() || ids.contains(r.id)) {
                r.id = java.util.UUID.randomUUID().toString();
            }
            ids.add(r.id);
            if (r.name == null || r.name.trim().isEmpty()) r.name = "导入的规则";
            cur.add(r);
            n++;
        }
        if (n == 0) throw new Exception("文件里没有可识别的规则");
        save(c, cur);
        return n;
    }
}
