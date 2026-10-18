package com.z7ui.ruletree;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 落盘日志：files/rt.log，超 256KB 滚动到 rt.old.log。 */
public class Logx {

    private static final Object LOCK = new Object();
    private static final long MAX = 256 * 1024;

    public static void i(Context c, String msg) {
        android.util.Log.i("RT", msg);
        if (c == null) return;
        try {
            synchronized (LOCK) {
                File f = new File(c.getFilesDir(), "rt.log");
                if (f.length() > MAX) {
                    File old = new File(c.getFilesDir(), "rt.old.log");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                    f = new File(c.getFilesDir(), "rt.log");
                }
                String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date()) + "  " + msg + "\n";
                FileOutputStream out = new FileOutputStream(f, true);
                try {
                    out.write(line.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String read(Context c) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(new File(c.getFilesDir(), "rt.log").toPath());
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "(无日志)";
        }
    }
}
