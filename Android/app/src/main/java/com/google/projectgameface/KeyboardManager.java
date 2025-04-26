package com.google.projectgameface;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.core.content.ContextCompat;

import com.google.projectgameface.utils.DebuggingStats;

import java.util.List;

/**
 * Manages keyboard-related functionality including bounds detection, type detection,
 * and event injection.
 */
public class KeyboardManager {
    private static final String TAG = "KeyboardManager";
    private final Context context;
    private final CursorController cursorController;
    private final ServiceUiManager serviceUiManager;
    private final DebuggingStats gboardDebuggingStats;
    private final DebuggingStats openboardDebuggingStats;
    private DebuggingStats currentDebuggingStats;
    private WindowManager windowManager;
    private Point screenSize;

    private Rect keyboardBounds = new Rect();
    private Rect _keyboardBounds = new Rect();
    private Rect navBarBounds = new Rect();
    private boolean isKeyboardOpen = false;
    private String currentKeyboard = "Unknown";

    public KeyboardManager(Context context, CursorController cursorController, 
                         ServiceUiManager serviceUiManager) {
        this.context = context;
        this.cursorController = cursorController;
        this.serviceUiManager = serviceUiManager;
        this.gboardDebuggingStats = new DebuggingStats("GBoard");
        this.openboardDebuggingStats = new DebuggingStats("OpenBoard");
        this.currentDebuggingStats = gboardDebuggingStats;
        this.windowManager = ContextCompat.getSystemService(this.context, WindowManager.class);
        this.screenSize = new Point();
        windowManager.getDefaultDisplay().getRealSize(screenSize);
    }

    /**
     * Check for keyboard bounds and update the cursor controller's temporary bounds if necessary.
     * This method is called when an accessibility event occurs.
     *
     * @param event The accessibility event to check.
     */
    public void checkForKeyboardBounds(AccessibilityEvent event) {
        if (cursorController.isSwiping) return;

        boolean keyboardFound = false;
        Rect tempBounds = new Rect();

        List<AccessibilityWindowInfo> windows = ((CursorAccessibilityService) context).getWindows();
        for (AccessibilityWindowInfo window : windows) {
            window.getBoundsInScreen(tempBounds);
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                keyboardFound = true;
                window.getBoundsInScreen(_keyboardBounds);
                window.getBoundsInScreen(keyboardBounds);
                if (keyboardBounds.equals(cursorController.getTemporaryBounds())) {
                    return;
                }
                Log.d(TAG, "keyboard Found @ : " + window);
            } else if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM
                    && window.getTitle() != null && window.getTitle().equals("Navigation bar")) {
                window.getBoundsInScreen(navBarBounds);
            }
        }

        if (keyboardFound == isKeyboardOpen && keyboardBounds.equals(cursorController.getTemporaryBounds())) {
            return;
        }
        isKeyboardOpen = keyboardFound;

        if (isKeyboardOpen) {
            cursorController.setTemporaryBounds(keyboardBounds);
            checkForKeyboardType();
        } else {
            cursorController.clearTemporaryBounds();
        }
    }

    /**
     * Check for the current keyboard type and update the debugging stats accordingly.
     * This method is called when an accessibility event occurs.
     */
    public void checkForKeyboardType() {
        String currentKeyboardStr = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD
        );
        if (currentKeyboardStr.toLowerCase().contains("openboard")) {
            currentKeyboard = "OpenBoard";
            currentDebuggingStats = openboardDebuggingStats;
        } else if (currentKeyboardStr.toLowerCase().contains("google")) {
            currentKeyboard = "GBoard";
            currentDebuggingStats = gboardDebuggingStats;
        } else {
            currentKeyboard = "Unknown";
        }
    }

    /**
     * Check if events can be injected into the window at (x, y)
     *
     * @param x The x coordinate of the touch event
     * @param y The y coordinate of the touch event
     * @return true if the event can be injected, false otherwise
     */
    public boolean canInjectEvent(float x, float y) {
        for (AccessibilityWindowInfo window : ((CursorAccessibilityService) context).getWindows()) {
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);

            if (isInjectableWindow(window)) {
                if (bounds.contains((int) x, (int) y)) {
                    cursorController.checkForSwipingFromRightKbd = false;
                    return true;
                } else if (isKeyboardOpen && y >= keyboardBounds.top) {
                    if (x == 0) {
                        x = 1;
                    } else if (x > screenSize.x - 1) {
                        x = screenSize.x - 1;
                        cursorController.checkForSwipingFromRightKbd = true;
                    }
                    if (bounds.contains((int) x, (int) y)) {
                        return true;
                    } else {
                        cursorController.checkForSwipingFromRightKbd = false;
                    }
                }
            }
        }

        cursorController.checkForSwipingFromRightKbd = false;
        return false;
    }

    /**
     * Check if the given window is injectable.
     *
     * @param window The AccessibilityWindowInfo to check.
     * @return true if the window is injectable, false otherwise.
     */
    private boolean isInjectableWindow(AccessibilityWindowInfo window) {
        if (window == null) {
            return false;
        }

        AccessibilityNodeInfo rootNode = window.getRoot();
        if (rootNode == null) {
            return false;
        }

        CharSequence packageName = rootNode.getPackageName();
        if (packageName == null) {
            return false;
        }

        return isMyAppPackage(packageName.toString());
    }

    /**
     * Check if the package name belongs to the app.
     *
     * @param packageName The package name to check.
     * @return true if the package name belongs to the app, false otherwise.
     */
    private boolean isMyAppPackage(String packageName) {
        String[] myApps = {
                "org.dslul.openboard.inputmethod.latin"
        };

        for (String myApp : myApps) {
            if (packageName.equals(myApp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inject a motion event into the system.
     *
     * @param event The motion event to inject.
     */
    public void sendMotionEventToIME(MotionEvent event) {
        Log.d(TAG, "[openboard] Sending MotionEvent to IME");
        Intent intent = new Intent("com.headswype.ACTION_SEND_EVENT");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("x", event.getX());
        intent.putExtra("y", event.getY());
        intent.putExtra("action", event.getAction());
        intent.putExtra("downTime", event.getDownTime());
        intent.putExtra("eventTime", event.getEventTime());
        context.sendBroadcast(intent, "com.headswype.permission.SEND_EVENT");
    }

    /**
     * Send key event to OpenBoard IME to simulate virtual keyboard key presses
     *
     * @param keyCode The key code to send.
     * @param isDown Whether the key is pressed down or released.
     * @param isLongPress Whether the key is a long press.
     */
    public void sendKeyEventToIME(int keyCode, boolean isDown, boolean isLongPress) {
        Log.d(TAG, "[openboard] Sending keyEvent to IME");
        Intent intent = new Intent("com.headswype.ACTION_SEND_KEY_EVENT");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("keyCode", keyCode);
        intent.putExtra("isDown", isDown);
        intent.putExtra("isLongPress", isLongPress);
        context.sendBroadcast(intent, "com.headswype.permission.SEND_EVENT");
    }

    /**
     * Send gesture trail color to OpenBoard IME.
     *
     * @param color The color to send. ("green", "red", "orange")
     */
    public void sendGestureTrailColorToIME(String color) {
        Log.d(TAG, "[openboard] Sending gesture trail color to IME");
        Intent intent = new Intent("com.headswype.ACTION_CHANGE_TRAIL_COLOR");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("color", color);
        context.sendBroadcast(intent, "com.headswype.permission.SEND_EVENT");
    }

    /**
     * Send long press delay to OpenBoard IME.
     *
     * @param delay The long press delay in milliseconds.
     */
    public void sendLongPressDelayToIME(int delay) {
        Log.d(TAG, "[openboard] Sending long press delay to IME");
        Intent intent = new Intent("com.headswype.ACTION_SET_LONG_PRESS_DELAY");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("delay", delay);
        context.sendBroadcast(intent, "com.headswype.permission.SEND_EVENT");
    }

    // Getters and setters
    public Rect getKeyboardBounds() {
        return keyboardBounds;
    }

    public boolean isKeyboardOpen() {
        return isKeyboardOpen;
    }

    public String getCurrentKeyboard() {
        return currentKeyboard;
    }

    public DebuggingStats getCurrentDebuggingStats() {
        return currentDebuggingStats;
    }
} 