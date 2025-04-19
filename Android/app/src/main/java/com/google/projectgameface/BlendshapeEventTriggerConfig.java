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

import static android.content.Context.RECEIVER_EXPORTED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.auto.value.AutoValue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The blendshape event trigger config of Gameface app. */
public class BlendshapeEventTriggerConfig {
  private static final String TAG = "BlendshapeEventTriggerConfig";
  private static final int PREFERENCE_INT_NOT_FOUND = -1;

  /** Persistent storage on device (Data/data/{app}) */
  SharedPreferences sharedPreferences;

  /**
   * Events this app can create. such as touch, swipe or some button action. (created event will be
   * dispatch in Accessibility service)
   */
  public enum EventType {
    NONE,
    CURSOR_TOUCH,
    CURSOR_PAUSE,
    CURSOR_RESET,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    SWIPE_UP,
    SWIPE_DOWN,
    DRAG_TOGGLE,
    HOME,
    BACK,
    SHOW_NOTIFICATION,
    SWIPE_START,
    SWIPE_STOP,
    SHOW_APPS,
    TOGGLE_TOUCH,
    CONTINUOUS_TOUCH,
    CURSOR_LONG_TOUCH,
    BEGIN_TOUCH,
    END_TOUCH,
    DELETE_PREVIOUS_WORD,
    SMART_TOUCH,
  }

  // EventType string name used in title bar UI.
  public static final HashMap<EventType, String> BEATIFY_EVENT_TYPE_NAME = new HashMap<EventType, String>() {{
    put(EventType.NONE, "None");
    put(EventType.CURSOR_TOUCH, "Select");
    put(EventType.CURSOR_PAUSE, "Pause / Unpause");
    put(EventType.CURSOR_RESET, "Reset");
    put(EventType.SWIPE_LEFT, "Swipe left");
    put(EventType.SWIPE_RIGHT, "Swipe right");
    put(EventType.SWIPE_UP, "Swipe up");
    put(EventType.SWIPE_DOWN, "Swipe down");
    put(EventType.DRAG_TOGGLE, "Drag toggle");
    put(EventType.HOME, "Home");
    put(EventType.BACK, "Back");
    put(EventType.SHOW_NOTIFICATION, "Notification");
    put(EventType.SHOW_APPS, "All apps");
    put(EventType.TOGGLE_TOUCH, "Toggle touch");
    put(EventType.CONTINUOUS_TOUCH, "Continuous touch");
    put(EventType.CURSOR_LONG_TOUCH, "Long touch");
    put(EventType.BEGIN_TOUCH, "Begin touch");
    put(EventType.END_TOUCH, "End touch");
    put(EventType.DELETE_PREVIOUS_WORD, "Delete previous word");
    put(EventType.SMART_TOUCH, "Combined Tap");
  }};

  // String for display in the UI only.
  public static final HashMap<Blendshape, String> BEAUTIFY_BLENDSHAPE_NAME = new HashMap<Blendshape, String>() {{
    put(Blendshape.NONE, "No binding");
    put(Blendshape.OPEN_MOUTH, "Open mouth");
    put(Blendshape.MOUTH_LEFT, "Mouth left");
    put(Blendshape.MOUTH_RIGHT, "Mouth right");
    put(Blendshape.ROLL_LOWER_MOUTH, "Roll lower mouth");
    put(Blendshape.RAISE_RIGHT_EYEBROW, "Raise right eyebrow");
    put(Blendshape.RAISE_LEFT_EYEBROW, "Raise left eyebrow");
    put(Blendshape.LOWER_RIGHT_EYEBROW, "Lower right eyebrow");
    put(Blendshape.LOWER_LEFT_EYEBROW, "Lower left eyebrow");
    put(Blendshape.SWITCH_ONE, "Switch one");
    put(Blendshape.SWITCH_TWO, "Switch two");
    put(Blendshape.SWITCH_THREE, "Switch three");
    put(Blendshape.SWIPE_FROM_RIGHT_KBD, "Swipe from right side of keyboard");
  }};

  public static final HashMap<EventType, Boolean> EVENT_TYPE_SHOULD_SHOW_SWIPING_INPUTS = new HashMap<EventType, Boolean>() {{
    put(EventType.NONE, false);
    put(EventType.CURSOR_TOUCH, false);
    put(EventType.CURSOR_PAUSE, false);
    put(EventType.CURSOR_RESET, false);
    put(EventType.SWIPE_LEFT, false);
    put(EventType.SWIPE_RIGHT, false);
    put(EventType.SWIPE_UP, false);
    put(EventType.SWIPE_DOWN, false);
    put(EventType.DRAG_TOGGLE, false);
    put(EventType.HOME, true);
    put(EventType.BACK, true);
    put(EventType.SHOW_NOTIFICATION, true);
    put(EventType.SHOW_APPS, true);
    put(EventType.TOGGLE_TOUCH, false);
    put(EventType.CONTINUOUS_TOUCH, false);
    put(EventType.CURSOR_LONG_TOUCH, false);
    put(EventType.BEGIN_TOUCH, false);
    put(EventType.END_TOUCH, false);
    put(EventType.DELETE_PREVIOUS_WORD, true);
    put(EventType.SMART_TOUCH, false);
  }};

  public static final HashMap<Blendshape, Boolean> BLENDSHAPE_IS_SWIPING_INPUT = new HashMap<Blendshape, Boolean>() {{
    put(Blendshape.NONE, false);
    put(Blendshape.OPEN_MOUTH, false);
    put(Blendshape.MOUTH_LEFT, false);
    put(Blendshape.MOUTH_RIGHT, false);
    put(Blendshape.ROLL_LOWER_MOUTH, false);
    put(Blendshape.RAISE_RIGHT_EYEBROW, false);
    put(Blendshape.RAISE_LEFT_EYEBROW, false);
    put(Blendshape.LOWER_RIGHT_EYEBROW, false);
    put(Blendshape.LOWER_LEFT_EYEBROW, false);
    put(Blendshape.SWITCH_ONE, false);
    put(Blendshape.SWITCH_TWO, false);
    put(Blendshape.SWITCH_THREE, false);
    put(Blendshape.SWIPE_FROM_RIGHT_KBD, true);
  }};

  /** Allowed blendshape that our app can use and its array index (from MediaPipe's). */
  public enum Blendshape {
    SWITCH_ONE(-11),
    SWITCH_TWO(-22),
    SWITCH_THREE(-33),
    SWIPE_FROM_RIGHT_KBD(-2),
    NONE(-1),
    OPEN_MOUTH(25),
    MOUTH_LEFT(39),
    MOUTH_RIGHT(33),
    ROLL_LOWER_MOUTH(40),
    RAISE_LEFT_EYEBROW(5),
    LOWER_LEFT_EYEBROW(2),
    RAISE_RIGHT_EYEBROW(4),
    LOWER_RIGHT_EYEBROW(1);
    public final int value;

    Blendshape(int index) {
      this.value = index;
    }
  }

  /** For converting blendshapeIndexInUI to Blendshape enum. */
  protected static final List<Blendshape> BLENDSHAPE_FROM_ORDER_IN_UI = Stream.of(
      Blendshape.OPEN_MOUTH, Blendshape.MOUTH_LEFT,
      Blendshape.MOUTH_RIGHT, Blendshape.ROLL_LOWER_MOUTH,
      Blendshape.RAISE_RIGHT_EYEBROW, Blendshape.RAISE_LEFT_EYEBROW,
      Blendshape.LOWER_RIGHT_EYEBROW, Blendshape.LOWER_LEFT_EYEBROW,
      Blendshape.SWITCH_ONE, Blendshape.SWITCH_TWO,
      Blendshape.SWITCH_THREE, Blendshape.SWIPE_FROM_RIGHT_KBD,
      Blendshape.NONE
  ).collect(Collectors.toList());

  @AutoValue
  abstract static class BlendshapeAndThreshold {
    /**
     * Blendshape and its threshold value
     *
     * @param shape The blendshape target {@link Blendshape}.
     * @param threshold The threshold for trigger some gesture.
     * @return Value of blendshape event trigger.
     */
    static BlendshapeAndThreshold create(Blendshape shape, float threshold) {

      return new AutoValue_BlendshapeEventTriggerConfig_BlendshapeAndThreshold(shape, threshold);
    }

    abstract Blendshape shape();

    abstract float threshold();

    /**
     * Create BlendshapeAndThreshold from blendshape order in UI instead of {@link Blendshape}
     *
     * @param blendshapeIndexInUi Index of the blendshape in UI.
     * @param threshold Range 0 - 1.0.
     * @return BlendshapeAndThreshold.
     */
    @Nullable
    public static BlendshapeAndThreshold createFromIndexInUi(
        int blendshapeIndexInUi, float threshold) {
      if ((blendshapeIndexInUi > BLENDSHAPE_FROM_ORDER_IN_UI.size()) || (blendshapeIndexInUi < 0)) {
        Log.w(
            TAG,
            "Cannot create BlendshapeAndThreshold from blendshapeIndexInUi: "
                + blendshapeIndexInUi);
        return null;
      }
      Blendshape shape = BLENDSHAPE_FROM_ORDER_IN_UI.get(blendshapeIndexInUi);
      return BlendshapeAndThreshold.create(shape, threshold);
    }
  }

  public final HashMap<EventType, BlendshapeAndThreshold> configMap;

  /**
   * Stores event and Blendshape pair that will be triggered when the threshold is passed.
   *
   * @param context Context for open SharedPreference in device's local storage.
   */
  private BroadcastReceiver profileChangeReceiver;

  public BlendshapeEventTriggerConfig(Context context) {
    Log.i(TAG, "Create BlendshapeEventTriggerConfig.");
    // Create or retrieve SharedPreference.
    String profileName = ProfileManager.getCurrentProfile(context);
    sharedPreferences = context.getSharedPreferences(profileName, Context.MODE_PRIVATE);

    configMap = new HashMap<>();
    updateAllConfigFromSharedPreference();

    profileChangeReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        String profileName = ProfileManager.getCurrentProfile(context);
        sharedPreferences = context.getSharedPreferences(profileName, Context.MODE_PRIVATE);
        updateAllConfigFromSharedPreference();
      }
    };
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"), RECEIVER_EXPORTED);
    } else {
      context.registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"));
    }
    updateProfile(context, ProfileManager.getCurrentProfile(context));
  }

  public void updateProfile(Context context, String profileName) {
    sharedPreferences = context.getSharedPreferences(profileName, Context.MODE_PRIVATE);
    updateAllConfigFromSharedPreference();
  }

  public void cleanup(Context context) {
    context.unregisterReceiver(profileChangeReceiver);
  }

  /** Get every EventType-BlendshapeAndThreshold pairs. */
  public HashMap<EventType, BlendshapeAndThreshold> getAllConfig() {
    return configMap;
  }

  public void updateAllConfigFromSharedPreference() {
    Log.i(TAG, "Update all config from local SharedPreference...");
    for (EventType eventType : EventType.values()) {
      updateOneConfigFromSharedPreference(eventType.name());
    }
  }

  /**
   * Update the face blendshape event trigger config from SharedPreference.
   *
   * @param eventTypeString String of {@link EventType} to update, such as "TOUCH" or "SWIPE_LEFT".
   */
  public void updateOneConfigFromSharedPreference(String eventTypeString) {
    Log.i(TAG, "updateOneConfigFromSharedPreference: " + eventTypeString);

    if (sharedPreferences == null) {
      Log.w(TAG, "sharedPreferences instance does not exist.");
      return;
    }

    EventType eventType;
    try {
      eventType = EventType.valueOf(eventTypeString);
    } catch (IllegalArgumentException e) {
      Log.w(TAG, eventTypeString + " not exist in EventType enum.");
      return;
    }

    int blendshapeIndexInUi = sharedPreferences.getInt(eventTypeString, -1);
    if (blendshapeIndexInUi == -1) {
      Log.i(
          TAG,
          "Key " + eventTypeString + " not found in SharedPreference, keep using default value.");
      return;
    }

    int thresholdInUi =
        sharedPreferences.getInt(eventTypeString + "_size", PREFERENCE_INT_NOT_FOUND);
    if (thresholdInUi == PREFERENCE_INT_NOT_FOUND) {
      Log.w(TAG, "Cannot find " + eventTypeString + "_size" + " in SharedPreference.");
      return;
    }

    float threshold = (float) thresholdInUi / 100.f;
    BlendshapeAndThreshold blendshapeAndThreshold =
        BlendshapeAndThreshold.createFromIndexInUi(blendshapeIndexInUi, threshold);

    if (blendshapeAndThreshold != null) {
      configMap.put(eventType, blendshapeAndThreshold);
      Log.i(
          TAG,
          "Apply "
              + eventType.name()
              + " with value: "
              + blendshapeAndThreshold.shape()
              + " "
              + blendshapeAndThreshold.threshold());
    }
  }

  /**
   * Write binding config to local sharedpref a
   * nd also send broadcast to tell background service to update its config.
   * @param blendshape What face gesture needed to perform.
   * @param eventType What event action to trigger.
   * @param thresholdInUI threshold in UI unit from 0 to 100.
   */
  static void writeBindingConfig(Context context, Blendshape blendshape, EventType eventType,
      int thresholdInUI)
  {
    Log.i(TAG, "writeBindingConfig: " + blendshape.toString() +" "+ eventType.toString() + " " + thresholdInUI);

    String profileName = ProfileManager.getCurrentProfile(context);
    SharedPreferences preferences = context.getSharedPreferences(profileName, Context.MODE_PRIVATE);
    SharedPreferences.Editor editor = preferences.edit();
    editor.putInt(eventType.toString(), BLENDSHAPE_FROM_ORDER_IN_UI.indexOf(blendshape));
    editor.putInt(eventType.toString()+"_size", thresholdInUI);
    editor.putString(blendshape.toString()+"_event", eventType.toString());
    editor.apply();

    // Tell service to refresh its config.
    Intent intent = new Intent("LOAD_SHARED_CONFIG_GESTURE");
    intent.putExtra("configName", eventType.toString());
    context.sendBroadcast(intent);
  }

  /**
   * Get description text of event action type.
   * @param eventType
   * @return
   */
  public static String getActionDescription(Context context, BlendshapeEventTriggerConfig.EventType eventType) {
    String[] keys = context.getResources().getStringArray(R.array.event_type_description_keys);
    String[] values = context.getResources().getStringArray(R.array.event_type_description_keys_values);

    int index = Arrays.asList(keys).indexOf(String.valueOf(eventType));
    return values[index];
  }

}
