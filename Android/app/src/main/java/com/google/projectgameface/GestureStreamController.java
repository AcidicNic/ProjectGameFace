package com.google.projectgameface;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;

public final class GestureStreamController {
    private static final String TAG = "GestureStreamController";
    
    // Aligned to 60fps input rate (16ms) with slight buffer for system overhead
    private static final long SEGMENT_MS = 17; // slice size (~59fps, matches tick interval)
    private static final float EPS_PX = 1.25f; // min movement to enqueue
    private static final long MIN_SAMPLE_MS = 10; // throttle duplicates
    private static final int MAX_QUEUE = 64; // backpressure
    private static final long START_DEBOUNCE_MS = 50; // avoid double start spam
    
    // Preemptive dispatch: dispatch next segment at 80% of current segment duration
    private static final double PREEMPTIVE_DISPATCH_RATIO = 0.80;
    private static final long PREEMPTIVE_DISPATCH_MS = (long) (SEGMENT_MS * PREEMPTIVE_DISPATCH_RATIO);
    
    // Adaptive timing: track completion times in rolling window
    private static final int COMPLETION_TIME_HISTORY_SIZE = 10;

    private final AccessibilityService svc;
    private final HandlerThread worker = new HandlerThread("GestureStream");
    private final Handler bg;

    private final ArrayDeque<PointF> queue = new ArrayDeque<>();
    private final ArrayDeque<Long> completionTimes = new ArrayDeque<>(); // Track actual completion times
    private GestureDescription.StrokeDescription inFlight;
    private boolean isStreaming = false;
    private boolean dispatching = false;
    private float lastX, lastY;
    private long tCursor = 0L;
    private long lastEnqueueMs = 0L;
    private long lastStartUptime = 0L;
    private long segmentStartTime = 0L; // Track when current segment started
    private Runnable preemptiveDispatchRunnable = null; // For scheduled preemptive dispatch

    public GestureStreamController(AccessibilityService svc) {
        this.svc = svc;
        worker.start();
        bg = new Handler(worker.getLooper());
    }

    public synchronized boolean start(float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (isStreaming || (now - lastStartUptime) < START_DEBOUNCE_MS) return false;

        isStreaming = true;
        lastStartUptime = now;
        lastX = x; lastY = y;
        queue.clear();
        completionTimes.clear();
        tCursor = 0L;
        inFlight = null;
        dispatching = false;
        segmentStartTime = now;
        
        // Cancel any pending preemptive dispatch
        if (preemptiveDispatchRunnable != null) {
            bg.removeCallbacks(preemptiveDispatchRunnable);
            preemptiveDispatchRunnable = null;
        }

        queue.add(new PointF(x, y));  // seed point
        pump();
        return true;
    }

    public synchronized void restart(float x, float y) {
        cancel();
        lastStartUptime = 0L;
        start(x, y);
    }

    public synchronized void update(float x, float y) {
        if (!isStreaming) return;

        float dx = x - (queue.isEmpty() ? lastX : queue.peekLast().x);
        float dy = y - (queue.isEmpty() ? lastY : queue.peekLast().y);
        long now = SystemClock.uptimeMillis();

        if (dx*dx + dy*dy < EPS_PX*EPS_PX) {
            if (now - lastEnqueueMs < MIN_SAMPLE_MS) return;
        }

        // Improved queue management: drop oldest points when queue is full
        // This is more graceful than dropping half, as it maintains recent movement data
        if (queue.size() >= MAX_QUEUE) {
            // Drop oldest points until we have room (drop 25% at a time for smoother transitions)
            int dropCount = MAX_QUEUE / 4;
            while (dropCount-- > 0 && queue.size() >= MAX_QUEUE && !queue.isEmpty()) {
                queue.pollFirst();
            }
        }

        queue.add(new PointF(x, y));
        lastEnqueueMs = now;
        pump();
    }

    public synchronized void end() {
        if (!isStreaming) return;
        isStreaming = false;
        
        // Cancel preemptive dispatch
        if (preemptiveDispatchRunnable != null) {
            bg.removeCallbacks(preemptiveDispatchRunnable);
            preemptiveDispatchRunnable = null;
        }
        
        pump();
    }

    public synchronized void cancel() {
        isStreaming = false;
        queue.clear();
        completionTimes.clear();
        inFlight = null;
        dispatching = false;
        tCursor = 0L;
        
        // Cancel preemptive dispatch
        if (preemptiveDispatchRunnable != null) {
            bg.removeCallbacks(preemptiveDispatchRunnable);
            preemptiveDispatchRunnable = null;
        }
    }

    public void shutdown() {
        try { worker.quitSafely(); } catch (Throwable ignored) {}
    }

    public synchronized boolean isActive() { return isStreaming; }

    private void pump() {
        synchronized (this) {
            if (dispatching) return;
            if (inFlight == null && queue.isEmpty()) return;
            dispatching = true;
        }
        bg.post(this::emitNextSegment);
    }
    
    /**
     * Schedule preemptive dispatch of next segment before current one completes.
     * This reduces gaps between segments by overlapping dispatch with execution.
     */
    private void schedulePreemptiveDispatch() {
        // Cancel any existing preemptive dispatch
        if (preemptiveDispatchRunnable != null) {
            bg.removeCallbacks(preemptiveDispatchRunnable);
        }
        
        preemptiveDispatchRunnable = () -> {
            synchronized (GestureStreamController.this) {
                // Only dispatch if we're still streaming and have data
                if (isStreaming && (inFlight != null || !queue.isEmpty())) {
                    if (!dispatching) {
                        dispatching = true;
                        bg.post(this::emitNextSegment);
                    }
                }
            }
        };
        
        // Schedule dispatch at 80% of segment duration
        bg.postDelayed(preemptiveDispatchRunnable, PREEMPTIVE_DISPATCH_MS);
    }
    
    /**
     * Calculate adaptive segment duration based on actual completion times.
     */
    private long getAdaptiveSegmentDuration() {
        if (completionTimes.isEmpty()) {
            return SEGMENT_MS; // Use default if no history
        }
        
        // Calculate average of recent completion times
        long sum = 0;
        for (Long time : completionTimes) {
            sum += time;
        }
        long avgCompletionTime = sum / completionTimes.size();
        
        // Use average if it's reasonable, otherwise use default
        if (avgCompletionTime > 0 && avgCompletionTime < SEGMENT_MS * 2) {
            return avgCompletionTime;
        }
        return SEGMENT_MS;
    }

    private void emitNextSegment() {
        try {
            long segmentDuration = getAdaptiveSegmentDuration();
            long dispatchTime = SystemClock.uptimeMillis();
            
            synchronized (this) {
                if (inFlight == null) {
                    // First segment - remove 0.1px hack, use actual points
                    PointF start = queue.pollFirst();
                    if (start == null) { 
                        dispatching = false; 
                        return; 
                    }

                    Path path = new Path();
                    path.moveTo(start.x, start.y);
                    
                    // Build path with adaptive number of points based on queue size and movement
                    int pointsToUse = calculatePointsPerSegment();
                    PointF lastPoint = start;
                    int pointsAdded = 0;
                    
                    while (pointsAdded < pointsToUse && !queue.isEmpty()) {
                        lastPoint = queue.pollFirst();
                        path.lineTo(lastPoint.x, lastPoint.y);
                        pointsAdded++;
                    }
                    
                    // If no points in queue, create a minimal path from start point
                    if (pointsAdded == 0) {
                        path.lineTo(start.x, start.y);
                        lastPoint = start;
                    }

                    boolean willContinue = isStreaming || !queue.isEmpty();
                    GestureDescription.StrokeDescription stroke =
                        new GestureDescription.StrokeDescription(path, tCursor, segmentDuration, willContinue);

                    GestureDescription.Builder b = new GestureDescription.Builder().addStroke(stroke);
                    boolean ok = svc.dispatchGesture(b.build(), cbContinue(dispatchTime), bg);
                    if (!ok) {
                        resetState();
                        return;
                    }
                    
                    inFlight = stroke;
                    lastX = lastPoint.x;
                    lastY = lastPoint.y;
                    segmentStartTime = dispatchTime;
                    
                    // Use adaptive timing for tCursor
                    tCursor += segmentDuration;
                    
                    // Schedule preemptive dispatch if continuing
                    if (willContinue) {
                        schedulePreemptiveDispatch();
                    }
                    return;
                }

                if (queue.isEmpty() && isStreaming) { 
                    dispatching = false; 
                    return; 
                }

                // Continue stroke with improved path building
                Path path = new Path();
                path.moveTo(lastX, lastY);

                // Adaptive point count based on movement speed and queue size
                int pointsToUse = calculatePointsPerSegment();
                PointF last = null;
                int pointsAdded = 0;
                
                while (pointsAdded < pointsToUse && !queue.isEmpty()) {
                    last = queue.pollFirst();
                    path.lineTo(last.x, last.y);
                    pointsAdded++;
                }
                
                // If no points available, extend from last position (no 0.1px hack)
                if (last == null) {
                    last = new PointF(lastX, lastY);
                    // Create a minimal continuation path
                    path.lineTo(last.x, last.y);
                }

                boolean willContinue = isStreaming || !queue.isEmpty();
                GestureDescription.StrokeDescription next =
                    inFlight.continueStroke(path, tCursor, segmentDuration, willContinue);

                GestureDescription.Builder b = new GestureDescription.Builder().addStroke(next);
                boolean ok = svc.dispatchGesture(b.build(), cbContinue(dispatchTime), bg);
                if (!ok) {
                    resetState();
                    return;
                }
                
                inFlight = next;
                lastX = last.x;
                lastY = last.y;
                segmentStartTime = dispatchTime;
                
                // Use adaptive timing for tCursor
                tCursor += segmentDuration;
                
                // Schedule preemptive dispatch if continuing
                if (willContinue) {
                    schedulePreemptiveDispatch();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error emitting gesture segment", e);
            resetState();
        }
    }
    
    /**
     * Calculate adaptive number of points per segment based on queue size and movement characteristics.
     */
    private int calculatePointsPerSegment() {
        if (queue.isEmpty()) {
            return 1;
        }
        
        int queueSize = queue.size();
        
        // Base: use more points if queue is building up (indicates fast movement)
        if (queueSize > 20) {
            return 5; // Fast movement, process more points
        } else if (queueSize > 10) {
            return 4; // Moderate movement
        } else if (queueSize > 5) {
            return 3; // Normal movement
        } else {
            return 2; // Slow movement, fewer points needed
        }
    }

    /**
     * Create callback that tracks actual completion times for adaptive timing.
     */
    private AccessibilityService.GestureResultCallback cbContinue(long dispatchTime) {
        return new AccessibilityService.GestureResultCallback() {
            @Override 
            public void onCompleted(GestureDescription g) {
                long completionTime = SystemClock.uptimeMillis();
                long actualDuration = completionTime - dispatchTime;
                
                synchronized (GestureStreamController.this) {
                    // Track completion time for adaptive timing
                    completionTimes.addLast(actualDuration);
                    if (completionTimes.size() > COMPLETION_TIME_HISTORY_SIZE) {
                        completionTimes.pollFirst();
                    }
                    
                    // Cancel preemptive dispatch if it hasn't fired yet
                    if (preemptiveDispatchRunnable != null) {
                        bg.removeCallbacks(preemptiveDispatchRunnable);
                        preemptiveDispatchRunnable = null;
                    }
                    
                    dispatching = false;
                    
                    // Only pump if preemptive dispatch didn't already start next segment
                    if (isStreaming || !queue.isEmpty()) {
                        // Check if we need to dispatch (preemptive might have already done it)
                        if (!dispatching) {
                            pump();
                        }
                    } else {
                        inFlight = null;
                    }
                }
            }
            
            @Override 
            public void onCancelled(GestureDescription g) { 
                synchronized (GestureStreamController.this) {
                    resetState(); 
                }
            }
        };
    }

    private void resetState() {
        dispatching = false;
        inFlight = null;
        isStreaming = false;
        queue.clear();
        completionTimes.clear();
        tCursor = 0L;
        
        // Cancel preemptive dispatch
        if (preemptiveDispatchRunnable != null) {
            bg.removeCallbacks(preemptiveDispatchRunnable);
            preemptiveDispatchRunnable = null;
        }
    }
}
