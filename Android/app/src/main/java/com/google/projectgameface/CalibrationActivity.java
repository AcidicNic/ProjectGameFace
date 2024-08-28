package com.google.projectgameface;

import android.view.MenuItem;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.math.MathUtils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.projectgameface.CursorAccessibilityService.ServiceState;

import java.util.Objects;

public class CalibrationActivity extends AppCompatActivity {

    private static final String TAG = "CalibrationActivity";
    BroadcastReceiver stateReceiver;
    BroadcastReceiver scoreReceiver;
    private boolean isServicePreviouslyEnabled = false;

    private ProgressBar progressBarLeft;
    private ProgressBar progressBarRight;
    private ProgressBar progressBarUp;
    private ProgressBar progressBarDown;

    private int thresholdInUi;

    private final int seekBarDefaultValue = 2;
    private static final int SEEK_BAR_MAXIMUM_VALUE = 10;
    private static final int SEEK_BAR_MINIMUM_VALUE = 0;

    private static final int SEEK_BAR_LOW_CLIP = 10;
    private static final int SEEK_BAR_HIGH_CLIP = 100;
    private LinearLayout cameraBoxPlaceHolder;

    private Boolean isPlaceHolderLaidOut = false;

    private float minPitch = 0.0f;    // Minimum pitch (down)
    private float maxPitch = 0.0f;    // Maximum pitch (up)
    private float minYaw = 0.0f;      // Minimum yaw (left)
    private float maxYaw = 0.0f;      // Maximum yaw (right)

    public void resetMinMaxValues() {
        minPitch = 0.0f;
        maxPitch = 0.0f;
        minYaw = 0.0f;
        maxYaw = 0.0f;
    }

    public void updatePitchYawMinMax(float[] pitchYawXY) {
        if (pitchYawXY[0] == 0 && pitchYawXY[1] == 0) {
            return;
        }
        if (pitchYawXY[0] > maxPitch) {
            maxPitch = pitchYawXY[0];
        }
        if (pitchYawXY[0] < minPitch) {
            minPitch = pitchYawXY[0];
        }
        if (pitchYawXY[1] > maxYaw) {
            maxYaw = pitchYawXY[1];
        }
        if (pitchYawXY[1] < minYaw) {
            minYaw = pitchYawXY[1];
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);

        //setting actionbar
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("Calibration");

        //Check if service is enabled.
        checkIfServiceEnabled();

        scorePreview(true, null);

        SeekBar leftSeekBar = findViewById(R.id.leftSeekBar);
//        thresholdInUi = preferences.getInt(pageEventType +"_size", seekBarDefaultValue * 10);
        thresholdInUi = 50;
        thresholdInUi = MathUtils.clamp(thresholdInUi, SEEK_BAR_LOW_CLIP, SEEK_BAR_HIGH_CLIP);
        leftSeekBar.setMax(SEEK_BAR_MAXIMUM_VALUE);
        leftSeekBar.setMin(SEEK_BAR_MINIMUM_VALUE);
        leftSeekBar.setProgress(thresholdInUi / 10);

        TextView targetGesture = findViewById(R.id.targetGesture);

//        String beautifyBlendshapeName = BlendshapeEventTriggerConfig.BEAUTIFY_BLENDSHAPE_NAME.get(selectedGesture);
//        targetGesture.setText("Perform \""+beautifyBlendshapeName+"\"");


        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int stateIndex = intent.getIntExtra("state", ServiceState.DISABLE.ordinal());
                isServicePreviouslyEnabled =
                        Objects.requireNonNull(ServiceState.values()[stateIndex]) != ServiceState.DISABLE;
            }
        };

        scoreReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Pitch in degrees. Positive is down, negative is up.
                float pitch = intent.getFloatExtra("PITCH", 0.0f);
                // Yaw in degrees. Positive is left, negative is right.
                float yaw = intent.getFloatExtra("YAW", 0.0f);

                float[] headCoordsXY = intent.getFloatArrayExtra("CURRHEADXY");
                float[] noseCoordsXY = intent.getFloatArrayExtra("CURRNOSEXY");
                targetGesture.setText("Pitch: " + pitch + "; Yaw: " + yaw + ";\nHead: (" + headCoordsXY[0] + ", " + headCoordsXY[1] + ");\nNose: (" + noseCoordsXY[0] + " " + noseCoordsXY[1] + ")");

                int progressLeft = (int) ((yaw - 0) / (50 - 0) * 100);
                int progressRight = (int) ((0 - yaw) / (0 - -50) * 100);
                int progressDown = (int) ((pitch - 0) / (50 - 0) * 100);
                int progressUp = (int) ((0 - pitch) / (0 - -50) * 100);

                if (progressLeft > thresholdInUi) progressBarLeft.setProgressDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.custom_progress, null));
                else progressBarLeft.setProgressDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.custom_progress_threshold,null));
                progressBarLeft.setProgress(progressLeft);

                if (progressRight > thresholdInUi) progressBarRight.setProgressDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.custom_progress, null));
                else progressBarRight.setProgressDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.custom_progress_threshold,null));
                progressBarRight.setProgress(progressRight);

                if (progressUp > thresholdInUi) progressBarUp.setProgressDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.custom_progress, null));
                else progressBarUp.setProgressDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.custom_progress_threshold,null));
                progressBarUp.setProgress(progressUp);

                if (progressDown > thresholdInUi) progressBarDown.setProgressDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.custom_progress, null));
                else progressBarDown.setProgressDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.custom_progress_threshold,null));
                progressBarDown.setProgress(progressDown);
            }
        };

        cameraBoxPlaceHolder = findViewById(R.id.cameraBoxPlaceHolder);

        // Move camera window to match the cameraBoxPlaceHolder in the layout.
        cameraBoxPlaceHolder.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                isPlaceHolderLaidOut = true;
                int[] locationOnScreen = new int[2];
                cameraBoxPlaceHolder.getLocationOnScreen(locationOnScreen);
                fitCameraBoxToPlaceHolder(locationOnScreen[0], locationOnScreen[1],
                        cameraBoxPlaceHolder.getWidth(), cameraBoxPlaceHolder.getHeight());

                cameraBoxPlaceHolder.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });

        findViewById(R.id.doneBtn).setOnClickListener(v -> {
//            BlendshapeEventTriggerConfig.writeBindingConfig(getBaseContext(), selectedGesture, pageEventType,
//                    thresholdInUi);
            try {
                CharSequence text = "Setting Completed!";
                int duration = Toast.LENGTH_LONG;
                Toast toast = Toast.makeText(getBaseContext(), text, duration);
                toast.show();
            } catch (Exception e) {
                Log.i(TAG, e.toString());
            }
            // Go back to cursor binding page.
            Intent intent = new Intent(this, CursorBinding.class);
            startActivity(intent);

            restorePreviousServiceState();
            finish();


        });

        findViewById(R.id.backBtn).setOnClickListener(v -> {
            finish();
        });



        leftSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Prevent user from set the threshold to 0.
                if (progress == 0) {
                    seekBar.setProgress(1);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                thresholdInUi = seekBar.getProgress() * 10;
            }
        });

        progressBarLeft = findViewById(R.id.leftBar);
        progressBarRight = findViewById(R.id.rightBar);
        progressBarUp = findViewById(R.id.upBar);
        progressBarDown = findViewById(R.id.downBar);

        registerReceiver(scoreReceiver, new IntentFilter("PITCH_YAW"),RECEIVER_EXPORTED);
        registerReceiver(stateReceiver, new IntentFilter("SERVICE_STATE_GESTURE"),RECEIVER_EXPORTED);
    }

    private void fitCameraBoxToPlaceHolder(int placeholderX, int placeholderY,
                                           int placeHolderWidth, int placeHolderHeight) {

        // Temporary change to GLOBAL_STICK state.
        Intent intentChangeServiceState = new Intent("CHANGE_SERVICE_STATE");
        intentChangeServiceState.putExtra("state", CursorAccessibilityService.ServiceState.GLOBAL_STICK.ordinal());
        sendBroadcast(intentChangeServiceState);


        Intent intentFlyIn = new Intent("FLY_IN_FLOAT_WINDOW");
        intentFlyIn.putExtra("positionX", placeholderX);
        intentFlyIn.putExtra("positionY", placeholderY);
        intentFlyIn.putExtra("width", placeHolderWidth);
        intentFlyIn.putExtra("height", placeHolderHeight);
        sendBroadcast(intentFlyIn);
    }

    public void checkIfServiceEnabled() {
        // send broadcast to service to check its state.
        Intent intent = new Intent("REQUEST_SERVICE_STATE");
        intent.putExtra("state","gesture");
        sendBroadcast(intent);
    }

    private void scorePreview(boolean status, String requestedScoreName) {
        Intent intent = new Intent("ENABLE_SCORE_PREVIEW");
        intent.putExtra("enable", status);
        sendBroadcast(intent);
    }


    private void restorePreviousServiceState() {

        // Resume the previous service state.
        if(isServicePreviouslyEnabled){
            Intent intentChangeServiceState = new Intent("CHANGE_SERVICE_STATE");
            intentChangeServiceState.putExtra("state", CursorAccessibilityService.ServiceState.ENABLE.ordinal());
            sendBroadcast(intentChangeServiceState);
        }
        else{
            Intent intentPreviewCameraMode = new Intent("CHANGE_SERVICE_STATE");
            intentPreviewCameraMode.putExtra("state", CursorAccessibilityService.ServiceState.DISABLE.ordinal());
            sendBroadcast(intentPreviewCameraMode);
        }
    }


    @Override
    protected void onResume() {
        super.onResume();

        if (!isPlaceHolderLaidOut){
            return;
        }

        int[] locationOnScreen = new int[2];
        cameraBoxPlaceHolder.getLocationOnScreen(locationOnScreen);
        fitCameraBoxToPlaceHolder(locationOnScreen[0], locationOnScreen[1],
                cameraBoxPlaceHolder.getWidth(), cameraBoxPlaceHolder.getHeight());


    }

    @Override
    protected void onPause() {
        super.onPause();
        scorePreview(false, null);
        try {
            unregisterReceiver(scoreReceiver);
        } catch (Exception ignored){

        }
        restorePreviousServiceState();
    }



    /**
     * Make back button work as back action in device's navigation.
     * @param item The menu item that was selected.
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);

    }

}