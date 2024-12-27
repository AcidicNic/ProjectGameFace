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

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.round;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SparseBooleanArray;
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

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.projectgameface.utils.DebuggingStats;
import com.google.projectgameface.utils.WriteToFile;
import com.google.projectgameface.utils.Config;
import android.graphics.Color;

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
    private BroadcastReceiver resetDebuggingStatsReciever;
    private BroadcastReceiver screenCaptureReceiver;
    private boolean isSwiping = false;
    private Rect keyboardBounds = new Rect();
    private Rect _keyboardBounds = new Rect();
    private boolean isKeyboardOpen = false;
    private long startUptime;
    private long startTime;
    private long endUptime;
    private long endTime;
    private Instrumentation instrumentation;
    private HandlerThread handlerThread;
    private Handler handler;
    private SparseBooleanArray keyStates = new SparseBooleanArray();
    private DebuggingStats gboardDebuggingStats = new DebuggingStats("GBoard");
    private DebuggingStats openboardDebuggingStats = new DebuggingStats("OpenBoard");
    private DebuggingStats debuggingStats = gboardDebuggingStats;
    private WriteToFile writeToFile;

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
    private String[] debugText = {"", ""};

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

        resetDebuggingStatsReciever = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkForKeyboardType();
                gboardDebuggingStats.load(context);
                openboardDebuggingStats.load(context);
                debuggingStats.load(context);
            }
        };

        screenCaptureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
//                Log.d(TAG, "screenCaptureReceiver: " + intent.getAction());
//                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
//                Intent data = intent.getParcelableExtra("data");
//
//                if (resultCode == Activity.RESULT_OK && data != null) {
//                    if (projectionManager == null) projectionManager = getSystemService(MediaProjectionManager.class);
//                    if (mediaProjection != null) mediaProjection.stop();
//
//                    mediaProjection = projectionManager.getMediaProjection(resultCode, data);
//                    attemptScreenCaptureSetup();
//                }
            }
        };

        if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    changeServiceStateReceiver, new IntentFilter("CHANGE_SERVICE_STATE"),
                    RECEIVER_EXPORTED);
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
                    enableScorePreviewReceiver, new IntentFilter("ENABLE_SCORE_PREVIEW"),
                    RECEIVER_EXPORTED);
            registerReceiver(
                    serviceUiManager.flyInWindowReceiver, new IntentFilter("FLY_IN_FLOAT_WINDOW"),
                    RECEIVER_EXPORTED);
            registerReceiver(
                    serviceUiManager.flyOutWindowReceiver,  new IntentFilter("FLY_OUT_FLOAT_WINDOW"),
                    RECEIVER_EXPORTED);
            registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"),
                    RECEIVER_EXPORTED);
            registerReceiver(resetDebuggingStatsReciever, new IntentFilter("RESET_DEBUGGING_STATS"),
                    RECEIVER_EXPORTED);
//            registerReceiver(screenCaptureReceiver, new IntentFilter("SCREEN_CAPTURE_PERMISSION_RESULT"),
//                    RECEIVER_EXPORTED);
        } else {
            registerReceiver(changeServiceStateReceiver, new IntentFilter("CHANGE_SERVICE_STATE"));
            registerReceiver(requestServiceStateReceiver, new IntentFilter("REQUEST_SERVICE_STATE"));
            registerReceiver(loadSharedConfigBasicReceiver, new IntentFilter("LOAD_SHARED_CONFIG_BASIC"));
            registerReceiver(loadSharedConfigGestureReceiver, new IntentFilter("LOAD_SHARED_CONFIG_GESTURE"));
            registerReceiver(enableScorePreviewReceiver, new IntentFilter("ENABLE_SCORE_PREVIEW"));
            registerReceiver(serviceUiManager.flyInWindowReceiver, new IntentFilter("FLY_IN_FLOAT_WINDOW"));
            registerReceiver(serviceUiManager.flyOutWindowReceiver, new IntentFilter("FLY_OUT_FLOAT_WINDOW"));
            registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"));
            registerReceiver(resetDebuggingStatsReciever, new IntentFilter("RESET_DEBUGGING_STATS"));
//            registerReceiver(screenCaptureReceiver, new IntentFilter("SCREEN_CAPTURE_PERMISSION_RESULT"));
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
        handler = new Handler(handlerThread.getLooper());

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
        writeToFile = new WriteToFile(this);

        gboardDebuggingStats.load(this);
        openboardDebuggingStats.load(this);
        checkForKeyboardType();
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

    public boolean isPitchYawEnabled() {
        return cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.PITCH_YAW);
    }

    public boolean isNoseTipEnabled() {
        return cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.NOSE_TIP);
    }

    public boolean isDebugSwipeEnabled() {
        return cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DEBUG_SWIPE);
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
//                        int gapFrames =
//                                round(max(((float) facelandmarkerHelper.gapTimeMs / (float) UI_UPDATE), 1.0f));

                            cursorController.updateInternalCursorPosition(
                                    facelandmarkerHelper.getHeadCoordXY(false),
                                    facelandmarkerHelper.getNoseCoordXY(false),
                                    facelandmarkerHelper.getPitchYaw(),
                                    new int[]{facelandmarkerHelper.mpInputWidth, facelandmarkerHelper.frameHeight},
                                    new int[]{screenSize.x, screenSize.y}
                            );

                            // Actually update the UI cursor image.
                            serviceUiManager.updateCursorImagePositionOnScreen(
                                    cursorController.getCursorPositionXY()
                            );

                            dispatchEvent();

                            if (isPitchYawEnabled() && isNoseTipEnabled()) {
                                serviceUiManager.drawHeadCenter(
                                        facelandmarkerHelper.getNoseCoordXY(false),
                                        facelandmarkerHelper.mpInputWidth,
                                        facelandmarkerHelper.mpInputHeight
                                );
                                serviceUiManager.drawSecondDot(
                                        facelandmarkerHelper.getHeadCoordXY(false),
                                        facelandmarkerHelper.mpInputWidth,
                                        facelandmarkerHelper.mpInputHeight
                                );
                            } else if (isPitchYawEnabled()) {
                                serviceUiManager.drawHeadCenter(
                                        facelandmarkerHelper.getHeadCoordXY(false),
                                        facelandmarkerHelper.mpInputWidth,
                                        facelandmarkerHelper.mpInputHeight
                                );
                            } else {
                                serviceUiManager.drawHeadCenter(
                                        facelandmarkerHelper.getNoseCoordXY(false),
                                        facelandmarkerHelper.mpInputWidth,
                                        facelandmarkerHelper.mpInputHeight
                                );
                            }

                            if (isDebugSwipeEnabled()) {
                                serviceUiManager.updateDebugTextOverlay(
                                        debugText[0],
                                        debugText[1],
                                        serviceState == ServiceState.PAUSE
                                );
                            } else {
                                serviceUiManager.updateDebugTextOverlay(
                                        "pre: " + facelandmarkerHelper.preprocessTimeMs + "ms",
                                        "med: " + facelandmarkerHelper.mediapipeTimeMs + "ms",
                                        serviceState == ServiceState.PAUSE
                                );
                            }

//                            if (mediaProjection != null && currentKeyboard == "GBoard") {
//                                if (checkForPrediction && !previousWordPredictionCheckRunning) {
//                                    if (startTime + 500 <= System.currentTimeMillis()) {
//                                        checkForWordPrediction();
//                                    }
//                                }
//                                if (updateCanvas) {
//                                    serviceUiManager.updatePreviewBitmap(previousWordPredictionBitmap, predictionBounds);
//                                    updateCanvas = false;
//                                }
//                            }

                            if (cursorController.isSwiping() || previousWordPredictionCheckRunning) {
                                serviceUiManager.fullScreenCanvas.invalidate();
                            }

                            break;

                        case PAUSE:
                            // In PAUSE state user cannot move cursor
                            // but still can perform some event from face gesture.
//                        dispatchEvent();
//
//                        if (isPitchYawEnabled() && isNoseTipEnabled()) {
//                            serviceUiManager.drawHeadCenter(
//                                    facelandmarkerHelper.getCombinedNoseAndHeadCoords(),
//                                    facelandmarkerHelper.mpInputWidth,
//                                    facelandmarkerHelper.mpInputHeight
//                            );
//                        } else if (isPitchYawEnabled()) {
//                            serviceUiManager.drawHeadCenter(
//                                    facelandmarkerHelper.getHeadCoordXY(false),
//                                    facelandmarkerHelper.mpInputWidth,
//                                    facelandmarkerHelper.mpInputHeight
//                            );
//                        } else {
//                            serviceUiManager.drawHeadCenter(
//                                    facelandmarkerHelper.getNoseCoordXY(false),
//                                    facelandmarkerHelper.mpInputWidth,
//                                    facelandmarkerHelper.mpInputHeight
//                            );
//                        }

                            serviceUiManager.updateDebugTextOverlay(
                                    "",
                                    "",
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
        if (requestedScoreBlendshapeName != null) {
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
        } else {
            try {
                float[] pitchYaw = facelandmarkerHelper.getPitchYaw();
                float[] currHeadXY = facelandmarkerHelper.getHeadCoordXY(true);
                float[] currNoseXY = facelandmarkerHelper.getNoseCoordXY(true);
                Intent intent = new Intent("PITCH_YAW");
                intent.putExtra("PITCH", pitchYaw[0]);
                intent.putExtra("YAW", pitchYaw[1]);
                intent.putExtra("CURRHEADXY", currHeadXY);
                intent.putExtra("CURRNOSEXY", currNoseXY);
                sendBroadcast(intent);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "err while retrieving pitch & yaw " + e);
            }
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
        Log.i(TAG, "enableService, current: " + serviceState);

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
                cursorController.resetRawCoordMinMax();
                facelandmarkerHelper.resetMinMaxValues();

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

                // stop and cleanup mediaprojection
                cleanupScreenCapture();

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
        cursorController.cleanup();
        cleanupScreenCapture();
        // Unregister when the service is destroyed
        unregisterReceiver(changeServiceStateReceiver);
        unregisterReceiver(loadSharedConfigBasicReceiver);
        unregisterReceiver(loadSharedConfigGestureReceiver);
        unregisterReceiver(requestServiceStateReceiver);
        unregisterReceiver(enableScorePreviewReceiver);
        unregisterReceiver(serviceUiManager.flyInWindowReceiver);
        unregisterReceiver(serviceUiManager.flyOutWindowReceiver);
        unregisterReceiver(profileChangeReceiver);
        unregisterReceiver(screenCaptureReceiver);

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

        switch (serviceState) {
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
        if (serviceState != ServiceState.ENABLE) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            CharSequence newText = event.getText().toString();

            if (newText != null && newText.length() > 0) {
                processTypedText(newText);
            }
        } else {
            checkForKeyboardBounds(event);
        }
    }

    private StringBuilder typedText = new StringBuilder();

    // TO/DO: MOVE TO DEBUGGINGSTATS CLASS
    private boolean checkForNewWord = false;
    private long checkForNewWordTimeStamp = 0;
    private String newWord;

    private void processTypedText(CharSequence newText) {
        typedText.append(newText);
        String[] words = typedText.toString().split("\\s+");
        Log.d(TAG, "processTypedText(): [words==" + words + "] [words.length==" + words.length + "]");
        if (words.length > 0) {
            Long now = System.currentTimeMillis();
            newWord = words[words.length - 1];
            Log.d(TAG, "processTypedText(): [newWord==" + newWord + "] [checkForNewWord==" + checkForNewWord + "] [(checkForNewWordTimeStamp + 1000 >= now)==" + (checkForNewWordTimeStamp + 1000 >= now) + "]");
            if (checkForNewWord && (checkForNewWordTimeStamp + 1000 >= now)) {
                if (newWord != null) {
                    debuggingStats.addWordSwiped(newWord, startTime, endTime);
                    debuggingStats.save(this);
                    checkForNewWord = false;
                }
            } else {
                Log.d(TAG, "processTypedText(): checkForNewWord is false, not adding word to stats.");
            }
        }
    }

    private String currentKeyboard = "Unknown";

    private void checkForKeyboardType() {
        String currentKeyboardStr = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD
        );
        if (currentKeyboardStr.toLowerCase().contains("openboard")) {
//                Log.d(TAG, "OpenBoard keyboard detected");
            currentKeyboard = "OpenBoard";
            debuggingStats = openboardDebuggingStats;
        } else if (currentKeyboardStr.toLowerCase().contains("google")) {
//                Log.d(TAG, "GBoard keyboard detected");
            currentKeyboard = "GBoard";
            debuggingStats = gboardDebuggingStats;
        } else {
//                Log.d(TAG, "Unknown keyboard detected: " + currentKeyboardStr);
            currentKeyboard = "Unknown";
        }
    }

    private void checkForKeyboardBounds(AccessibilityEvent event) {
//        new Handler(Looper.getMainLooper()).postDelayed(() -> {
        if (isSwiping) {
            return;
        }

        boolean keyboardFound = false;
        Rect tempBounds = new Rect();
        Rect navBarBounds = new Rect();

        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            window.getBoundsInScreen(tempBounds);
//                Log.d(TAG, "Window title: " + window.getTitle() + ", type: " + window.getType() + ", bounds: " + tempBounds);
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                keyboardFound = true;
                window.getBoundsInScreen(_keyboardBounds);
                window.getBoundsInScreen(keyboardBounds);
//                for (int i = 0; i < window.getChildCount(); i++) {
//                    Rect childBounds = new Rect();
//                    window.getChild(i).getBoundsInScreen(childBounds);
//                    Log.d(TAG, "child: " + window.getChild(i) + ", title: " + window.getChild(i).getTitle() + ", describeContents: " + window.getChild(i).describeContents() + ", bounds: " + childBounds);
//                }
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
//            int rotation = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
//            boolean isPortraitMode = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180;
//
//            if (!navBarBounds.isEmpty() && isPortraitMode) {
//                keyboardBounds.union(navBarBounds); // Expand the bounds to include the navigation bar
//                Log.d(TAG, "Navigation bar bounds added: " + navBarBounds);
//            }
            if (keyboardBounds.top > navBarBounds.top) {
                keyboardBounds.union(navBarBounds);
            }
            cursorController.setTemporaryBounds(keyboardBounds);
            Log.d(TAG, "Temporary bounds set: " + keyboardBounds);

            checkForKeyboardType();
//            serviceUiManager.fullScreenCanvas.setRect(keyboardBounds);
        } else {
            cursorController.clearTemporaryBounds();
//            serviceUiManager.fullScreenCanvas.setRect(null);
        }
//        Log.d(TAG, "Keyboard " + (isKeyboardOpen ? "opened" : "closed"));
    }

    @Override
    public void onInterrupt() {
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service connected");
    }


    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.PAUSE) {
            return false;
        }
        if (Config.VALID_KEY_EVENT_KEYS.contains(event.getKeyCode())) {
            return handleKeyEvent(event);
        }
        return false;
    }

    private boolean swipeToggle = false;
    private boolean dragToggle = false;

    private boolean handleKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.PAUSE) {
            return false;
        }
        int eventAction = event.getAction();
        int keyCode = event.getKeyCode();
        int[] cursorPosition = new int[2];
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_2:
                if (eventAction == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "TAP KeyEvent.ACTION_DOWN");
                    DispatchEventHelper.checkAndDispatchEvent(
                            this,
                            cursorController,
                            serviceUiManager,
                            BlendshapeEventTriggerConfig.EventType.CURSOR_TOUCH);
                }
                return true;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                cursorPosition = getCursorPosition();
                if (eventAction == KeyEvent.ACTION_DOWN) {
                    keyStates.put(keyCode, true);
                    Log.d(TAG, "SWIPE KeyEvent.ACTION_DOWN");
                    if (canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                        startRealtimeSwipe();
//                        startSwipe();
                    } else {
                        dragToggle = true;
                        DispatchEventHelper.checkAndDispatchEvent(
                                this,
                                cursorController,
                                serviceUiManager,
                                BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE);
                    }
                } else if (eventAction == KeyEvent.ACTION_UP) {
                    keyStates.put(keyCode, false);
                    Log.d(TAG, "SWIPE KeyEvent.ACTION_UP");
                    if (isSwiping) {
                        stopRealtimeSwipe();
//                        stopSwipe();
                    } else if (dragToggle) {
                        dragToggle = false;
                        DispatchEventHelper.checkAndDispatchEvent(
                                this,
                                cursorController,
                                serviceUiManager,
                                BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_3:
                cursorPosition = getCursorPosition();

                if (eventAction == KeyEvent.ACTION_DOWN) {
                    if (dragToggle) {
                        Log.d(TAG, "STOP DRAG TOGGLE KeyEvent.ACTION_DOWN");
                        dragToggle = false;
                        DispatchEventHelper.checkAndDispatchEvent(
                                this,
                                cursorController,
                                serviceUiManager,
                                BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE);
                    } else if (isSwiping && swipeToggle) {
                        Log.d(TAG, "STOP SWIPE TOGGLE KeyEvent.ACTION_DOWN");
                        swipeToggle = false;
                        stopRealtimeSwipe();
                    } else if (canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                        Log.d(TAG, "START SWIPE TOGGLE KeyEvent.ACTION_DOWN");
                        startRealtimeSwipe();
                    } else {
                        Log.d(TAG, "START SWIPE TOGGLE KeyEvent.ACTION_DOWN");
                        swipeToggle = true;
                        DispatchEventHelper.checkAndDispatchEvent(
                                this,
                                cursorController,
                                serviceUiManager,
                                BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE);
                    }
                }
                return true;

            case KeyEvent.KEYCODE_4:
                if (eventAction == KeyEvent.ACTION_DOWN && isKeyboardOpen && cursorController.getCursorPositionXY()[1] > keyboardBounds.top) {
                    Log.d(TAG, "SCREENSHOT TEST KEY KeyEvent.ACTION_DOWN");
//                    saveScreenshot();
                }
                return true;
        }
        return false;
    }

//    private ArrayList<Long> swipeTimes = new ArrayList<>();

//    private void startRealtimeSwipe() {
//        isSwiping = true;
//        cursorController.isRealtimeSwipe = true;
//
//        new Thread(() -> {
//            while (isSwiping) {
//                List<float[]> pointsToProcess;
//                synchronized (cursorController.swipePathPoints) {
//                    if (cursorController.swipePathPoints.size() < 2) {
//                        // Not enough points to build a segment
//                        try {
//                            Thread.sleep(5); // Wait and try again
//                        } catch (InterruptedException e) {
//                            Log.e(TAG, "Swipe thread interrupted: " + e);
//                        }
//                        continue;
//                    }
//                    // Copy points for processing
//                    pointsToProcess = new ArrayList<>(cursorController.swipePathPoints);
//                    // Retain only the last point for continuity
//                    float[] lastPoint = cursorController.swipePathPoints.get(cursorController.swipePathPoints.size() - 1);
//                    cursorController.swipePathPoints.clear();
//                    cursorController.swipePathPoints.add(lastPoint);
//                }
//
//                // Validate points before building the Path
//                if (pointsToProcess.size() < 2) {
//                    Log.e(TAG, "Insufficient points to create a valid Path segment.");
//                    continue;
//                }
//
//                // Build a path from the collected points
//                Path segmentPath = new Path();
//                float[] startPoint = pointsToProcess.get(0);
//                segmentPath.moveTo(startPoint[0], startPoint[1]);
//                for (int i = 1; i < pointsToProcess.size(); i++) {
//                    float[] point = pointsToProcess.get(i);
//                    segmentPath.lineTo(point[0], point[1]);
//                }
//
//                try {
//                    GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
//                    GestureDescription.StrokeDescription stroke =
//                            new GestureDescription.StrokeDescription(segmentPath, 0, 16, false);
//                    gestureBuilder.addStroke(stroke);
//
//                    GestureDescription gesture = gestureBuilder.build();
//
//                    // Dispatch gesture and chain to the next segment on completion
//                    this.dispatchGesture(gesture, new GestureResultCallback() {
//                        @Override
//                        public void onCompleted(GestureDescription gestureDescription) {
//                            if (isSwiping) {
//                                Log.d(TAG, "Gesture segment completed.");
//                            }
//                        }
//
//                        @Override
//                        public void onCancelled(GestureDescription gestureDescription) {
//                            Log.e(TAG, "Gesture segment cancelled.");
//                            // No explicit retry logic needed here, as the loop will handle it
//                        }
//                    }, null);
//
//                } catch (Exception e) {
//                    Log.e(TAG, "Error during swipe gesture dispatch: " + e);
//                }
//            }
//
//            // End the swipe with a final ACTION_UP
//            Path endPath = new Path();
//            synchronized (cursorController.swipePathPoints) {
//                if (!cursorController.swipePathPoints.isEmpty()) {
//                    float[] lastPoint = cursorController.swipePathPoints.get(cursorController.swipePathPoints.size() - 1);
//                    endPath.moveTo(lastPoint[0], lastPoint[1]);
//                }
//            }
//
//            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
//            GestureDescription.StrokeDescription finalStroke =
//                    new GestureDescription.StrokeDescription(endPath, 0, 1, false);
//            gestureBuilder.addStroke(finalStroke);
//
//            GestureDescription finalGesture = gestureBuilder.build();
//            this.dispatchGesture(finalGesture, null, null); // No callback needed
//        }).start();
//    }
//
//    /**
//     * Stops the swipe and dispatches a final ACTION_UP event.
//     */
//    private void stopRealtimeSwipe() {
//        isSwiping = false;
//        cursorController.isRealtimeSwipe = false;
//        endUptime = SystemClock.uptimeMillis();
//        endTime = System.currentTimeMillis();
//
//        if (!cursorController.swipePathPoints.isEmpty()) {
//            Path endPath = new Path();
//            float[] lastPoint = cursorController.swipePathPoints.get(cursorController.swipePathPoints.size() - 1);
//            endPath.moveTo(lastPoint[0], lastPoint[1]);
//
//            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
//            GestureDescription.StrokeDescription finalStroke =
//                    new GestureDescription.StrokeDescription(endPath, 0, 1, false);
//            gestureBuilder.addStroke(finalStroke);
//
//            GestureDescription finalGesture = gestureBuilder.build();
//            this.dispatchGesture(finalGesture, null, null); // No callback needed
//        }
//        cursorController.swipePathPoints.clear();
//    }
//
//    private Point currentPoint;
//    private Point lastPoint;
//    private static final long GESTURE_DURATION = 100;
//    private static final long GESTURE_DELAY = 100;
//
//    public void startSwipe() {
//        if (isSwiping) {
//            return;
//        }
//        isSwiping = true;
//        simulateTouch(true);
//        handler.post(updateGestureRunnable);
//    }
//
//    public void stopSwipe() {
//        isSwiping = false;
//        simulateTouch(false);
//    }
//
//    private Runnable updateGestureRunnable = new Runnable() {
//        @Override
//        public void run() {
//            if (isSwiping) {
//                simulateMove();
//                handler.postDelayed(this, GESTURE_DELAY);
//            }
//        }
//    };
//
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
//                    path.moveTo(lastPoint.x, lastPoint.y);
//                    path.lineTo(cursorPosition[0], cursorPosition[1]);
//                }
//
//                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
//                        path, 0, down ? GESTURE_DURATION : 1, down);
//
//
//                GestureDescription gestureDescription = new GestureDescription.Builder()
//                        .addStroke(stroke)
//                        .build();
//
//                dispatchGesture(gestureDescription, null, null);
//                lastPoint = new Point(cursorPosition[0], cursorPosition[1]);
//            }
//        });
//    }
//
//    private void simulateMove() {
//        handler.post(new Runnable() {
//            @Override
//            public void run() {
//                int[] cursorPosition = cursorController.getCursorPositionXY();
//                if (lastPoint.x == cursorPosition[0] && lastPoint.y == cursorPosition[1]) {
//                    return;
//                }
//
//                Path path = new Path();
//                path.moveTo(lastPoint.x, lastPoint.y);
//                path.lineTo(cursorPosition[0], cursorPosition[1]);
//
//                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
//                        path, 0, GESTURE_DURATION, false);
//                GestureDescription gestureDescription = new GestureDescription.Builder()
//                        .addStroke(stroke)
//                        .build();
//
//                dispatchGesture(gestureDescription, null, null);
//                Log.d("CursorAccessibilityService", "simulateMove: " + lastPoint.x + ", " + lastPoint.y + " -> " + cursorPosition[0] + ", " + cursorPosition[1]);
//                lastPoint = new Point(cursorPosition[0], cursorPosition[1]);
//            }
//        });
//
//    }
//
//
//    public boolean onTouchEvent(MotionEvent event){
//        switch(event.getAction()) {
//            case (MotionEvent.ACTION_DOWN) :
//                Log.d(DEBUG_TAG,"Action was DOWN");
//                return true;
//            case (MotionEvent.ACTION_MOVE) :
//                Log.d(DEBUG_TAG,"Action was MOVE");
//                return true;
//            case (MotionEvent.ACTION_UP) :
//                Log.d(DEBUG_TAG,"Action was UP");
//                return true;
//            case (MotionEvent.ACTION_CANCEL) :
//                Log.d(DEBUG_TAG,"Action was CANCEL");
//                return true;
//            case (MotionEvent.ACTION_OUTSIDE) :
//                Log.d(DEBUG_TAG,"Movement occurred outside bounds of current screen element");
//                return true;
//            default :
//                return super.onTouchEvent(event);
//        }
//    }

    int[] lastValidCoords = new int[2];

    private void startRealtimeSwipe() {
        isSwiping = true;
        cursorController.isRealtimeSwipe = true;
//        swipePath = new ArrayList<>();

        new Thread(() -> {
            startUptime = SystemClock.uptimeMillis();
            startTime = System.currentTimeMillis();

            int[] initialPosition = getCursorPosition();
            if (isKeyboardOpen && initialPosition[1] > keyboardBounds.top) {
                checkForPrediction = true;
                // TODO: free up screencapture resources until ^^^
                Log.d(TAG, "kbd open and swipe starting inside of keyboard region");
            }

            if (canInjectEvent(initialPosition[0],  initialPosition[1])) {
                lastValidCoords = initialPosition;
                MotionEvent event = MotionEvent.obtain(
                        startUptime,
                        startUptime,
                        MotionEvent.ACTION_DOWN,
                        initialPosition[0],
                        initialPosition[1],
                        0
                );
                injectMotionEvent(event);
                debugText[0] = "Swiping";
                debugText[1] = "X, Y: (" + initialPosition[0] + ", " + initialPosition[1] + ")";
            } else {
                Log.d(TAG, "Coords do not belong to either senderapp or IME. TODO: Implement for 3rd party apps.");
            }

            long lastCheckTime = System.currentTimeMillis();
            while (isSwiping) {
                int[] cursorPosition = getCursorPosition();
                long now = SystemClock.uptimeMillis();
                try {
                    if (canInjectEvent(cursorPosition[0],  cursorPosition[1])) {
                        lastValidCoords = cursorPosition;
                        MotionEvent event = MotionEvent.obtain(
                                startUptime,
                                now,
                                MotionEvent.ACTION_MOVE,
                                cursorPosition[0],
                                cursorPosition[1],
                                0
                        );
                        injectMotionEvent(event);
                        debugText[0] = "Swiping";
                        debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";
                    } else {
                        Log.d(TAG, "Coords do not belong to either senderapp or IME. TODO: Implement for 3rd party apps.");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error while injecting swipe input event in startRealtimeSwipe: " + e);
                }
                try {
                    Thread.sleep(16); // 60 FPS
                } catch (Exception e) {
                    Log.e(TAG, "Error while sleeping in startRealtimeSwipe: " + e);
                }

                now = System.currentTimeMillis();

                // Check if the button is still being pressed every 500ms
//                if (isSwiping && (now - lastCheckTime) >= 500) {
//                    if (!isSwipeKeyStillPressed()) {
//                        Log.e(TAG, "Button not pressed, manually ending swipe.");
//                        writeToFile.logError(TAG, "Button not pressed, manually ending swipe.");
//                        stopRealtimeSwipe();
//                        break;
//                    }
//                    lastCheckTime = now;
//                }

            }
        }).start();
    }

    private void stopRealtimeSwipe() {
        endUptime = SystemClock.uptimeMillis();
        endTime = System.currentTimeMillis();
        int[] cursorPosition = getCursorPosition();
        serviceUiManager.clearPreviewBitmap();
        new Thread(() -> {
            try {
                if (canInjectEvent(cursorPosition[0],  cursorPosition[1])) {
                    MotionEvent event = MotionEvent.obtain(
                            startUptime,
                            endUptime,
                            MotionEvent.ACTION_UP,
                            cursorPosition[0],
                            cursorPosition[1],
                            0
                    );
                    injectMotionEvent(event);
                    debugText[0] = "Swiping";
                    debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";
                    Log.d(TAG, "MotionEvent.ACTION_UP @ (" + cursorPosition[0] + ", " + cursorPosition[1] + ")");
                } else {
                    MotionEvent event = MotionEvent.obtain(
                            startUptime,
                            endUptime,
                            MotionEvent.ACTION_UP,
                            lastValidCoords[0],
                            lastValidCoords[1],
                            0
                    );
                    injectMotionEvent(event);
                    debugText[0] = "Swiping";
                    debugText[1] = "X, Y: (" + lastValidCoords[0] + ", " + lastValidCoords[1] + ")";
                    Log.d(TAG, "MotionEvent.ACTION_UP @ (" + lastValidCoords[0] + ", " + lastValidCoords[1] + ")");
                }

                // TODO: pause screencapture sometime after it seems like the user has finished swipe-typing.

            } catch (Exception e) {
                writeToFile.logError(TAG, "ERROR WHILE ENDING SWIPE!!!: sendPointerSync cannot be called from the main thread." + e);
                Log.e(TAG, "sendPointerSync cannot be called from the main thread.", e);
            }
            isSwiping = false;
            cursorController.isRealtimeSwipe = false;
            if (checkForPrediction) {
                checkForNewWord = true;
                checkForNewWordTimeStamp = endTime;
                checkForPrediction = false;
                previousWordPredictionBitmap = null;
                predictionBounds = null;
            }
//            displaySwipeInfo();
        }).start();
    }

    // Unified MotionEvent injection method
    private void injectMotionEvent(MotionEvent event) {
        try {
            instrumentation.sendPointerSync(event);
            Log.d(TAG, "MotionEvent sent: (" + event.getX() + ", " + event.getY() + ", action=" + event.getAction() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send MotionEvent(" + event.getX() + ", " + event.getY() + ", action=" + event.getAction() + ")", e);
        }
    }
    // Check if events can be injected into the window at (x, y)
    public boolean canInjectEvent(float x, float y) {
        for (AccessibilityWindowInfo window : getWindows()) {
            // Get the bounds of the window
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);

            // Check if the coordinates fall within this window
            if (bounds.contains((int) x, (int) y)) {
                if (isInjectableWindow(window)) {
                    Log.d(TAG, "Injectable window found at (" + x + ", " + y + ").");
                    return true;
                } else {
                    Log.d(TAG, "Window at (" + x + ", " + y + ") is not injectable.");
                    return false;
                }
            }
        }

        Log.d(TAG, "No window found at (" + x + ", " + y + ").");
        return false;
    }

    // Helper method to check if a window is injectable
    private boolean isInjectableWindow(AccessibilityWindowInfo window) {
        // Check if the window is owned by your app or IME
        CharSequence packageName = window.getRoot().getPackageName();
        if (packageName != null && isMyAppPackage(packageName.toString())) {
            return true;
        }

        // Optionally, you can add additional checks here, e.g., if it's a system window
        // or if it's a third-party app but supports gestures via accessibility.

        // For now, we'll assume only windows from our apps are injectable.
        return false;
    }

    // Helper method to check if a package belongs to your apps
    private boolean isMyAppPackage(String packageName) {
        String[] myApps = {
                "com.google.projectgameface",
                "org.dslul.openboard.inputmethod.latin"
        };

        for (String myApp : myApps) {
            if (packageName.equals(myApp)) {
                return true;
            }
        }
        return false;
    }

    public double getDistanceBetweenPoints(double x1, double y1, double x2, double y2) {
        return Math.sqrt((y2 - y1) * (y2 - y1) + (x2 - x1) * (x2 - x1));
    }

    private boolean isSwipeKeyStillPressed() {
        return keyStates.get(KeyEvent.KEYCODE_BUTTON_A, false) || keyStates.get(KeyEvent.KEYCODE_ENTER, false) || keyStates.get(KeyEvent.KEYCODE_1, false) || swipeToggle;
    }


    private int[] getCursorPosition() {
        return cursorController.getCursorPositionXY();
    }

    /** old event inject method, requires platform signed app and INJECT_EVENTS perm. */
    private void injectInputEvent(MotionEvent event) {
        try {
            InputManager inputManager = (InputManager) this.getSystemService(Context.INPUT_SERVICE);
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method injectInputEventMethod = inputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
            injectInputEventMethod.setAccessible(true);

            // INJECT_INPUT_EVENT_MODE_ASYNC is 0
            injectInputEventMethod.invoke(inputManager, event, 0);
        } catch (Exception e) {
            Log.e(TAG, "Error while injecting input event: " + e);
            e.printStackTrace();
        }
    }



    // Class variables
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaProjectionManager projectionManager;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;
    private Handler backgroundHandlerMP;
    private HandlerThread handlerThreadMP;

    private boolean attemptScreenCaptureSetup() {
        if (startMediaProjectionBackgroundThread() && setupImageReader() && setupVirtualDisplay()) {
            Log.d(TAG, "Screen capture setup.");
            return true;
        } else {
            cleanupScreenCapture();
            return false;
        }
    }

    private void cleanupScreenCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        stopMediaProjectionBackgroundThread();
    }

    private boolean setupImageReader() {
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            int screenDensity = metrics.densityDpi;
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
            return true;
        } catch (Exception e) {
            writeToFile.logError(TAG, "setupImageReader() failed: " + e);
            Log.e(TAG, "setupImageReader() failed: " + e);
            return false;
        }
    }

    /**
     * Create a virtual display to capture the screen.
     * @return success: true if the virtual display was successfully created, false otherwise
     */
    private boolean setupVirtualDisplay() {
        if (mediaProjection == null) {
            Log.e(TAG, "setupVirtualDisplay() failed: mediaProjection is null");
            return false;
        }
        try {
            // Register the MediaProjection.Callback for cleanup on stop
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped");
                    cleanupScreenCapture(); // Custom method for cleanup
                }
            }, backgroundHandlerMP);

            // Create a virtual display to capture the screen
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                    imageReader.getWidth(), imageReader.getHeight(), getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    new VirtualDisplay.Callback() {
                        @Override
                        public void onPaused() {
                            Log.d(TAG, "VirtualDisplay paused");
                        }

                        @Override
                        public void onResumed() {
                            Log.d(TAG, "VirtualDisplay resumed");
                        }

                        @Override
                        public void onStopped() {
                            Log.d(TAG, "VirtualDisplay stopped");
                        }
                    },
                    backgroundHandlerMP);
            Log.d(TAG, "setupVirtualDisplay() succeeded");
            return true;
        } catch (Exception e) {
            writeToFile.logError(TAG, "setupVirtualDisplay() failed: " + e);
            Log.e(TAG, "setupVirtualDisplay() failed: " + e);
            return false;
        }
    }

    private boolean startMediaProjectionBackgroundThread() {
        try {
            if (backgroundHandlerMP != null) {
                stopMediaProjectionBackgroundThread();
            }
            handlerThreadMP = new HandlerThread("ScreenCaptureThread");
            handlerThreadMP.start();
            backgroundHandlerMP = new Handler(handlerThreadMP.getLooper());
            return true;
        } catch (Exception e) {
            writeToFile.logError(TAG, "startBackgroundThread() failed: " + e);
            Log.e(TAG, "startBackgroundThread() failed: " + e);
            return false;
        }
    }

    private void stopMediaProjectionBackgroundThread() {
        if (handlerThreadMP != null) {
            handlerThreadMP.quitSafely();
            try {
                handlerThreadMP.join();
                handlerThreadMP = null;
                backgroundHandlerMP = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // Restore the interrupt status
                Log.e(TAG, "Thread interrupted while stopping handlerThreadMP: " + e);
                writeToFile.logError(TAG, "Thread interrupted while stopping handlerThreadMP: " + e);
            }
        }
    }
    private Image image = null;
    private Bitmap getScreenCaptureBitmap() {
        try {
            if (image != null) {
                image.close();
                image = null;
            }
            image = imageReader.acquireLatestImage();
            if (image == null) return null;

            // Get the necessary information from the image
            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();

            // Create a bitmap with the correct width and height
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            // Handle row padding
            int rowPadding = rowStride - pixelStride * width;
            int[] pixels = new int[width * height];

            buffer.rewind();
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int pixel = 0;
                    pixel |= (buffer.get() & 0xFF) << 16; // Red
                    pixel |= (buffer.get() & 0xFF) << 8;  // Green
                    pixel |= (buffer.get() & 0xFF);       // Blue
                    pixel |= (buffer.get() & 0xFF) << 24; // Alpha
                    pixels[offset + x] = pixel;
                }
                // Skip any padding bytes at the end of the row
                buffer.position(buffer.position() + rowPadding);
            }

            // Set pixels in the bitmap
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

            // Close the image to prevent memory leaks
            image.close();
            image = null;

            return bitmap;
        } catch (Exception e) {
            writeToFile.logError(TAG, "captureScreenshot() failed: " + e);
            Log.e(TAG, "captureScreenshot() failed: " + e);
            return null;
        } finally {
            if (image != null) {
                image.close(); // Close image to prevent maxImages limit
                image = null;
            }
        }
    }

    private Bitmap cropBitmapToRect(Bitmap originalBitmap, Rect region) {
        // Ensure the Rect is within the bounds of the original Bitmap
        int left = Math.max(0, region.left);
        int top = Math.max(0, region.top);
        int right = Math.min(originalBitmap.getWidth(), region.right);
        int bottom = Math.min(originalBitmap.getHeight(), region.bottom);

        // Check if the cropped area is valid
        if (left >= right || top >= bottom) {
            Log.e(TAG, "Invalid crop region");
            return null;
        }

        // Create a cropped Bitmap
        return Bitmap.createBitmap(originalBitmap, left, top, right - left, bottom - top);
    }

    public static Bitmap replaceColorWithTransparent(Bitmap original) {
        // Get the color of the top-left pixel (0,0)
        int targetColor = original.getPixel(0, 0);

        // Create a mutable copy of the original bitmap
        Bitmap resultBitmap = original.copy(Bitmap.Config.ARGB_8888, true);

        // Iterate over each pixel in the bitmap
        for (int x = 0; x < resultBitmap.getWidth(); x++) {
            for (int y = 0; y < resultBitmap.getHeight(); y++) {
                // Get the current pixel color
                int pixelColor = resultBitmap.getPixel(x, y);

                // Replace the target color with transparent
                if (pixelColor == targetColor) {
                    resultBitmap.setPixel(x, y, Color.TRANSPARENT);
                }
            }
        }

        return resultBitmap;
    }

    private void saveScreenshot() {
        Bitmap screenshot = getScreenCaptureBitmap();
        Bitmap croppedScreenshot = cropBitmapToRect(screenshot, keyboardBounds);
        if (croppedScreenshot != null) {
            writeToFile.saveBitmap(croppedScreenshot);
        }
    }

    private Bitmap previousWordPredictionBitmap = null;
    private Rect predictionBounds = new Rect();
    private boolean previousWordPredictionCheckRunning = false;
    private int WORD_PREDICTION_DELAY = 500;

    private void checkForWordPrediction() {

        new Thread(() -> {
            try {
                previousWordPredictionCheckRunning = true;
                while (checkForPrediction) {
                    int[] cursorPosition = cursorController.getCursorPositionXY();
                    Bitmap screenshot = getScreenCaptureBitmap();
                    if (screenshot == null) {
                        Log.e(TAG, "Screenshot is null.");
                        continue;
                    }
                    Rect cropWordPredictionRegion = new Rect(
                            keyboardBounds.right / 4,
                            keyboardBounds.top,
                            keyboardBounds.right - (keyboardBounds.right / 4),
                            keyboardBounds.top + (keyboardBounds.height() / 6)
                    );
                    // if cursorPosition is within the word prediction region, stop the task
                    if (cursorPosition[0] >= cropWordPredictionRegion.left && cursorPosition[0] <= cropWordPredictionRegion.right && cursorPosition[1] >= cropWordPredictionRegion.top && cursorPosition[1] <= cropWordPredictionRegion.bottom) {
                        Log.d(TAG, "Cursor is within word prediction region.");
                        continue;
                    }
                    Bitmap croppedScreenshot = replaceColorWithTransparent(cropBitmapToRect(screenshot, cropWordPredictionRegion));
                    if (previousWordPredictionBitmap != null && previousWordPredictionBitmap.sameAs(croppedScreenshot)) {
                        Log.d(TAG, "Word prediction bitmap is the same as previous.");
                        continue;
                    }
                    previousWordPredictionBitmap = croppedScreenshot;
                    Rect showPredictionRegion = new Rect(
                            0,
                            keyboardBounds.top + (keyboardBounds.height() / 3) - croppedScreenshot.getHeight(),
                            keyboardBounds.width(),
                            keyboardBounds.top + (keyboardBounds.height() / 3) + croppedScreenshot.getHeight()
                    );
                    Log.d(TAG, "Showing word prediction region: " + showPredictionRegion);
                    predictionBounds = showPredictionRegion;
                    updateCanvas = true;
//                    writeToFile.saveBitmap(croppedScreenshot);

                    Thread.sleep(1);
                }
                previousWordPredictionBitmap = null;
                previousWordPredictionCheckRunning = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Thread interrupted while waiting to stop task: " + e);
                writeToFile.logError(TAG, "Thread interrupted while waiting to stop task: " + e);
                previousWordPredictionBitmap = null;
                previousWordPredictionCheckRunning = false;
            } catch (Exception e) {
                Log.e(TAG, "Error while checking for word prediction: " + e);
                writeToFile.logError(TAG, "Error while checking for word prediction: " + e);
                previousWordPredictionBitmap = null;
                previousWordPredictionCheckRunning = false;
            }
        }).start();
    }

    private boolean checkForPrediction = false;
    private boolean updateCanvas = false;

}


    //    OLD REALTIME SWIPE USING GESTURE DESCRIPTION
//
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

