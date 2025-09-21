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
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.projectgameface.utils.Config;

import java.util.HashMap;
import java.util.Map;

/**
 * Store cursor movement config such as speed or smoothing value.
 */
class CursorMovementConfig {

    public void updateProfile(Context context, String profileName) {
        sharedPreferences = context.getSharedPreferences(profileName, Context.MODE_PRIVATE);
        updateAllConfigFromSharedPreference();
    }

    public enum CursorMovementConfigType {
        UP_SPEED, DOWN_SPEED, RIGHT_SPEED, LEFT_SPEED, SMOOTH_POINTER, SMOOTH_BLENDSHAPES, HOLD_TIME_MS,
        HOLD_RADIUS, EDGE_HOLD_DURATION, DRAG_TOGGLE_DURATION, HEAD_COORD_SCALE_FACTOR_X,
        HEAD_COORD_SCALE_FACTOR_Y, AVG_SMOOTHING, PATH_CURSOR, QUICK_TAP_THRESHOLD, LONG_TAP_THRESHOLD, UI_FEEDBACK_DELAY,

        LATEST_AVG_WPM, AVG_WPM, AVG_WORDS_PER_PHRASE, AVG_SWIPE_DURATION, AVG_PHRASE_LENGTH,
    }

    public enum CursorMovementBooleanConfigType {
        REALTIME_SWIPE, DURATION_POP_OUT, DIRECT_MAPPING, NOSE_TIP, PITCH_YAW, DEBUG_SWIPE,
        EXPONENTIAL_SMOOTHING,
    }

    private final BroadcastReceiver profileChangeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // Reload the configuration for the new profile
            updateAllConfigFromSharedPreference();
            Log.i(TAG, "Profile changed, updated configs in CursorMovementConfig.");
        }
    };
    private static final String TAG = "CursorMovementConfig";
    private static final int PREFERENCE_INT_NOT_FOUND = -1;

    /**
     * Persistent storage on device (Data/data/{app})
     */
    SharedPreferences sharedPreferences;

    /**
     * Raw int value, same as the UI's slider.
     */
    private final Map<CursorMovementConfigType, Integer> rawValueMap;

    /**
     * Raw float value, same as the UI's slider.
     */
    private final Map<CursorMovementConfigType, Float> rawFloatValueMap;

    /**
     * Raw boolean value, same as the UI's toggle.
     */
    private final Map<CursorMovementBooleanConfigType, Boolean> rawBooleanValueMap;

    public static final class InitialRawValue {
        public static final int SPEED = 3;
        public static final int SMOOTH_POINTER = 1;
        public static final int HOLD_TIME_MS = 5;
        public static final int HOLD_RADIUS = 2;
        public static final boolean REALTIME_SWIPE = Config.DEFAULT_REALTIME_SWIPE;
        public static final boolean DEBUG_SWIPE = Config.DEFAULT_DEBUG_SWIPE;
        public static final boolean DURATION_POP_OUT = Config.DEFAULT_DURATION_POP_OUT;
        public static final boolean DIRECT_MAPPING = Config.DEFAULT_DIRECT_MAPPING;
        public static final boolean EXPONENTIAL_SMOOTHING = Config.DEFAULT_EXPONENTIAL_SMOOTHING;
        public static final int EDGE_HOLD_DURATION = Config.DEFAULT_EDGE_HOLD_DURATION;
        public static final int DRAG_TOGGLE_DURATION = Config.DEFAULT_DRAG_TOGGLE_DURATION;
        public static final float HEAD_COORD_SCALE_FACTOR_X = Config.DEFAULT_HEAD_COORD_SCALE_FACTOR_X;
        public static final float HEAD_COORD_SCALE_FACTOR_Y = Config.DEFAULT_HEAD_COORD_SCALE_FACTOR_Y;
        public static final int AVG_SMOOTHING = Config.DEFAULT_RAW_SMOOTHING;
        public static final boolean PITCH_YAW = Config.DEFAULT_PITCH_YAW;
        public static final boolean NOSE_TIP = Config.DEFAULT_NOSE_TIP;
        public static final int QUICK_TAP_THRESHOLD = Config.DEFAULT_QUICK_TAP_THRESHOLD;
        public static final int LONG_TAP_THRESHOLD = Config.DEFAULT_LONG_TAP_THRESHOLD;
        public static final int UI_FEEDBACK_DELAY = Config.DEFAULT_UI_FEEDBACK_DELAY;
        public static final int PATH_CURSOR = Config.DEFAULT_PATH_CURSOR;

        public static final float LATEST_AVG_WPM = 0.0f;
        public static final float AVG_WPM = 0.0f;
        public static final float AVG_WORDS_PER_PHRASE = 0.0f;
        public static final float AVG_SWIPE_DURATION = 0.0f;
        public static final float AVG_PHRASE_LENGTH = 0.0f;


        public static final boolean DEFAULT_ENABLE_FEATURE = false;
    }


    /**
     * Multiply raw int value from UI to real usable float value.
     */
    public static final class RawConfigMultiplier {
        public static final float UP_SPEED = 175.f;
        public static final float DOWN_SPEED = 175.f;
        public static final float RIGHT_SPEED = 175.f;
        public static final float LEFT_SPEED = 175.f;
        public static final float SMOOTH_POINTER = 5.f;
        public static final float SMOOTH_BLENDSHAPES = 30.f;
        public static final float HOLD_TIME_MS = 200.f;
        public static final float HOLD_RADIUS = 50;
        public static final float EDGE_HOLD_DURATION = 1.0f;
        public static final float DRAG_TOGGLE_DURATION = 1.0f;
        public static final float HEAD_COORD_SCALE_FACTOR_X = 1.0f;
        public static final float HEAD_COORD_SCALE_FACTOR_Y = 1.0f;
        public static final float AVG_SMOOTHING = 1.0f;

        public static final float LATEST_AVG_WPM = 1.0f;
        public static final float AVG_WPM = 1.0f;
        public static final float AVG_WORDS_PER_PHRASE = 1.0f;
        public static final float AVG_SWIPE_DURATION = 1.0f;
        public static final float AVG_PHRASE_LENGTH = 1.0f;

        private RawConfigMultiplier() {}
    }

    /**
     * Stores cursor configs such as LEFT_SPEED or SMOOTH_POINTER.
     * @param context Context for open SharedPreference in device's local storage.
     */
    public CursorMovementConfig(Context context) {
        Log.i(TAG, "Create CursorMovementConfig.");

        // Create or retrieve SharedPreference.
        String profileName = ProfileManager.getCurrentProfile(context);
        sharedPreferences = context.getSharedPreferences(profileName, Context.MODE_PRIVATE);

        // Initialize default slider values.
        rawValueMap = new HashMap<>();
        rawValueMap.put(CursorMovementConfigType.UP_SPEED, InitialRawValue.SPEED);
        rawValueMap.put(CursorMovementConfigType.DOWN_SPEED, InitialRawValue.SPEED);
        rawValueMap.put(CursorMovementConfigType.RIGHT_SPEED, InitialRawValue.SPEED);
        rawValueMap.put(CursorMovementConfigType.LEFT_SPEED, InitialRawValue.SPEED);
        rawValueMap.put(CursorMovementConfigType.SMOOTH_POINTER, InitialRawValue.SMOOTH_POINTER);
        rawValueMap.put(CursorMovementConfigType.SMOOTH_BLENDSHAPES, InitialRawValue.SPEED);
        rawValueMap.put(CursorMovementConfigType.HOLD_TIME_MS, InitialRawValue.HOLD_TIME_MS);
        rawValueMap.put(CursorMovementConfigType.HOLD_RADIUS, InitialRawValue.HOLD_RADIUS);
        rawValueMap.put(CursorMovementConfigType.EDGE_HOLD_DURATION, InitialRawValue.EDGE_HOLD_DURATION);
        rawValueMap.put(CursorMovementConfigType.DRAG_TOGGLE_DURATION, InitialRawValue.DRAG_TOGGLE_DURATION);
        rawValueMap.put(CursorMovementConfigType.AVG_SMOOTHING, InitialRawValue.AVG_SMOOTHING);
        rawValueMap.put(CursorMovementConfigType.QUICK_TAP_THRESHOLD, InitialRawValue.QUICK_TAP_THRESHOLD);
        rawValueMap.put(CursorMovementConfigType.LONG_TAP_THRESHOLD, InitialRawValue.LONG_TAP_THRESHOLD);
        rawValueMap.put(CursorMovementConfigType.UI_FEEDBACK_DELAY, InitialRawValue.UI_FEEDBACK_DELAY);
        rawValueMap.put(CursorMovementConfigType.PATH_CURSOR, InitialRawValue.PATH_CURSOR);

        // Initialize default float values.
        rawFloatValueMap = new HashMap<>();
        rawFloatValueMap.put(CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_X, InitialRawValue.HEAD_COORD_SCALE_FACTOR_X);
        rawFloatValueMap.put(CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_Y, InitialRawValue.HEAD_COORD_SCALE_FACTOR_Y);

        rawFloatValueMap.put(CursorMovementConfigType.LATEST_AVG_WPM, InitialRawValue.LATEST_AVG_WPM);
        rawFloatValueMap.put(CursorMovementConfigType.AVG_WPM, InitialRawValue.AVG_WPM);
        rawFloatValueMap.put(CursorMovementConfigType.AVG_WORDS_PER_PHRASE, InitialRawValue.AVG_WORDS_PER_PHRASE);
        rawFloatValueMap.put(CursorMovementConfigType.AVG_SWIPE_DURATION, InitialRawValue.AVG_SWIPE_DURATION);
        rawFloatValueMap.put(CursorMovementConfigType.AVG_PHRASE_LENGTH, InitialRawValue.AVG_PHRASE_LENGTH);


        // Initialize default boolean values.
        rawBooleanValueMap = new HashMap<>();
        rawBooleanValueMap.put(CursorMovementBooleanConfigType.REALTIME_SWIPE, InitialRawValue.REALTIME_SWIPE);
        rawBooleanValueMap.put(CursorMovementBooleanConfigType.DURATION_POP_OUT, InitialRawValue.DURATION_POP_OUT);
        rawBooleanValueMap.put(CursorMovementBooleanConfigType.DIRECT_MAPPING, InitialRawValue.DIRECT_MAPPING);
        rawBooleanValueMap.put(CursorMovementBooleanConfigType.PITCH_YAW, InitialRawValue.PITCH_YAW);
        rawBooleanValueMap.put(CursorMovementBooleanConfigType.NOSE_TIP, InitialRawValue.NOSE_TIP);
        rawBooleanValueMap.put(CursorMovementBooleanConfigType.DEBUG_SWIPE, InitialRawValue.DEBUG_SWIPE);
        rawBooleanValueMap.put(CursorMovementBooleanConfigType.EXPONENTIAL_SMOOTHING, InitialRawValue.EXPONENTIAL_SMOOTHING);


        // Register the receiver
            ContextCompat.registerReceiver(context, profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    public void unregisterReceiver(Context context) {
        context.unregisterReceiver(profileChangeReceiver);
    }

    public void reloadSharedPreferences(Context context) {
        String profileName = ProfileManager.getCurrentProfile(context);
        sharedPreferences = context.getSharedPreferences(profileName, Context.MODE_PRIVATE);
        updateAllConfigFromSharedPreference();
    }

    /**
     * Set config with the raw value from UI or SharedPreference.
     * @param configName     Name of the target config such as "UP_SPEED" or "SMOOTH_POINTER"
     * @param rawValueFromUi Slider value.
     */
    public void setRawValueFromUi(String configName, int rawValueFromUi) {
        try {
            CursorMovementConfigType targetConfig = CursorMovementConfigType.valueOf(configName);
            rawValueMap.put(targetConfig, rawValueFromUi);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, configName + " does not exist in CursorMovementConfigType enum.");
        }
    }

    /**
     * Set config with the raw float value from UI or SharedPreference.
     * @param configName     Name of the target config such as "HEAD_COORD_SCALE_FACTOR"
     * @param rawValueFromUi Slider value.
     */
    public void setRawFloatValueFromUi(String configName, float rawValueFromUi) {
        try {
            CursorMovementConfigType targetConfig = CursorMovementConfigType.valueOf(configName);
            rawFloatValueMap.put(targetConfig, rawValueFromUi);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, configName + " does not exist in CursorMovementConfigType enum.");
        }
    }

    /**
     * Set boolean config with the raw value from UI or SharedPreference.
     * @param configName     Name of the target config such as "ENABLE_FEATURE_X" or "ENABLE_FEATURE_Y"
     * @param rawValueFromUi Boolean value.
     */
    public void setRawBooleanValueFromUi(String configName, boolean rawValueFromUi) {
        try {
            CursorMovementBooleanConfigType targetConfig = CursorMovementBooleanConfigType.valueOf(configName);
            rawBooleanValueMap.put(targetConfig, rawValueFromUi);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, configName + " does not exist in CursorMovementBooleanConfigType enum.");
        }
    }

    /**
     * Get the config and also apply UI-multiplier value.
     * @param targetConfig Config to get.
     * @return Action value of cursor.
     */
    public float get(CursorMovementConfigType targetConfig) {
        if (targetConfig == CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_X) {
            float rawValue = (rawFloatValueMap.get(targetConfig) != null) ? rawFloatValueMap.get(targetConfig) : InitialRawValue.HEAD_COORD_SCALE_FACTOR_X;
            return rawValue * RawConfigMultiplier.HEAD_COORD_SCALE_FACTOR_X;

        } else if (targetConfig == CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_Y) {
            float rawValue = (rawFloatValueMap.get(targetConfig) != null) ? rawFloatValueMap.get(targetConfig) : InitialRawValue.HEAD_COORD_SCALE_FACTOR_Y;
            return rawValue * RawConfigMultiplier.HEAD_COORD_SCALE_FACTOR_Y;

        } else {
            int rawValue = (rawValueMap.get(targetConfig) != null) ? rawValueMap.get(targetConfig) : 0;
            float multiplier;
            switch (targetConfig) {
                case UP_SPEED:
                    multiplier = RawConfigMultiplier.UP_SPEED;
                    break;
                case DOWN_SPEED:
                    multiplier = RawConfigMultiplier.DOWN_SPEED;
                    break;
                case RIGHT_SPEED:
                    multiplier = RawConfigMultiplier.RIGHT_SPEED;
                    break;
                case LEFT_SPEED:
                    multiplier = RawConfigMultiplier.LEFT_SPEED;
                    break;
                case SMOOTH_POINTER:
                    multiplier = RawConfigMultiplier.SMOOTH_POINTER;
                    break;
                case SMOOTH_BLENDSHAPES:
                    multiplier = RawConfigMultiplier.SMOOTH_BLENDSHAPES;
                    break;
                case HOLD_TIME_MS:
                    multiplier = RawConfigMultiplier.HOLD_TIME_MS;
                    break;
                case HOLD_RADIUS:
                    multiplier = RawConfigMultiplier.HOLD_RADIUS;
                    break;
//                case EDGE_HOLD_DURATION:
//                    multiplier = RawConfigMultiplier.EDGE_HOLD_DURATION;
//                    break;
//                case AVG_SMOOTHING:
//                    multiplier = RawConfigMultiplier.AVG_SMOOTHING;
//                    break;
//                case DRAG_TOGGLE_DURATION:
//                    multiplier = RawConfigMultiplier.DRAG_TOGGLE_DURATION;
//                    break;
                default:
                    multiplier = 1.0f;
            }
            return (float) rawValue * multiplier;
        }
    }

    /**
     * Get the boolean config.
     * @param targetConfig Boolean config to get.
     * @return Boolean value of the config.
     */
    public boolean get(CursorMovementBooleanConfigType targetConfig) {
        return Boolean.TRUE.equals(rawBooleanValueMap.getOrDefault(targetConfig, false));
    }

    /**
     * Update and overwrite value from SharedPreference.
     */
    public void updateAllConfigFromSharedPreference() {
        Log.i(TAG, "Update all config from local SharedPreference...");
        for (CursorMovementConfigType configType: CursorMovementConfigType.values()) {
            updateOneConfigFromSharedPreference(configType.name());
        }
        for (CursorMovementBooleanConfigType configType: CursorMovementBooleanConfigType.values()) {
            updateOneBooleanConfigFromSharedPreference(configType.name());
        }
    }

    /**
     * Update cursor movement config from SharedPreference (persistent storage on device).
     * @param configName String of {@link CursorMovementConfig}.
     */
    public void updateOneConfigFromSharedPreference(String configName) {
        Log.i(TAG, "updateOneConfigFromSharedPreference: " + configName);

        if (sharedPreferences == null) {
            Log.w(TAG, "sharedPreferences instance does not exist.");
            return;
        }

        CursorMovementConfigType targetConfig;
        try {
            targetConfig = CursorMovementConfigType.valueOf(configName);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, configName + " does not exist in CursorMovementBooleanConfigType enum.");
            return;
        }

        if (targetConfig == CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_X) {
            float configValueInUi = sharedPreferences.getFloat(configName, InitialRawValue.HEAD_COORD_SCALE_FACTOR_X);
            setRawFloatValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw float value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_Y) {
            float configValueInUi = sharedPreferences.getFloat(configName, InitialRawValue.HEAD_COORD_SCALE_FACTOR_Y);
            setRawFloatValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw float value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.LATEST_AVG_WPM) {
            float configValueInUi = sharedPreferences.getFloat(configName, InitialRawValue.LATEST_AVG_WPM);
            setRawFloatValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw float value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.AVG_WPM) {
            float configValueInUi = sharedPreferences.getFloat(configName, InitialRawValue.AVG_WPM);
            setRawFloatValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw float value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.AVG_WORDS_PER_PHRASE) {
            float configValueInUi = sharedPreferences.getFloat(configName, InitialRawValue.AVG_WORDS_PER_PHRASE);
            setRawFloatValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw float value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.AVG_SWIPE_DURATION) {
            float configValueInUi = sharedPreferences.getFloat(configName, InitialRawValue.AVG_SWIPE_DURATION);
            setRawFloatValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw float value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.AVG_PHRASE_LENGTH) {
            float configValueInUi = sharedPreferences.getFloat(configName, InitialRawValue.AVG_PHRASE_LENGTH);
            setRawFloatValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw float value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.AVG_SMOOTHING) {
            int configValueInUi = sharedPreferences.getInt(configName, InitialRawValue.AVG_SMOOTHING);
            setRawValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw int value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.EDGE_HOLD_DURATION) {
            int configValueInUi = sharedPreferences.getInt(configName, InitialRawValue.EDGE_HOLD_DURATION);
            setRawValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.DRAG_TOGGLE_DURATION) {
            int configValueInUi = sharedPreferences.getInt(configName, InitialRawValue.DRAG_TOGGLE_DURATION);
            setRawValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.QUICK_TAP_THRESHOLD) {
            int configValueInUi = sharedPreferences.getInt(configName, InitialRawValue.QUICK_TAP_THRESHOLD);
            setRawValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.LONG_TAP_THRESHOLD) {
            int configValueInUi = sharedPreferences.getInt(configName, InitialRawValue.LONG_TAP_THRESHOLD);
            setRawValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.UI_FEEDBACK_DELAY) {
            int configValueInUi = sharedPreferences.getInt(configName, InitialRawValue.UI_FEEDBACK_DELAY);
            setRawValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw value to: " + configValueInUi);
        } else if (targetConfig == CursorMovementConfigType.PATH_CURSOR) {
            int configValueInUi = sharedPreferences.getInt(configName, InitialRawValue.PATH_CURSOR);
            setRawValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw value to: " + configValueInUi);
        } else {
            int configValueInUi = sharedPreferences.getInt(configName, PREFERENCE_INT_NOT_FOUND);
            if (configValueInUi == PREFERENCE_INT_NOT_FOUND) {
                Log.i(TAG, "Key " + configName + " not found in SharedPreference, keep using default value.");
                return;
            }
            setRawValueFromUi(configName, configValueInUi);
            Log.i(TAG, "Set raw value to: " + configValueInUi);
        }
    }

    /**
     * Update boolean cursor movement config from SharedPreference (persistent storage on device).
     * @param configName String of {@link CursorMovementConfig}.
     */
    public void updateOneBooleanConfigFromSharedPreference(String configName) {
        Log.i(TAG, "updateOneBooleanConfigFromSharedPreference: " + configName);

        if (sharedPreferences == null) {
            Log.w(TAG, "sharedPreferences instance does not exist.");
            return;
        }

        CursorMovementBooleanConfigType targetConfig;
        try {
            targetConfig = CursorMovementBooleanConfigType.valueOf(configName);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, configName + " does not exist in CursorMovementBooleanConfigType enum.");
            return;
        }

        boolean defaultValue;
        switch (targetConfig) {
            case REALTIME_SWIPE:
                defaultValue = InitialRawValue.REALTIME_SWIPE;
                break;
            case DURATION_POP_OUT:
                defaultValue = InitialRawValue.DURATION_POP_OUT;
                break;
            case DIRECT_MAPPING:
                defaultValue = InitialRawValue.DIRECT_MAPPING;
                break;
            case PITCH_YAW:
                defaultValue = InitialRawValue.PITCH_YAW;
                break;
            case NOSE_TIP:
                defaultValue = InitialRawValue.NOSE_TIP;
                break;
            case DEBUG_SWIPE:
                defaultValue = InitialRawValue.DEBUG_SWIPE;
                break;
            case EXPONENTIAL_SMOOTHING:
                defaultValue = InitialRawValue.EXPONENTIAL_SMOOTHING;
                break;
            default:
                defaultValue = InitialRawValue.DEFAULT_ENABLE_FEATURE;
                break;
        }

        boolean configValueInUi = sharedPreferences.getBoolean(configName, defaultValue);
        setRawBooleanValueFromUi(configName, configValueInUi);
        Log.i(TAG, "Set raw boolean value to: " + configValueInUi);
    }

    public static boolean isBooleanConfig(String configName) {
        for (CursorMovementBooleanConfigType type: CursorMovementBooleanConfigType.values()) {
            if (type.name().equals(configName)) {
                return true;
            }
        }
        return false;
    }
}
