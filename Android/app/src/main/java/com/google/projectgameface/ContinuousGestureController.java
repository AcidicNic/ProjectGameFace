package com.google.projectgameface;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controller for simulating continuous touch gestures using GestureDescription with willContinue.
 *
 * This class handles the complexity of segmenting long gestures, managing timing,
 * and ensuring smooth continuous motion across multiple gesture dispatches.
 *
 * Thread-safe and optimized for laggy main threads by using a background processing thread.
 */
public class ContinuousGestureController {
    private static final String TAG = "ContinuousGestureCtrl";

    // Configuration constants
    private static final int SEGMENT_DURATION_MS = 350; // Optimal segment duration for smooth gestures
    private static final int MIN_POINTS_PER_SEGMENT = 15;
    private static final int MAX_POINTS_PER_SEGMENT = 25;
    private static final int PATH_BUFFER_SIZE_MS = 500; // Buffer last 500ms of positions
    private static final long MAX_POSITION_AGE_MS = 100; // Discard positions older than this

    // Gesture state
    private final AtomicBoolean isGestureActive = new AtomicBoolean(false);
    private final AtomicLong gestureStartTime = new AtomicLong(0);
    private final AtomicReference<GestureState> currentState = new AtomicReference<>(GestureState.IDLE);

    // Cursor position buffer (thread-safe)
    private final List<CursorPosition> positionBuffer = new ArrayList<>();
    private final Object bufferLock = new Object();

    // Background processing thread
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // Main thread handler (for dispatchGesture which must run on main thread)
    private Handler mainHandler;

    // AccessibilityService reference
    private AccessibilityService accessibilityService;

    // Current segment tracking
    private CursorPosition lastSegmentEnd = null;
    private boolean isDispatching = false;
    private int segmentCounter = 0;

    /**
     * Gesture state enumeration
     */
    public enum GestureState {
        IDLE,           // No gesture active
        STARTING,       // Gesture is starting (first segment being prepared)
        ACTIVE,         // Gesture is actively running
        STOPPING,       // Gesture is stopping (final segment being dispatched)
        ERROR           // Error state
    }

    /**
     * Cursor position data structure
     */
    private static class CursorPosition {
        final float x;
        final float y;
        final long timestamp;

        CursorPosition(float x, float y, long timestamp) {
            this.x = x;
            this.y = y;
            this.timestamp = timestamp;
        }
    }

    /**
     * Gesture status information
     */
    public static class GestureStatus {
        public final boolean isActive;
        public final GestureState state;
        public final long startTime;
        public final long elapsedTime;
        public final int segmentCount;
        public final int bufferedPositions;

        GestureStatus(boolean isActive, GestureState state, long startTime,
            long elapsedTime, int segmentCount, int bufferedPositions) {
            this.isActive = isActive;
            this.state = state;
            this.startTime = startTime;
            this.elapsedTime = elapsedTime;
            this.segmentCount = segmentCount;
            this.bufferedPositions = bufferedPositions;
        }
    }

    /**
     * Constructor
     *
     * @param accessibilityService The AccessibilityService instance to use for dispatching gestures
     * @param mainHandler Handler for the main/UI thread (for dispatchGesture calls)
     */
    public ContinuousGestureController(AccessibilityService accessibilityService, Handler mainHandler) {
        this.accessibilityService = accessibilityService;
        this.mainHandler = mainHandler;

        // Create background thread for processing
        backgroundThread = new HandlerThread("GestureController");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * Start a new continuous gesture.
     *
     * This should be called when the user indicates they want to start touching/swiping.
     * The gesture will begin with the next cursor position update.
     */
    public void startGesture() {
        if (isGestureActive.getAndSet(true)) {
            Log.w(TAG, "startGesture called but gesture already active");
            return;
        }

        backgroundHandler.post(() -> {
            synchronized (bufferLock) {
                positionBuffer.clear();
                lastSegmentEnd = null;
                segmentCounter = 0;
                isDispatching = false;
            }

            gestureStartTime.set(SystemClock.uptimeMillis());
            currentState.set(GestureState.STARTING);
            Log.d(TAG, "Gesture started");
        });
    }

    /**
     * Stop the current continuous gesture.
     *
     * This should be called when the user indicates they want to stop touching/swiping.
     * The gesture will complete with the next segment (which will have willContinue=false).
     */
    public void stopGesture() {
        if (!isGestureActive.getAndSet(false)) {
            Log.w(TAG, "stopGesture called but no gesture active");
            return;
        }

        backgroundHandler.post(() -> {
            currentState.set(GestureState.STOPPING);
            Log.d(TAG, "Gesture stopping - will complete with next segment");

            // If we're not currently dispatching, dispatch the final segment immediately
            if (!isDispatching) {
                dispatchNextSegment(true);
            }
        });
    }

    /**
     * Update the cursor position.
     *
     * Call this method every time a new cursor position is calculated (~16-17ms intervals).
     * This method is thread-safe and optimized for laggy main threads.
     *
     * @param x X coordinate of cursor position
     * @param y Y coordinate of cursor position
     */
    public void updateCursorPosition(float x, float y) {
        long timestamp = SystemClock.uptimeMillis();
        CursorPosition position = new CursorPosition(x, y, timestamp);

        // Add to buffer (thread-safe, fast operation)
        synchronized (bufferLock) {
            positionBuffer.add(position);

            // Clean up old positions
            long cutoffTime = timestamp - PATH_BUFFER_SIZE_MS;
            positionBuffer.removeIf(p -> p.timestamp < cutoffTime);
        }

        // Process on background thread if gesture is active
        if (isGestureActive.get()) {
            backgroundHandler.post(() -> {
                if (isGestureActive.get() && !isDispatching) {
                    // Check if we should dispatch a new segment
                    checkAndDispatchSegment();
                }
            });
        }
    }

    /**
     * Get the current status of the gesture system.
     *
     * @return GestureStatus object with current state information
     */
    public GestureStatus getStatus() {
        boolean active = isGestureActive.get();
        GestureState state = currentState.get();
        long startTime = gestureStartTime.get();
        long elapsed = active ? (SystemClock.uptimeMillis() - startTime) : 0;

        int bufferedCount;
        synchronized (bufferLock) {
            bufferedCount = positionBuffer.size();
        }

        return new GestureStatus(active, state, startTime, elapsed, segmentCounter, bufferedCount);
    }

    /**
     * Clean up resources. Call this when the service is being destroyed.
     */
    public void cleanup() {
        stopGesture();
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for background thread to finish");
            }
        }
    }

    // ========== Private Methods ==========

    /**
     * Check if we should dispatch a new segment and do so if conditions are met.
     */
    private void checkAndDispatchSegment() {
        if (!isGestureActive.get() || isDispatching) {
            return;
        }

        List<CursorPosition> positions;
        synchronized (bufferLock) {
            if (positionBuffer.isEmpty()) {
                return;
            }

            // Get recent positions for the segment
            positions = getPositionsForSegment();
            if (positions.size() < 2) {
                return; // Need at least 2 points for a path
            }
        }

        // Dispatch the segment
        dispatchNextSegment(false);
    }

    /**
     * Get positions from buffer to use for the next segment.
     */
    private List<CursorPosition> getPositionsForSegment() {
        List<CursorPosition> segmentPositions = new ArrayList<>();
        long now = SystemClock.uptimeMillis();
        long cutoffTime = now - MAX_POSITION_AGE_MS;

        // Get recent valid positions
        for (int i = positionBuffer.size() - 1; i >= 0; i--) {
            CursorPosition pos = positionBuffer.get(i);
            if (pos.timestamp >= cutoffTime) {
                segmentPositions.add(0, pos); // Insert at beginning to maintain order
            }
        }

        // Limit to reasonable number of points
        if (segmentPositions.size() > MAX_POINTS_PER_SEGMENT) {
            // Take evenly spaced points
            int step = segmentPositions.size() / MAX_POINTS_PER_SEGMENT;
            List<CursorPosition> sampled = new ArrayList<>();
            for (int i = 0; i < segmentPositions.size(); i += step) {
                sampled.add(segmentPositions.get(i));
            }
            // Always include the last position
            if (!sampled.contains(segmentPositions.get(segmentPositions.size() - 1))) {
                sampled.add(segmentPositions.get(segmentPositions.size() - 1));
            }
            segmentPositions = sampled;
        }

        // Ensure minimum points
        if (segmentPositions.size() < MIN_POINTS_PER_SEGMENT && positionBuffer.size() >= MIN_POINTS_PER_SEGMENT) {
            // Take more points from buffer
            int needed = MIN_POINTS_PER_SEGMENT - segmentPositions.size();
            for (int i = positionBuffer.size() - segmentPositions.size() - 1;
                 i >= 0 && needed > 0; i--) {
                CursorPosition pos = positionBuffer.get(i);
                if (!segmentPositions.contains(pos)) {
                    segmentPositions.add(0, pos);
                    needed--;
                }
            }
        }

        return segmentPositions;
    }

    /**
     * Dispatch the next gesture segment.
     *
     * @param isFinal true if this is the final segment (willContinue=false)
     */
    private void dispatchNextSegment(boolean isFinal) {
        if (isDispatching) {
            Log.w(TAG, "Attempted to dispatch segment while another is in progress");
            return;
        }

        List<CursorPosition> positions;
        synchronized (bufferLock) {
            positions = getPositionsForSegment();
            if (positions.isEmpty()) {
                Log.w(TAG, "No positions available for segment");
                return;
            }
        }

        // Ensure continuity: if we have a previous segment, the new path must start where it ended
        CursorPosition segmentStart;
        if (lastSegmentEnd != null) {
            segmentStart = lastSegmentEnd;
            // Prepend the last end point to ensure continuity
            CursorPosition firstPos = positions.isEmpty() ? null : positions.get(0);
            if (firstPos == null || firstPos.x != segmentStart.x || firstPos.y != segmentStart.y) {
                positions.add(0, segmentStart);
            }
        } else {
            segmentStart = positions.get(0);
        }

        CursorPosition segmentEnd = positions.get(positions.size() - 1);

        // Build the path
        Path path = buildPath(positions);

        // Determine if this segment should continue
        boolean willContinue = !isFinal && isGestureActive.get();

        // Create stroke description
        StrokeDescription stroke = new StrokeDescription(
            path,
            0, // startTime relative to this gesture
            SEGMENT_DURATION_MS,
            willContinue
        );

        // Build gesture
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        GestureDescription gesture = builder.build();

        // Update state
        isDispatching = true;
        segmentCounter++;
        lastSegmentEnd = segmentEnd;

        if (currentState.get() == GestureState.STARTING) {
            currentState.set(GestureState.ACTIVE);
        }
        if (isFinal) {
            currentState.set(GestureState.STOPPING);
        }

        // Dispatch on main thread (required for dispatchGesture)
        mainHandler.post(() -> {
            if (accessibilityService == null) {
                Log.e(TAG, "AccessibilityService is null, cannot dispatch gesture");
                isDispatching = false;
                return;
            }

            accessibilityService.dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    backgroundHandler.post(() -> {
                        isDispatching = false;

                        if (isFinal || !isGestureActive.get()) {
                            // Gesture is complete
                            currentState.set(GestureState.IDLE);
                            isGestureActive.set(false);
                            lastSegmentEnd = null;
                            segmentCounter = 0;
                            Log.d(TAG, "Gesture completed");
                        } else {
                            // Continue with next segment
                            checkAndDispatchSegment();
                        }
                    });
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    backgroundHandler.post(() -> {
                        isDispatching = false;
                        currentState.set(GestureState.ERROR);
                        Log.e(TAG, "Gesture segment cancelled");

                        // Try to recover: if gesture should still be active, try next segment
                        if (isGestureActive.get() && !isFinal) {
                            // Wait a bit before retrying to avoid rapid retry loops
                            backgroundHandler.postDelayed(() -> {
                                if (isGestureActive.get() && !isDispatching) {
                                    checkAndDispatchSegment();
                                }
                            }, 50);
                        } else {
                            isGestureActive.set(false);
                            currentState.set(GestureState.IDLE);
                        }
                    });
                }
            }, null);
        });

        Log.d(TAG, String.format("Dispatched segment %d (willContinue=%s, points=%d)",
            segmentCounter, willContinue, positions.size()));
    }

    /**
     * Build a Path object from a list of cursor positions.
     */
    private Path buildPath(List<CursorPosition> positions) {
        if (positions.isEmpty()) {
            return new Path();
        }

        Path path = new Path();
        CursorPosition first = positions.get(0);
        path.moveTo(first.x, first.y);

        // Use lineTo for remaining points (smooth enough for most cases)
        // For smoother curves, you could use quadTo or cubicTo with interpolation
        for (int i = 1; i < positions.size(); i++) {
            CursorPosition pos = positions.get(i);
            path.lineTo(pos.x, pos.y);
        }

        return path;
    }

}

