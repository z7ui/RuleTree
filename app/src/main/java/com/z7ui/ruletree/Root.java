package com.z7ui.ruletree;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * root 执行层。系统开关类动作（飞行模式、移动数据、亮度…）没有公开 API，
 * 只能用 su 去跑 svc / cmd / settings。
 *
 * 没 root 时这里统一返回失败，动作日志里写清楚，不会静默什么都不做。
 */
public class Root {

    /** 结果：成不成，加上输出，方便写日志。 */
    public static class Result {
        public final boolean ok;
        public final String out;

        Result(boolean ok, String out) {
            this.ok = ok;
            this.out = out;
        }
    }

    /** 真正执行一次 su，确认真能用。别用文件存在性判断：KSU 对没授权的应用是直接 EACCES。 */
    public static boolean available(Context c) {
        Result r = run(c, "id");
        return r.ok && r.out.contains("uid=0");
    }

    public static Result run(Context c, String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                Logx.i(c, "root 命令超时：" + cmd);
                return new Result(false, "timeout");
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            int code = p.exitValue();
            String out = sb.toString().trim();
            Logx.i(c, "root[" + code + "] " + cmd + (out.isEmpty() ? "" : " → " + clip(out)));
            return new Result(code == 0, out);
        } catch (Exception e) {
            // KSU / Magisk 的 su 只在拿到授权后才会出现在进程的挂载命名空间里，
            // 所以不能靠「文件存不存在」判断，只能真跑一次看结果。
            Logx.i(c, "root 执行失败（可能是没有 su，或没有授权）" + cmd + ": " + e);
            return new Result(false, String.valueOf(e));
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String clip(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
