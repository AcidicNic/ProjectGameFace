/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dslul.openboard.inputmethod.latin.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;

import org.dslul.openboard.inputmethod.latin.AudioAndHapticFeedbackManager;
import org.dslul.openboard.inputmethod.latin.R;
import org.dslul.openboard.inputmethod.latin.SystemBroadcastReceiver;

/**
 * "Gesture typing preferences" settings sub screen.
 *
 * This settings sub screen handles the following gesture typing preferences.
 * - Enable gesture typing
 * - Dynamic floating preview
 * - Show gesture trail
 * - Phrase gesture
 */
public final class GestureSettingsFragment extends SubScreenFragment {
    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_gesture);


        final Resources res = getResources();
        final Context context = getActivity();

        // When we are called from the Settings application but we are not already running, some
        // singleton and utility classes may not have been initialized.  We have to call
        // initialization method of these classes here. See {@link LatinIME#onCreate()}.
        AudioAndHapticFeedbackManager.init(context);

        final SharedPreferences prefs = getSharedPreferences();

        if (!Settings.isInternal(prefs)) {
            removePreference(Settings.SCREEN_DEBUG);
        }

        setupLangVsSpatialModelWeightSettings();
    }


    private void setupLangVsSpatialModelWeightSettings() {
        final SharedPreferences prefs = getSharedPreferences();
        final Resources res = getResources();
        final SeekBarDialogPreference pref = (SeekBarDialogPreference)findPreference(
                Settings.PREF_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL);
        if (pref == null) {
            return;
        }
        pref.setInterface(new SeekBarDialogPreference.ValueProxy() {
            @Override
            public void writeValue(final int value, final String key) {
                float floatValue = ((value - 1) / 49.0f) * 4.9f + 0.1f;
                prefs.edit().putFloat(key, floatValue).apply();
            }

            @Override
            public void writeDefaultValue(final String key) {
                prefs.edit().putFloat(key, 4.0f).apply();
            }

            @Override
            public int readValue(final String key) {
                float value = Settings.readWeightOfLangModelVsSpatialModel(prefs);
                return Math.round(((value - 0.1f) / 4.9f) * 49 + 1);
            }

            @Override
            public int readDefaultValue(final String key) {
                return 40;
            }

            @Override
            public String getValueText(final int value) {
//                return ((float) (((value - 1) / 19.0f) * 1.9f + 0.1f)) + "f";
                return String.valueOf(value);
            }

            @Override
            public void feedbackValue(final int value) {}
        });
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (key.equals(Settings.PREF_SHOW_SETUP_WIZARD_ICON)) {
            SystemBroadcastReceiver.toggleAppIcon(getActivity());
        }
    }
}
