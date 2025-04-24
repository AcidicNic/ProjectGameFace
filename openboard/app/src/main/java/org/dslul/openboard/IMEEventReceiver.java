package org.dslul.openboard;

import static org.dslul.openboard.inputmethod.latin.utils.DeviceProtectedUtils.getSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import org.dslul.openboard.inputmethod.keyboard.MainKeyboardView;
import org.dslul.openboard.inputmethod.latin.LatinIME;
import org.dslul.openboard.inputmethod.latin.settings.Settings;
import org.dslul.openboard.inputmethod.latin.settings.SettingsValues;

public class IMEEventReceiver extends BroadcastReceiver {
    private static final String TAG = "HeadBoardReceiver";

    public static final String ACTION_SEND_MOTION_EVENT = "com.headswype.ACTION_SEND_EVENT";
    public static final String ACTION_SEND_KEY_EVENT = "com.headswype.ACTION_SEND_KEY_EVENT";
    public static final String ACTION_SET_LONG_PRESS_DELAY = "com.headswype.ACTION_SET_LONG_PRESS_DELAY";
    public static final String ACTION_CHANGE_TRAIL_COLOR = "com.headswype.ACTION_CHANGE_TRAIL_COLOR";

    private LatinIME mIme;

    public IMEEventReceiver(LatinIME ime) {
        mIme = ime;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case ACTION_SEND_MOTION_EVENT:
                handleMotionEvent(intent);
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
        }
    }

    private void handleMotionEvent(Intent intent) {
        float x = intent.getFloatExtra("x", -1);
        float y = intent.getFloatExtra("y", -1);
        int action = intent.getIntExtra("action", MotionEvent.ACTION_DOWN);
//            long downTime = intent.getLongExtra("downTime", SystemClock.uptimeMillis());
//            long eventTime = intent.getLongExtra("eventTime", SystemClock.uptimeMillis());

        Log.d(TAG, "Received MotionEvent: (" + x + ", " + y + ", action=" + action + ")");

//        Log.d(TAG, "context class of: " + context.getClass().getName());
        // Forward event to LatinIME
        if (mIme != null) {
            mIme.dispatchMotionEvent(x, y, action);
        } else {
            Log.e(TAG, "LatinIME instance is null. Cannot dispatch motion event.");
        }
    }

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

    private void handleSetLongPressDelay(Intent intent) {
        if (mIme == null) return;

        int delay = intent.getIntExtra("delay", -1);
        if (delay < 0) return;

        final SharedPreferences prefs = getSharedPreferences(mIme);
        prefs.edit().putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, delay).apply();
    }

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
}
