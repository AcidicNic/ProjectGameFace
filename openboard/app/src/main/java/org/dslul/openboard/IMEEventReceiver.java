package org.dslul.openboard;

import static org.dslul.openboard.inputmethod.latin.utils.DeviceProtectedUtils.getSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;

import org.dslul.openboard.inputmethod.keyboard.Key;
import org.dslul.openboard.inputmethod.keyboard.MainKeyboardView;
import org.dslul.openboard.inputmethod.latin.LatinIME;
import org.dslul.openboard.inputmethod.latin.settings.Settings;
import org.dslul.openboard.inputmethod.keyboard.Keyboard;

public class IMEEventReceiver extends BroadcastReceiver {
    private static final String TAG = "HeadBoardReceiver";

    public static final String HEADBOARD_PACKAGE_NAME = "com.google.projectgameface";

    public static final String ACTION_SEND_MOTION_EVENT = "com.headswype.ACTION_SEND_EVENT";
    public static final String ACTION_SEND_KEY_EVENT = "com.headswype.ACTION_SEND_KEY_EVENT";
    public static final String ACTION_SET_LONG_PRESS_DELAY = "com.headswype.ACTION_SET_LONG_PRESS_DELAY";
    public static final String ACTION_CHANGE_TRAIL_COLOR = "com.headswype.ACTION_CHANGE_TRAIL_COLOR";
    public static final String ACTION_GET_KEY_INFO = "com.headswype.ACTION_GET_KEY_INFO";
    public static final String ACTION_GET_KEY_BOUNDS = "com.headswype.ACTION_GET_KEY_BOUNDS";
    public static final String ACTION_SHOW_OR_HIDE_KEY_POPUP = "com.headswype.ACTION_SHOW_OR_HIDE_KEY_POPUP";
    public static final String ACTION_HIGHLIGHT_KEY = "com.headswype.ACTION_HIGHLIGHT_KEY";

    private LatinIME mIme;
    private static IMEEventReceiver sInstance;

    // Zero-argument constructor required for manifest registration
    public IMEEventReceiver() {
        sInstance = this;
    }

    // Constructor for dynamic registration with LatinIME instance
    public IMEEventReceiver(LatinIME ime) {
        mIme = ime;
        sInstance = this;
    }

    public static void setLatinIME(LatinIME ime) {
        if (sInstance != null) {
            Log.d(TAG, "Setting LatinIME instance in IMEEventReceiver");
            sInstance.mIme = ime;
        } else {
            Log.e(TAG, "IMEEventReceiver instance is null. Cannot set LatinIME.");
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // If this receiver was created via manifest and mIme is null,
        // try to get the LatinIME instance from the context
        if (context instanceof LatinIME) {
            mIme = (LatinIME) context;
        }

        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Received action: " + action);

        switch (action) {
            case ACTION_SEND_MOTION_EVENT:
                handleMotionEvent(context, intent);
                break;
            case ACTION_SEND_KEY_EVENT:
                handleKeyEvent(intent);
                break;
            case ACTION_SET_LONG_PRESS_DELAY:
                handleSetLongPressDelay(intent);
                break;
            case ACTION_CHANGE_TRAIL_COLOR:
                handleTrailColorChange(context, intent);
                break;
            case ACTION_GET_KEY_INFO:
                handleGetKeyInfo(intent);
                break;
            case ACTION_GET_KEY_BOUNDS:
                handleGetKeyBounds(intent);
                break;
            case ACTION_SHOW_OR_HIDE_KEY_POPUP:
                showOrHideKeyPopup(intent);
                break;
        }
    }

    /**
     * Handles motion events sent from the external application.
     * @param intent The intent containing the motion event data.
     */
    private void handleMotionEvent(Context context, Intent intent) {
        float x = intent.getFloatExtra("x", -1);
        float y = intent.getFloatExtra("y", -1);
        int action = intent.getIntExtra("action", MotionEvent.ACTION_DOWN);
//            long downTime = intent.getLongExtra("downTime", SystemClock.uptimeMillis());
//            long eventTime = intent.getLongExtra("eventTime", SystemClock.uptimeMillis());

        Log.d(TAG, "Received MotionEvent: (" + x + ", " + y + ", action=" + action + ")");

        if (context == null || !(context instanceof LatinIME)) {
            Log.e(TAG, "[handleMotionEvent()] LatinIME instance is null. Cannot dispatch motion event.");
            return;
        } else if (x < 0 || y < 0) {
            Log.e(TAG, "[handleMotionEvent()] invalid (x, y) coords: (" + x + ", " + y + ")");
            return;
        }

        // Forward event to LatinIME
        ((LatinIME) context).dispatchMotionEvent(x, y, action);
    }

    /**
     * Handles key events sent from the external application.
     * @param intent The intent containing the key event data.
     */
    private void handleKeyEvent(Intent intent) {
        int keyCode = intent.getIntExtra("keyCode", -1);
        boolean isDown = intent.getBooleanExtra("isDown", true);
        boolean isLongPress = intent.getBooleanExtra("isLongPress", false);

        Log.d(TAG, "Received key event: keyCode=" + keyCode +
                ", isDown=" + isDown +
                ", isLongPress=" + isLongPress);

        // Forward event to LatinIME
        if (mIme != null) {
            mIme.dispatchKeyEvent(keyCode, isDown, isLongPress);
        } else {
            Log.e(TAG, "LatinIME instance is null. Cannot dispatch key event.");
        }
    }

    /**
     * Handles setting the long press delay for the keyboard.
     * @param intent The intent containing the delay value.
     */
    private void handleSetLongPressDelay(Intent intent) {
        if (mIme == null) return;

        int delay = intent.getIntExtra("delay", -1);
        if (delay < 0) return;

        final SharedPreferences prefs = getSharedPreferences(mIme);
        prefs.edit().putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, delay).apply();
        Log.d(TAG, "Long press delay to: " + delay + " ms");
    }

    /**
     * Handles changing the gesture trail color.
     * Changes the color of the trail for the CURRENT gesture. Color will revert to default on next gesture.
     * @param context The context of the application.
     * @param intent The intent containing the color value.
     */
    private void handleTrailColorChange(Context context, Intent intent) {
        String colorName = intent.getStringExtra("color");
        int color;
        
        switch(colorName) {
            case "green":
                color = Color.GREEN;
                break;
            case "orange":
                color = Color.rgb(255, 165, 0); // Orange
                break;
            case "red":
                color = Color.RED;
                break;
            default:
                color = Color.GREEN; // Default color
                break;
        }

        Log.d(TAG, "Changing gesture trail color to: " + colorName);

        if (mIme != null) {
            mIme.setGestureTrailColor(color);
        } else {
            Log.e(TAG, "LatinIME instance is null. Cannot change trail color.");
        }
    }

    /**
     * Handles getting key information at specific coordinates.
     * @param intent The intent containing the coordinates.
     */
    private void handleGetKeyInfo(Intent intent) {
        if (mIme == null) {
            Log.e(TAG, "LatinIME instance is null. Cannot get key info.");
            return;
        }

        float x = intent.getFloatExtra("x", -1);
        float y = intent.getFloatExtra("y", -1);

        if (x < 0 || y < 0) {
            Log.e(TAG, "Invalid coordinates provided: (" + x + ", " + y + ")");
            return;
        }

        Key key = mIme.getKeyFromCoords(x, y);

    }

    /**
     * Handles getting key bounds for a specific key code.
     * @param intent The intent containing the key code.
     */
    private void handleGetKeyBounds(Intent intent) {
        if (mIme == null) {
            Log.e(TAG, "LatinIME instance is null. Cannot get key bounds.");
            return;
        }

        int keyCode = intent.getIntExtra("keyCode", -1);
        if (keyCode < 0) {
            Log.e(TAG, "Invalid key code provided: " + keyCode);
            return;
        }

        MainKeyboardView mainKeyboardView = mIme.mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            Log.e(TAG, "MainKeyboardView is null. Cannot get key bounds.");
            return;
        }

        // Get the keyboard and find the key with the specified code
        Keyboard keyboard = mainKeyboardView.getKeyboard();
        if (keyboard == null) {
            Log.e(TAG, "Keyboard is null. Cannot get key bounds.");
            return;
        }

        Key targetKey = null;
        for (Key key : keyboard.getSortedKeys()) {
            if (key.getCode() == keyCode) {
                targetKey = key;
                break;
            }
        }

        if (targetKey == null) {
            Log.d(TAG, "No key found with code: " + keyCode);
            return;
        }

        // Get the key's bounds in screen coordinates
        int[] location = new int[2];
        mainKeyboardView.getLocationOnScreen(location);
        Rect keyBounds = targetKey.getHitBox();

        sendKeyBounds(keyBounds);

        // Log the key information
        Log.d(TAG, String.format(
            "Key with code %d:\n" +
            "  Label: %s\n" +
            "  Bounds: [%d, %d, %d, %d]",
            keyCode,
            targetKey.getLabel(),
            keyBounds.left,
            keyBounds.top,
            keyBounds.right,
            keyBounds.bottom
        ));
    }

    public void showOrHideKeyPopup(Intent intent) {
        Log.d(TAG, "showOrHideKeyPopup()");
        if (mIme == null) {
            Log.e(TAG, "LatinIME instance is null. Cannot show/hide key popup.");
            return;
        }

        int x = intent.getIntExtra("x", -1);
        int y = intent.getIntExtra("y", -1);
        if (x < 0 || y < 0) {
            Log.e(TAG, "Invalid coordinates provided for key popup");
            return;
        }
        boolean showKeyPreview = intent.getBooleanExtra("showKeyPreview", false);
        boolean withAnimation = intent.getBooleanExtra("withAnimation", false);
        boolean isLongPress = intent.getBooleanExtra("isLongPress", false);

        mIme.showOrHideKeyPopup(showKeyPreview, new int[] {x, y}, withAnimation, isLongPress);
    }

    public void highlightKey(Intent intent) {
        Log.d(TAG, "showOrHideKeyPopup()");
        if (mIme == null) {
            Log.e(TAG, "LatinIME instance is null. Cannot show/hide key popup.");
            return;
        }

        int x = intent.getIntExtra("x", -1);
        int y = intent.getIntExtra("y", -1);
        if (x < 0 || y < 0) {
            Log.e(TAG, "Invalid coordinates provided for key popup");
            return;
        }
        boolean showKeyPreview = intent.getBooleanExtra("showKeyPreview", false);
        boolean withAnimation = intent.getBooleanExtra("withAnimation", false);
        boolean isLongPress = intent.getBooleanExtra("isLongPress", false);

        mIme.showOrHideKeyPopup(showKeyPreview, new int[] {x, y}, withAnimation, isLongPress);
    }

    /**
     * Sends a broadcast request to the external application.
     * @param action The action string for the broadcast.
     * @param extras The extras to include in the broadcast.
     */
    public void sendResponse(String action, Bundle extras) {
        Intent intent = new Intent(action);

        if (extras != null) intent.putExtras(extras);

        intent.setPackage(HEADBOARD_PACKAGE_NAME);

        mIme.sendBroadcast(intent);

        Log.d(TAG, "Sent broadcast request with action: " + action);
    }

    public void sendKeyInfo(float x, float y) {
        Bundle extras = new Bundle();
        //  TODO: Add the key information to the extras
        sendResponse("org.dslul.openboard.ACTION_GET_KEY_INFO", extras);
    }

    public void sendKeyBounds(Rect keyBounds) {
        Bundle extras = new Bundle();
        extras.putInt("top", keyBounds.top);
        extras.putInt("left", keyBounds.left);
        extras.putInt("bottom", keyBounds.bottom);
        extras.putInt("right", keyBounds.right);
        sendResponse("org.dslul.openboard.ACTION_GET_KEY_BOUNDS", extras);
    }
}
