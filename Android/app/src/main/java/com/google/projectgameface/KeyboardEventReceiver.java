package com.google.projectgameface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * BroadcastReceiver class for handling broadcast request events from the IME.
 */
public class KeyboardEventReceiver extends BroadcastReceiver {
    public static final String KEYBOARD_PACKAGE_NAME = "org.dslul.openboard.inputmethod.latin";
    public static final String ACTION_SEND_MOTION_EVENT = "com.headswype.ACTION_SEND_EVENT";

    public static final String ACTION_SWIPE_START = "com.google.projectgameface.ACTION_SWIPE_START";
    public static final String ACTION_LONGPRESS_ANIMATION = "com.google.projectgameface.ACTION_LONGPRESS_ANIMATION";

    private static final String TAG = "KeyboardEventReceiver";

    private final WeakReference<CursorAccessibilityService> serviceRef;

    public KeyboardEventReceiver(CursorAccessibilityService service) {
        this.serviceRef = new WeakReference<>(service);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        CursorAccessibilityService service = serviceRef.get();
        if (service == null) {
            Log.w(TAG, "Service reference lost; cannot handle keyboard event");
            return;
        }

        String action = intent.getAction();
        if (ACTION_SWIPE_START.equals(action)) {
            Log.d(TAG, "Received ACTION_SWIPE_START");
            service.onKeyboardSwipeStart();
        } else if (ACTION_LONGPRESS_ANIMATION.equals(action)) {
            Log.d(TAG, "Received ACTION_LONGPRESS_ANIMATION");
            service.onKeyboardLongpressAnimation();
        }
    }
}
