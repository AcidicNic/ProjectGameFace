package org.dslul.openboard;

import static org.dslul.openboard.inputmethod.latin.utils.DeviceProtectedUtils.getSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;

import org.dslul.openboard.inputmethod.keyboard.Key;
import org.dslul.openboard.inputmethod.keyboard.MainKeyboardView;
import org.dslul.openboard.inputmethod.latin.LatinIME;
import org.dslul.openboard.inputmethod.latin.settings.Settings;
import org.dslul.openboard.inputmethod.keyboard.Keyboard;

/**
 * BroadcastReceiver that handles communication between HeadBoard and the OpenBoard IME.
 */
public class IMEEventReceiver extends BroadcastReceiver {
    private static final String TAG = "IMEEventReceiver";
    public static final String HEADBOARD_PACKAGE_NAME = "com.google.projectgameface";
    
    // Actions for incoming broadcasts
    public static final String ACTION_SEND_MOTION_EVENT = "com.headswype.ACTION_SEND_EVENT";
    public static final String ACTION_SEND_KEY_EVENT = "com.headswype.ACTION_SEND_KEY_EVENT";
    public static final String ACTION_SET_LONG_PRESS_DELAY = "com.headswype.ACTION_SET_LONG_PRESS_DELAY";
    public static final String ACTION_CHANGE_TRAIL_COLOR = "com.headswype.ACTION_CHANGE_TRAIL_COLOR";
    public static final String ACTION_GET_KEY_INFO = "com.headswype.ACTION_GET_KEY_INFO";
    public static final String ACTION_GET_KEY_BOUNDS = "com.headswype.ACTION_GET_KEY_BOUNDS";
    public static final String ACTION_SHOW_OR_HIDE_KEY_POPUP = "com.headswype.ACTION_SHOW_OR_HIDE_KEY_POPUP";
    public static final String ACTION_HIGHLIGHT_KEY = "com.headswype.ACTION_HIGHLIGHT_KEY";
    
    // Response actions
    private static final String RESPONSE_ACTION_GET_KEY_INFO = "org.dslul.openboard.ACTION_GET_KEY_INFO";
    private static final String RESPONSE_ACTION_GET_KEY_BOUNDS = "org.dslul.openboard.ACTION_GET_KEY_BOUNDS";
    
    // Intent extra keys
    private static final String EXTRA_X = "x";
    private static final String EXTRA_Y = "y";
    private static final String EXTRA_ACTION = "action";
    private static final String EXTRA_KEY_CODE = "keyCode";
    private static final String EXTRA_IS_DOWN = "isDown";
    private static final String EXTRA_IS_LONG_PRESS = "isLongPress";
    private static final String EXTRA_DELAY = "delay";
    private static final String EXTRA_COLOR = "color";
    private static final String EXTRA_SHOW_KEY_PREVIEW = "showKeyPreview";
    private static final String EXTRA_WITH_ANIMATION = "withAnimation";
    
    // Bundle keys for responses
    private static final String BUNDLE_TOP = "top";
    private static final String BUNDLE_LEFT = "left";
    private static final String BUNDLE_RIGHT = "right";
    private static final String BUNDLE_BOTTOM = "bottom";
    
    // Color constants
    private static final String COLOR_GREEN = "green";
    private static final String COLOR_ORANGE = "orange";
    private static final String COLOR_RED = "red";
    
    // Default/invalid values
    private static final float INVALID_COORDINATE = -1f;
    private static final int INVALID_INT = -1;

    private LatinIME mIme;
    private static IMEEventReceiver sInstance;

    /**
     * Zero arg constructor required for manifest registration.
     */
    public IMEEventReceiver() {
        sInstance = this;
        Log.d(TAG, "IMEEventReceiver created via manifest registration");
    }

    /**
     * Constructor for dynamic registration with LatinIME instance.
     * @param ime The LatinIME instance to associate with this receiver
     */
    public IMEEventReceiver(LatinIME ime) {
        if (ime == null) {
            throw new IllegalArgumentException("LatinIME instance cannot be null");
        }
        mIme = ime;
        sInstance = this;
        Log.d(TAG, "IMEEventReceiver created with LatinIME instance");
    }

    /**
     * Sets the LatinIME instance for the singleton receiver.
     * This method should be called when the receiver is created via manifest registration.
     * @param ime The LatinIME instance to set
     * @throws IllegalStateException if the singleton instance is null
     */
    public static void setLatinIME(LatinIME ime) {
        if (ime == null) {
            Log.e(TAG, "Cannot set null LatinIME instance");
            return;
        }
        
        if (sInstance != null) {
            Log.d(TAG, "Setting LatinIME instance in IMEEventReceiver");
            sInstance.mIme = ime;
        } else {
            Log.e(TAG, "IMEEventReceiver instance is null. Cannot set LatinIME.");
            throw new IllegalStateException("IMEEventReceiver singleton instance is null");
        }
    }

    public static IMEEventReceiver getInstance() {
        return sInstance;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            Log.e(TAG, "Received null context or intent");
            return;
        }

        // If this receiver was created via manifest and mIme is null,
        // try to get the LatinIME instance from the context
        if (mIme == null && context instanceof LatinIME) {
            mIme = (LatinIME) context;
            Log.d(TAG, "Retrieved LatinIME instance from context");
        }

        String action = intent.getAction();
        if (action == null) {
            Log.w(TAG, "Received intent with null action");
            return;
        }

        Log.d(TAG, "Received action: " + action);

        try {
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
                case ACTION_GET_KEY_BOUNDS:
                    handleGetKeyBounds(intent);
                    break;
                case ACTION_SHOW_OR_HIDE_KEY_POPUP:
                    showOrHideKeyPopup(intent);
                    break;
                case ACTION_HIGHLIGHT_KEY:
                    handleHighlightKey(intent);
                    break;
                default:
                    Log.w(TAG, "Unknown action received: " + action);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling action: " + action, e);
        }
    }

    /**
     * Handles motion events sent from the external application.
     * @param context The context of the application
     * @param intent The intent containing the motion event data
     */
    private void handleMotionEvent(Context context, Intent intent) {
        if (context == null || intent == null) {
            Log.e(TAG, "handleMotionEvent: null context or intent");
            return;
        }

        float x = intent.getFloatExtra(EXTRA_X, INVALID_COORDINATE);
        float y = intent.getFloatExtra(EXTRA_Y, INVALID_COORDINATE);
        int action = intent.getIntExtra(EXTRA_ACTION, MotionEvent.ACTION_DOWN);

        Log.d(TAG, String.format("Received MotionEvent: (%.2f, %.2f, action=%d)", x, y, action));

        if (!(context instanceof LatinIME)) {
            Log.e(TAG, "handleMotionEvent: context is not LatinIME instance");
            return;
        }

        if (!isValidCoordinate(x) || !isValidCoordinate(y)) {
            Log.e(TAG, String.format("handleMotionEvent: invalid coordinates (%.2f, %.2f)", x, y));
            return;
        }

        try {
            ((LatinIME) context).dispatchMotionEvent(x, y, action);
            Log.d(TAG, "Motion event dispatched successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error dispatching motion event", e);
        }
    }

    /**
     * Handles key events sent from the external application.
     * @param intent The intent containing the key event data
     */
    private void handleKeyEvent(Intent intent) {
        if (intent == null) {
            Log.e(TAG, "handleKeyEvent: null intent");
            return;
        }

        int keyCode = intent.getIntExtra(EXTRA_KEY_CODE, INVALID_INT);
        boolean isDown = intent.getBooleanExtra(EXTRA_IS_DOWN, true);
        boolean isLongPress = intent.getBooleanExtra(EXTRA_IS_LONG_PRESS, false);

        Log.d(TAG, String.format("Received key event: keyCode=%d, isDown=%b, isLongPress=%b", 
                keyCode, isDown, isLongPress));

        if (keyCode == INVALID_INT) {
            Log.e(TAG, "handleKeyEvent: invalid key code");
            return;
        }

        if (mIme == null) {
            Log.e(TAG, "handleKeyEvent: LatinIME instance is null");
            return;
        }

        try {
            mIme.dispatchKeyEvent(keyCode, isDown, isLongPress);
            Log.d(TAG, "Key event dispatched successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error dispatching key event", e);
        }
    }

    /**
     * Validates if a coordinate value is valid (non-negative).
     * @param coordinate The coordinate value to validate
     * @return true if the coordinate is valid, false otherwise
     */
    private boolean isValidCoordinate(float coordinate) {
        return coordinate >= 0;
    }

    /**
     * Handles setting the long press delay for the keyboard.
     * @param intent The intent containing the delay value
     */
    private void handleSetLongPressDelay(Intent intent) {
        if (intent == null) {
            Log.e(TAG, "handleSetLongPressDelay: null intent");
            return;
        }

        if (mIme == null) {
            Log.e(TAG, "handleSetLongPressDelay: LatinIME instance is null");
            return;
        }

        int delay = intent.getIntExtra(EXTRA_DELAY, INVALID_INT);
        if (delay < 0) {
            Log.e(TAG, "handleSetLongPressDelay: invalid delay value: " + delay);
            return;
        }

        try {
            final SharedPreferences prefs = getSharedPreferences(mIme);
            prefs.edit().putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, delay).apply();
            Log.d(TAG, "Long press delay set to: " + delay + " ms");
        } catch (Exception e) {
            Log.e(TAG, "Error setting long press delay", e);
        }
    }

    /**
     * Handles changing the gesture trail color.
     * Changes the color of the trail for the CURRENT gesture. Color will revert to default on next gesture.
     * @param context The context of the application
     * @param intent The intent containing the color value
     */
    private void handleTrailColorChange(Context context, Intent intent) {
        if (context == null || intent == null) {
            Log.e(TAG, "handleTrailColorChange: null context or intent");
            return;
        }

        if (mIme == null) {
            Log.e(TAG, "handleTrailColorChange: LatinIME instance is null");
            return;
        }

        String colorName = intent.getStringExtra(EXTRA_COLOR);
        if (colorName == null || colorName.trim().isEmpty()) {
            Log.e(TAG, "handleTrailColorChange: null or empty color name");
            return;
        }

        int color = getColorFromName(colorName);
        Log.d(TAG, "Changing gesture trail color to: " + colorName);

        try {
            mIme.setGestureTrailColor(color);
            Log.d(TAG, "Gesture trail color changed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error changing gesture trail color", e);
        }
    }

    /**
     * Converts a color name string to the corresponding Color constant.
     * @param colorName The name of the color
     * @return The corresponding Color constant, or Color.GREEN as default
     */
    private int getColorFromName(String colorName) {
        if (colorName == null) {
            return Color.GREEN;
        }

        switch (colorName.toLowerCase().trim()) {
            case COLOR_GREEN:
                return Color.GREEN;
            case COLOR_ORANGE:
                return Color.rgb(255, 165, 0); // Orange
            case COLOR_RED:
                return Color.RED;
            default:
                Log.w(TAG, "Unknown color name: " + colorName + ", using default green");
                return Color.GREEN;
        }
    }

    /**
     * Handles getting key bounds for a specific key code.
     * @param intent The intent containing the key code
     */
    private void handleGetKeyBounds(Intent intent) {
        if (intent == null) {
            Log.e(TAG, "handleGetKeyBounds: null intent");
            return;
        }

        if (mIme == null) {
            Log.e(TAG, "handleGetKeyBounds: LatinIME instance is null");
            return;
        }

        float x = intent.getFloatExtra(EXTRA_X, INVALID_COORDINATE);
        float y = intent.getFloatExtra(EXTRA_Y, INVALID_COORDINATE);

        if (!isValidCoordinate(x) || !isValidCoordinate(y)) {
            Log.e(TAG, String.format("handleGetKeyInfo: invalid coordinates (%.2f, %.2f)", x, y));
            return;
        }

        try {
            Key targetKey = mIme.getKeyFromCoords(x, y);
            Rect keyBounds = targetKey.getHitBox();

            sendKeyBounds(keyBounds, targetKey);

            Log.d(TAG, String.format("Key found: Label=%s, Bounds=[%d, %d, %d, %d]",
                targetKey.getLabel(), keyBounds.left, keyBounds.top, keyBounds.right, keyBounds.bottom));
        } catch (Exception e) {
            Log.e(TAG, "Error getting key bounds", e);
        }
    }

    /**
     * Finds a key by its code in the given keyboard.
     * @param keyboard The keyboard to search in
     * @param keyCode The key code to find
     * @return The key with the specified code, or null if not found
     */
    private Key findKeyByCode(Keyboard keyboard, int keyCode) {
        if (keyboard == null) {
            return null;
        }

        for (Key key : keyboard.getSortedKeys()) {
            if (key.getCode() == keyCode) {
                return key;
            }
        }
        return null;
    }

    /**
     * Handles showing or hiding key popup at specific coordinates.
     * @param intent The intent containing the popup parameters
     */
    public void showOrHideKeyPopup(Intent intent) {
        if (intent == null) {
            Log.e(TAG, "showOrHideKeyPopup: null intent");
            return;
        }

        if (mIme == null) {
            Log.e(TAG, "showOrHideKeyPopup: LatinIME instance is null");
            return;
        }

        int x = intent.getIntExtra(EXTRA_X, INVALID_INT);
        int y = intent.getIntExtra(EXTRA_Y, INVALID_INT);
        if (x < 0 || y < 0) {
            Log.e(TAG, "showOrHideKeyPopup: invalid coordinates (" + x + ", " + y + ")");
            return;
        }

        boolean showKeyPreview = intent.getBooleanExtra(EXTRA_SHOW_KEY_PREVIEW, false);
        boolean withAnimation = intent.getBooleanExtra(EXTRA_WITH_ANIMATION, false);
        boolean isLongPress = intent.getBooleanExtra(EXTRA_IS_LONG_PRESS, false);

        try {
            mIme.showOrHideKeyPopup(showKeyPreview, new int[] {x, y}, withAnimation, isLongPress);
            Log.d(TAG, String.format("Key popup %s at (%d, %d)", 
                    showKeyPreview ? "shown" : "hidden", x, y));
        } catch (Exception e) {
            Log.e(TAG, "Error showing/hiding key popup", e);
        }
    }

    /**
     * Handles highlighting a key at specific coordinates.
     * This is an alias for showOrHideKeyPopup for backward compatibility.
     * @param intent The intent containing the highlight parameters
     */
    public void handleHighlightKey(Intent intent) {
        Log.d(TAG, "handleHighlightKey: delegating to showOrHideKeyPopup");
        showOrHideKeyPopup(intent);
    }

    /**
     * Sends a broadcast response to the external application.
     * @param action The action string for the broadcast
     * @param extras The extras to include in the broadcast
     */
    public void sendResponse(String action, Bundle extras) {
        if (action == null || action.trim().isEmpty()) {
            Log.e(TAG, "sendResponse: null or empty action");
            return;
        }

        if (mIme == null) {
            Log.e(TAG, "sendResponse: LatinIME instance is null");
            return;
        }

        try {
            Log.d(TAG, "Sent broadcast response with action: " + action);
            Intent intent = new Intent(action);
            if (extras != null) {
                intent.putExtras(extras);
            }
            intent.setPackage(HEADBOARD_PACKAGE_NAME);
            mIme.sendBroadcast(intent, null);
        } catch (Exception e) {
            Log.e(TAG, "Error sending broadcast response", e);
        }
    }

    /**
     * Sends key bounds information to the external application.
     * @param keyBounds The bounds of the key
     */
    public void sendKeyBounds(Rect keyBounds, Key key) {
        if (keyBounds == null) {
            Log.e(TAG, "sendKeyBounds: keyBounds is null");
            return;
        }

        Bundle extras = new Bundle();
        extras.putInt(BUNDLE_TOP, keyBounds.top);
        extras.putInt(BUNDLE_LEFT, keyBounds.left);
        extras.putInt(BUNDLE_BOTTOM, keyBounds.bottom);
        extras.putInt(BUNDLE_RIGHT, keyBounds.right);
        
        sendResponse(RESPONSE_ACTION_GET_KEY_BOUNDS, extras);
    }
}
