package com.google.projectgameface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver class for handling broadcast request events from the IME.
 */
public class KeyboardEventReceiver extends BroadcastReceiver {
    public static final String KEYBOARD_PACKAGE_NAME = "org.dslul.openboard.inputmethod.latin";

    public static final String ACTION_SWIPE_START = "com.headswype.ACTION_SWIPE_START";
    public static final String ACTION_LONGPRESS_ANIMATION = "com.headswype.ACTION_LONGPRESS_ANIMATION";

    private static final String TAG = "KeyboardEventReceiver";

    // Default constructor required for Android system instantiation
    public KeyboardEventReceiver() {
    }


    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        if (!(context instanceof CursorAccessibilityService)) {
            Log.w(TAG, "Context is not an instance of CursorAccessibilityService");
            return;
        }

        CursorAccessibilityService service = (CursorAccessibilityService) context;

        switch (action) {
            case ACTION_SWIPE_START:
                Log.d(TAG, "Received ACTION_SWIPE_START");
                service.onKeyboardSwipeStart();
                break;
            case ACTION_LONGPRESS_ANIMATION:
                Log.d(TAG, "Received ACTION_LONGPRESS_ANIMATION");
                service.onKeyboardLongpressAnimation();
                break;
            default:
                Log.w(TAG, "Received unknown action: " + action);
        }
    }
}
