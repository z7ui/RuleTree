package com.z7ui.ruletree;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 每条规则最近一次执行的结果，存 files/runs.json，只留最后一次。
 *
 * 规则不生效时最想知道的就是「它到底跑没跑、跑到哪一步断了」，
 * 光有流水日志不够——日志会滚动，而且分不清属于哪条规则。
 */
public class RunLog {

    public static class Rec {
        public long ts;
        public boolean ok;
        public long ms;
        public String note = "";
    }

    private static final String FILE = "runs.json";

    public static synchronized void record(Context c, String rid, boolean ok, long ms, String note) {
        try {
            JSONObject root = read(c);
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("ok", ok);
            o.put("ms", ms);
            o.put("note", note == null ? "" : note);
            root.put(rid, o);
            write(c, root);
        } catch (Exception e) {
            Logx.i(c, "运行记录写入失败: " + e);
        }
    }

    public static Rec last(Context c, String rid) {
        try {
            JSONObject o = read(c).optJSONObject(rid);
            if (o == null) return null;
            Rec r = new Rec();
            r.ts = o.optLong("ts");
            r.ok = o.optBoolean("ok");
            r.ms = o.optLong("ms");
            r.note = o.optString("note", "");
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** 卡片上那一行。没跑过就说没跑过，别装作一切正常。 */
    public static String describe(Context c, String rid) {
        Rec r = last(c, rid);
        if (r == null) return "还没运行过";
        String when = new SimpleDateFormat("M-d HH:mm", Locale.CHINA).format(new Date(r.ts));
        String result = r.ok ? "成功" : (r.note.isEmpty() ? "失败" : r.note);
        return "上次 " + when + " · " + result + " · " + r.ms + "ms";
    }

    public static void clear(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (f.exists()) f.delete();
    }

    private static JSONObject read(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return new JSONObject();
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void write(Context c, JSONObject o) throws Exception {
        File f = new File(c.getFilesDir(), FILE);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            tmp.delete();
        }
    }
}
