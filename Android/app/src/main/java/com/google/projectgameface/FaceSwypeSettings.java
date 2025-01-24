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
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.util.Objects;

/** The cursor speed activity of Gameface app. */
public class FaceSwypeSettings extends AppCompatActivity {

    private CursorMovementConfig cursorMovementConfig;
    private Switch realtimeSwipeSwitch;
    private Switch durationPopOutSwitch;
    private Switch directMappingSwitch;
    private Switch debugSwipeSwitch;
    private Switch noseTipSwitch;
    private Switch pitchYawSwitch;
    private SeekBar holdDurationSeekBar;
    private SeekBar smoothingSeekBar;
    private TextView smoothingTxt;
    private TextView smoothingProgress;
    private SeekBar headCoordScaleFactorXSeekBar;
    private SeekBar headCoordScaleFactorYSeekBar;
    private TextView holdDurationTxt;
    private TextView headCoordScaleFactorXTxt;
    private TextView headCoordScaleFactorYTxt;
    private TextView headCoordScaleFactorXProgress;
    private TextView headCoordScaleFactorYProgress;
    private ConstraintLayout holdDurationLayout;
    private ConstraintLayout headCoordScaleFactorLayout;
    private Button switchKeyboardBtn;
    private Button debuggingStatsBtn;

    private final int[] viewIds = {
            R.id.holdDurationFaster,
            R.id.holdDurationSlower,
            R.id.headCoordScaleFactorXSlower,
            R.id.headCoordScaleFactorXFaster,
            R.id.headCoordScaleFactorYSlower,
            R.id.headCoordScaleFactorYFaster,
            R.id.slowerSmoothing,
            R.id.fasterSmoothing
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_faceswype_config);
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
        realtimeSwipeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendValueToService("REALTIME_SWIPE", isChecked);
        });

        // Debug Swipe
        debugSwipeSwitch = findViewById(R.id.debugSwipeSwitch);
        debugSwipeSwitch.setChecked(
                cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DEBUG_SWIPE));
        debugSwipeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendValueToService("DEBUG_SWIPE", isChecked);
        });

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
        headCoordScaleFactorXTxt = findViewById(R.id.headCoordScaleFactorXTxt);
        headCoordScaleFactorYSeekBar = findViewById(R.id.headCoordScaleFactorYSeekBar);
        headCoordScaleFactorYProgress = findViewById(R.id.headCoordScaleFactorYProgress);
        headCoordScaleFactorYTxt = findViewById(R.id.headCoordScaleFactorYTxt);

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
//                Intent intent = new Intent(FaceSwypeSettings.this, CalibrationActivity.class);
//                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
//                startActivity(intent);
                Intent intent = new Intent(FaceSwypeSettings.this, DebuggingStatsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
        });

        // Binding buttons
        for (int id : viewIds) {
            findViewById(id).setOnClickListener(buttonClickListener);
        }
    }

    private final View.OnClickListener buttonClickListener =
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int currentValue;
                    int newValue = 0;
                    boolean isFaster = true; // False means slower

                    if (v.getId() == R.id.holdDurationFaster) {
                        currentValue = holdDurationSeekBar.getProgress();
                        newValue = currentValue + 1;
                    } else if (v.getId() == R.id.holdDurationSlower) {
                        currentValue = holdDurationSeekBar.getProgress();
                        newValue = currentValue - 1;
                    } else if (v.getId() == R.id.headCoordScaleFactorXFaster) {
                        currentValue = headCoordScaleFactorXSeekBar.getProgress();
                        newValue = currentValue + 1;
                    } else if (v.getId() == R.id.headCoordScaleFactorXSlower) {
                        currentValue = headCoordScaleFactorXSeekBar.getProgress();
                        newValue = currentValue - 1;
                    } else if (v.getId() == R.id.headCoordScaleFactorYFaster) {
                        currentValue = headCoordScaleFactorYSeekBar.getProgress();
                        newValue = currentValue + 1;
                    } else if (v.getId() == R.id.headCoordScaleFactorYSlower) {
                        currentValue = headCoordScaleFactorYSeekBar.getProgress();
                        newValue = currentValue - 1;
                    }

                    if ((isFaster && newValue < 15) || (!isFaster && newValue >= 0)) {
                        if (v.getId() == R.id.holdDurationFaster || v.getId() == R.id.holdDurationSlower) {
                            holdDurationSeekBar.setProgress(newValue);
                            int duration = (newValue + 1) * 200;
                            sendValueToService("EDGE_HOLD_DURATION", duration);
                        } else if (v.getId() == R.id.headCoordScaleFactorXFaster || v.getId() == R.id.headCoordScaleFactorXSlower) {
                            headCoordScaleFactorXSeekBar.setProgress(newValue);
                            float scaleFactor = (newValue + 1) / 10.0f;
                            sendValueToService("HEAD_COORD_SCALE_FACTOR_X", scaleFactor);
                        } else if (v.getId() == R.id.headCoordScaleFactorYFaster || v.getId() == R.id.headCoordScaleFactorYSlower) {
                            headCoordScaleFactorYSeekBar.setProgress(newValue);
                            float scaleFactor = (newValue + 1) / 10.0f;
                            sendValueToService("HEAD_COORD_SCALE_FACTOR_Y", scaleFactor);
                        }
                    }
                }
            };

    private void setUpScaleFactorSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(15); // 0.0 to 1.0 in increments of 0.1 means 10 steps
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        float savedProgress = preferences.getFloat(preferencesId, 1.5f); // Default to 1.2
        int progress = Math.round((savedProgress - 1.0f) * 10);
        seekBar.setProgress(progress); // Set initial progress
        textView.setText(String.valueOf(savedProgress)); // Set initial text

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float displayValue = 1.0f + (progress / 10.0f);
                textView.setText(String.valueOf(displayValue));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float scaleFactor = 1.0f + (seekBar.getProgress() / 10.0f);
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
        } else {
            savedProgress = preferences.getInt(preferencesId, CursorMovementConfig.InitialRawValue.DEFAULT_SPEED);
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