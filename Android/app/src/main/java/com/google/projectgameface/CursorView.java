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

import java.util.LinkedList;
import java.util.Queue;
import java.util.Collections;

import com.google.projectgameface.utils.Colors;
import com.google.projectgameface.utils.Config;

public class CursorView extends View {

    public static final int FILL_WHITE = 0xD9FFFFFF;
    public static final int OUTLINE_WHITE = Color.WHITE;

    public static class ColorState {
        public int fillColor;
        public int outlineColor;

        public ColorState(Colors color) {
            this.fillColor = color.cursorFill();
            this.outlineColor = color.cursorOutline();
        }

        public ColorState(int fillColor, int outlineColor) {
            this.fillColor = fillColor;
            this.outlineColor = outlineColor;
        }
    }

    private final HashMap<String, ColorState> colorStatesMap;

    private float sweepAngle;
    private ValueAnimator animator;
    private boolean isAnimationHidden = false;
    private String hiddenColor = null;
    private long animationStartTime = 0;
    private int animationDuration = 0;
    private String targetColorName = null;
    private String lastColorName = "WHITE"; // Track the last non-hidden color
    private Queue<AnimationStep> animationQueue = new LinkedList<>();
    private boolean isAnimating = false;

    public static class AnimationStep {
        String targetColor;
        int duration;
        int startOffset;  // Where in the animation to start (0 to duration-1)
        long startTime;

        AnimationStep(String targetColor, int duration) {
            this(targetColor, duration, 0);
        }

        AnimationStep(String targetColor, int duration, int startOffset) {
            if (startOffset >= duration) {
                throw new IllegalArgumentException("startOffset must be less than duration");
            }
            this.targetColor = targetColor;
            this.duration = duration;
            this.startOffset = startOffset;
        }
    }

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
        colorStatesMap.put("WHITE", new ColorState(Colors.WHITE));
        colorStatesMap.put("GREEN", new ColorState(Colors.GREEN));
        colorStatesMap.put("RED", new ColorState(Colors.RED));
        colorStatesMap.put("ORANGE", new ColorState(Colors.ORANGE));
        colorStatesMap.put("BLUE", new ColorState(Colors.BLUE));
        colorStatesMap.put("YELLOW", new ColorState(Colors.YELLOW));

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
        lastColorName = colorName; // Update last color
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
     * Queues a series of animations to be played back to back.
     * Each animation will start immediately after the previous one finishes.
     *
     * @param animations Array of animation steps, each containing a color and duration
     */
    public void queueAnimations(AnimationStep... animations) {
        if (animations == null || animations.length == 0) return;

        // Add all animations to queue
        Collections.addAll(animationQueue, animations);

        // If not currently animating, start the first animation
        if (!isAnimating) {
            startNextAnimation();
        }
    }

    /**
     * Starts the next animation in the queue if one exists.
     */
    private void startNextAnimation() {
        if (animationQueue.isEmpty()) {
            isAnimating = false;
            return;
        }

        isAnimating = true;
        AnimationStep step = animationQueue.poll();
        step.startTime = System.currentTimeMillis();
        
        // Store animation info
        targetColorName = step.targetColor;
        animationStartTime = step.startTime - step.startOffset;
        animationDuration = step.duration;

        // If animation is hidden, don't start the animator
        if (isAnimationHidden) {
            return;
        }

        // Start the animation with offset
        animateToColor(step.targetColor, step.duration, step.startOffset);
    }

    /**
     * Temporarily hides all animations and shows a different color.
     * The animations continue running in the background.
     *
     * @param colorName The color to show while animations are hidden
     */
    public void hideAnimation(String colorName) {
        if (isAnimationHidden) return;
        
        isAnimationHidden = true;
        hiddenColor = colorName;
        
        // Cancel current animation if running
        if (animator.isRunning()) {
            animator.cancel();
        }
        
        // Show the temporary color without updating lastColorName
        ColorState colorState = colorStatesMap.get(colorName);
        if (colorState != null) {
            fillPaint.setColor(colorState.fillColor);
            outlinePaint.setColor(colorState.outlineColor);
            invalidate();
        }
    }

    /**
     * Shows animations again after they were hidden.
     * If there was an animation in progress when hidden, it will continue from where it left off.
     * Otherwise, returns to the last color state.
     */
    public void showAnimation() {
        if (!isAnimationHidden) return;
        isAnimationHidden = false;
        hiddenColor = null;
        
        // If we have a target color and animation duration, restart the animation
        if (targetColorName != null && animationDuration > 0) {
            long currentTime = System.currentTimeMillis();
            long elapsedTime = currentTime - animationStartTime;
            
            // If animation hasn't completed, continue from where it left off
            if (elapsedTime < animationDuration) {
                setColor(lastColorName);
                animateToColor(targetColorName, animationDuration, (int)elapsedTime);
            } else {
                // Animation would have completed, just set the final color
                setColor(targetColorName);
            }
        } else {
            // No animation in progress, return to last color state
            setColor(lastColorName);
        }
    }

    /**
     * Cancels all animations and resets the cursor to the last color state.
     */
    public void cancelAllAnimations() {
        // Clear the animation queue
        animationQueue.clear();
        isAnimating = false;
        
        // Cancel current animation if running
        if (animator.isRunning()) {
            animator.cancel();
        }
        
        // Reset animation state
        isAnimationHidden = false;
        hiddenColor = null;
        targetColorName = null;
        animationStartTime = 0;
        animationDuration = 0;
        
        // Reset to last color state
        setColor(lastColorName);
    }

    /**
     * Animates the cursor color change using a sweep effect with specified duration.
     *
     * @param targetColorName The name of the target color state (e.g., "WHITE", "GREEN").
     * @param durationMs The duration of the animation in milliseconds.
     */
    public void animateToColor(String targetColorName, int durationMs) {
        animateToColor(targetColorName, durationMs, 0);
    }

    /**
     * Animates the cursor color change using a sweep effect with specified duration and start offset.
     *
     * @param targetColorName The name of the target color state (e.g., "WHITE", "GREEN").
     * @param durationMs The duration of the animation in milliseconds.
     * @param startOffsetMs Where in the animation to start (0 to durationMs-1).
     */
    public void animateToColor(String targetColorName, int durationMs, int startOffsetMs) {
        if (startOffsetMs >= durationMs) {
            throw new IllegalArgumentException("startOffsetMs must be less than durationMs");
        }

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
        
        // Store animation info
        this.targetColorName = targetColorName;
        animationStartTime = System.currentTimeMillis() - startOffsetMs;
        animationDuration = durationMs;
        
        // Add listener to start next animation when this one finishes
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Update last color when animation completes successfully
                lastColorName = targetColorName;
                // Animation finished naturally, start next one
                startNextAnimation();
            }
        });

        // Calculate the starting value based on offset
        float startValue = (startOffsetMs * 360f) / durationMs;
        animator.setCurrentPlayTime(startOffsetMs);
        
        // Start the animation
        animator.start();
    }

    /**
     * Cancels the current animation. The cursor color remains
     * whatever it was when cancel was called.
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

        if (animator.isRunning() && !isAnimationHidden) {
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
            // Draw when idle or animation is hidden
            canvas.drawCircle(cx, cy, radius, fillPaint);
            canvas.drawCircle(cx, cy, radius, outlinePaint);
        }

        canvas.drawCircle(cx, cy, CENTER_DOT_RADIUS, centerDotPaint);
    }
}
