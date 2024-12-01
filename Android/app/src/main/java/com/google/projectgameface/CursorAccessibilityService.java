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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.google.projectgameface.utils.DebuggingStats;
import com.google.projectgameface.utils.SwipePoint;
import com.google.projectgameface.utils.WriteToFile;
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
    private static final long GESTURE_DURATION = 100;
    private static final long MIN_GESTURE_DURATION = 100;
    private static final float DURATION_MULTIPLIER = 2.0f;
    private boolean gestureInProgress = false;
    public boolean durationPopOut;
    private Rect keyboardBounds = new Rect();
    private Rect _keyboardBounds = new Rect();
    private boolean isKeyboardOpen = false;
    private long startTime;
    private long endTime;
    private Instrumentation instrumentation;
    private Handler handler;
    private HandlerThread handlerThread;
    private SparseBooleanArray keyStates = new SparseBooleanArray();
    private DebuggingStats gboardDebuggingStats = new DebuggingStats("GBoard");
    private DebuggingStats openboardDebuggingStats = new DebuggingStats("OpenBoard");
    private DebuggingStats debuggingStats = gboardDebuggingStats;

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
    private ArrayList<SwipePoint> swipePath = null;
    private static final String CHANNEL_ID = "accessibility_service_channel";
    private static final int NOTIFICATION_ID = 1001;
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
                phraseStartTimestamp = 0;
                lastWordTypedTimestamp = 0;
                wordsPerPhrase = 0;
                phraseWordsPerMinute.clear();
                runningWordsPerMinute.clear();
                runningWordsPerPhrase.clear();
                runningSwipeDuration.clear();

                checkForKeyboardType();
                gboardDebuggingStats.load(context);
                openboardDebuggingStats.load(context);
                debuggingStats.load(context);
            }
        };

        screenCaptureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "screenCaptureReceiver: " + intent.getAction());
                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra("data");

                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (projectionManager == null) projectionManager = getSystemService(MediaProjectionManager.class);
                    if (mediaProjection != null) mediaProjection.stop();

                    mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                    attemptScreenCaptureSetup();
                }
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
            registerReceiver(screenCaptureReceiver, new IntentFilter("SCREEN_CAPTURE_PERMISSION_RESULT"),
                    RECEIVER_EXPORTED);
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
            registerReceiver(screenCaptureReceiver, new IntentFilter("SCREEN_CAPTURE_PERMISSION_RESULT"));
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

                            if (mediaProjection != null && currentKeyboard == "GBoard") {
                                if (checkForPrediction && !previousWordPredictionCheckRunning) {
                                    if (startTime + 500 <= System.currentTimeMillis()) {
                                        checkForWordPrediction();
                                    }
                                }
                                if (updateCanvas) {
                                    serviceUiManager.updatePreviewBitmap(previousWordPredictionBitmap, predictionBounds);
                                    updateCanvas = false;
                                }
                            }

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
    private int PHRASE_COOLDOWN = 4000;
    private long phraseStartTimestamp;
    private long lastWordTypedTimestamp;
    private boolean isTyping = false;
    private boolean checkForNewWord = false;
    private String lastWord;
    private String newWord;

    private void processTypedText(CharSequence newText) {
        typedText.append(newText);
        String[] words = typedText.toString().split("\\s+");
        if (words.length > 0) {
            Long now = System.currentTimeMillis();
            logToFile.log(TAG, "Word typed: " + lastWord);
            Log.d(TAG, "Word typed: " + lastWord);
            Log.d(TAG, "processTypedText(): [checkForNewWord==" + checkForNewWord + "] [(lastWordTypedTimestamp + 2000 <= now)==" + (lastWordTypedTimestamp + 2000 <= now) + "]");
            if (checkForNewWord) {
                // TODO: MOVE THIS TO MAIN LOOP CHECK EVERY TICK INSTEAD OF HERE
//                if  (lastWordTypedTimestamp + 1000 <= now) {
//                    // TODO: CLEAR FLAGS AND RESET TIMESTAMPS
//                    Log.d(TAG, "processTypedText(): false alert of word typed. not saving word to stats.");
//                }

                if (lastWord == null) {
                    lastWord = words[0];
                }
                lastWord = newWord;
                newWord = words[words.length - 1];
//                logNewWordSwipe(lastWord);
                debuggingStats.addWordSwiped(lastWord, startTime, endTime);
                checkForNewWord = false;
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

        // Set up background thread and image reader
//        startBackgroundThread();
//        setupImageReader();
    }

    private final List<Integer> validKeyEventKeys = Arrays.asList(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4);
//    private final List<Integer> validKeyEventActions = Arrays.asList(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP);

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.PAUSE) {
            return false;
        }
        if (validKeyEventKeys.contains(event.getKeyCode())) {
            return handleKeyEvent(event);
        }
        return false;
    }

    private boolean swipeToggle = false;

    private boolean handleKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.PAUSE) {
            return false;
        }
        int eventAction = event.getAction();
        int keyCode = event.getKeyCode();
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
                if (eventAction == KeyEvent.ACTION_DOWN) {
                    keyStates.put(keyCode, true);
                    Log.d(TAG, "SWIPE KeyEvent.ACTION_DOWN");
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
                    keyStates.put(keyCode, false);
                    Log.d(TAG, "SWIPE KeyEvent.ACTION_UP");
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
            case KeyEvent.KEYCODE_3:
                if (eventAction == KeyEvent.ACTION_DOWN) {
                    if (swipeToggle) {
                        Log.d(TAG, "STOP SWIPE TOGGLE KeyEvent.ACTION_DOWN");
                        swipeToggle = false;
                        if (isRealtimeSwipeEnabled()) {
                            stopRealtimeSwipe();
                        } else {
                            DispatchEventHelper.checkAndDispatchEvent(
                                    this,
                                    cursorController,
                                    serviceUiManager,
                                    BlendshapeEventTriggerConfig.EventType.SWIPE_STOP);
                        }
                    } else {
                        Log.d(TAG, "START SWIPE TOGGLE KeyEvent.ACTION_DOWN");
                        swipeToggle = true;
                        if (isRealtimeSwipeEnabled()) {
                            startRealtimeSwipe();
                        } else {
                            DispatchEventHelper.checkAndDispatchEvent(
                                    this,
                                    cursorController,
                                    serviceUiManager,
                                    BlendshapeEventTriggerConfig.EventType.SWIPE_START);
                        }
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

    private ArrayList<Long> swipeTimes = new ArrayList<>();

    private void startRealtimeSwipe() {
        isSwiping = true;
        cursorController.isRealtimeSwipe = true;
        swipePath = new ArrayList<>();

        new Thread(() -> {
            startTime = SystemClock.uptimeMillis();
            // timeBetweenSwypes = startTime - endTime;
            // if (timeBetweenSwypes > maxPauseBetweenWords)
            //      startNewSwypePhrase(startTime);
            // else
            //      recordTimeBetweenSwypes(timeBetweenSwypes);
            float[] initialPosition = getCursorPosition();
            if (isKeyboardOpen && initialPosition[1] > keyboardBounds.top) {
                checkForPrediction = true;
                // TODO: free up screencapture resources until ^^^
                Log.d(TAG, "kbd open and swipe starting inside of keyboard region");
            }
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
            swipePath.add(new SwipePoint((int) initialPosition[0], (int) initialPosition[1], 0));
            swipeTimes.add(startTime);
            debugText[0] = "Swiping";
            debugText[1] = "X, Y: (" + initialPosition[0] + ", " + initialPosition[1] + ")";

            long lastCheckTime = System.currentTimeMillis();
            while (isSwiping) {
                float[] cursorPosition = getCursorPosition();
                long now = SystemClock.uptimeMillis();
                try {
                    event = MotionEvent.obtain(
                            startTime,
                            now,
                            MotionEvent.ACTION_MOVE,
                            cursorPosition[0],
                            cursorPosition[1],
                            0
                    );
                    injectInputEvent(event);
                } catch (Exception e) {
                    Log.e(TAG, "Error while injecting swipe input event in startRealtimeSwipe: " + e);
                }
                if (swipePath.get(swipePath.size() - 1).x == cursorPosition[0] && swipePath.get(swipePath.size() - 1).y == cursorPosition[1]) {
                    // skipping
                } else {
                    swipePath.add(new SwipePoint((int) cursorPosition[0], (int) cursorPosition[1], now - startTime));
                }
                debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";

                try {
                    Thread.sleep(16); // 60 FPS
                } catch (Exception e) {
                    Log.e(TAG, "Error while sleeping in startRealtimeSwipe: " + e);
                }

                now = System.currentTimeMillis();

                // Check if the button is still being pressed every 500ms
                if (isSwiping && (now - lastCheckTime) >= 500) {
                    if (!isSwipeKeyStillPressed()) {
                        Log.e(TAG, "Button not pressed, manually ending swipe.");
                        logToFile.logError(TAG, "Button not pressed, manually ending swipe.");
                        stopRealtimeSwipe();
                        break;
                    }
                    lastCheckTime = now;
                }

            }
        }).start();
    }

    private void stopRealtimeSwipe() {
        endTime = SystemClock.uptimeMillis();
        float[] cursorPosition = getCursorPosition();
        serviceUiManager.clearPreviewBitmap();
        new Thread(() -> {
            try {
                MotionEvent event = MotionEvent.obtain(
                        startTime,
                        endTime,
                        MotionEvent.ACTION_UP,
                        cursorPosition[0],
                        cursorPosition[1],
                        0
                );
                injectInputEvent(event);
                Log.d(TAG, "MotionEvent.ACTION_UP @ (" + cursorPosition[0] + ", " + cursorPosition[1] + ")");

                // TODO: pause screencapture sometime after it seems like the user has finished swipe-typing.

            } catch (Exception e) {
                logToFile.logError(TAG, "ERROR WHILE ENDING SWIPE!!!: sendPointerSync cannot be called from the main thread." + e);
                Log.e(TAG, "sendPointerSync cannot be called from the main thread.", e);
            }
            swipePath.add(new SwipePoint((int) cursorPosition[0], (int) cursorPosition[1], endTime - startTime));
            isSwiping = false;
            cursorController.isRealtimeSwipe = false;
            if (checkForPrediction) {
                checkForNewWord = true;
                checkForPrediction = false;
                previousWordPredictionBitmap = null;
                predictionBounds = null;
            }
//            displaySwipeInfo();
        }).start();
    }

    private int wordsPerPhrase = 0;
    private ArrayList<Float> phraseWordsPerMinute = new ArrayList<>();
    private ArrayList<Float> runningWordsPerMinute = new ArrayList<>();
    private ArrayList<Integer> runningWordsPerPhrase = new ArrayList<>();
    private ArrayList<Integer> runningSwipeDuration = new ArrayList<>();

    @SuppressLint("DefaultLocale")
    public void displaySwipeInfo() {
        int swipeDurationMs = (int) swipePath.get(swipePath.size() - 1).getTimestamp();
        Log.d(TAG, "Swipe duration: " + swipeDurationMs);

        ArrayList<Integer> deltaDurationMs = new ArrayList<>();
        ArrayList<Integer> deltaX = new ArrayList<>();
        ArrayList<Integer> deltaY = new ArrayList<>();
        ArrayList<Integer> deltaX2ndOrder = new ArrayList<>();
        ArrayList<Integer> deltaY2ndOrder = new ArrayList<>();
        ArrayList<Double> distanceBetween = new ArrayList<>();
        ArrayList<Double> velocity = new ArrayList<>();

        StringBuilder pathPointsStr = new StringBuilder();

        int tMaxXDelta = 0;
        int tMaxYDelta = 0;
        int maxDeltaX = 0;
        int maxDeltaY = 0;
        for (int i = 1; i < swipePath.size(); i++) {
            SwipePoint previousPoint = swipePath.get(i - 1);
            SwipePoint currentPoint = swipePath.get(i);
            deltaDurationMs.add((int) (currentPoint.getTimestamp() - previousPoint.getTimestamp()));

            int tDeltaX = currentPoint.x - previousPoint.x;
            int tDeltaY = currentPoint.y - previousPoint.y;
            deltaX.add(tDeltaX);
            deltaY.add(tDeltaY);
            if (Math.abs(tDeltaX) > tMaxXDelta) {
                tMaxXDelta = Math.abs(tDeltaX);
                maxDeltaX = tDeltaX;
            }
            if (Math.abs(tDeltaY) > tMaxYDelta) {
                tMaxYDelta = Math.abs(tDeltaY);
                maxDeltaY = tDeltaY;
            }

            distanceBetween.add(getDistanceBetweenPoints(previousPoint.x, previousPoint.y, currentPoint.x, currentPoint.y));
            pathPointsStr.append(String.format(Locale.getDefault(), "(%d, %d, %d), ", previousPoint.x, previousPoint.y, previousPoint.getTimestamp()));
            velocity.add(distanceBetween.get(i - 1) / deltaDurationMs.get(i - 1));
        }

        pathPointsStr.append(String.format(Locale.getDefault(), "(%d, %d, %d)", swipePath.get(swipePath.size()-1).x, swipePath.get(swipePath.size()-1).y, swipePath.get(swipePath.size() - 1).getTimestamp()));

        for (int i = 1; i < deltaX.size(); i++) {
            deltaX2ndOrder.add(Math.abs(deltaX.get(i) - deltaX.get(i - 1)));
            deltaY2ndOrder.add(Math.abs(deltaY.get(i) - deltaY.get(i - 1)));
        }

        double averageDeltaX = deltaX.stream().mapToInt(Integer::intValue).average().orElse(0);
        double averageDeltaY = deltaY.stream().mapToInt(Integer::intValue).average().orElse(0);
        double averageDistance = distanceBetween.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double averageDuration = deltaDurationMs.stream().mapToInt(Integer::intValue).average().orElse(0);
        int maxDeltaX2ndOrder = Collections.max(deltaX2ndOrder);
        int maxDeltaY2ndOrder = Collections.max(deltaY2ndOrder);
        double averageDeltaX2ndOrder = deltaX2ndOrder.stream().mapToInt(Integer::intValue).average().orElse(0);
        double averageDeltaY2ndOrder = deltaY2ndOrder.stream().mapToInt(Integer::intValue).average().orElse(0);
        double averageVelocity = velocity.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int maxVelocityIndex = velocity.indexOf(Collections.max(velocity));
        int maxDistanceIndex = distanceBetween.indexOf(Collections.max(distanceBetween));
        int minDurationIndex = deltaDurationMs.indexOf(Collections.min(deltaDurationMs));

        boolean swipeInKDBRegion = isKeyboardOpen && swipePath.get(0).y > keyboardBounds.top && swipePath.get(swipePath.size() - 1).y > keyboardBounds.top;

        String swipeInfo = String.format(Locale.getDefault(), "Delta: MAX(%dx, %dy) AVG(%.1fx, %.1fy); 2nd Delta: MAX(%dx, %dy) AVG(%.1fx, %.1fy)",
                maxDeltaX, maxDeltaY, averageDeltaX, averageDeltaY, maxDeltaX2ndOrder, maxDeltaY2ndOrder, averageDeltaX2ndOrder, averageDeltaY2ndOrder);

        debugText[0] = String.format("MX %dx %dy AV %dx %dy", maxDeltaX, maxDeltaY, Math.round(averageDeltaX), Math.round(averageDeltaY));
        debugText[1] = String.format("MX %dx %dy AV %dx %dy", maxDeltaX2ndOrder, maxDeltaY2ndOrder, Math.round(averageDeltaX2ndOrder), Math.round(averageDeltaY2ndOrder));

        swipeInfo = "[" + (swipeInKDBRegion ? "KBD" : "NAV") + "]\n" + swipeInfo + "\nDuration: " + swipeDurationMs + "ms, AVG Duration: " + averageDuration + "ms, MIN Duration: " + Collections.min(deltaDurationMs) + " [index: " + minDurationIndex + "]\n" +
                "Distance: AVG " + String.format("%,.1f", averageDistance) + "px, Max " + Collections.max(distanceBetween) + "px [index:" + maxDistanceIndex + "]\n" +
                "Velocity: AVG " + String.format("%,.1f", averageVelocity) + "px/ms, Max " + String.format("%,.1f", Collections.max(velocity)) + "px/ms [index:" + maxVelocityIndex + "]";
//                "Path size: " + swipePath.size() + ", Path Points: " + pathPointsStr + "\n";
        logToFile.log(TAG, swipeInfo);

        long now = System.currentTimeMillis();

        if (swipeInKDBRegion) {
            // To calculate the words per minute for each phrase, you need:
            //  (1) The total length of time spent swyping.  For a phrase with N words, this is the sum of:
            //         N swype durations - this can be acccumulated in totalSwypingTime
            //         (N - 1) pauses between words - this can be acccumulated in totalPausingTime
            // Float totalPhraseTime = totalSwypingTimen + totalPausingTime;
            // There are two different ways we want to calculate words per minute:
            //    (1) Literally counting how many separate words were output (the number N as defined above)
            //          float nWords = N;
            //    (2) An average word in English is generally considered to be 5 characters long.  With a space being autmatically output between each word, this gives a value:
            //          float nFiveLetterWords = totalPhraseTextLength / 6;
            // Then we would have the following, but this is only calculated at the END of a phrase:
            // Float latestWordsPerMinute = (float) (nWords / (totalPhraseTime/60000));
            // Float latestFiveLetterWordsPerMinute = (float) (nFiveLetterWords / (totalPhraseTime/60000));
            // And for good measure, we can throw in:
            // Float grandTotalPhraseTime.add(totalPhraseTime);            // Keep track of the total time that the user spends Swyping
            // runningSwypingDuration.add(swipeDurationMs);

            Float latestWordsPerMinute = (float) (60000 / swipeDurationMs);
            phraseWordsPerMinute.add(latestWordsPerMinute);
            runningWordsPerMinute.add(latestWordsPerMinute);
            runningSwipeDuration.add(swipeDurationMs);

            // starting phrase
            if (phraseStartTimestamp == 0) {
                phraseStartTimestamp = startTime;
                lastWordTypedTimestamp = endTime;
                wordsPerPhrase = 1;

            // phrase in progress
                // This should be checking the time from the END of the preceding Swype to the start of this new Swype)
            } else if (startTime - lastWordTypedTimestamp < PHRASE_COOLDOWN) {
                lastWordTypedTimestamp = endTime;
                wordsPerPhrase += 1;

            // ending phrase
            } else {
                runningWordsPerPhrase.add(wordsPerPhrase);

                // update debugging stats

//                debuggingStats.setWpmLatestAvg(phraseWordsPerMinute.stream().collect(Collectors.averagingDouble(Float::floatValue)).floatValue());
                debuggingStats.setWordsPerMinAvg(runningWordsPerMinute.stream().collect(Collectors.averagingDouble(Float::floatValue)).floatValue());
                debuggingStats.setWordsPerSessionAvg(runningWordsPerPhrase.stream().collect(Collectors.averagingDouble(Integer::intValue)).floatValue());
                debuggingStats.setSwipeDurationAvg(runningSwipeDuration.stream().collect(Collectors.averagingDouble(Integer::intValue)).floatValue());
                debuggingStats.save(this);

                // log debugging stats to file and console
                String logStr = String.format("# of words in phrase: %d, phrase typing time: %d, avg words per minute: %.2f, running avg words per minute: %.2f, running avg words per phrase: %.2f, running avg swipe duration: %.2f",
                    wordsPerPhrase,
                    swipeDurationMs,
//                    debuggingStats.getWpmLatestAvg(),
                    debuggingStats.getWordsPerMinAvg(),
                    debuggingStats.getWordsPerSessionAvg(),
                    debuggingStats.getSwipeDurationAvg()
                );
                logToFile.log(TAG, logStr);
                Log.d(TAG, logStr);

                // clear phrase stats
                wordsPerPhrase = 0;
                phraseStartTimestamp = 0;
                lastWordTypedTimestamp = 0;
                phraseWordsPerMinute.clear();
            }
        }

        Log.d(TAG, swipeInfo);
    }

    public double getDistanceBetweenPoints(double x1, double y1, double x2, double y2) {
        return Math.sqrt((y2 - y1) * (y2 - y1) + (x2 - x1) * (x2 - x1));
    }

    private boolean isSwipeKeyStillPressed() {
        return keyStates.get(KeyEvent.KEYCODE_BUTTON_A, false) || keyStates.get(KeyEvent.KEYCODE_ENTER, false) || keyStates.get(KeyEvent.KEYCODE_1, false) || swipeToggle;
    }

    private WriteToFile logToFile = new WriteToFile(this);

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
            logToFile.logError(TAG, "setupImageReader() failed: " + e);
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
            logToFile.logError(TAG, "setupVirtualDisplay() failed: " + e);
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
            logToFile.logError(TAG, "startBackgroundThread() failed: " + e);
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
                logToFile.logError(TAG, "Thread interrupted while stopping handlerThreadMP: " + e);
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
            logToFile.logError(TAG, "captureScreenshot() failed: " + e);
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
            WriteToFile writeToFile = new WriteToFile(this);
            writeToFile.saveBitmap(croppedScreenshot);
        }
    }

    private Bitmap previousWordPredictionBitmap = null;
    private Rect predictionBounds = new Rect();
    private boolean previousWordPredictionCheckRunning = false;
    private int WORD_PREDICTION_DELAY = 500;

    private void checkForWordPrediction() {
        WriteToFile writeToFile = new WriteToFile(this);

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
