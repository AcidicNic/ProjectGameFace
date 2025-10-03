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

    public static final String ACTION_SWIPE_START = "com.headswype.ACTION_SWIPE_START";
    public static final String ACTION_LONGPRESS_ANIMATION = "com.headswype.ACTION_LONGPRESS_ANIMATION";

    private static final String TAG = "KeyboardEventReceiver";

    private final WeakReference<CursorAccessibilityService> serviceRef;

    // Default constructor required for Android system instantiation
    public KeyboardEventReceiver() {
        this.serviceRef = null;
    }

    public KeyboardEventReceiver(CursorAccessibilityService service) {
        this.serviceRef = new WeakReference<>(service);
    }

    private CursorAccessibilityService getService(Context context) {
        if (context instanceof CursorAccessibilityService) {
            return (CursorAccessibilityService) context;
        } else if (serviceRef != null) {
            return serviceRef.get();
        }
        return null;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        CursorAccessibilityService service = getService(context);
        if (service == null) {
            Log.w(TAG, "Cannot get service reference; cannot handle keyboard event");
            return;
        }

        switch (intent.getAction()) {
            case ACTION_SWIPE_START:
                Log.d(TAG, "Received ACTION_SWIPE_START");
                service.onKeyboardSwipeStart();
                break;
            case ACTION_LONGPRESS_ANIMATION:
                Log.d(TAG, "Received ACTION_LONGPRESS_ANIMATION");
                service.onKeyboardLongpressAnimation();
                break;
            default:
                Log.w(TAG, "Received unknown action: " + intent.getAction());
        }
    }
}
