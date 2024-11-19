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


import static androidx.core.app.ActivityCompat.startActivityForResult;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 200;
    private static final int BLUETOOTH_PERMISSION_CODE = 201;
    private static final int BLUETOOTH_ADMIN_PERMISSION_CODE = 202;
    private static final int BLUETOOTH_CONNECT_PERMISSION_CODE = 203;
    private static final int POST_NOTIFICATIONS_PERMISSION_CODE = 204;
    private static final int MEDIA_PROJECTION_PERMISSION_CODE = 333;
    private static final String KEY_FIRST_RUN = "GameFaceFirstRun";

    private final String TAG = "MainActivity";

    private Intent cursorServiceIntent;

    private SharedPreferences preferences;
    private boolean isServiceBound = false;
    private boolean keep = true;
    private static final String PROFILE_PREFS = "SelectedProfilePrefs";
    private static final String SELECTED_PROFILE_KEY = "selectedProfile";
    private static final String FIRST_LAUNCH_PREFS = "FirstLaunchPrefs";

    private ActivityResultLauncher<Intent> startMediaProjection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Handle the splash screen transition.
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        // Register the result launcher to handle the MediaProjection request
        startMediaProjection = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent intent = new Intent("SCREEN_CAPTURE_PERMISSION_RESULT");
                        intent.putExtra("resultCode", result.getResultCode());
                        intent.putExtra("data", result.getData());
                        sendBroadcast(intent);
                    }
                }
        );

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Spinner setup
        Spinner profileSpinner = findViewById(R.id.profileSpinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ProfileManager.getProfiles(this));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);

        // Restore the selected profile from SharedPreferences
        SharedPreferences profilePrefs = getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);
        String selectedProfile = profilePrefs.getString(SELECTED_PROFILE_KEY, ProfileManager.DEFAULT_PROFILE);
        int selectedIndex = adapter.getPosition(selectedProfile);
        if (selectedIndex != -1) {
            profileSpinner.setSelection(selectedIndex);
        }

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                String selectedProfile = (String) parentView.getItemAtPosition(position);
                ProfileManager.setCurrentProfile(MainActivity.this, selectedProfile);

                // Save selected profile to SharedPreferences
                SharedPreferences profilePrefs = getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = profilePrefs.edit();
                editor.putString(SELECTED_PROFILE_KEY, selectedProfile);
                editor.apply();

                // Broadcast to update all settings
                Intent intent = new Intent("PROFILE_CHANGED");
                sendBroadcast(intent);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });


        try {
            TextView versionNumber = findViewById(R.id.versionNumber);
            String versionName = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0 ).versionName;
            versionNumber.setText(versionName);
        } catch (NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }


        findViewById(R.id.speedRow).setOnClickListener(v -> {
            Intent intent = new Intent(this, CursorSpeed.class);
            startActivity(intent);
        });

        findViewById(R.id.bindingRow).setOnClickListener(v -> {
            Intent intent = new Intent(this, CursorBinding.class);
            startActivity(intent);
        });

        findViewById(R.id.faceSwypeRow).setOnClickListener(v -> {
            Intent intent = new Intent(this, FaceSwypeSettings.class);
            startActivity(intent);
        });


        findViewById(R.id.helpButton).setOnClickListener(v -> {
            Intent intent = new Intent(this, TutorialActivity.class);
            startActivity(intent);
        });


        Switch gameFaceToggleSwitch = findViewById(R.id.gameFaceToggleSwitch);


        //Check if service is enabled.
        checkIfServiceEnabled();

        requestNotificationPermission();

        // Receive service state message and force toggle the switch accordingly
        BroadcastReceiver toggleStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "toggleStateReceiver onReceive");
                if (intent.getAction().equals("SERVICE_STATE")) {
                    int stateIndex = intent.getIntExtra("state", CursorAccessibilityService.ServiceState.DISABLE.ordinal());
                    switch (CursorAccessibilityService.ServiceState.values()[stateIndex]) {
                        case ENABLE:
                            gameFaceToggleSwitch.setChecked(true);
                        case PAUSE:
                            gameFaceToggleSwitch.setChecked(true);
                        case GLOBAL_STICK:
                            gameFaceToggleSwitch.setChecked(true);
                            break;
                        case DISABLE:
                            gameFaceToggleSwitch.setChecked(false);
                            break;
                    }

                }
            }

        };
        registerReceiver(toggleStateReceiver, new IntentFilter("SERVICE_STATE"), RECEIVER_EXPORTED);


        // Toggle switch interaction.
        gameFaceToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if(!checkAccessibilityPermission()){
                gameFaceToggleSwitch.setChecked(false);
                CameraDialog();
            } else if(isChecked){
                wakeUpService();
            } else {
                sleepCursorService();
            }

        });


        if (isFirstLaunch()) {
            // Assign some default binding so user can navigate around.
            Log.i(TAG, "First launch, assign default binding");
            // Your default binding logic here

            // Goto tutorial page.
            Intent intent = new Intent(this, TutorialActivity.class);
            startActivity(intent);

            // Set the first launch flag to false
            SharedPreferences firstLaunchPrefs = getSharedPreferences(FIRST_LAUNCH_PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = firstLaunchPrefs.edit();
            editor.putBoolean(KEY_FIRST_RUN, false);
            editor.apply();
        }


        findViewById(R.id.addProfileButton).setOnClickListener(v -> {
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_add_profile, null);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setView(dialogView);
            AlertDialog dialog = builder.create();
            dialog.show();

            EditText profileNameEditText = dialogView.findViewById(R.id.profileNameEditText);
            Button addButton = dialogView.findViewById(R.id.buttonAdd);
            Button cancelButton = dialogView.findViewById(R.id.buttonCancel);

            addButton.setOnClickListener(view -> {
                String newProfileName = profileNameEditText.getText().toString().trim();
                if (!newProfileName.isEmpty()) {
                    ProfileManager.addProfile(this, newProfileName);
                    adapter.add(newProfileName);
                    adapter.notifyDataSetChanged();
                    profileSpinner.setSelection(adapter.getPosition(newProfileName));
                    dialog.dismiss();
                } else {
                    Toast.makeText(this, "Profile name cannot be empty", Toast.LENGTH_SHORT).show();
                }
            });

            cancelButton.setOnClickListener(view -> dialog.dismiss());
        });

        // Adding the Remove Profile Dialog
        findViewById(R.id.removeProfileButton).setOnClickListener(v -> {
            String currentProfile = (String) profileSpinner.getSelectedItem();
            if (!currentProfile.equals(ProfileManager.DEFAULT_PROFILE)) {
                LayoutInflater inflater = LayoutInflater.from(this);
                View dialogView = inflater.inflate(R.layout.dialog_remove_profile, null);
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setView(dialogView);
                AlertDialog dialog = builder.create();
                dialog.show();

                TextView removeProfileText = dialogView.findViewById(R.id.removeProfileText);
                removeProfileText.setText(Html.fromHtml("Are you sure you want to delete <b>" + currentProfile + "</b>?"));

                Button yesButton = dialogView.findViewById(R.id.buttonYes);
                Button noButton = dialogView.findViewById(R.id.buttonNo);

                yesButton.setOnClickListener(view -> {
                    ProfileManager.removeProfile(this, currentProfile);
                    adapter.remove(currentProfile);
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                });

                noButton.setOnClickListener(view -> dialog.dismiss());
            }
        });

    }

    /**Send broadcast to service request service enable state
     * Service should send back its state via SERVICE_STATE message*/
    public void checkIfServiceEnabled() {
        // send broadcast to service to check its state.
        Intent intent = new Intent("REQUEST_SERVICE_STATE");
        intent.putExtra("state", "main");
        sendBroadcast(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(!isFirstLaunch()){
            CameraDialog();
        }

        checkIfServiceEnabled();
    }

    private void CameraDialog() {
        // Check Camera Permission
        if(!checkCameraPermission()){
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            String alertMsg = "Allow Project GameFace to access \nthe camera?";
            builder.setTitle("Access Camera");
            builder.setMessage(alertMsg);
            builder.setPositiveButton("Allow", (dialog, which) -> {
                RequestCameraPermission();
                dialog.dismiss();
            });
            builder.setNegativeButton("Deny", (dialog, which) -> {
                dialog.cancel();
                Intent intent = new Intent(getBaseContext(), GrantPermissionActivity.class);
                intent.putExtra("permission", "grantCamera");
                startActivity(intent);
            });
            AlertDialog alertDialog = builder.create();
            alertDialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setTextColor(getResources().getColor(R.color.blue));
                Button negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(getResources().getColor(R.color.blue));
            });
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
            Button positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setTransformationMethod(null);
            Button negativeButton = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            negativeButton.setTransformationMethod(null);
        } else {
            AccessibilityDialog();
        }
    }

    public void AccessibilityDialog(){
        // Check Accessibility Permission
        if(!checkAccessibilityPermission()){
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            String alertMsg = "Full control is appropriate for apps \nthat help you with accessibility \nneeds, but not for most apps.";
            builder.setTitle("Allow Project GameFace to have full control of your device?");
            builder.setMessage(alertMsg);
            builder.setPositiveButton("Allow", (dialog, which) -> {
                RequestAccessibilityPermission();
                dialog.dismiss();
            });
            builder.setNegativeButton("Deny", (dialog, which) -> {
                dialog.cancel();
                Intent intent = new Intent(getBaseContext(), GrantPermissionActivity.class);
                intent.putExtra("permission", "grantAccessibility");
                startActivity(intent);
            });
            AlertDialog alertDialog = builder.create();
            alertDialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setTextColor(getResources().getColor(R.color.blue));
                Button negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(getResources().getColor(R.color.blue));
            });
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
            Button positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setTransformationMethod(null);
            Button negativeButton = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            negativeButton.setTransformationMethod(null);
        }
    }

    /**
     * Check the local preferences if this is the first time user launch the app.
     * @return boolean flag
     */
    private boolean isFirstLaunch() {
        SharedPreferences firstLaunchPrefs = getSharedPreferences(FIRST_LAUNCH_PREFS, Context.MODE_PRIVATE);
        return firstLaunchPrefs.getBoolean(KEY_FIRST_RUN, true);
    }

    public void wakeUpService(){
        Log.i(TAG, "MainActivity wakeUpService");
        findViewById(R.id.gameFaceToggleSwitch).setEnabled(false);
        if (!checkAccessibilityPermission()){
            Log.i(TAG, "MainActivity RequestAccessibilityPermission");
            RequestAccessibilityPermission();
            return;
        }
        if (!checkCameraPermission()){
            Log.i(TAG, "MainActivity RequestCameraPermission");
            RequestCameraPermission();
            return;
        }

        // Run onStartCommand in service, currently doing nothing.
        cursorServiceIntent = new Intent(this, CursorAccessibilityService.class);
        startService(cursorServiceIntent);

        // Request MediaProjection permission
//        String currentKeyboardStr = Settings.Secure.getString(
//                getContentResolver(),
//                Settings.Secure.DEFAULT_INPUT_METHOD
//        );
//        if (currentKeyboardStr.contains("google")) {
            MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent());
//        }

        // Send broadcast to wake up service.
        Intent intent = new Intent("CHANGE_SERVICE_STATE");
        intent.putExtra("state", CursorAccessibilityService.ServiceState.ENABLE.ordinal());
        sendBroadcast(intent);

        Intent intentFlyOut = new Intent("FLY_OUT_FLOAT_WINDOW");
        sendBroadcast(intentFlyOut);
        findViewById(R.id.gameFaceToggleSwitch).setEnabled(true);
    }
    public void sleepCursorService(){
        Log.i(TAG, "sleepCursorService");
        findViewById(R.id.gameFaceToggleSwitch).setEnabled(false);
        // Send broadcast to stop service (sleep mode).
        Intent intent = new Intent("CHANGE_SERVICE_STATE");
        intent.putExtra("state", CursorAccessibilityService.ServiceState.DISABLE.ordinal());
        sendBroadcast(intent);
        if (isServiceBound) {
            isServiceBound = false;
        }
        cursorServiceIntent = null;
        findViewById(R.id.gameFaceToggleSwitch).setEnabled(true);
    }

    public boolean checkAccessibilityPermission() {
        int enabled = 0;
        final String gamefaceServiceName = this.getPackageName()
            + "/"
            + this.getPackageName()
            + "."
            + CursorAccessibilityService.class.getSimpleName();

        Log.i(TAG, "GameFace service name: "+gamefaceServiceName);

        try {
            enabled = Settings.Secure.getInt(
                    this.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED
            );
        } catch (Settings.SettingNotFoundException e) {
            // Handle the exception
        }

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        if (enabled == 1) {
            String allAccessibilityServices = Settings.Secure.getString(
                    this.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );

            if (allAccessibilityServices != null) {
                splitter.setString(allAccessibilityServices);
                while (splitter.hasNext()) {
                    String accessibilityService = splitter.next();
                    if (accessibilityService.equalsIgnoreCase(gamefaceServiceName)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // Request accessibility permission using intent
    public void RequestAccessibilityPermission()
    {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }


    public boolean checkCameraPermission()
    {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }


    // Request camera permission using basic requestPermissions method
    public void RequestCameraPermission()
    {
        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.CAMERA
        }, CAMERA_PERMISSION_CODE);
    }

    private void requestNotificationPermission() {
        // Only for Android 13 and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Check if the notification permission is granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                // Request the POST_NOTIFICATIONS permission
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        POST_NOTIFICATIONS_PERMISSION_CODE);
            }
        }
    }
}
