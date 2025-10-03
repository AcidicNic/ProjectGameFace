package com.google.projectgameface;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
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
    private Rect navBarBounds = new Rect();
    private boolean isKeyboardOpen = false;
    private String currentKeyboard = "Unknown";

    public KeyboardManager(
        Context context,
        CursorController cursorController,
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
     * @param event The accessibility event to check.
     */
    public void checkForKeyboardBounds() {

        boolean keyboardFound = false;
        boolean navBarFound = false;
        Rect tempBounds = new Rect();

        List<AccessibilityWindowInfo> windows = ((CursorAccessibilityService) context).getWindows();
        for (AccessibilityWindowInfo window: windows) {
            window.getBoundsInScreen(tempBounds);
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
//                AccessibilityNodeInfo root = window.getRoot();
//                if (root != null) {
////                    Log.d(TAG, "[checkForKeyboardBounds()] IME: " + window);
//                    // Find main keyboard view by searching through IME nodes
//                    AccessibilityNodeInfo keyboardView = findChildNodeWithViewId(
//                        root,
//                        Config.OPENBOARD_KDB_VIEW_ID);
//
////                    LogKeyboardViews(root); // Log all keyboard views for debugging
//                    if (keyboardView != null) {
//                        keyboardFound = true;
////                        Log.d(TAG, "[checkForKeyboardBounds()] KBD VIEW: " + keyboardView);
//                        keyboardView.getBoundsInScreen(keyboardBounds);
//                        keyboardView.recycle();
//                        break;
//                    }
//                }

                if (tempBounds.top > screenSize.y / 2) {
                    keyboardFound = true;
                    window.getBoundsInScreen(keyboardBounds);
                }

//                root.recycle();
            } else if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && window.getTitle() != null &&
                window.getTitle().equals("Navigation bar")) {
                navBarFound = true;
                window.getBoundsInScreen(navBarBounds);
            }
        }

        if (isKeyboardOpen == keyboardFound && keyboardBounds.equals(cursorController.getKeyboardBounds())) {
            return;
        }
        isKeyboardOpen = keyboardFound;

        if (navBarFound) {
            Log.d(TAG, "[checkForKeyboardBounds()] set nav bar: " + navBarBounds);
            cursorController.setNavBarBounds(navBarBounds);
        } else if (cursorController.getNavBarBounds().isEmpty()) {
            // clear the nav bar bounds if it was not found
//            Log.d(TAG, "[checkForKeyboardBounds()] clear nav bar");
            cursorController.clearNavBarBounds();
        }

        if (isKeyboardOpen) {
            Log.d(TAG, "[checkForKeyboardBounds()] set kbd: " + keyboardBounds);
            cursorController.setKeyboardBounds(keyboardBounds);
            checkForKeyboardType();
        } else if (!cursorController.getNavBarBounds().isEmpty()) {
            // clear the kbd bounds
//            Log.d(TAG, "[checkForKeyboardBounds()] clear kbd");
            cursorController.clearKeyboardBounds();
        }
    }

    private AccessibilityNodeInfo findChildNodeWithViewId(AccessibilityNodeInfo root, String targetViewId) {
        if (root == null) return null;

        // Check if this is the keyboard view by resource ID
        String viewId = root.getViewIdResourceName();
        if (viewId != null && viewId.equals(targetViewId)) {
            return root;
        }

        // Recursively search children
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findChildNodeWithViewId(child, targetViewId);
                if (result != null) {
                    child.recycle();
                    return result;
                }
                child.recycle();
            }
        }

        return null;
    }

    private AccessibilityNodeInfo LogKeyboardViews(AccessibilityNodeInfo root) {
        if (root == null) return null;

        String viewId = root.getViewIdResourceName();
        if (root.getChildCount() > 0) {
            Log.d("LogKeyboardViews", "* Node with " + root.getChildCount() + " children: " + root);
        } else {
            Log.d("LogKeyboardViews", "** Childless Node: " + root + " with no children");
        }

        // Recursively search children
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo result = LogKeyboardViews(child);
        }

        return null;  // Always return null for this debugging version
    }

    /**
     * Check for the current keyboard type and update the debugging stats accordingly.
     * This method is called when an accessibility event occurs.
     */
    public String checkForKeyboardType() {
        String currentKeyboardStr = Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD);
        if (currentKeyboardStr.toLowerCase().contains("openboard")) {
            currentKeyboard = "OpenBoard";
            currentDebuggingStats = openboardDebuggingStats;
        } else if (currentKeyboardStr.toLowerCase().contains("google")) {
            currentKeyboard = "GBoard";
            currentDebuggingStats = gboardDebuggingStats;
        } else {
            currentKeyboard = "Unknown";
        }
        return currentKeyboard;
    }

    /**
     * Check if events can be injected into the window at (x, y)
     * @param x The x coordinate of the touch event
     * @param y The y coordinate of the touch event
     * @return true if the event can be injected, false otherwise
     */
    public boolean canInjectEvent(float x, float y) {
        for (AccessibilityWindowInfo window: ((CursorAccessibilityService) context).getWindows()) {
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
     * @param packageName The package name to check.
     * @return true if the package name belongs to the app, false otherwise.
     */
    private boolean isMyAppPackage(String packageName) {
        String[] myApps = {"org.dslul.openboard.inputmethod.latin"};

        for (String myApp: myApps) {
            if (packageName.equals(myApp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inject a motion event into OpenBoard.
     * @param x      The x coordinate of the touch event
     * @param y      The y coordinate of the touch event
     * @param action The action of the touch event (e.g., MotionEvent.ACTION_DOWN)
     */
    public void sendMotionEventToIME(int x, int y, int action) {
//        Log.d(TAG, "[openboard] Sending MotionEvent to IME - (" + x + ", " + y + ") action: " + action);
        Intent intent = new Intent("com.headswype.ACTION_SEND_EVENT");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("x", (float) x);
        intent.putExtra("y", (float) y);
        intent.putExtra("action", action);
//        intent.putExtra("downTime", event.getDownTime());
//        intent.putExtra("eventTime", event.getEventTime());
        sendBroadcastToIME(intent);
    }

    /**
     * Send key event to OpenBoard IME to simulate virtual keyboard key presses
     * @param keyCode     The key code to send.
     * @param isDown      Whether the key is pressed down or released.
     * @param isLongPress Whether the key is a long press.
     */
    public void sendKeyEventToIME(int keyCode, boolean isDown, boolean isLongPress) {
        Log.d(TAG, "[openboard] Sending keyEvent to IME - keyCode: " + keyCode + ", isDown: " + isDown +
            ", isLongPress: " + isLongPress);
        Intent intent = new Intent("com.headswype.ACTION_SEND_KEY_EVENT");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("keyCode", keyCode);
        intent.putExtra("isDown", isDown);
        intent.putExtra("isLongPress", isLongPress);
        sendBroadcastToIME(intent);
    }

    /**
     * Send gesture trail color to OpenBoard IME.
     * @param color The color to send. ("green", "red", "orange")
     */
    public void sendGestureTrailColorToIME(String color) {
        Log.d(TAG, "[openboard] Sending gesture trail color to IME - " + color);
        Intent intent = new Intent("com.headswype.ACTION_CHANGE_TRAIL_COLOR");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("color", color);
        sendBroadcastToIME(intent);
    }

    /**
     * Send long press delay to OpenBoard IME.
     * @param delay The long press delay in milliseconds.
     */
    public void sendLongPressDelayToIME(int delay) {
        Log.d(TAG, "[openboard] Sending long press delay to IME - " + delay + "ms");
        Intent intent = new Intent("com.headswype.ACTION_SET_LONG_PRESS_DELAY");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("delay", delay);
        sendBroadcastToIME(intent);
    }

    public void getKeyInfoFromIME(int x, int y) {
        Log.d(TAG, "[openboard] Get Key Info from IME - (" + x + ", " + y + ")");
        Intent intent = new Intent("com.headswype.ACTION_GET_KEY_INFO");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("x", x);
        intent.putExtra("y", y);
        sendBroadcastToIME(intent);
    }

    private void showOrHideKeyPopupIME(int x, int y, boolean showKeyPreview, boolean withAnimation, boolean isLongPress) {
        int adjustedX = x - keyboardBounds.left;
        int adjustedY = y - keyboardBounds.top;

        Intent intent = new Intent("com.headswype.ACTION_SHOW_OR_HIDE_KEY_POPUP");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("x", adjustedX);
        intent.putExtra("y", adjustedY);
        intent.putExtra("showKeyPreview", showKeyPreview);
        intent.putExtra("withAnimation", withAnimation);
        intent.putExtra("isLongPress", isLongPress);
        sendBroadcastToIME(intent);
    }

    private void sendBroadcastToIME(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE /* API 34 */) {
            context.sendOrderedBroadcast(intent, "com.headboard.permission.SEND_EVENT");
        } else {
            context.sendBroadcast(intent, "com.headboard.permission.SEND_EVENT");
        }
    }

    public void showKeyPopupIME(int x, int y, boolean withAnimation) {
        Log.d(TAG, "showKeyPopupIME() - (" + x + ", " + y + ") withAnimation: " + withAnimation);
        showOrHideKeyPopupIME(x, y, true, withAnimation, false);
    }

    public void showAltKeyPopupIME(int x, int y) {
        Log.d(TAG, "showAltKeyPopupIME() - (" + x + ", " + y + ")");
        showOrHideKeyPopupIME(x, y, true, false, true);
    }

    public void hideKeyPopupIME(int x, int y, boolean withAnimation) {
        Log.d(TAG, "hideKeyPopupIME() - (" + x + ", " + y + ") withAnimation: " + withAnimation);
        showOrHideKeyPopupIME(x, y, false, withAnimation, false);
    }

    public void hideAltKeyPopupIME(int x, int y) {
        Log.d(TAG, "hideAltKeyPopupIME() - (" + x + ", " + y + ")");
        showOrHideKeyPopupIME(x, y, false, false, true);
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

    // TODO: Implement this method to get key bounds from IME (or via searching accessibilityNodeInfo?)
    public Rect getKeyBounds(int[] swipeStartPosition) {
        return null;
    }

    // TODO: send highlight request to IME
    public void highlightKeyAt(int x, int y) {
        Log.d(TAG, "[openboard] Highlight key at - (" + x + ", " + y + ")");
        Intent intent = new Intent("com.headswype.ACTION_HIGHLIGHT_KEY");
        intent.setPackage("org.dslul.openboard.inputmethod.latin");
        intent.putExtra("x", (float) x);
        intent.putExtra("y", (float) y);
        sendBroadcastToIME(intent);
    }
}
