package com.google.projectgameface;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class CursorView extends View {
    private float sweepAngle;
    private ValueAnimator animator;
    private Paint fillPaint, outlinePaint, sweepFillPaint, sweepOutlinePaint, linePaint;
    private RectF circleBounds;
    private float radius = 13f;

    public CursorView(Context ctx) {
        super(ctx);
        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x80FFFFFF);
        outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(2f);
        outlinePaint.setColor(Color.WHITE);
        sweepFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sweepFillPaint.setStyle(Paint.Style.FILL);
        sweepFillPaint.setColor(0x8000FF00);
        sweepOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sweepOutlinePaint.setStyle(Paint.Style.STROKE);
        sweepOutlinePaint.setStrokeWidth(2f);
        sweepOutlinePaint.setColor(Color.GREEN);
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f);
        linePaint.setColor(Color.GREEN);

        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(4000);
        animator.addUpdateListener(a -> { sweepAngle = (float) a.getAnimatedValue(); invalidate(); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                sweepAngle = 0f; invalidate();
            }
        });
    }

    public void startSweep() {
        if (animator != null) animator.start();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f, cy = h / 2f;
        circleBounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = circleBounds.centerX(), cy = circleBounds.centerY();
        canvas.drawCircle(cx, cy, radius, fillPaint);
        canvas.drawCircle(cx, cy, radius, outlinePaint);
        if (animator.isRunning() || sweepAngle > 0) {
            canvas.drawArc(circleBounds, -90f, sweepAngle, true, sweepFillPaint);
            canvas.drawArc(circleBounds, -90f, sweepAngle, false, sweepOutlinePaint);
            double rad = Math.toRadians(sweepAngle - 90);
            float x = (float) (cx + radius * Math.cos(rad));
            float y = (float) (cy + radius * Math.sin(rad));
            canvas.drawLine(cx, cy, x, y, linePaint);
        }
    }

    public void cancelSweep() {
        if (animator != null && animator.isRunning()) animator.cancel();
    }
}
