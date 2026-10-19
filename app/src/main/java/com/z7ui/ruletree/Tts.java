package com.z7ui.ruletree;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 朗读。TTS 引擎初始化是异步的，第一次用要等一下；之后复用同一个实例。
 * 引擎初始化最多等 5 秒，超时就算失败，别把整条规则卡死。
 */
public class Tts {

    private static TextToSpeech engine;
    private static boolean ready;

    public static synchronized boolean speak(Context c, String text) {
        if (text == null || text.isEmpty()) return false;
        final Context app = c.getApplicationContext();

        if (engine == null) {
            final CountDownLatch latch = new CountDownLatch(1);
            engine = new TextToSpeech(app, status -> {
                ready = status == TextToSpeech.SUCCESS;
                latch.countDown();
            });
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            if (ready) {
                try {
                    engine.setLanguage(Locale.CHINA);
                } catch (Exception ignored) {
                }
            }
        }

        if (!ready) {
            Logx.i(app, "朗读跳过：系统里没有可用的 TTS 引擎，或引擎没能在 5 秒内就绪");
            return false;
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rt");
        Logx.i(app, "朗读：" + text);
        return true;
    }
}
