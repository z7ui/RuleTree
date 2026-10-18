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
}
