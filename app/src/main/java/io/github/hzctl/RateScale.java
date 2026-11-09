package io.github.hzctl;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * 刷新率刻度条：每个档位一个刻度，当前刷新率是一枚会滑动的实心标记，
 * 接管目标是一个空心圈。档位按数值等比排布，60→165 的跨度一眼可见。
 */
public class RateScale extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final List<Integer> stops = new ArrayList<>();
    private float shown = -1;      // 动画中的当前值
    private float target = -1;     // 接管目标，<0 不画
    private ValueAnimator anim;

    public RateScale(Context c, AttributeSet a) {
        super(c, a);
        int onSurfVar = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant);
        track.setColor(outline);
        tick.setColor(onSurfVar);
        tick.setStrokeWidth(dp(1.5f));
        label.setColor(onSurfVar);
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
        label.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2));
        ring.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void setStops(List<Integer> s) {
        stops.clear();
        stops.addAll(s);
        invalidate();
    }

    void setTarget(float hz) {
        target = hz;
        invalidate();
    }

    void setCurrent(float hz) {
        if (shown < 0) {
            shown = hz;
            invalidate();
            return;
        }
        if (Math.abs(hz - shown) < 0.5f && (anim == null || !anim.isRunning())) return;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(shown, hz);
        anim.setDuration(380);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(v -> {
            shown = (float) v.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w), (int) dp(46));
    }

    private float x(float hz, float lo, float hi) {
        float pad = dp(18);
        float t = hi > lo ? (hz - lo) / (hi - lo) : 0.5f;
        t = Math.max(0, Math.min(1, t));
        return pad + t * (getWidth() - 2 * pad);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (stops.isEmpty()) return;
        float lo = stops.get(0), hi = stops.get(stops.size() - 1);
        float cy = dp(14);

        rect.set(dp(18), cy - dp(2), getWidth() - dp(18), cy + dp(2));
        c.drawRoundRect(rect, dp(2), dp(2), track);

        for (int s : stops) {
            float sx = x(s, lo, hi);
            c.drawLine(sx, cy - dp(6), sx, cy + dp(6), tick);
            c.drawText(String.valueOf(s), sx, cy + dp(26), label);
        }
        if (target > 0) c.drawCircle(x(target, lo, hi), cy, dp(9), ring);
        if (shown > 0) {
            marker.setColor(Rates.color(shown));
            c.drawCircle(x(shown, lo, hi), cy, dp(6.5f), marker);
        }
    }
}
