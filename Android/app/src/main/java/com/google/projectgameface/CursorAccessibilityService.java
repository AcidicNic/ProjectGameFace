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

import static java.lang.Math.max;
import static java.lang.Math.round;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The cursor service of GameFace app. */
@SuppressLint("UnprotectedReceiver") // All of the broadcasts can only be sent by system.
public class CursorAccessibilityService extends AccessibilityService implements LifecycleOwner {
    private static final String TAG = "CursorAccessibilityService";

    /** Limit UI update rate to 60 fps */
    public static final int UI_UPDATE = 16;

    /** Limit the FaceLandmark detect rate. */
    private static final int MIN_PROCESS = 30;


    private static final int IMAGE_ANALYZER_WIDTH = 300;
    private static final int IMAGE_ANALYZER_HEIGHT = 400;
    ServiceUiManager serviceUiManager;
    public CursorController cursorController;
    private FaceLandmarkerHelper facelandmarkerHelper;
    public WindowManager windowManager;
    private Handler tickFunctionHandler;
    public Point screenSize;

    private ProcessCameraProvider cameraProvider;

    /** Blocking ML operations are performed using this executor */
    private ExecutorService backgroundExecutor;

    private LifecycleRegistry lifecycleRegistry;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private long lastSendMessage = 0;
    private BroadcastReceiver changeServiceStateReceiver;
    private BroadcastReceiver requestServiceStateReceiver;
    private BroadcastReceiver loadSharedConfigBasicReceiver;
    private BroadcastReceiver loadSharedConfigGestureReceiver;
    private BroadcastReceiver enableScorePreviewReceiver;
    private BroadcastReceiver profileChangeReceiver;
    private boolean isSwiping = false;
    private static final long GESTURE_DURATION = 100;
    private static final long MIN_GESTURE_DURATION = 100;
    private static final float DURATION_MULTIPLIER = 2.0f;
    private boolean gestureInProgress = false;
    public boolean durationPopOut;
    private Rect keyboardBounds = new Rect();
    private boolean isKeyboardOpen = false;
    private long startTime;
    private Instrumentation instrumentation;
    private Handler handler;
    private HandlerThread handlerThread;

    /** This is state of cursor. */
    public enum ServiceState {
        ENABLE,
        DISABLE,
        /** User cannot move cursor but can still perform event from face gesture. */
        PAUSE,
        /**
         * For user to see themself in config page. Remove buttons and make camera feed static.
         */
        GLOBAL_STICK
    }

    private ServiceState serviceState = ServiceState.DISABLE;

    /** The setting app may request the float blendshape score. */
    private String requestedScoreBlendshapeName = "";

    /** Should we send blendshape score to front-end or not. */
    private boolean shouldSendScore = false;

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "ObsoleteSdkInt"})
    private void defineAndRegisterBroadcastMessageReceivers() {

        loadSharedConfigBasicReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String configName = intent.getStringExtra("configName");
                        if (CursorMovementConfig.isBooleanConfig(configName)) {
                            cursorController.cursorMovementConfig.updateOneBooleanConfigFromSharedPreference(configName);
                        } else {
                            cursorController.cursorMovementConfig.updateOneConfigFromSharedPreference(configName);
                        }
                    }
                };

        loadSharedConfigGestureReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String configName = intent.getStringExtra("configName");
                    cursorController.blendshapeEventTriggerConfig.updateOneConfigFromSharedPreference(
                        configName);
                }
            };

        changeServiceStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int receivedEnumValue = intent.getIntExtra("state", -1);
                    Log.i(TAG, "changeServiceStateReceiver: " + ServiceState.values()[receivedEnumValue]);

                    // Target state to be changing.
                    switch (ServiceState.values()[receivedEnumValue]) {
                        case ENABLE:
                            enableService();
                            break;
                        case DISABLE:
                            disableService();
                            break;
                        case PAUSE:
                            togglePause();
                            break;
                        case GLOBAL_STICK:
                            enterGlobalStickState();
                            break;
                    }
                }
            };

        requestServiceStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String state = intent.getStringExtra("state");
                    sendBroadcastServiceState(state);
                }
            };

        enableScorePreviewReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    shouldSendScore = intent.getBooleanExtra("enable", false);
                    requestedScoreBlendshapeName = intent.getStringExtra("blendshapesName");
                }
            };

        profileChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Profile change detected. Reloading configuration.");
                cursorController.cursorMovementConfig.reloadSharedPreferences(context);
                cursorController.blendshapeEventTriggerConfig.updateAllConfigFromSharedPreference();
            }
        };


        if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            registerReceiver(
                changeServiceStateReceiver, new IntentFilter("CHANGE_SERVICE_STATE"), RECEIVER_EXPORTED);
            registerReceiver(
                requestServiceStateReceiver,
                new IntentFilter("REQUEST_SERVICE_STATE"),
                RECEIVER_EXPORTED);
            registerReceiver(
                loadSharedConfigBasicReceiver,
                new IntentFilter("LOAD_SHARED_CONFIG_BASIC"),
                RECEIVER_EXPORTED);
            registerReceiver(
                loadSharedConfigGestureReceiver,
                new IntentFilter("LOAD_SHARED_CONFIG_GESTURE"),
                RECEIVER_EXPORTED);
            registerReceiver(
                enableScorePreviewReceiver, new IntentFilter("ENABLE_SCORE_PREVIEW"), RECEIVER_EXPORTED);
            registerReceiver(
                serviceUiManager.flyInWindowReceiver,
                new IntentFilter("FLY_IN_FLOAT_WINDOW"),
                RECEIVER_EXPORTED);
            registerReceiver(
                serviceUiManager.flyOutWindowReceiver,
                new IntentFilter("FLY_OUT_FLOAT_WINDOW"),
                RECEIVER_EXPORTED);
            registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"), RECEIVER_EXPORTED);
        } else {
            registerReceiver(changeServiceStateReceiver, new IntentFilter("CHANGE_SERVICE_STATE"));
            registerReceiver(requestServiceStateReceiver, new IntentFilter("REQUEST_SERVICE_STATE"));
            registerReceiver(loadSharedConfigBasicReceiver, new IntentFilter("LOAD_SHARED_CONFIG_BASIC"));
            registerReceiver(
                loadSharedConfigGestureReceiver, new IntentFilter("LOAD_SHARED_CONFIG_GESTURE"));
            registerReceiver(enableScorePreviewReceiver, new IntentFilter("ENABLE_SCORE_PREVIEW"));
            registerReceiver(
                serviceUiManager.flyInWindowReceiver, new IntentFilter("FLY_IN_FLOAT_WINDOW"));
            registerReceiver(
                serviceUiManager.flyOutWindowReceiver, new IntentFilter("FLY_OUT_FLOAT_WINDOW"));
            registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"));
        }
    }

    /** Get current service state. */
    public ServiceState getServiceState() {
        return serviceState;
    }

    /**
     * One-time service setup. This will run immediately after user toggle grant Accessibility
     *
     * <p>permission.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "my onCreate");

//        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);

        instrumentation = new Instrumentation();
        handlerThread = new HandlerThread("MotionEventThread");
        handlerThread.start();

        windowManager = ContextCompat.getSystemService(this, WindowManager.class);

        screenSize = new Point();
        windowManager.getDefaultDisplay().getRealSize(screenSize);

        cursorController = new CursorController(this, screenSize.x, screenSize.y);
        serviceUiManager = new ServiceUiManager(this, windowManager, cursorController);

        lifecycleRegistry = new LifecycleRegistry(this::getLifecycle);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        defineAndRegisterBroadcastMessageReceivers();

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor();

        backgroundExecutor.execute(
            () -> {
                facelandmarkerHelper = new FaceLandmarkerHelper();
                facelandmarkerHelper.setFrontCameraOrientation(CameraHelper.checkFrontCameraOrientation(this));
                facelandmarkerHelper.setRotation(windowManager.getDefaultDisplay().getRotation());
                facelandmarkerHelper.start();
                facelandmarkerHelper.init(this);
            });

        setImageAnalyzer();

        // Initialize the Handler
        tickFunctionHandler = new Handler();
        tickFunctionHandler.postDelayed(tick, 0);

        if (isPlatformSignedAndCanInjectEvents()) {
            Log.d(TAG, "Platform signed and can inject events!");
        }
    }

    private boolean isPlatformSigned() {
        try {
            PackageManager pm = getPackageManager();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28 and above
                PackageInfo packageInfo = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo platformPackageInfo = pm.getPackageInfo("android", PackageManager.GET_SIGNING_CERTIFICATES);

                if (packageInfo.signingInfo != null && platformPackageInfo.signingInfo != null) {
                    Signature[] appSignatures = packageInfo.signingInfo.getApkContentsSigners();
                    Signature[] platformSignatures = platformPackageInfo.signingInfo.getApkContentsSigners();

                    for (Signature appSignature : appSignatures) {
                        for (Signature platformSignature : platformSignatures) {
                            if (appSignature.equals(platformSignature)) {
                                return true;
                            }
                        }
                    }
                }
            } else { // Below API 28
                PackageInfo packageInfo = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
                PackageInfo platformPackageInfo = pm.getPackageInfo("android", PackageManager.GET_SIGNATURES);

                Signature[] appSignatures = packageInfo.signatures;
                Signature[] platformSignatures = platformPackageInfo.signatures;

                for (Signature appSignature : appSignatures) {
                    for (Signature platformSignature : platformSignatures) {
                        if (appSignature.equals(platformSignature)) {
                            return true;
                        }
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isPlatformSignedAndCanInjectEvents() {
        boolean isPlatformSigned = isPlatformSigned();
        boolean canInjectEvents = checkCallingOrSelfPermission("android.permission.INJECT_EVENTS") == PackageManager.PERMISSION_GRANTED;


        if (canInjectEvents) {
            Log.d(TAG, "INJECT_EVENTS permission granted!");
        } else {
            Log.d(TAG, "INJECT_EVENTS permission not granted.");
        }

        if (isPlatformSigned) {
            Log.d(TAG, "App is platform signed!");
        } else {
            Log.d(TAG, "App is not platform signed.");
        }
        return canInjectEvents && isPlatformSigned;
    }

    public boolean isRealtimeSwipeEnabled() {
        return cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.REALTIME_SWIPE);
    }

    /** Set image property to match the MediaPipe model. - Using RGBA 8888. - Lowe the resolution. */
    private ImageAnalysis imageAnalyzer =
        new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                new ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        new ResolutionStrategy(
                            new Size(IMAGE_ANALYZER_WIDTH, IMAGE_ANALYZER_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
            .build();

    /**
     * Tick function of the service. This function runs every {@value UI_UPDATE}
     *
     * <p>Milliseconds. 1. Update cursor location on screen. 2. Dispatch event. 2. Change status icon.
     */
    private final Runnable tick =
        new Runnable() {
            @Override
            public void run() {
                if (facelandmarkerHelper == null) {
                    // Back-off.
                    tickFunctionHandler.postDelayed(this, CursorAccessibilityService.UI_UPDATE);
                }

                switch (serviceState) {
                    case GLOBAL_STICK:
                        if (shouldSendScore) {
                            sendBroadcastScore();
                        }
                    case ENABLE:
                        // Drag drag line if in drag mode.
                        if (cursorController.isDragging) {
                            serviceUiManager.updateDragLine(cursorController.getCursorPositionXY());
                        }

                        // Use for smoothing.
                        int gapFrames =
                                round(max(((float) facelandmarkerHelper.gapTimeMs / (float) UI_UPDATE), 1.0f));

                        cursorController.updateInternalCursorPosition(
                                facelandmarkerHelper.getHeadCoordXY(),
                                gapFrames, screenSize.x, screenSize.y
                        );

                        // Actually update the UI cursor image.
                        serviceUiManager.updateCursorImagePositionOnScreen(
                                cursorController.getCursorPositionXY()
                        );

                        dispatchEvent();

                        serviceUiManager.drawHeadCenter(
                                facelandmarkerHelper.getHeadCoordXY(),
                                facelandmarkerHelper.mpInputWidth,
                                facelandmarkerHelper.mpInputHeight
                        );

                        serviceUiManager.updateDebugTextOverlay(
                                facelandmarkerHelper.preprocessTimeMs,
                                facelandmarkerHelper.mediapipeTimeMs,
                                serviceState == ServiceState.PAUSE
                        );

                        if (cursorController.isSwiping()) {
                            serviceUiManager.fullScreenCanvas.invalidate();
                        }

                        break;

                    case PAUSE:
                        // In PAUSE state user cannot move cursor
                        // but still can perform some event from face gesture.
                        dispatchEvent();

                        serviceUiManager.drawHeadCenter(
                                facelandmarkerHelper.getHeadCoordXY(),
                                facelandmarkerHelper.mpInputWidth,
                                facelandmarkerHelper.mpInputHeight
                        );

                        serviceUiManager.updateDebugTextOverlay(
                                facelandmarkerHelper.preprocessTimeMs,
                                facelandmarkerHelper.mediapipeTimeMs,
                                getServiceState() == ServiceState.PAUSE
                        );

                        break;

                    default:
                        break;
                }

                serviceUiManager.updateStatusIcon(
                        serviceState == ServiceState.PAUSE, checkFaceVisibleInFrame()
                );

                tickFunctionHandler.postDelayed(this, CursorAccessibilityService.UI_UPDATE);
            }
        };

    /** Assign function to image analyzer to send it to MediaPipe */
    private void setImageAnalyzer() {
        imageAnalyzer.setAnalyzer(
            backgroundExecutor,
            imageProxy -> {
                if ((SystemClock.uptimeMillis() - lastSendMessage) > MIN_PROCESS) {

                    // Create a new message and attach image.
                    Message msg = Message.obtain();
                    msg.obj = imageProxy;

                    if ((facelandmarkerHelper != null) && (facelandmarkerHelper.getHandler() != null)) {
                        // Send message to the thread to process.
                        facelandmarkerHelper.getHandler().sendMessage(msg);
                        lastSendMessage = SystemClock.uptimeMillis();
                    }

                } else {
                    // It will be closed by FaceLandmarkHelper.
                    imageProxy.close();
                }
            });
    }

    /** Send out blendshape score for visualize in setting page.*/
    private void sendBroadcastScore() {
        if (!shouldSendScore) {
            return;
        }

        // Get float score of the requested blendshape.
        try {
            BlendshapeEventTriggerConfig.Blendshape enumValue =
                BlendshapeEventTriggerConfig.Blendshape.valueOf(requestedScoreBlendshapeName);

            float score = facelandmarkerHelper.getBlendshapes()[enumValue.value];
            Intent intent = new Intent(requestedScoreBlendshapeName);
            intent.putExtra("score", score);
            sendBroadcast(intent);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "No Blendshape named " + requestedScoreBlendshapeName);
        }
    }

    private void sendBroadcastServiceState(String state) {
        Intent intent;
        if (state.equals("main")) {
            intent = new Intent("SERVICE_STATE");
        } else {
            intent = new Intent("SERVICE_STATE_GESTURE");
        }
        intent.putExtra("state", serviceState.ordinal());
        sendBroadcast(intent);
    }

    /** Called from startService in MainActivity. After user click the "Start" button. */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        serviceUiManager.cameraBoxView.findViewById(R.id.popBtn).setBackground(null);

        return START_STICKY;
    }

    /** Toggle between Pause <-> ENABLE. */
    public void togglePause() {
        switch (serviceState) {
            case ENABLE:
                // Already enable, goto pause mode.
                serviceState = ServiceState.PAUSE;
                serviceUiManager.hideCursor();
                break;

            case PAUSE:
                // In pause mode, enable it.
                serviceState = ServiceState.ENABLE;
                serviceUiManager.showCursor();
                break;
            default:
        }
        serviceUiManager.setCameraBoxDraggable(true);
    }

    /**
     * Enter {@link ServiceState#GLOBAL_STICK} state.
     * For binding gesture size page.
     * Remove buttons and make camera feed static.
     */
    public void enterGlobalStickState() {
        Log.i(TAG, "enterGlobalStickState");
        switch (serviceState) {
            case PAUSE:
                togglePause();
                break;
            case DISABLE:
                enableService();
                break;
            default:
                break;
        }
        serviceState = ServiceState.GLOBAL_STICK;
        serviceUiManager.setCameraBoxDraggable(false);
    }

    /** Enable GameFace service. */
    public void enableService() {
        Log.i(TAG, "enableService, current: "+serviceState);

        switch (serviceState) {
            case ENABLE:
                return;


            case DISABLE:
                //Start camera.
                cameraProviderFuture = ProcessCameraProvider.getInstance(this);
                cameraProviderFuture.addListener(
                        () -> {
                            try {
                                cameraProvider = cameraProviderFuture.get();
                                CameraHelper.bindPreview(
                                        cameraProvider, serviceUiManager.innerCameraImageView, imageAnalyzer, this);
                            } catch (ExecutionException | InterruptedException e) {
                                Log.e(TAG, "cameraProvider failed to get provider future: " + e.getMessage());
                            }
                        },
                        ContextCompat.getMainExecutor(this));

                facelandmarkerHelper.resumeThread();
                setImageAnalyzer();
                cursorController.resetHeadCoord();

            case PAUSE:
            case GLOBAL_STICK:

                break;
            default:
        }

        serviceUiManager.showAllWindows();
        serviceUiManager.fitCameraBoxToScreen();
        serviceUiManager.setCameraBoxDraggable(true);

        serviceState = ServiceState.ENABLE;

    }

    /** Disable GameFace service. */
    public void disableService() {
        Log.i(TAG, "disableService");
        switch (serviceState) {
            case ENABLE:
            case GLOBAL_STICK:
            case PAUSE:
                serviceUiManager.hideAllWindows();
                serviceUiManager.setCameraBoxDraggable(true);

                // stop the service functions.
                facelandmarkerHelper.pauseThread();
                imageAnalyzer.clearAnalyzer();

                // Stop camera.
                cameraProviderFuture = ProcessCameraProvider.getInstance(this);
                cameraProviderFuture.addListener(
                    () -> {
                        try {
                            cameraProvider = cameraProviderFuture.get();
                            cameraProvider.unbindAll();
                        } catch (ExecutionException | InterruptedException e) {
                            Log.e(TAG, "cameraProvider failed to get provider future: " + e.getMessage());
                        }
                    },
                    ContextCompat.getMainExecutor(this));
                serviceState = ServiceState.DISABLE;

                break;
            default:
                break;
        }
    }

    /** Destroy GameFace service and unregister broadcasts. */
    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        disableService();
        disableSelf();
        handlerThread.quitSafely();
        // Unregister when the service is destroyed
        unregisterReceiver(changeServiceStateReceiver);
        unregisterReceiver(loadSharedConfigBasicReceiver);
        unregisterReceiver(loadSharedConfigGestureReceiver);
        unregisterReceiver(requestServiceStateReceiver);
        unregisterReceiver(enableScorePreviewReceiver);
        unregisterReceiver(serviceUiManager.flyInWindowReceiver);
        unregisterReceiver(serviceUiManager.flyOutWindowReceiver);
        unregisterReceiver(profileChangeReceiver);
        cursorController.cleanup();

        super.onDestroy();
    }


    /** Function for perform {@link BlendshapeEventTriggerConfig.EventType} actions. */
    private void dispatchEvent() {
        // Check what event to dispatch.
        BlendshapeEventTriggerConfig.EventType event =
            cursorController.createCursorEvent(facelandmarkerHelper.getBlendshapes());


        switch (event) {
            case NONE:
                return;
            case DRAG_TOGGLE:
                break;
            default:
                // Cancel drag if user perform any other event.
                cursorController.prepareDragEnd(0, 0);
                serviceUiManager.fullScreenCanvas.clearDragLine();
                break;
        }



        switch (serviceState) {
            case GLOBAL_STICK:
            case ENABLE:
                // Check event type and dispatch it.
                DispatchEventHelper.checkAndDispatchEvent(
                    this,
                    cursorController,
                    serviceUiManager,
                    event);
                break;

            case PAUSE:
                // In PAUSE state user can only perform togglePause
                // with face gesture.
                if (event == BlendshapeEventTriggerConfig.EventType.CURSOR_PAUSE) {
                    togglePause();
                }
                if (cursorController.isDragging) {
                    serviceUiManager.fullScreenCanvas.clearDragLine();
                    cursorController.prepareDragEnd(0, 0);
                }
                break;
            default:
                break;
        }
    }

    private Boolean checkFaceVisibleInFrame() {
        if (facelandmarkerHelper == null) {
            return false;
        }
        return facelandmarkerHelper.isFaceVisible;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged");
        super.onConfigurationChanged(newConfig);

        // Temporary hide UIs while screen is rotating.
        serviceUiManager.hideAllWindows();

        windowManager.getDefaultDisplay().getRealSize(screenSize);

        // Rotate mediapipe input.
        if (windowManager != null && facelandmarkerHelper != null) {
            int newRotation = windowManager.getDefaultDisplay().getRotation();
            facelandmarkerHelper.setRotation(newRotation);
        }

        // On-going drag event will be cancel when screen is rotate.
        cursorController.prepareDragEnd(0, 0);
        serviceUiManager.fullScreenCanvas.clearDragLine();

        switch (serviceState)  {
            case ENABLE:
            case GLOBAL_STICK:
                serviceUiManager.showAllWindows();
            case PAUSE:
                serviceUiManager.showCameraBox();
                break;
            case DISABLE:
                break;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (serviceState != ServiceState.ENABLE) { return; }
        checkForKeyboard(event);
    }

    private void checkForKeyboard(AccessibilityEvent event) {
//        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean keyboardFound = false;
            Rect tempBounds = new Rect();
            Rect navBarBounds = new Rect();
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                window.getBoundsInScreen(tempBounds);
//                Log.d(TAG, "Window title: " + window.getTitle() + ", type: " + window.getType() + ", bounds: " + tempBounds);
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    keyboardFound = true;
                    window.getBoundsInScreen(keyboardBounds);
                    if (keyboardBounds.equals(cursorController.getTemporaryBounds())) {
                        return;
                    }
                    Log.d(TAG, "keyboard Found @ : " + window);
                } else if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM
                        && window.getTitle() != null && window.getTitle().equals("Navigation bar")
                ) {
                    window.getBoundsInScreen(navBarBounds);
                }
            }

            if (keyboardFound == isKeyboardOpen && keyboardBounds.equals(cursorController.getTemporaryBounds())) {
                return;
            }
            isKeyboardOpen = keyboardFound;

            if (isKeyboardOpen) {
                if (!navBarBounds.isEmpty()) {
                    keyboardBounds.union(navBarBounds); // Expand the bounds to include the navigation bar
                    Log.d(TAG, "Navigation bar bounds added: " + navBarBounds);
                }
                cursorController.setTemporaryBounds(keyboardBounds);
                serviceUiManager.fullScreenCanvas.setRect(keyboardBounds);
            } else {
                cursorController.clearTemporaryBounds();
                serviceUiManager.fullScreenCanvas.setRect(null);
            }
            serviceUiManager.fullScreenCanvas.invalidate();

            Log.d(TAG, "Keyboard " + (isKeyboardOpen ? "opened" : "closed"));
//        }, 400);
    }


    @Override
    public void onInterrupt() {}

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG,"Service connected");
    }


    private final List<Integer> validKeyEventKeys = Arrays.asList(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4);
//    private final List<Integer> validKeyEventActions = Arrays.asList(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP);

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE) { return false; }
        if (validKeyEventKeys.contains(event.getKeyCode())) {
            return handleKeyEvent(event);
        }
        return false;
    }

    private boolean handleKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE) { return false; }
        int eventAction = event.getAction();
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
                if (eventAction == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "SPACE KeyEvent.ACTION_DOWN");
                    DispatchEventHelper.checkAndDispatchEvent(
                        this,
                        cursorController,
                        serviceUiManager,
                        BlendshapeEventTriggerConfig.EventType.CURSOR_TOUCH);
                }
                return true;
            case KeyEvent.KEYCODE_ENTER:
                if (eventAction == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "ENTER KeyEvent.ACTION_DOWN");
                    if (isRealtimeSwipeEnabled()) {
                        startRealtimeSwipe();
                    } else {
                        DispatchEventHelper.checkAndDispatchEvent(
                                this,
                                cursorController,
                                serviceUiManager,
                                BlendshapeEventTriggerConfig.EventType.SWIPE_START);
                    }
                } else if (eventAction == KeyEvent.ACTION_UP) {
                    Log.d(TAG, "ENTER KeyEvent.ACTION_UP");
                    if (isRealtimeSwipeEnabled()) {
                        stopRealtimeSwipe();
                    } else {
                        DispatchEventHelper.checkAndDispatchEvent(
                                this,
                                cursorController,
                                serviceUiManager,
                                BlendshapeEventTriggerConfig.EventType.SWIPE_STOP);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_2:
                return true;
        }
        return false;
    }

    private void startRealtimeSwipe() {
        isSwiping = true;
        new Thread(() -> {
            startTime = SystemClock.uptimeMillis();
            float[] initialPosition = getCursorPosition();
            MotionEvent event = MotionEvent.obtain(
                    startTime,
                    startTime,
                    MotionEvent.ACTION_DOWN,
                    initialPosition[0],
                    initialPosition[1],
                    0
            );

            Log.d(TAG, "MotionEvent.ACTION_DOWN @ (" + initialPosition[0] + ", " + initialPosition[1] + ")");
//            instrumentation.sendPointerSync(event);
            injectInputEvent(event);

            while (isSwiping) {
                float[] cursorPosition = getCursorPosition();
                event = MotionEvent.obtain(
                        startTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        cursorPosition[0],
                        cursorPosition[1],
                        0
                );
                try {
//                    instrumentation.sendPointerSync(event);
                    injectInputEvent(event);
                    Thread.sleep(16); // 60 FPS
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (RuntimeException e) {
                    // Log the error if sendPointerSync is called from the main thread.
                    Log.e(TAG, "sendPointerSync cannot be called from the main thread.", e);
                }
            }
        }).start();
    }

    private void stopRealtimeSwipe() {
        isSwiping = false;
        new Thread(() -> {
            long endTime = SystemClock.uptimeMillis();
            float[] cursorPosition = getCursorPosition();
            MotionEvent event = MotionEvent.obtain(
                    startTime,
                    endTime,
                    MotionEvent.ACTION_UP,
                    cursorPosition[0],
                    cursorPosition[1],
                    0
            );
            try {
                Log.d(TAG, "MotionEvent.ACTION_UP @ (" + cursorPosition[0] + ", " + cursorPosition[1] + ")");
//                instrumentation.sendPointerSync(event);
                injectInputEvent(event);
            } catch (RuntimeException e) {
                Log.e(TAG, "sendPointerSync cannot be called from the main thread.", e);
            }
        }).start();
    }

    private float[] getCursorPosition() {
        int[] cursorPosition = cursorController.getCursorPositionXY();
        return new float[]{cursorPosition[0], cursorPosition[1]};
    }

    private void injectInputEvent(InputEvent event) {
        try {
            InputManager inputManager = (InputManager) this.getSystemService(Context.INPUT_SERVICE);
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method injectInputEventMethod = inputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
            injectInputEventMethod.setAccessible(true);

            // INJECT_INPUT_EVENT_MODE_ASYNC is 0
            injectInputEventMethod.invoke(inputManager, event, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void simulateSwipe(float startX, float startY, float endX, float endY, long duration) {
        new Thread(() -> {
            long startTime = SystemClock.uptimeMillis();
            long endTime = startTime + duration;
            float deltaX = endX - startX;
            float deltaY = endY - startY;

            // Simulate initial touch down event
            MotionEvent event = MotionEvent.obtain(startTime, startTime, MotionEvent.ACTION_DOWN, startX, startY, 0);
//            instrumentation.sendPointerSync(event);
            injectInputEvent(event);

            while (SystemClock.uptimeMillis() < endTime) {
                long currentTime = SystemClock.uptimeMillis();
                float progress = (float)(currentTime - startTime) / duration;
                float currentX = startX + (deltaX * progress);
                float currentY = startY + (deltaY * progress);

                event = MotionEvent.obtain(startTime, currentTime, MotionEvent.ACTION_MOVE, currentX, currentY, 0);
//                instrumentation.sendPointerSync(event);
                injectInputEvent(event);

                try {
                    Thread.sleep(16); // 60 FPS
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // Simulate final touch up event
            event = MotionEvent.obtain(startTime, endTime, MotionEvent.ACTION_UP, endX, endY, 0);
//            instrumentation.sendPointerSync(event);
            injectInputEvent(event);
        }).start();
    }


//    OLD REALTIME SWIPE USING GESTURE DESCRIPTION

//    private void simulateTouch(final boolean down) {
//        handler.post(new Runnable() {
//            @Override
//            public void run() {
//                Path path = new Path();
//                int[] cursorPosition = cursorController.getCursorPositionXY();
//
//                if (down) {
//                    path.moveTo(cursorPosition[0], cursorPosition[1]);
//                } else {
//                    if (lastPoint != null) {
//                        path.moveTo(lastPoint.x, lastPoint.y);
//                        path.lineTo(cursorPosition[0], cursorPosition[1]);
//                    }
//                }
//
//                if (path.isEmpty()) {
//                    Log.w("CursorAccessibilityService", "Path is empty, skipping gesture dispatch.");
//                    return;
//                }
//
//                long duration = down ? GESTURE_DURATION : 1;
//
//                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
//                        path, 0, duration, down);
//
//                GestureDescription gestureDescription = new GestureDescription.Builder()
//                        .addStroke(stroke)
//                        .build();
//
//                dispatchGesture(gestureDescription, new GestureResultCallback() {
//                    @Override
//                    public void onCompleted(GestureDescription gestureDescription) {
//                        super.onCompleted(gestureDescription);
//                        Log.d("CursorAccessibilityService", "Gesture completed: " + gestureDescription.toString());
//                        lastPoint = new Point(cursorPosition[0], cursorPosition[1]);
//                    }
//
//                    @Override
//                    public void onCancelled(GestureDescription gestureDescription) {
//                        super.onCancelled(gestureDescription);
//                        Log.d("CursorAccessibilityService", "Gesture cancelled: " + gestureDescription.toString());
//                        lastPoint = new Point(cursorPosition[0], cursorPosition[1]);
//                    }
//                }, null);
//            }
//        });
//    }
//
//
//    private void simulateMove() {
//        handler.post(new Runnable() {
//            @Override
//            public void run() {
//                if (!isRealTimeSwiping) {
//                    return; // Exit if swipe has been stopped
//                }
//
//                if (gestureInProgress) {
//                    return; // Skip if a gesture is already in progress
//                }
//
//                int[] cursorPosition = cursorController.getCursorPositionXY();
//                if (lastPoint == null || (lastPoint.x == cursorPosition[0] && lastPoint.y == cursorPosition[1])) {
//                    return;
//                }
//
//                Path path = new Path();
//                List<Point> swipePathPoints = cursorController.getRealtimeSwipePathPoints();
//
//                if (swipePathPoints.isEmpty()) {
//                    path.moveTo(lastPoint.x, lastPoint.y);
//                    path.lineTo(cursorPosition[0], cursorPosition[1]);
//                } else {
//                    Point startPoint = swipePathPoints.get(0);
//                    path.moveTo(startPoint.x, startPoint.y);
//                    for (Point point : swipePathPoints) {
//                        path.lineTo(point.x, point.y);
//                    }
//                }
//
//                if (path.isEmpty()) {
//                    Log.w("CursorAccessibilityService", "Path is empty, skipping gesture dispatch.");
//                    return;
//                }
//
//                long duration = calculateDynamicDuration(swipePathPoints);
//
//                gestureInProgress = true;
//
//                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
//                        path, 0, duration, isRealTimeSwiping);
//
//                GestureDescription gestureDescription = new GestureDescription.Builder()
//                        .addStroke(stroke)
//                        .build();
//
//                dispatchGesture(gestureDescription, new GestureResultCallback() {
//                    @Override
//                    public void onCompleted(GestureDescription gestureDescription) {
//                        super.onCompleted(gestureDescription);
//                        Log.d("CursorAccessibilityService", "Gesture completed: " + gestureDescription.toString());
//                        if (!swipePathPoints.isEmpty()) {
//                            Point lastSwipePoint = swipePathPoints.get(swipePathPoints.size() - 1);
//                            lastPoint = new Point(lastSwipePoint.x, lastSwipePoint.y);
//                        } else {
//                            lastPoint = new Point(cursorPosition[0], cursorPosition[1]);
//                        }
//                        cursorController.clearSwipePathPoints();
//                        gestureInProgress = false; // Mark gesture as completed
//                        if (isRealTimeSwiping) {
//                            simulateMove(); // Schedule the next gesture if still swiping
//                        }
//                    }
//
//                    @Override
//                    public void onCancelled(GestureDescription gestureDescription) {
//                        super.onCancelled(gestureDescription);
//                        Log.d("CursorAccessibilityService", "Gesture cancelled: " + gestureDescription.toString());
//                        if (!swipePathPoints.isEmpty()) {
//                            Point lastSwipePoint = swipePathPoints.get(swipePathPoints.size() - 1);
//                            lastPoint = new Point(lastSwipePoint.x, lastSwipePoint.y);
//                        } else {
//                            lastPoint = new Point(cursorPosition[0], cursorPosition[1]);
//                        }
//                        cursorController.clearSwipePathPoints();
//                        gestureInProgress = false; // Mark gesture as completed
//                        if (isRealTimeSwiping) {
//                            simulateMove(); // Schedule the next gesture if still swiping
//                        }
//                    }
//                }, null);
//
//                Log.d("CursorAccessibilityService", "simulateMove: " + lastPoint.x + ", " + lastPoint.y + " -> " + cursorPosition[0] + ", " + cursorPosition[1]);
//            }
//        });
//    }
//
//    /**
//     * Calculate a dynamic duration based on the distance between swipe points.
//     *
//     * @param points List of points representing the swipe path.
//     * @return Calculated duration for the gesture.
//     */
//    private long calculateDynamicDuration(List<Point> points) {
//        if (points.isEmpty()) {
//            return GESTURE_DURATION; // Default duration if no points
//        }
//
//        float totalDistance = 0;
//        Point previousPoint = points.get(0);
//
//        for (Point point : points) {
//            totalDistance += calculateDistance(previousPoint, point);
//            previousPoint = point;
//        }
//
//        // Adjust the duration based on the total distance
//        Log.d("CursorAccessibilityService", "Math.max(MIN_GESTURE_DURATION, (long) (totalDistance * DURATION_MULTIPLIER)): " + Math.max(MIN_GESTURE_DURATION, (long) (totalDistance * DURATION_MULTIPLIER)));
//        return Math.max(MIN_GESTURE_DURATION, (long) (totalDistance * DURATION_MULTIPLIER));
//    }
//
//    /**
//     * Calculate the Euclidean distance between two points.
//     *
//     * @param point1 The first point.
//     * @param point2 The second point.
//     * @return The distance between the points.
//     */
//    private float calculateDistance(Point point1, Point point2) {
//        return (float) Math.sqrt(Math.pow(point1.x - point2.x, 2) + Math.pow(point1.y - point2.y, 2));
//    }
}
