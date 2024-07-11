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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager.LayoutParams;
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
    private SeekBar holdDurationSeekBar;
    private SeekBar headCoordScaleFactorSeekBar;
    private TextView holdDurationTxt;
    private TextView headCoordScaleFactorTxt;
    private TextView headCoordScaleFactorProgress;
    private ConstraintLayout holdDurationLayout;
    private ConstraintLayout headCoordScaleFactorLayout;

    private final int[] viewIds = {
            R.id.fasterHoldDuration,
            R.id.slowerHoldDuration,
            R.id.headCoordScaleFactorSlower,
            R.id.headCoordScaleFactorFaster
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_faceswype_config);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        // setting actionbar
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("FaceSwype Settings");

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
        headCoordScaleFactorSeekBar = findViewById(R.id.headCoordScaleFactorSeekBar);
        headCoordScaleFactorProgress = findViewById(R.id.headCoordScaleFactorProgress);
        headCoordScaleFactorTxt = findViewById(R.id.headCoordScaleFactorTxt);

        setUpScaleFactorSeekBarAndTextView(
                headCoordScaleFactorSeekBar, headCoordScaleFactorProgress, String.valueOf(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR)
        );

        // Set initial visibility based on the DIRECT_MAPPING value
        headCoordScaleFactorLayout.setVisibility(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DIRECT_MAPPING) ? View.VISIBLE : View.GONE);

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

                    if (v.getId() == R.id.fasterHoldDuration) {
                        currentValue = holdDurationSeekBar.getProgress();
                        newValue = currentValue + 1;
                    } else if (v.getId() == R.id.slowerHoldDuration) {
                        currentValue = holdDurationSeekBar.getProgress();
                        newValue = currentValue - 1;
                    } else if (v.getId() == R.id.headCoordScaleFactorFaster) {
                        currentValue = headCoordScaleFactorSeekBar.getProgress();
                        newValue = currentValue + 1;
                    } else if (v.getId() == R.id.headCoordScaleFactorSlower) {
                        currentValue = headCoordScaleFactorSeekBar.getProgress();
                        newValue = currentValue - 1;
                    }

                    if ((isFaster && newValue < 10) || (!isFaster && newValue >= 0)) {
                        if (v.getId() == R.id.fasterHoldDuration || v.getId() == R.id.slowerHoldDuration) {
                            holdDurationSeekBar.setProgress(newValue);
                            int duration = (newValue + 1) * 200;
                            sendValueToService("EDGE_HOLD_DURATION", duration);
                        } else if (v.getId() == R.id.headCoordScaleFactorFaster || v.getId() == R.id.headCoordScaleFactorSlower) {
                            headCoordScaleFactorSeekBar.setProgress(newValue);
                            float scaleFactor = (newValue + 1) / 10.0f;
                            sendValueToService("HEAD_COORD_SCALE_FACTOR", scaleFactor);
                        }
                    }
                }
            };

    private void setUpScaleFactorSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(10); // 0.0 to 1.0 in increments of 0.1 means 10 steps
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        float savedProgress = preferences.getFloat(preferencesId, 1.2f); // Default to 1.2
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
                sendValueToService("HEAD_COORD_SCALE_FACTOR", scaleFactor);
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

    // Ensure to reload config on profile change
    private final BroadcastReceiver profileChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            cursorMovementConfig = new CursorMovementConfig(context);
            cursorMovementConfig.updateAllConfigFromSharedPreference();
            // Update UI elements with new profile settings
            realtimeSwipeSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.REALTIME_SWIPE));
            durationPopOutSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DURATION_POP_OUT));
            directMappingSwitch.setChecked(cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DIRECT_MAPPING));
            int edgeHoldDuration = (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.EDGE_HOLD_DURATION);
            int progress = (edgeHoldDuration / 200) - 1;
            holdDurationSeekBar.setProgress(progress);
            holdDurationTxt.setText(String.valueOf(progress + 1));

            float scaleFactor = cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR);
            int scaleFactorProgress = Math.round((scaleFactor - 1.0f) * 10);
            headCoordScaleFactorSeekBar.setProgress(scaleFactorProgress);
            headCoordScaleFactorProgress.setText(String.valueOf(scaleFactor));
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"), RECEIVER_EXPORTED);
        } else {
            registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"));
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