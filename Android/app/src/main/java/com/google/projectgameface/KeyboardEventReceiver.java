package com.google.projectgameface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BroadcastReceiver class for handling broadcast request events from the IME.
 */
public class KeyboardEventReceiver extends BroadcastReceiver {
    public static final String KEYBOARD_PACKAGE_NAME = "org.dslul.openboard.inputmethod.latin";
    public static final String ACTION_SEND_MOTION_EVENT = "com.headswype.ACTION_SEND_EVENT";
    @Override
    public void onReceive(Context context, Intent intent) {
        // TODO: handle kbd swype started event
    }

}
