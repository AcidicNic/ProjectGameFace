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
    private Paint centerDotPaint;
    private RectF circleBounds;
    private float radius;
    private static final float CENTER_DOT_RADIUS = 6f;
    private final float shadowRadius = 10f;
    private final float shadowDx = 4f;
    private final float shadowDy = 4f;
    private final int shadowColor = 0x26000000;

    public CursorView(Context ctx) {
        super(ctx);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xD9FFFFFF);

        outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(4f);
        outlinePaint.setColor(Color.WHITE);
        outlinePaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);

        sweepFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sweepFillPaint.setStyle(Paint.Style.FILL);
        sweepFillPaint.setColor(0xD900FF00);

        sweepOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sweepOutlinePaint.setStyle(Paint.Style.STROKE);
        sweepOutlinePaint.setStrokeWidth(4f);
        sweepOutlinePaint.setColor(Color.GREEN);

        centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerDotPaint.setStyle(Paint.Style.FILL);
        centerDotPaint.setColor(0xFF4285F4);

//        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
//        linePaint.setStyle(Paint.Style.STROKE);
//        linePaint.setStrokeWidth(8f);
//        linePaint.setColor(Color.GREEN);

        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(1000);
        animator.addUpdateListener(a -> { sweepAngle = (float) a.getAnimatedValue(); invalidate(); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                sweepAngle = 0f; invalidate();
            }
            @Override
            public void onAnimationCancel(Animator animation) {
                sweepAngle = 0f; invalidate();
            }
        });
    }

    public void startSweep() {
        if (animator != null) animator.start();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f;
        float cy = h / 2f;

        float shadowPadding = Math.max(shadowDx, shadowDy) + shadowRadius;

        float halfStrokeWidth = outlinePaint.getStrokeWidth() / 2f;

        float totalPadding = halfStrokeWidth + shadowPadding;

        radius = Math.min(w / 2f, h / 2f) - totalPadding;

        if (radius < 0) radius = 0;

        circleBounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (circleBounds == null || radius <= 0) {
            return;
        }
        float cx = circleBounds.centerX();
        float cy = circleBounds.centerY();

        if (animator.isRunning() || sweepAngle > 0) {
            canvas.drawArc(circleBounds, -90f, sweepAngle, true, sweepFillPaint);
            canvas.drawArc(circleBounds, -90f, sweepAngle, false, sweepOutlinePaint);
        } else {
            canvas.drawCircle(cx, cy, radius, fillPaint);
        }
        canvas.drawCircle(cx, cy, radius, outlinePaint);
        canvas.drawCircle(cx, cy, CENTER_DOT_RADIUS, centerDotPaint);
    }

    public void cancelSweep() {
        if (animator != null && animator.isRunning()) animator.cancel();
    }
}
