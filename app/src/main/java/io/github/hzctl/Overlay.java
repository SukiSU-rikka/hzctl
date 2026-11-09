package io.github.hzctl;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 悬浮刷新率指示器。拖动移动，单击切换是否显示渲染帧率 */
final class Overlay {
    private static Overlay inst;

    static synchronized boolean showing() { return inst != null; }

    static synchronized void show(Context c) {
        if (inst != null || !Settings.canDrawOverlays(c)) return;
        Overlay o = new Overlay(c.getApplicationContext());
        try {
            o.attach();
            inst = o;
        } catch (Throwable ignored) {
        }
    }

    static synchronized void hide() {
        if (inst == null) return;
        inst.detach();
        inst = null;
    }

    static synchronized void reconfigure() {
        if (inst != null) inst.applyConfig();
    }

    // ------------------------------------------------------------

    private final Context ctx;
    private final WindowManager wm;
    private final DisplayManager dm;
    private final SharedPreferences local;
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView tvHz, tvRender;
    private GradientDrawable bg;
    private WindowManager.LayoutParams lp;
    private int lastShown = -1, lastRender = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 250);
        }
    };

    private final DisplayManager.DisplayListener dl = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) { if (id == Display.DEFAULT_DISPLAY) refresh(); }
    };

    private Overlay(Context c) {
        ctx = c;
        wm = c.getSystemService(WindowManager.class);
        dm = c.getSystemService(DisplayManager.class);
        local = App.local(c);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attach() {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(10), dp(4), dp(10), dp(5));
        bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xB3101418);
        bg.setStroke(dp(1), 0x33FFFFFF);
        root.setBackground(bg);

        tvHz = new TextView(ctx);
        tvHz.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        tvHz.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvHz.setIncludeFontPadding(false);
        root.addView(tvHz);

        tvRender = new TextView(ctx);
        tvRender.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        tvRender.setTextColor(0xCCFFFFFF);
        tvRender.setIncludeFontPadding(false);
        root.addView(tvRender);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.x = local.getInt("x", dp(16));
        lp.y = local.getInt("y", dp(96));
        lp.setTitle("HzCtl");

        root.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = lp.x;
                        oy = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.hypot(dx, dy) < dp(4)) return true;
                        moved = true;
                        lp.x = Math.max(0, ox + (int) dx);
                        lp.y = Math.max(0, oy + (int) dy);
                        wm.updateViewLayout(root, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            local.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        } else {
                            local.edit().putBoolean("render", !local.getBoolean("render", true)).apply();
                            applyConfig();
                        }
                        return true;
                }
                return false;
            }
        });

        applyFlags();
        wm.addView(root, lp);
        applyConfig();
        dm.registerDisplayListener(dl, h);
        h.post(tick);
    }

    private void detach() {
        h.removeCallbacksAndMessages(null);
        try { dm.unregisterDisplayListener(dl); } catch (Throwable ignored) {}
        try { wm.removeViewImmediate(root); } catch (Throwable ignored) {}
    }

    private void applyFlags() {
        if (local.getBoolean("pass", false))
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    private void applyConfig() {
        tvRender.setVisibility(local.getBoolean("render", true) ? View.VISIBLE : View.GONE);
        applyFlags();
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
        lastShown = -1;
        refresh();
    }

    private void refresh() {
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return;
        float mode = d.getMode().getRefreshRate();
        float render = d.getRefreshRate();
        int m = Math.round(mode), r = Math.round(render);
        if (m == lastShown && r == lastRender) return;
        lastShown = m;
        lastRender = r;
        tvHz.setText(m + " Hz");
        tvHz.setTextColor(Rates.color(mode));
        tvRender.setText("渲染 " + r);
    }
}
