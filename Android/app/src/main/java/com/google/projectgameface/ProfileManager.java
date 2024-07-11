package com.google.projectgameface;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProfileManager {

    public static final String DEFAULT_PROFILE = "Default Profile";
    private static final String PREF_PROFILES = "GameFaceProfiles";
    private static final String PREF_CURRENT_PROFILE = "CurrentProfile";

    public static List<String> getProfiles(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_PROFILES, Context.MODE_PRIVATE);
        String profiles = prefs.getString(PREF_PROFILES, DEFAULT_PROFILE);
        return new ArrayList<>(Arrays.asList(profiles.split(",")));
    }

    public static void addProfile(Context context, String profileName) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_PROFILES, Context.MODE_PRIVATE);
        String profiles = prefs.getString(PREF_PROFILES, DEFAULT_PROFILE);
        if (!profiles.contains(profileName)) {
            profiles = profiles + "," + profileName;
            prefs.edit().putString(PREF_PROFILES, profiles).apply();
        }
    }

    public static void removeProfile(Context context, String profileName) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_PROFILES, Context.MODE_PRIVATE);
        String profiles = prefs.getString(PREF_PROFILES, DEFAULT_PROFILE);
        List<String> profileList = new ArrayList<>(Arrays.asList(profiles.split(",")));
        profileList.remove(profileName);
        profiles = String.join(",", profileList);
        prefs.edit().putString(PREF_PROFILES, profiles).apply();
        // Also remove the profile's settings
        context.getSharedPreferences(profileName, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static String getCurrentProfile(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_PROFILES, Context.MODE_PRIVATE);
        return prefs.getString(PREF_CURRENT_PROFILE, DEFAULT_PROFILE);
    }

    public static void setCurrentProfile(Context context, String profileName) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_PROFILES, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_CURRENT_PROFILE, profileName).apply();
    }
}