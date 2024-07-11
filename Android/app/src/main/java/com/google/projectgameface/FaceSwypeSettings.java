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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private SeekBar holdDurationSeekBar;
    private TextView holdDurationTxt;
    private ConstraintLayout holdDurationLayout;

    private final int[] viewIds = {
            R.id.fasterHoldDuration,
            R.id.slowerHoldDuration
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

        // Binding buttons
        for (int id : viewIds) {
            findViewById(id).setOnClickListener(buttonClickListener);
        }
    }

    private void setUpSeekBarAndTextView(SeekBar seekBar, TextView textView, String preferencesId) {
        seekBar.setMax(9); // 1 to 10 in increments of 1 means 9 steps
        SharedPreferences preferences = getSharedPreferences("GameFaceLocalConfig", Context.MODE_PRIVATE);
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

    private final View.OnClickListener buttonClickListener =
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int currentValue = holdDurationSeekBar.getProgress();
                    int newValue = 0;
                    boolean isFaster = true; // False means slower

                    if (v.getId() == R.id.fasterHoldDuration) {
                        newValue = currentValue + 1;
                        isFaster = true;
                    } else if (v.getId() == R.id.slowerHoldDuration) {
                        newValue = currentValue - 1;
                        isFaster = false;
                    }

                    if ((isFaster && newValue < 10) || (!isFaster && newValue >= 0)) {
                        holdDurationSeekBar.setProgress(newValue);
                        int duration = (newValue + 1) * 200;
                        sendValueToService("EDGE_HOLD_DURATION", duration);
                    }
                }
            };

    private void sendValueToService(String configName, int value) {
        SharedPreferences preferences = getSharedPreferences("GameFaceLocalConfig", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(configName, value);
        editor.apply();
        Intent intent = new Intent("LOAD_SHARED_CONFIG_BASIC");
        intent.putExtra("configName", configName);
        sendBroadcast(intent);
    }

    private void sendValueToService(String configName, boolean value) {
        SharedPreferences preferences = getSharedPreferences("GameFaceLocalConfig", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(configName, value);
        editor.apply();
        Intent intent = new Intent("LOAD_SHARED_CONFIG_BASIC");
        intent.putExtra("configName", configName);
        sendBroadcast(intent);
    }
}