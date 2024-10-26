package com.google.projectgameface;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class ScreenCapturePermissionActivity extends Activity {
    public static final String ACTION_PERMISSION_RESULT = "SCREEN_CAPTURE_PERMISSION_RESULT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent screenCaptureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(screenCaptureIntent, 333); // Arbitrary request code
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 333 && resultCode == RESULT_OK) {
            Intent intent = new Intent(ACTION_PERMISSION_RESULT);
            intent.putExtra("resultCode", resultCode);
            intent.putExtra("data", data);
            sendBroadcast(intent);  // Broadcast the result back
        }
        finish();
    }
}