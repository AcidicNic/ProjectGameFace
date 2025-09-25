/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.projectgameface;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.projectgameface.utils.Config;

import java.util.Objects;

/** Settings activity. */
public class HeadBoardSettings extends AppCompatActivity {

    private CursorMovementConfig cursorMovementConfig;
    private SwitchCompat realtimeSwipeSwitch;
    private SwitchCompat durationPopOutSwitch;
    private Switch directMappingSwitch;
    private Switch debugSwipeSwitch;
    private Switch noseTipSwitch;
    private Switch pitchYawSwitch;
    private SeekBar holdDurationSeekBar;
    private SeekBar dragToggleDurSeekBar;
    private SeekBar smoothingSeekBar;
    private TextView smoothingProgress;
    private SeekBar headCoordScaleFactorXSeekBar;
    private SeekBar headCoordScaleFactorYSeekBar;
    private TextView holdDurationTxt;
    private TextView dragToggleDurTxt;
    private TextView headCoordScaleFactorXProgress;
    private TextView headCoordScaleFactorYProgress;
    private ConstraintLayout holdDurationLayout;
    private ConstraintLayout headCoordScaleFactorLayout;
    private Button switchKeyboardBtn;
    private Button debuggingStatsBtn;
    private SeekBar quickTapThresholdSeekBar;
    private TextView progressQuickTapThreshold;
    private SeekBar longTapThresholdSeekBar;
    private TextView progressLongTapThreshold;

    private SeekBar uiFeedbackDelaySeekBar;
    private TextView progressUiFeedbackDelay;
    private Switch exponentialSmoothingSwitch;

    // Path Cursor UI elements
    private SeekBar pathCursorSeekBar;
    private TextView progressPathCursor;
    private SeekBar pathCursorMinSeekBar;
    private TextView progressPathCursorMin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_headboard_config);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        // setting actionbar
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("HeadBoard Settings");

        // Initialize CursorMovementConfig
        cursorMovementConfig = new CursorMovementConfig(this);
        cursorMovementConfig.updateAllConfigFromSharedPreference();

        // Realtime Swipe
        realtimeSwipeSwitch = findViewById(R.id.realtimeSwipeSwitch);
        realtimeSwipeSwitch.setChecked(
                cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.REALTIME_SWIPE));
        realtimeSwipeSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                sendValueToService("REALTIME_SWIPE", isChecked));

        // Debug Swipe
        debugSwipeSwitch = findViewById(R.id.debugSwipeSwitch);
        debugSwipeSwitch.setChecked(
                cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DEBUG_SWIPE));
        debugSwipeSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                sendValueToService("DEBUG_SWIPE", isChecked));

        // Pop Out Method
        holdDurationLayout = findViewById(R.id.edgeHoldDurationLayout);
        durationPopOutSwitch = findViewById(R.id.durationPopOutSwitch);
        durationPopOutSwitch.setChecked(
                cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DURATION_POP_OUT));
        durationPopOutSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendValueToService("DURATION_POP_OUT", isChecked);
            holdDurationLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Edge Hold Duration
        holdDurationSeekBar = findViewById(R.id.holdDurationSeekBar);
        holdDurationTxt = findViewById(R.id.progressHoldDuration);
        setUpSeekBarAndTextView(
                holdDurationSeekBar, holdDurationTxt, String.valueOf(CursorMovementConfig.CursorMovementConfigType.EDGE_HOLD_DURATION)
        );

        // Edge Hold Duration
        dragToggleDurSeekBar = findViewById(R.id.dragToggleDurSeekBar);
        dragToggleDurTxt = findViewById(R.id.progressDragToggleDur);
        setUpSeekBarAndTextViewTOGGLE(
                dragToggleDurSeekBar, dragToggleDurTxt, String.valueOf(CursorMovementConfig.CursorMovementConfigType.DRAG_TOGGLE_DURATION)
        );

        // Set initial visibility based on the DURATION_POP_OUT value
        holdDurationLayout.setVisibility(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DURATION_POP_OUT) ? View.VISIBLE : View.GONE);

        // Direct Mapping
        directMappingSwitch = findViewById(R.id.directMappingSwitch);
        directMappingSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DIRECT_MAPPING));
        directMappingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendValueToService("DIRECT_MAPPING", isChecked);
            headCoordScaleFactorLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Head Coord Scale Factor
        headCoordScaleFactorLayout = findViewById(R.id.headCoordScaleFactorLayout);
        headCoordScaleFactorXSeekBar = findViewById(R.id.headCoordScaleFactorXSeekBar);
        headCoordScaleFactorXProgress = findViewById(R.id.headCoordScaleFactorXProgress);
        headCoordScaleFactorYSeekBar = findViewById(R.id.headCoordScaleFactorYSeekBar);
        headCoordScaleFactorYProgress = findViewById(R.id.headCoordScaleFactorYProgress);

        setUpScaleFactorSeekBarAndTextView(
                headCoordScaleFactorXSeekBar, headCoordScaleFactorXProgress, String.valueOf(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_X)
        );

        setUpScaleFactorSeekBarAndTextView(
                headCoordScaleFactorYSeekBar, headCoordScaleFactorYProgress, String.valueOf(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_Y)
        );

        // Set initial visibility based on the DIRECT_MAPPING value
        headCoordScaleFactorLayout.setVisibility(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DIRECT_MAPPING) ? View.VISIBLE : View.GONE);

        // Nose Tip Cursor Movement
        noseTipSwitch = findViewById(R.id.noseTipSwitch);
        noseTipSwitch.setChecked(
                cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.NOSE_TIP));
        noseTipSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendValueToService("NOSE_TIP", isChecked);
        });

        // Pitch + Yaw Cursor Movement
        pitchYawSwitch = findViewById(R.id.pitchYawSwitch);
        pitchYawSwitch.setChecked(
                cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.PITCH_YAW));
        pitchYawSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendValueToService("PITCH_YAW", isChecked);
        });

        // Smoothing
        smoothingSeekBar = findViewById(R.id.smoothingSeekBar);
        smoothingProgress = findViewById(R.id.progressSmoothing);
        setUpSmoothingSeekBarAndTextView(
                smoothingSeekBar, smoothingProgress, String.valueOf(CursorMovementConfig.CursorMovementConfigType.AVG_SMOOTHING)
        );

        // Exponential Smoothing
        exponentialSmoothingSwitch = findViewById(R.id.exponentialSmoothingSwitch);
        exponentialSmoothingSwitch.setChecked(
                cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.EXPONENTIAL_SMOOTHING));
        exponentialSmoothingSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                sendValueToService("EXPONENTIAL_SMOOTHING", isChecked));

        // Switch Keyboard
        switchKeyboardBtn = findViewById(R.id.switchKeyboardBtn);
        switchKeyboardBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // Allows the user to quickly select another keyboard in-app (without going to settings)
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();

                // Fully opens the settings app to switch keyboards
                // startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        });

        debuggingStatsBtn = findViewById(R.id.debuggingStatsBtn);
        debuggingStatsBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(HeadBoardSettings.this, DebuggingStatsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
        });

        // Ui feedback delay
        uiFeedbackDelaySeekBar = findViewById(R.id.uiFeedbackDelaySeekBar);
        progressUiFeedbackDelay = findViewById(R.id.progressUiFeedbackDelay);

        setUpUiFeedbackDelaySeekBarAndTextView(
                uiFeedbackDelaySeekBar, progressUiFeedbackDelay, String.valueOf(CursorMovementConfig.CursorMovementConfigType.UI_FEEDBACK_DELAY)
        );

        // Quick Tap Threshold
        quickTapThresholdSeekBar = findViewById(R.id.quickTapThresholdSeekBar);
        progressQuickTapThreshold = findViewById(R.id.progressQuickTapThreshold);

        setUpQuickTapThresholdSeekBarAndTextView(
                quickTapThresholdSeekBar, progressQuickTapThreshold, String.valueOf(CursorMovementConfig.CursorMovementConfigType.QUICK_TAP_THRESHOLD)
        );

        // Long Tap Threshold
        longTapThresholdSeekBar = findViewById(R.id.longTapThresholdSeekBar);
        progressLongTapThreshold = findViewById(R.id.progressLongTapThreshold);

        setUpLongTapThresholdSeekBarAndTextView(
                longTapThresholdSeekBar, progressLongTapThreshold, String.valueOf(CursorMovementConfig.CursorMovementConfigType.LONG_TAP_THRESHOLD)
        );

        // Path Cursor
        pathCursorSeekBar = findViewById(R.id.pathCursorSeekBar);
        progressPathCursor = findViewById(R.id.progressPathCursor);

        setUpPathCursorSeekBarAndTextView(
                pathCursorSeekBar, progressPathCursor, String.valueOf(CursorMovementConfig.CursorMovementConfigType.PATH_CURSOR)
        );

        // Path Cursor Min
        pathCursorMinSeekBar = findViewById(R.id.pathCursorMinSeekBar);
        progressPathCursorMin = findViewById(R.id.pathCursorMinProgress);

        setUpPathCursorMinSeekBarAndTextView(
                pathCursorMinSeekBar, progressPathCursorMin, String.valueOf(CursorMovementConfig.CursorMovementConfigType.PATH_CURSOR_MIN)
        );

        // Binding buttons with individual listeners
        findViewById(R.id.holdDurationFaster).setOnClickListener(v -> {
            int currentValue = holdDurationSeekBar.getProgress();
            int newValue = currentValue + 1;
            if (newValue >= 0 && holdDurationSeekBar.getProgress() <= holdDurationSeekBar.getMax()) {
                holdDurationSeekBar.setProgress(newValue);
                int duration = (newValue + 1) * 200;
                sendValueToService("EDGE_HOLD_DURATION", duration);
            }
        });

        findViewById(R.id.holdDurationSlower).setOnClickListener(v -> {
            int currentValue = holdDurationSeekBar.getProgress();
            int newValue = currentValue - 1;
            if (newValue >= 0 && holdDurationSeekBar.getProgress() <= holdDurationSeekBar.getMax()) {
                holdDurationSeekBar.setProgress(newValue);
                int duration = (newValue + 1) * 200;
                sendValueToService("EDGE_HOLD_DURATION", duration);
            }
        });

        findViewById(R.id.headCoordScaleFactorXFaster).setOnClickListener(v -> {
            int currentValue = headCoordScaleFactorXSeekBar.getProgress();
            int newValue = currentValue + 1;
            if (newValue >= 0 && headCoordScaleFactorXSeekBar.getProgress() <= headCoordScaleFactorXSeekBar.getMax()) {
                headCoordScaleFactorXSeekBar.setProgress(newValue);
                float scaleFactor = 1.0f + (newValue * 0.2f);
                sendValueToService("HEAD_COORD_SCALE_FACTOR_X", scaleFactor);
            }
        });

        findViewById(R.id.headCoordScaleFactorXSlower).setOnClickListener(v -> {
            int currentValue = headCoordScaleFactorXSeekBar.getProgress();
            int newValue = currentValue - 1;
            if (newValue >= 0 && headCoordScaleFactorXSeekBar.getProgress() <= headCoordScaleFactorXSeekBar.getMax()) {
                headCoordScaleFactorXSeekBar.setProgress(newValue);
                float scaleFactor = 1.0f + (newValue * 0.2f);
                sendValueToService("HEAD_COORD_SCALE_FACTOR_X", scaleFactor);
            }
        });

        findViewById(R.id.headCoordScaleFactorYFaster).setOnClickListener(v -> {
            int currentValue = headCoordScaleFactorYSeekBar.getProgress();
            int newValue = currentValue + 1;
            if (newValue >= 0 && headCoordScaleFactorYSeekBar.getProgress() <= headCoordScaleFactorYSeekBar.getMax()) {
                headCoordScaleFactorYSeekBar.setProgress(newValue);
                float scaleFactor = 1.0f + (newValue * 0.2f);
                sendValueToService("HEAD_COORD_SCALE_FACTOR_Y", scaleFactor);
            }
        });

        findViewById(R.id.headCoordScaleFactorYSlower).setOnClickListener(v -> {
            int currentValue = headCoordScaleFactorYSeekBar.getProgress();
            int newValue = currentValue - 1;
            if (newValue >= 0 && headCoordScaleFactorYSeekBar.getProgress() <= headCoordScaleFactorYSeekBar.getMax()) {
                headCoordScaleFactorYSeekBar.setProgress(newValue);
                float scaleFactor = 1.0f + (newValue * 0.2f);
                sendValueToService("HEAD_COORD_SCALE_FACTOR_Y", scaleFactor);
            }
        });

        findViewById(R.id.decreaseSmoothing).setOnClickListener(v -> {
            int currentValue = smoothingSeekBar.getProgress();
            int newValue = currentValue - 1;
            if (newValue >= 0 && smoothingSeekBar.getProgress() <= smoothingSeekBar.getMax()) {
                smoothingSeekBar.setProgress(newValue);
                int smoothingVal = newValue + 1;
                sendValueToService("AVG_SMOOTHING", smoothingVal);
            }
        });

        findViewById(R.id.increaseSmoothing).setOnClickListener(v -> {
            int currentValue = smoothingSeekBar.getProgress();
            int newValue = currentValue + 1;
            if (newValue >= 0 && smoothingSeekBar.getProgress() <= smoothingSeekBar.getMax()) {
                smoothingSeekBar.setProgress(newValue);
                int smoothingVal = newValue + 1;
                sendValueToService("AVG_SMOOTHING", smoothingVal);
            }
        });

        findViewById(R.id.decreaseDragToggleDelay).setOnClickListener(v -> {
            int currentValue = dragToggleDurSeekBar.getProgress();
            int newValue = currentValue - 1;
            if (newValue >= dragToggleDurSeekBar.getMin() && dragToggleDurSeekBar.getProgress() <= dragToggleDurSeekBar.getMax()) {
                dragToggleDurSeekBar.setProgress(newValue);
                int duration = (newValue + 1) * 100;
                sendValueToService("DRAG_TOGGLE_DURATION", duration);
            }
        });

        findViewById(R.id.increaseDragToggleDelay).setOnClickListener(v -> {
            int currentValue = dragToggleDurSeekBar.getProgress();
            int newValue = currentValue + 1;
            if (newValue >= dragToggleDurSeekBar.getMin() && dragToggleDurSeekBar.getProgress() <= dragToggleDurSeekBar.getMax()) {
                dragToggleDurSeekBar.setProgress(newValue);
                int duration = (newValue + 1) * 100;
                sendValueToService("DRAG_TOGGLE_DURATION", duration);
            }
        });

        findViewById(R.id.decreaseQuickTapThreshold).setOnClickListener(v -> {
            int currentValue = quickTapThresholdSeekBar.getProgress();
            int newValue = currentValue - 1;
            if (newValue >= quickTapThresholdSeekBar.getMin() && quickTapThresholdSeekBar.getProgress() <= quickTapThresholdSeekBar.getMax()) {
                quickTapThresholdSeekBar.setProgress(newValue);
                int value = 200 + (newValue * 100);
                sendValueToService("QUICK_TAP_THRESHOLD", value);
            }
        });

        findViewById(R.id.increaseQuickTapThreshold).setOnClickListener(v -> {
            int currentValue = quickTapThresholdSeekBar.getProgress();
            int newValue = currentValue + 1;
            if (newValue >= quickTapThresholdSeekBar.getMin() && quickTapThresholdSeekBar.getProgress() <= quickTapThresholdSeekBar.getMax()) {
                quickTapThresholdSeekBar.setProgress(newValue);
                int value = 200 + (newValue * 100);
                sendValueToService("QUICK_TAP_THRESHOLD", value);
            }
        });

        findViewById(R.id.decreaseLongTapThreshold).setOnClickListener(v -> {
            int currentValue = longTapThresholdSeekBar.getProgress();
            int newValue = currentValue - 1;
            if (newValue >= longTapThresholdSeekBar.getMin() && longTapThresholdSeekBar.getProgress() <= longTapThresholdSeekBar.getMax()) {
                longTapThresholdSeekBar.setProgress(newValue);
                int value = 600 + (newValue * 100);
                sendValueToService("LONG_TAP_THRESHOLD", value);
            }
        });

        findViewById(R.id.increaseLongTapThreshold).setOnClickListener(v -> {
            int currentValue = longTapThresholdSeekBar.getProgress();
            int newValue = currentValue + 1;
            if (newValue >= longTapThresholdSeekBar.getMin() && longTapThresholdSeekBar.getProgress() <= longTapThresholdSeekBar.getMax()) {
                longTapThresholdSeekBar.setProgress(newValue);
                int value = 600 + (newValue * 100);
                sendValueToService("LONG_TAP_THRESHOLD", value);
            }
        });

        findViewById(R.id.decreaseUiFeedbackDelay).setOnClickListener(v -> {
            handleDecrease(uiFeedbackDelaySeekBar, "UI_FEEDBACK_DELAY");
        });

        findViewById(R.id.increaseUiFeedbackDelay).setOnClickListener(v -> {
            handleIncrease(uiFeedbackDelaySeekBar, "UI_FEEDBACK_DELAY");
        });

        findViewById(R.id.decreasePathCursor).setOnClickListener(v -> {
            handleDecrease(pathCursorSeekBar, "PATH_CURSOR");
        });

        findViewById(R.id.increasePathCursor).setOnClickListener(v -> {
            handleIncrease(pathCursorSeekBar, "PATH_CURSOR");
        });

        findViewById(R.id.pathCursorMinDecrease).setOnClickListener(v -> {
            handleDecrease(pathCursorMinSeekBar, "PATH_CURSOR_MIN");
        });

        findViewById(R.id.pathCursorMinIncrease).setOnClickListener(v -> {
            handleIncrease(pathCursorMinSeekBar, "PATH_CURSOR_MIN");
        });
    }

    private void handleDecrease(SeekBar seekbar, String configName) {
        int currentValue = seekbar.getProgress();
        int newValue = currentValue - 1;
        if (newValue >= seekbar.getMin() && seekbar.getProgress() <= seekbar.getMax()) {
            seekbar.setProgress(newValue);
            int value = newValue + 1;
            sendValueToService(configName, value);
        }
    }

    private void handleIncrease(SeekBar seekbar, String configName) {
        int currentValue = seekbar.getProgress();
        int newValue = currentValue + 1;
        if (newValue >= seekbar.getMin() && seekbar.getProgress() <= seekbar.getMax()) {
            seekbar.setProgress(newValue);
            int value = newValue + 1;
            sendValueToService(configName, value);
        }
    }

    private void setUpScaleFactorSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(20); // 1 to 20 (total 20 steps)
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        float savedProgress = preferences.getFloat(preferencesId, 1.5f); // Default to 1.5

        // Convert saved value to progress (1.0=0, 5.0=20)
        int progress = Math.round((savedProgress - 1.0f) * 5);
        progress = Math.min(progress, 20); // Ensure it doesn't exceed max
        progress = Math.max(progress, 0);  // Ensure it doesn't go below min

        seekBar.setProgress(progress); // Set initial progress
        textView.setText(String.valueOf(savedProgress)); // Set initial text

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Calculate display value (progress 0=1.0, 20=5.0)
                float displayValue = 1.0f + (progress * 0.2f);
                textView.setText(String.format("%.1f", displayValue));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float scaleFactor = 1.0f + (seekBar.getProgress() * 0.2f);
                sendValueToService(preferencesId, scaleFactor);
            }
        });
    }

    private void setUpSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(9); // 1 to 10 in increments of 1 means 9 steps
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        int savedProgress;
        if (Objects.equals(preferencesId, CursorMovementConfig.CursorMovementConfigType.EDGE_HOLD_DURATION.toString())) {
            savedProgress = preferences.getInt(preferencesId, CursorMovementConfig.InitialRawValue.EDGE_HOLD_DURATION);
        } else if (Objects.equals(preferencesId, CursorMovementConfig.CursorMovementConfigType.DRAG_TOGGLE_DURATION.toString())) {
            savedProgress = preferences.getInt(preferencesId, CursorMovementConfig.InitialRawValue.DRAG_TOGGLE_DURATION);
        } else {
            savedProgress = preferences.getInt(preferencesId, CursorMovementConfig.InitialRawValue.SPEED);
        }
        int progress = (savedProgress / 200) - 1;
        seekBar.setProgress(progress); // Set initial progress
        textView.setText(String.valueOf(progress + 1)); // Set initial text

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int displayValue = progress + 1;
                textView.setText(String.valueOf(displayValue));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int duration = (seekBar.getProgress() + 1) * 200;
                sendValueToService("EDGE_HOLD_DURATION", duration);
            }
        });
    }

    private void setUpSeekBarAndTextViewTOGGLE(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(9); // 1 to 10 in increments of 1 means 9 steps
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        int savedProgress = preferences.getInt(preferencesId, CursorMovementConfig.InitialRawValue.SPEED);
        int progress = (savedProgress / 100) - 1;
        seekBar.setProgress(progress); // Set initial progress
        textView.setText(String.valueOf((progress + 1) * 100)); // Set initial text

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int displayValue = (progress + 1) * 100;
                textView.setText(String.valueOf(displayValue));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int duration = (seekBar.getProgress() + 1) * 100;
                sendValueToService("DRAG_TOGGLE_DURATION", duration);
            }
        });
    }

    private void setUpSmoothingSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(9); // 1 to 10 in increments of 1 means 9 steps
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        int savedProgress = preferences.getInt(preferencesId, CursorMovementConfig.InitialRawValue.AVG_SMOOTHING);
        int progress = savedProgress;
        seekBar.setProgress(progress); // Set initial progress
        textView.setText(String.valueOf(progress)); // Set initial text

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int displayValue = progress + 1;
                textView.setText(String.valueOf(displayValue));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int duration = (seekBar.getProgress());
                sendValueToService("AVG_SMOOTHING", duration);
            }
        });
    }

    private void setUpQuickTapThresholdSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(7); // 500-4000ms in steps of 500ms
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        int savedProgress = preferences.getInt(preferencesId, CursorMovementConfig.InitialRawValue.QUICK_TAP_THRESHOLD);
        int progress = (savedProgress - 500) / 500;
        seekBar.setProgress(progress);
        textView.setText(String.valueOf(savedProgress));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 500 + (progress * 500);
                textView.setText(String.valueOf(value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = 500 + (seekBar.getProgress() * 500);
                sendValueToService(preferencesId, value);
            }
        });
    }

    private void setUpUiFeedbackDelaySeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(8); // 1-9 in steps of 1
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        int savedValue = preferences.getInt(preferencesId, 1); // Default to 1 if not set
        seekBar.setProgress(savedValue - 1); // Convert 1-9 to 0-8 for progress
        textView.setText(String.valueOf(savedValue));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 1; // Convert 0-8 back to 1-9
                textView.setText(String.valueOf(value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = seekBar.getProgress() + 1; // Convert 0-8 back to 1-9
                sendValueToService(preferencesId, value);
            }
        });
    }

    private void setUpLongTapThresholdSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(7); // 500-4000ms in steps of 500ms
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        int savedProgress = preferences.getInt(preferencesId, CursorMovementConfig.InitialRawValue.LONG_TAP_THRESHOLD);
        int progress = (savedProgress - 500) / 500;
        seekBar.setProgress(progress);
        textView.setText(String.valueOf(savedProgress));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 500 + (progress * 500);
                textView.setText(String.valueOf(value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = 500 + (seekBar.getProgress() * 500);
                sendValueToService(preferencesId, value);
            }
        });
    }

    private void setUpPathCursorSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(39); // 1-40 in steps of 1
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        int savedValue = preferences.getInt(preferencesId, Config.DEFAULT_PATH_CURSOR);
        seekBar.setProgress(savedValue - 1);
        textView.setText(String.format("%.3f", CursorController.getPathCursorPercentageFrom(savedValue)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = CursorController.getPathCursorPercentageFrom(progress + 1);
                textView.setText(String.format("%.3f", value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = seekBar.getProgress() + 1;
                sendValueToService(preferencesId, value);
            }
        });
    }

    private void setUpPathCursorMinSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(9);
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        int savedValue = preferences.getInt(preferencesId, Config.DEFAULT_PATH_CURSOR_MIN);
        seekBar.setProgress(savedValue - 1);
        textView.setText(String.valueOf(savedValue - 1));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 1;
                textView.setText(String.valueOf(value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = seekBar.getProgress() + 1;
                sendValueToService(preferencesId, value);
            }
        });
    }

    // Ensure to reload config on profile change
    private final BroadcastReceiver profileChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            cursorMovementConfig = new CursorMovementConfig(context);
            cursorMovementConfig.updateAllConfigFromSharedPreference();
            // Update UI elements with new profile settings
            realtimeSwipeSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.REALTIME_SWIPE));
            debugSwipeSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DEBUG_SWIPE));
            durationPopOutSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DURATION_POP_OUT));
            directMappingSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DIRECT_MAPPING));
            int edgeHoldDuration = (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.EDGE_HOLD_DURATION);
            int progress = (edgeHoldDuration / 200) - 1;
            holdDurationSeekBar.setProgress(progress);
            holdDurationTxt.setText(String.valueOf(progress + 1));

            dragToggleDurSeekBar.setProgress(((int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.DRAG_TOGGLE_DURATION) / 100) - 1);

            float scaleFactorX = cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_X);
            int scaleFactorXProgress = Math.round((scaleFactorX - 1.0f) * 15);
            headCoordScaleFactorXSeekBar.setProgress(scaleFactorXProgress);
            headCoordScaleFactorXProgress.setText(String.valueOf(scaleFactorX));

            float scaleFactorY = cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_Y);
            int scaleFactorYProgress = Math.round((scaleFactorY - 1.0f) * 15);
            headCoordScaleFactorYSeekBar.setProgress(scaleFactorYProgress);
            headCoordScaleFactorYProgress.setText(String.valueOf(scaleFactorY));

            noseTipSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.NOSE_TIP));
            pitchYawSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.PITCH_YAW));

            float avgSmoothing = cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.AVG_SMOOTHING);
            int avgSmoothingProgress = Math.round(avgSmoothing);
            smoothingSeekBar.setProgress(avgSmoothingProgress);
            smoothingProgress.setText(String.valueOf(avgSmoothing));

            // Update quick tap threshold
            int quickTapThreshold = (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.QUICK_TAP_THRESHOLD);
            int quickTapProgress = (quickTapThreshold - 200) / 100;
            quickTapThresholdSeekBar.setProgress(quickTapProgress);
            progressQuickTapThreshold.setText(String.valueOf(quickTapThreshold));

            // Update long tap threshold
            int longTapThreshold = (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.LONG_TAP_THRESHOLD);
            int longTapProgress = (longTapThreshold - 600) / 100;
            longTapThresholdSeekBar.setProgress(longTapProgress);
            progressLongTapThreshold.setText(String.valueOf(longTapThreshold));

            // Update UI feedback delay
            int uiFeedbackDelay = (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.UI_FEEDBACK_DELAY);
            uiFeedbackDelaySeekBar.setProgress(uiFeedbackDelay - 1);
            progressUiFeedbackDelay.setText(String.valueOf(uiFeedbackDelay - 1));

            // Update UI feedback delay
            int pathCursor = (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.PATH_CURSOR);
            pathCursorSeekBar.setProgress(pathCursor - 1);
            progressPathCursor.setText(String.valueOf(pathCursor - 1));

            // Update UI feedback delay
            int pathCursorMin = (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.PATH_CURSOR_MIN);
            pathCursorMinSeekBar.setProgress(pathCursorMin - 1);
            progressPathCursorMin.setText(String.valueOf(pathCursorMin - 1));
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"));
        } else {
            registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"), RECEIVER_EXPORTED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        unregisterReceiver(profileChangeReceiver);
    }

    private void sendValueToService(String configName, int value) {
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(configName, value);
        editor.apply();
        Intent intent = new Intent("LOAD_SHARED_CONFIG_BASIC");
        intent.putExtra("configName", configName);
        sendBroadcast(intent);
    }

    private void sendValueToService(String configName, float value) {
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat(configName, value);
        editor.apply();
        Intent intent = new Intent("LOAD_SHARED_CONFIG_BASIC");
        intent.putExtra("configName", configName);
        sendBroadcast(intent);
    }

    private void sendValueToService(String configName, boolean value) {
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(configName, value);
        editor.apply();
        Intent intent = new Intent("LOAD_SHARED_CONFIG_BASIC");
        intent.putExtra("configName", configName);
        sendBroadcast(intent);
    }
}
