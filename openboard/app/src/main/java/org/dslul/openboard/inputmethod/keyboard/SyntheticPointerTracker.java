// java
package org.dslul.openboard.inputmethod.keyboard;

import android.content.res.TypedArray;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.dslul.openboard.inputmethod.keyboard.internal.DrawingProxy;
import org.dslul.openboard.inputmethod.keyboard.internal.TimerProxy;

import java.lang.reflect.Method;

public final class SyntheticPointerTracker {
    // Use a modest but "out-of-band" id to avoid collisions with real touches (typically 0..9).
    public static final int DEFAULT_POINTER_ID = 31;

    private static Method ON_DOWN;     // onDownEvent(int x, int y, long t, KeyDetector kd)
    private static Method ON_MOVE;     // onMoveEvent(int x, int y, long t, MotionEvent me)
    private static Method ON_UP;       // onUpEvent(int x, int y, long t)
    private static Method ON_CANCEL;   // onCancelEvent(int x, int y, long t)

    private SyntheticPointerTracker() {}

    // Initialize PointerTracker dependencies and cache private methods.
    public static void initForTests(final TypedArray attrs, final TimerProxy timerProxy, final DrawingProxy drawingProxy) {
        PointerTracker.init(attrs, timerProxy, drawingProxy);
        ensureMethods();
    }

    // Convenience: use DEFAULT_POINTER_ID
    public static void down(final int x, final int y, final KeyDetector keyDetector) {
        down(DEFAULT_POINTER_ID, x, y, SystemClock.uptimeMillis(), keyDetector);
    }

    public static void move(final int x, final int y) {
        move(DEFAULT_POINTER_ID, x, y, SystemClock.uptimeMillis());
    }

    public static void up(final int x, final int y) {
        up(DEFAULT_POINTER_ID, x, y, SystemClock.uptimeMillis());
    }

    public static void cancel() {
        cancel(DEFAULT_POINTER_ID, 0, 0, SystemClock.uptimeMillis());
    }

    // Full control: explicit pointerId and eventTime
    public static void down(final int pointerId, final int x, final int y, final long eventTime, final KeyDetector keyDetector) {
        try {
            final PointerTracker t = PointerTracker.getPointerTracker(pointerId);
            ensureMethods();
            ON_DOWN.invoke(t, x, y, eventTime, keyDetector);
        } catch (Throwable th) {
            throw rethrow(th);
        }
    }

    public static void move(final int pointerId, final int x, final int y, final long eventTime) {
        try {
            final PointerTracker t = PointerTracker.getPointerTracker(pointerId);
            ensureMethods();
            ON_MOVE.invoke(t, x, y, eventTime, (MotionEvent) null);
        } catch (Throwable th) {
            throw rethrow(th);
        }
    }

    public static void up(final int pointerId, final int x, final int y, final long eventTime) {
        try {
            final PointerTracker t = PointerTracker.getPointerTracker(pointerId);
            ensureMethods();
            ON_UP.invoke(t, x, y, eventTime);
        } catch (Throwable th) {
            throw rethrow(th);
        }
    }

    public static void cancel(final int pointerId, final int x, final int y, final long eventTime) {
        try {
            final PointerTracker t = PointerTracker.getPointerTracker(pointerId);
            ensureMethods();
            ON_CANCEL.invoke(t, x, y, eventTime);
        } catch (Throwable th) {
            throw rethrow(th);
        }
    }

    private static void ensureMethods() {
        if (ON_DOWN != null) return;
        try {
            ON_DOWN = PointerTracker.class.getDeclaredMethod(
                "onDownEvent", int.class, int.class, long.class, KeyDetector.class);
            ON_MOVE = PointerTracker.class.getDeclaredMethod(
                "onMoveEvent", int.class, int.class, long.class, MotionEvent.class);
            ON_UP = PointerTracker.class.getDeclaredMethod(
                "onUpEvent", int.class, int.class, long.class);
            ON_CANCEL = PointerTracker.class.getDeclaredMethod(
                "onCancelEvent", int.class, int.class, long.class);
            ON_DOWN.setAccessible(true);
            ON_MOVE.setAccessible(true);
            ON_UP.setAccessible(true);
            ON_CANCEL.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("PointerTracker signature changed", e);
        }
    }

    private static RuntimeException rethrow(final Throwable th) {
        if (th instanceof RuntimeException) return (RuntimeException) th;
        final Throwable cause = th.getCause();
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        return new RuntimeException(th);
    }
}
