package org.dslul.openboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import org.dslul.openboard.inputmethod.latin.LatinIME;

public class IMEEventReceiver extends BroadcastReceiver {
    private static final String TAG = "IMEEventReceiver";
    long startUpTime = 0;

    public IMEEventReceiver() {
        // Empty constructor required for framework instantiation
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.headswype.ACTION_SEND_EVENT".equals(intent.getAction())) {
            float x = intent.getFloatExtra("x", -1);
            float y = intent.getFloatExtra("y", -1);
            int action = intent.getIntExtra("action", MotionEvent.ACTION_DOWN);
//            long downTime = intent.getLongExtra("downTime", SystemClock.uptimeMillis());
//            long eventTime = intent.getLongExtra("eventTime", SystemClock.uptimeMillis());

            Log.d(TAG, "[666] Received MotionEvent: (" + x + ", " + y + ", action=" + action + ")");

            // Forward event to LatinIME
            Log.d(TAG, "[666] context class of: " + context.getClass().getName());
            if (context instanceof LatinIME) {
                ((LatinIME) context).dispatchMotionEvent(x, y, action);
            } else {
                Log.e(TAG, "[666] LatinIME instance is null. Cannot dispatch motion event.");
            }
        }
    }
}