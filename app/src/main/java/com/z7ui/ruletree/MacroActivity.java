package com.z7ui.ruletree;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 手势录制。半透明铺满整屏，把手指的点和划记成坐标序列，回放时交给无障碍去执行。
 *
 * 只记坐标和时序，不读界面内容——录的时候下面那个应用是停住的，看到的还是它最后一帧。
 */
public class MacroActivity extends Activity {

    private final List<JSONObject> steps = new ArrayList<>();
    private TextView counter;

    private float downX, downY, curX, curY;
    private long downT, lastUpT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);

        TextView hint = new TextView(this);
        hint.setText("在屏幕上点或划，会被记成坐标序列\n只记位置，不读内容");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.CENTER;
        root.addView(hint, hp);

        // 顶部操作条。它自己吃掉触摸，免得点「完成」也被记进去
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE6000000);
        bar.setPadding(dp(16), dp(12), dp(16), dp(12));
        bar.setClickable(true);

        counter = new TextView(this);
        counter.setText("已录 0 步");
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(14);
        counter.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(counter);

        TextView undo = new TextView(this);
        undo.setText("撤销");
        undo.setTextColor(0xFFFFD54F);
        undo.setTextSize(14);
        undo.setPadding(dp(12), dp(6), dp(12), dp(6));
        undo.setOnClickListener(v -> {
            if (!steps.isEmpty()) {
                steps.remove(steps.size() - 1);
                refresh();
            }
        });
        bar.addView(undo);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(14);
        cancel.setPadding(dp(12), dp(6), dp(12), dp(6));
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        bar.addView(cancel);

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextColor(0xFF81C784);
        done.setTextSize(14);
        done.setPadding(dp(12), dp(6), dp(12), dp(6));
        done.setOnClickListener(v -> {
            if (steps.isEmpty()) {
                Toast.makeText(this, "还没录到动作", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray arr = new JSONArray();
            for (JSONObject o : steps) arr.put(o);
            Intent data = new Intent();
            data.putExtra("steps", arr.toString());
            setResult(RESULT_OK, data);
            finish();
        });
        bar.addView(done);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.TOP;
        // 状态栏会吃掉它下面那几十像素的触摸，操作条得让开
        bp.topMargin = statusBarHeight();
        root.addView(bar, bp);

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        curX = downX;
                        curY = downY;
                        downT = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        curX = e.getRawX();
                        curY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        record(e.getRawX(), e.getRawY());
                        return true;
                    default:
                        return false;
                }
            }
        });

        setContentView(root);
        hint.setVisibility(View.VISIBLE);
    }

    private void record(float upX, float upY) {
        long now = System.currentTimeMillis();
        long dt = now - downT;
        double dist = Math.hypot(upX - downX, upY - downY);
        // 位移小、时间短，当点击；否则当一次滑动
        boolean tap = dist < dp(24) && dt < 400;
        try {
            JSONObject o = new JSONObject();
            o.put("delay", steps.isEmpty() ? 400 : Math.min(3000, Math.max(0, downT - lastUpT)));
            if (tap) {
                o.put("type", "tap");
                o.put("x", Math.round(downX));
                o.put("y", Math.round(downY));
            } else {
                o.put("type", "swipe");
                o.put("x1", Math.round(downX));
                o.put("y1", Math.round(downY));
                o.put("x2", Math.round(upX));
                o.put("y2", Math.round(upY));
                o.put("ms", Math.max(100, Math.min(3000, dt)));
            }
            steps.add(o);
        } catch (Exception ignored) {
        }
        lastUpT = now;
        refresh();
    }

    private void refresh() {
        counter.setText("已录 " + steps.size() + " 步");
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }
}
