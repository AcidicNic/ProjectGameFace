package com.google.projectgameface;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.ArrayDeque;

public final class GestureStreamController {
    private static final long SEGMENT_MS = 24; // slice size (~42fps)
    private static final float EPS_PX = 1.25f; // min movement to enqueue
    private static final long MIN_SAMPLE_MS = 10; // throttle duplicates
    private static final int MAX_QUEUE = 64; // backpressure
    private static final long START_DEBOUNCE_MS = 50; // avoid double start spam

    private final AccessibilityService svc;
    private final HandlerThread worker = new HandlerThread("GestureStream");
    private final Handler bg;

    private final ArrayDeque<PointF> queue = new ArrayDeque<>();
    private GestureDescription.StrokeDescription inFlight;
    private boolean isStreaming = false;
    private boolean dispatching = false;
    private float lastX, lastY;
    private long tCursor = 0L;
    private long lastEnqueueMs = 0L;
    private long lastStartUptime = 0L;

    public GestureStreamController(AccessibilityService svc) {
        this.svc = svc;
        worker.start();
        bg = new Handler(worker.getLooper());
    }

    public boolean start(float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (isStreaming || (now - lastStartUptime) < START_DEBOUNCE_MS) return false;

        isStreaming = true;
        lastStartUptime = now;
        lastX = x; lastY = y;
        queue.clear();
        tCursor = 0L;
        inFlight = null;
        dispatching = false;

        queue.add(new PointF(x, y));  // seed point
        pump();
        return true;
    }

    public void restart(float x, float y) {
        cancel();
        lastStartUptime = 0L;
        start(x, y);
    }

    public void update(float x, float y) {
        if (!isStreaming) return;

        float dx = x - (queue.isEmpty() ? lastX : queue.peekLast().x);
        float dy = y - (queue.isEmpty() ? lastY : queue.peekLast().y);
        long now = SystemClock.uptimeMillis();

        if (dx*dx + dy*dy < EPS_PX*EPS_PX) {
            if (now - lastEnqueueMs < MIN_SAMPLE_MS) return;
        }

        if (queue.size() >= MAX_QUEUE) {
            int drop = MAX_QUEUE / 2;
            while (drop-- > 0 && !queue.isEmpty()) queue.pollFirst();
        }

        queue.add(new PointF(x, y));
        lastEnqueueMs = now;
        pump();
    }

    public void end() {
        if (!isStreaming) return;
        isStreaming = false;
        pump();
    }

    public void cancel() {
        isStreaming = false;
        queue.clear();
        inFlight = null;
        dispatching = false;
        tCursor = 0L;
    }

    public void shutdown() {
        try { worker.quitSafely(); } catch (Throwable ignored) {}
    }

    public boolean isActive() { return isStreaming; }

    private void pump() {
        if (dispatching) return;
        if (inFlight == null && queue.isEmpty()) return;
        dispatching = true;
        bg.post(this::emitNextSegment);
    }

    private void emitNextSegment() {
        try {
            if (inFlight == null) {
                PointF p = queue.peekFirst();
                if (p == null) { dispatching = false; return; }

                Path path = new Path();
                path.moveTo(p.x, p.y);
                path.lineTo(p.x + 0.1f, p.y + 0.1f);

                boolean willContinue = isStreaming || !queue.isEmpty();
                GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, tCursor, SEGMENT_MS, willContinue);

                GestureDescription.Builder b = new GestureDescription.Builder().addStroke(stroke);
                boolean ok = svc.dispatchGesture(b.build(), cbContinue(), bg);
                if (!ok) resetState();
                else {
                    inFlight = stroke;
                    tCursor += SEGMENT_MS;
                }
                return;
            }

            if (queue.isEmpty() && isStreaming) { dispatching = false; return; }

            Path path = new Path();
            path.moveTo(lastX, lastY);

            int maxPts = 3;
            PointF last = null;
            while (maxPts-- > 0 && !queue.isEmpty()) {
                last = queue.pollFirst();
                path.lineTo(last.x, last.y);
            }
            if (last == null) {
                last = new PointF(lastX, lastY);
                path.lineTo(last.x + 0.1f, last.y + 0.1f);
            }

            boolean willContinue = isStreaming || !queue.isEmpty();
            GestureDescription.StrokeDescription next =
                inFlight.continueStroke(path, tCursor, SEGMENT_MS, willContinue);

            GestureDescription.Builder b = new GestureDescription.Builder().addStroke(next);
            boolean ok = svc.dispatchGesture(b.build(), cbContinue(), bg);
            if (!ok) resetState();
            else {
                inFlight = next;
                lastX = last.x; lastY = last.y;
                tCursor += SEGMENT_MS;
            }
        } catch (Exception e) {
            resetState();
        }
    }

    private AccessibilityService.GestureResultCallback cbContinue() {
        return new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                dispatching = false;
                if (isStreaming || !queue.isEmpty()) pump();
                else inFlight = null;
            }
            @Override public void onCancelled(GestureDescription g) { resetState(); }
        };
    }

    private void resetState() {
        dispatching = false;
        inFlight = null;
        isStreaming = false;
        queue.clear();
        tCursor = 0L;
    }
}
