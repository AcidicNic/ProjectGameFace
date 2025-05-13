package com.google.projectgameface;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import java.util.HashMap;
import android.view.View;

import com.google.projectgameface.utils.Config;

public class CursorView extends View {

    public static final int FILL_WHITE = 0xD9FFFFFF;
    public static final int OUTLINE_WHITE = Color.WHITE;

    public static class ColorState {
        public int fillColor;
        public int outlineColor;

        public ColorState(int fillColor, int outlineColor) {
            this.fillColor = fillColor;
            this.outlineColor = outlineColor;
        }
    }

    private final HashMap<String, ColorState> colorStatesMap;

    private float sweepAngle;
    private ValueAnimator animator;

    // Paints for the CURRENT state
    private Paint fillPaint;
    private Paint outlinePaint;

    // Paints for the TARGET state during animation
    private Paint targetFillPaint;
    private Paint targetOutlinePaint;

    private Paint centerDotPaint;
    private RectF circleBounds;
    private float radius;
    private static final float CENTER_DOT_RADIUS = 6f;

    // Shadow parameters
    private final float shadowRadius = 10f;
    private final float shadowDx = 5f;
    private final float shadowDy = 5f;
    private final int shadowColor = 0x40000000;

    public CursorView(Context ctx) {
        super(ctx);

        // Initialize the color states map in the constructor
        colorStatesMap = new HashMap<>();
        colorStatesMap.put("WHITE", new ColorState(FILL_WHITE, OUTLINE_WHITE));
        colorStatesMap.put("GREEN", new ColorState(0xD94CAF50, 0x4CAF50));
        colorStatesMap.put("RED", new ColorState(0xD9FF5722, 0xFF5722));
        colorStatesMap.put("ORANGE", new ColorState(0xD9FF9800, 0xFF9800));
        colorStatesMap.put("BLUE", new ColorState(0xD92196F3, 0x2196F3));
        colorStatesMap.put("YELLOW", new ColorState(0xD9FFEB3B, 0xFFEB3B));

        // Initialize CURRENT Paints
        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(FILL_WHITE);

        outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(5f);
        outlinePaint.setColor(OUTLINE_WHITE);
        outlinePaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);

        targetFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetFillPaint.setStyle(Paint.Style.FILL);
        targetFillPaint.setColor(FILL_WHITE);

        targetOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetOutlinePaint.setStyle(Paint.Style.STROKE);
        targetOutlinePaint.setStrokeWidth(5f);
        targetOutlinePaint.setColor(OUTLINE_WHITE);
        targetOutlinePaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);

        centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerDotPaint.setStyle(Paint.Style.FILL);
        centerDotPaint.setColor(0xFF4285F4);

        // Animator Setup
        animator = ValueAnimator.ofFloat(0f /*60f*/, 360f);
        animator.addUpdateListener(a -> {
            sweepAngle = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Animation finished naturally, redraw with the new colors
                fillPaint.setColor(targetFillPaint.getColor());
                outlinePaint.setColor(targetOutlinePaint.getColor());
                sweepAngle = 0f;
                invalidate();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // Animation was cancelled, redraw with the original color (state before cancel)
                sweepAngle = 0f;
                invalidate();
            }
        });
    }
    /**
     * Instantly changes the cursor color without animation.
     *
     * @param colorName The name of the target color state (e.g., "WHITE", "GREEN").
     */
    public void setColor(String colorName) {
        ColorState colorState = colorStatesMap.get(colorName);
        if (colorState == null) {
            Log.e("CursorView", "Invalid color state: " + colorName);
            return;
        }

        // Cancel previous animation if running
        cancelAnimation();

        fillPaint.setColor(colorState.fillColor);
        outlinePaint.setColor(colorState.outlineColor);
        invalidate(); // Redraw with the new colors
    }

    /**
     * Animates the cursor color change using a sweep effect with default duration.
     *
     * @param targetColorName The name of the target color state (e.g., "WHITE", "GREEN").
     */
    public void animateToColor(String targetColorName) {
        animateToColor(targetColorName, Config.DEFAULT_ANIMATION_DURATION);
    }

    /**
     * Animates the cursor color change using a sweep effect with specified duration.
     *
     * @param targetColorName The name of the target color state (e.g., "WHITE", "GREEN").
     * @param durationMs The duration of the animation in milliseconds.
     */
    public void animateToColor(String targetColorName, int durationMs) {
        ColorState targetColor = colorStatesMap.get(targetColorName);
        if (targetColor == null) {
            Log.e("CursorView", "Invalid color state: " + targetColorName);
            return;
        }

        // Avoid animating if already the target color
        if (fillPaint.getColor() == targetColor.fillColor && outlinePaint.getColor() == targetColor.outlineColor) {
            cancelAnimation();
            return;
        }

        // Cancel previous animation if running
        cancelAnimation();

        // Set the target paint colors for the animation
        targetFillPaint.setColor(targetColor.fillColor);
        targetOutlinePaint.setColor(targetColor.outlineColor);
        targetOutlinePaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);

        // Set the animation duration
        animator.setDuration(durationMs);
        
        // Start the animation
        animator.start();
    }

    /**
     * Cancels any ongoing color change animation. The cursor color remains
     * whatever it was when cancel was called (or before animation started).
     */
    public void cancelAnimation() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f;
        float cy = h / 2f;

        float shadowPadding = Math.max(shadowDx, shadowDy) + shadowRadius;
        float halfStrokeWidth = outlinePaint.getStrokeWidth() / 2f;
        float totalPadding = halfStrokeWidth + shadowPadding;

        radius = Math.min(w / 2f, h / 2f) - totalPadding;
        if (radius < 0) radius = 0;

        circleBounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (circleBounds == null || radius <= 0) {
            return;
        }
        float cx = circleBounds.centerX();
        float cy = circleBounds.centerY();

        if (animator.isRunning()) {
            // Draw during animation
            float startAngleCurrent = -90f + sweepAngle;
            float sweepAngleCurrent = 360f - sweepAngle;

            // Draw the "current" color part (fill - no shadow)
            canvas.drawArc(circleBounds, startAngleCurrent, sweepAngleCurrent, true, fillPaint);
            // Draw the "target" color part (fill - no shadow)
            canvas.drawArc(circleBounds, -90f, sweepAngle, true, targetFillPaint);

            // Draw the "current" color part (outline - WITH shadow)
            canvas.drawArc(circleBounds, startAngleCurrent, sweepAngleCurrent, false, outlinePaint);
            // Draw the "target" color part (outline - WITH shadow)
            canvas.drawArc(circleBounds, -90f, sweepAngle, false, targetOutlinePaint);

        } else {
            // Draw when idle
            canvas.drawCircle(cx, cy, radius, fillPaint);
            canvas.drawCircle(cx, cy, radius, outlinePaint);
        }

        canvas.drawCircle(cx, cy, CENTER_DOT_RADIUS, centerDotPaint);
    }
}
