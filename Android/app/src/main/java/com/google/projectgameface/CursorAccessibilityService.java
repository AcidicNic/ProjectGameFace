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
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.content.res.Resources;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
import android.view.accessibility.AccessibilityNodeInfo;
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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.projectgameface.utils.CursorUtils;
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
    public boolean isSwiping = false;
    private Rect keyboardBounds = new Rect();
    private Rect _keyboardBounds = new Rect();
    private Rect navBarBounds = new Rect();
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

    public long getDragToggleDuration() {
        return (long) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.DRAG_TOGGLE_DURATION);
    }

    public int getQuickTapThreshold() {
        return (int) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.QUICK_TAP_THRESHOLD);
    }

    public int getLongTapThreshold() {
        return (int) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.LONG_TAP_THRESHOLD);
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

                            dispatchEvent(null, null);

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
    private void dispatchEvent(BlendshapeEventTriggerConfig.EventType inputEvent, KeyEvent keyEvent) {
        // Check what inputEvent to dispatch.
        if (inputEvent == null && keyEvent == null) {
            inputEvent = cursorController.createCursorEvent(facelandmarkerHelper.getBlendshapes());
        }

        switch (inputEvent) {
            case NONE:
                return;
            case DRAG_TOGGLE:
                break;
            case TOGGLE_TOUCH:
                break;
            case CONTINUOUS_TOUCH:
                break;
            case END_TOUCH:
                break;
            case BEGIN_TOUCH:
                break;
            default:
                // Cancel drag if user perform any other inputEvent.
                cursorController.prepareDragEnd(0, 0);
                serviceUiManager.fullScreenCanvas.clearDragLine();
                break;
        }

        Log.d(TAG, "dispatchEvent: " + inputEvent);

        switch (serviceState) {
            case GLOBAL_STICK:
            case ENABLE:
                // Check inputEvent type and dispatch it.
                DispatchEventHelper.checkAndDispatchEvent(
                        CursorAccessibilityService.this,
                        cursorController,
                        serviceUiManager,
                        inputEvent,
                        keyEvent);
                break;

            case PAUSE:
                // In PAUSE state user can only perform togglePause
                // with face gesture.
                if (inputEvent == BlendshapeEventTriggerConfig.EventType.CURSOR_PAUSE) {
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
//            if (keyboardBounds.top > navBarBounds.top) {
//                keyboardBounds.union(navBarBounds);
//            }
            cursorController.setTemporaryBounds(keyboardBounds);
//            Log.d(TAG, "Temporary bounds set: " + keyboardBounds);

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


//    public boolean handleSwipeAndTouch()


    /**
     * Handle key event for swipe and touch.
     *
     * @param event The key event.
     * @return True if the key event is handled.
     */
    private boolean handleKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.PAUSE) {
            return false;
        }
        SharedPreferences preferences = getSharedPreferences(ProfileManager.getCurrentProfile(this), Context.MODE_PRIVATE);
        String eventName = "NONE";
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_1) {
            eventName = preferences.getString(BlendshapeEventTriggerConfig.Blendshape.SWITCH_ONE.toString()+"_event", BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
            if (eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
                return false;
            }
        } else if (keyCode == KeyEvent.KEYCODE_2) {
            eventName = preferences.getString(BlendshapeEventTriggerConfig.Blendshape.SWITCH_TWO.toString()+"_event", BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
            if (eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
                return false;
            }
        } else if (keyCode == KeyEvent.KEYCODE_3) {
            eventName = preferences.getString(BlendshapeEventTriggerConfig.Blendshape.SWITCH_THREE.toString()+"_event", BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
            if (eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
                return false;
            }
        } else {
//            wrong key pressed
            return false;
        }

        BlendshapeEventTriggerConfig.EventType eventType = BlendshapeEventTriggerConfig.EventType.valueOf(eventName);
        Log.d(TAG, "handleKeyEvent: name " + eventName+ "; + type " + eventType);
        dispatchEvent(eventType, event);
        return true;
    }


    public void quickTap(int[] cursorPosition, int duration) {
        if (duration == -1) {
            duration = 200;
        }
        dispatchGesture(
            CursorUtils.createClick(
                    cursorPosition[0] ,
                    cursorPosition[1] ,
                    /* startTime= */ 0,
                    /* duration= */ duration),
            /* callback= */ null,
            /* handler= */ null);

        serviceUiManager.drawTouchDot(cursorPosition);
    }

    private static final int DRAG_DURATION_MS = 250;
    public void dispatchDragOrHold() {
        Log.d("dispatchDragOrHold", "dispatchDragOrHold");
        int[] cursorPosition = cursorController.getCursorPositionXY();

        // Register new drag action.
        if (!cursorController.isDragging) {
            Log.d("dispatchDragOrHold", "new drag action");

            cursorController.prepareDragStart(cursorPosition[0], cursorPosition[1]);

            serviceUiManager.fullScreenCanvas.setHoldRadius(
                    cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HOLD_RADIUS));

            serviceUiManager.setDragLineStart(cursorPosition[0], cursorPosition[1]);
        }
        // Finish drag action.
        else {
            Log.d("dispatchDragOrHold", "end drag action");
            cursorController.prepareDragEnd(cursorPosition[0], cursorPosition[1]);
            serviceUiManager.fullScreenCanvas.clearDragLine();

            // Cursor path distance.
            float xOffset = cursorController.dragEndX - cursorController.dragStartX;
            float yOffset = cursorController.dragEndY - cursorController.dragStartY;

            // Is action finished inside defined circle or not.
            boolean isFinishedInside =
                (Math.abs(xOffset)
                    < cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HOLD_RADIUS))
                    && (Math.abs(yOffset)
                    < cursorController.cursorMovementConfig.get(
                    CursorMovementConfig.CursorMovementConfigType.HOLD_RADIUS));

            // If finished inside a circle, trigger HOLD action.
            if (isFinishedInside) {
                // Dispatch HOLD event.
                dispatchGesture(
                    CursorUtils.createClick(
                            cursorController.dragStartX,
                            cursorController.dragStartY,
                            0,
                            (long)
                                    cursorController.cursorMovementConfig.get(
                                            CursorMovementConfig.CursorMovementConfigType.HOLD_TIME_MS)),
                    /* callback= */ null,
                    /* handler= */ null);

            }
            // Trigger normal DRAG action.
            else {
                dispatchGesture(
                    CursorUtils.createSwipe(
                            cursorController.dragStartX,
                            cursorController.dragStartY,
                            xOffset,
                            yOffset,
                            /* duration= */ DRAG_DURATION_MS),
                    /* callback= */ null,
                    /* handler= */ null);
            }
        }
    }

    boolean dragToggleActive = false;
    public boolean toggleTouch() {
        Log.d(TAG, "toggleTouch()");
        int[] cursorPosition = new int[2];
        cursorPosition = getCursorPosition();

//        if (isGestureSwiping) {
//            Log.d(TAG, "STOP GESTURE SWIPE KeyEvent.ACTION_DOWN");
//            stopSwipeGesture();
//        } else {
//            Log.d(TAG, "START GESTURE SWIPE KeyEvent.ACTION_DOWN");
//            startSwipeGesture();
//        }
        if (isSwiping && swipeToggle) {
            Log.d(TAG, "STOP SWIPE TOGGLE KeyEvent.ACTION_DOWN");
            swipeToggle = false;
            stopRealtimeSwipe();
        } else if (canInjectEvent(cursorPosition[0], cursorPosition[1])) {
            Log.d(TAG, "START SWIPE TOGGLE KeyEvent.ACTION_DOWN");
            swipeToggle = true;
            startRealtimeSwipe();
        } else {
            Log.d(TAG, "DRAG TOGGLE KeyEvent.ACTION_DOWN");
            DispatchEventHelper.checkAndDispatchEvent(
                    CursorAccessibilityService.this,
                    cursorController,
                    serviceUiManager,
                    BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE,
                    null);

            /* // drag toggle with delay for quick tap... not sure if this is useful?
            if (cursorController.isDragging) {
                DispatchEventHelper.checkAndDispatchEvent(
                        CursorAccessibilityService.this,
                        cursorController,
                        serviceUiManager,
                        BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE,
                        null);
                dragToggleActive = false;
            } else if (!dragToggleActive) {
                dragToggleStartTime = SystemClock.uptimeMillis();
                dragToggleCancelled = false;
                dragtoggleStartPosition = cursorPosition;
                dragToggleActive = true;
                dragToggleHandler.postDelayed(dragToggleRunnable, getDragToggleDuration());
            } else {
                long elapsedTime = SystemClock.uptimeMillis() - dragToggleStartTime;
                dragToggleHandler.removeCallbacks(dragToggleRunnable);
                if (elapsedTime < getDragToggleDuration()) {
                    dragToggleCancelled = true;
                    dragToggleActive = false;
                    // Perform quick tap instead of enabling drag toggle
                    quickTap(dragtoggleStartPosition, -1);
                }
            }
            */
        }
        return true;
    }

    public void deleteLastWord() {
        Log.d(TAG, "deleteLastWord()");
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.d(TAG, "deleteLastWord(): rootNode is null");
            return;
        }

        AccessibilityNodeInfo focusedNode = findFocusedEditText(rootNode);
        if (focusedNode != null && focusedNode.getText() != null) {
            Log.d(TAG, "deleteLastWord(): focusedNode: " + focusedNode);
            String text = focusedNode.getText().toString();
            if (text.isEmpty()) {
                Log.d(TAG, "deleteLastWord(): text is empty");
                return;
            }
            int cursorPosition = focusedNode.getTextSelectionStart(); // get current cursor position
            if (cursorPosition < 0) return;
            if (text.isEmpty() || cursorPosition == 0) return;
            DeleteResult modifiedTextResult = removeLastWord(text, cursorPosition);
//            Log.d(TAG, "deleteLastWord(): modifiedText: " + modifiedTextResult.text + ", newCursor: " + modifiedTextResult.newCursor);

            Bundle setModifiedTextargs = new Bundle();
            setModifiedTextargs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, modifiedTextResult.text);

            Bundle setCursorPositionargs = new Bundle();
            setCursorPositionargs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, modifiedTextResult.newCursor);
            setCursorPositionargs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, modifiedTextResult.newCursor);

            focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.getId(), setModifiedTextargs);
            focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION.getId(), setCursorPositionargs);
        }
    }

    private static class DeleteResult {
        String text;
        int newCursor;
    }

    private AccessibilityNodeInfo findFocusedEditText(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) {
            return null;
        }

        if (rootNode.isFocused() && rootNode.getClassName().equals("android.widget.EditText")) {
            return rootNode;
        }

        for (int i = 0; i < rootNode.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = rootNode.getChild(i);
            AccessibilityNodeInfo result = findFocusedEditText(childNode);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    private DeleteResult removeLastWord(String text, int cursor) {
        DeleteResult result = new DeleteResult();
        if (text == null || text.isEmpty()) {
            result.text = "";
            result.newCursor = 0;
            return result;
        }

        // First, find the start and end of the current word if cursor is inside a word
        int wordStart = cursor;
        int wordEnd = cursor;
        
        // Find start of current word
        while (wordStart > 0 && Character.isLetterOrDigit(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        
        // Find end of current word
        while (wordEnd < text.length() && Character.isLetterOrDigit(text.charAt(wordEnd))) {
            wordEnd++;
        }

        // If cursor is inside a word, delete that word
        if (wordStart < cursor && wordEnd > cursor) {
            result.text = text.substring(0, wordStart) + text.substring(wordEnd);
            result.newCursor = wordStart;
            return result;
        }

        // Otherwise, proceed with original logic
        int start = cursor;
        char ch = text.charAt(cursor - 1);
        if (ch == '\n') {
            start = cursor - 1;
        } else if (Character.isWhitespace(ch)) {
            while (start > 0) {
                char c = text.charAt(start - 1);
                if (c == '\n' || !Character.isWhitespace(c)) break;
                start--;
            }
            if (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
                while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) start--;
            }
        } else if (Character.isLetterOrDigit(ch)) {
            while (start > 0) {
                char c = text.charAt(start - 1);
                if (c == '\n' || !Character.isLetterOrDigit(c)) break;
                start--;
            }
        } else {
            start = cursor - 1;
        }
        result.text = text.substring(0, start) + text.substring(cursor);
        result.newCursor = start;
        return result;
    }

    public void dragToggle() {
        dispatchEvent(BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE, null);
    }

    int[] dragtoggleStartPosition = new int[2];

    public boolean continuousTouch (KeyEvent event) {
        Log.d(TAG, "continuousTouch() SWIPE KeyEvent: " + event);

        int keyCode = -1;
        int eventAction = -1;
        if (event != null) {
            eventAction = event.getAction();
            keyCode = event.getKeyCode();
        }
        int[] cursorPosition = new int[2];
        cursorPosition = getCursorPosition();

        if (eventAction == KeyEvent.ACTION_DOWN || !cursorController.continuousTouchActive) {
            if (keyCode <= 0) {
                keyStates.put(keyCode, true);
            }
            cursorController.continuousTouchActive = true;
            Log.d(TAG, "continuousTouch() SWIPE KeyEvent.ACTION_DOWN");

            if (canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                startRealtimeSwipe();
            } else {
                dragToggleStartTime = SystemClock.uptimeMillis();
                dragToggleCancelled = false;
                dragtoggleStartPosition = cursorPosition;
                dragToggle = true;
//                Log.d(TAG, "DRAG TOGGLE DELAY " + getDragToggleDuration());
                dragToggleHandler.postDelayed(dragToggleRunnable, getDragToggleDuration());
            }
        } else if (eventAction == KeyEvent.ACTION_UP || cursorController.continuousTouchActive) {
            if (keyCode <= 0) {
                keyStates.put(keyCode, false);
            }

            cursorController.continuousTouchActive = false;

            Log.d(TAG, "continuousTouch() SWIPE KeyEvent.ACTION_UP");
            if (isSwiping) {
                stopRealtimeSwipe();
            } else {
                long elapsedTime = SystemClock.uptimeMillis() - dragToggleStartTime;
                dragToggleHandler.removeCallbacks(dragToggleRunnable);
                if (elapsedTime < getDragToggleDuration()) {
                    dragToggleCancelled = true;
                    // Perform quick tap instead of enabling drag toggle
                    quickTap(dragtoggleStartPosition, -1);
                } else {
                    dragToggle = false;
                    if (cursorController.isDragging) {
                        DispatchEventHelper.checkAndDispatchEvent(
                                CursorAccessibilityService.this,
                                cursorController,
                                serviceUiManager,
                                BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE,
                                null);
                    }
                }
            }
        }
        return true;
    }

    public void startTouch() {
        int[] cursorPosition = new int[2];
        cursorPosition = getCursorPosition();
        if (canInjectEvent(cursorPosition[0], cursorPosition[1])) {
            Log.d(TAG, "START SWIPE");
            swipeToggle = true;
            startRealtimeSwipe();
        } else if (!cursorController.isDragging) {
            Log.d(TAG, "START DRAG");
            DispatchEventHelper.checkAndDispatchEvent(
                    CursorAccessibilityService.this,
                    cursorController,
                    serviceUiManager,
                    BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE,
                    null);
        }
    }

    public void endTouch() {
        int[] cursorPosition = new int[2];
        cursorPosition = getCursorPosition();
        if (isSwiping) {
            Log.d(TAG, "STOP SWIPE");
            stopRealtimeSwipe();
        } else if (cursorController.isDragging) {
            Log.d(TAG, "STOP DRAG");
            DispatchEventHelper.checkAndDispatchEvent(
                    CursorAccessibilityService.this,
                    cursorController,
                    serviceUiManager,
                    BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE,
                    null);
        }
    }


    // Constants for smart touch timing
    private int quickTapThreshold = getQuickTapThreshold();
    private int longTapThreshold = getLongTapThreshold();

    // Fields for smart touch state
    private long smartTouchStartTime;
    private int[] smartTouchStartPosition;
    private boolean smartTouchCancelled = false;
    private Handler smartTouchHandler = new Handler(Looper.getMainLooper());

    // Runnables for quick and long touch
    private final Runnable quickTouchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!smartTouchCancelled && cursorController.smartTouchActive) {
                // Start animating to red when we hit quick delay
                serviceUiManager.cursorView.animateToColor("RED", longTapThreshold - quickTapThreshold);
            }
        }
    };

    private final Runnable longTouchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!smartTouchCancelled && cursorController.smartTouchActive) {
                // Execute long tap
                quickTap(smartTouchStartPosition, 650);
                serviceUiManager.drawTouchDot(smartTouchStartPosition);
                // Reset cursor to white
                serviceUiManager.cursorView.setColor("WHITE");
                cursorController.smartTouchActive = false;
            }
        }
    };

    public boolean combinedTap(KeyEvent event) {
        Log.d(TAG, "smartTouch() KeyEvent: " + event);

        int keyCode = -1;
        int eventAction = -1;
        if (event != null) {
            eventAction = event.getAction();
            keyCode = event.getKeyCode();
        }

        if (eventAction == KeyEvent.ACTION_DOWN || !cursorController.smartTouchActive) {
            if (keyCode <= 0) {
                keyStates.put(keyCode, true);
            }

            quickTapThreshold = getQuickTapThreshold();
            longTapThreshold = getLongTapThreshold();

            // Start the smart touch sequence
            cursorController.smartTouchActive = true;
            smartTouchStartTime = SystemClock.uptimeMillis();
            smartTouchCancelled = false;
            smartTouchStartPosition = getCursorPosition();

            // Start animating to green, duration matches quick delay
            serviceUiManager.cursorAnimateToColor("GREEN", quickTapThreshold);

            // Schedule both quick and long touch handlers
            smartTouchHandler.postDelayed(quickTouchRunnable, quickTapThreshold);
            smartTouchHandler.postDelayed(longTouchRunnable, longTapThreshold);

        } else if (eventAction == KeyEvent.ACTION_UP || cursorController.smartTouchActive) {
            if (keyCode <= 0) {
                keyStates.put(keyCode, false);
            }

            // Calculate how long the touch has been active
            long elapsedTime = SystemClock.uptimeMillis() - smartTouchStartTime;

            // Remove pending handlers
            smartTouchHandler.removeCallbacks(quickTouchRunnable);
            smartTouchHandler.removeCallbacks(longTouchRunnable);

            // Mark as cancelled to prevent pending callbacks from executing
            smartTouchCancelled = true;
            cursorController.smartTouchActive = false;

            // Cancel any ongoing color animation
            serviceUiManager.cursorCancelAnimation();

            // Determine which action to take based on elapsed time
            if (elapsedTime >= quickTapThreshold) {
                // Long touch
                quickTap(smartTouchStartPosition, 650);
                serviceUiManager.drawTouchDot(smartTouchStartPosition);
            } else if (elapsedTime >= quickTapThreshold) {
                // Quick touch
                quickTap(smartTouchStartPosition, 250);
                serviceUiManager.drawTouchDot(smartTouchStartPosition);
            }

            // Reset cursor to white after any action
            serviceUiManager.cursorView.setColor("WHITE");
        }

        return true;
    }

    private Handler dragToggleHandler = new Handler(Looper.getMainLooper());
    private boolean dragToggleCancelled = false;
    private long dragToggleStartTime;

    // Runnable to handle delayed drag toggle start
    private Runnable dragToggleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!dragToggleCancelled) {
                DispatchEventHelper.checkAndDispatchEvent(
                        CursorAccessibilityService.this,
                        cursorController,
                        serviceUiManager,
                        BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE,
                        null);
            } else {
                Log.d(TAG, "Drag toggle cancelled");
                DispatchEventHelper.checkAndDispatchEvent(
                        CursorAccessibilityService.this,
                        cursorController,
                        serviceUiManager,
                        BlendshapeEventTriggerConfig.EventType.CURSOR_TOUCH,
                        null);
            }
        }
    };

    int[] lastValidCoords = new int[2];

    private void startRealtimeSwipe() {
        isSwiping = true;
        cursorController.isRealtimeSwipe = true;
        startUptime = SystemClock.uptimeMillis();
        startTime = System.currentTimeMillis();
        int[] initialPosition = getCursorPosition();

        if (checkForSwipingFromRightKbd) {
            lastValidCoords = initialPosition;
            startedSwipeFromRightKbd = true;

            /* Correct the cursor to start swipe */
//            initialPosition[0] = initialPosition[0] - 1;
            return;
        } else {
            startedSwipeFromRightKbd = false;
        }

        new Thread(() -> {
            if (isKeyboardOpen && initialPosition[1] > keyboardBounds.top) {
//                checkForPrediction = true;
//                 TODO: free up screencapture resources until ^^^
//                Log.d(TAG, "kbd open and swipe starting inside of keyboard region");
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

            }
        }).start();
    }

    private void stopRealtimeSwipe() {
        endUptime = SystemClock.uptimeMillis();
        endTime = System.currentTimeMillis();
        int[] cursorPosition = getCursorPosition();
        serviceUiManager.clearPreviewBitmap();
        int keyWidth = keyboardBounds.width() / 10;
        if (startedSwipeFromRightKbd &&
                (cursorPosition[0] < screenSize.x) &&
                (cursorPosition[0] >= screenSize.x - (keyWidth * 2)) /*(cursorPosition[0] (screenSize.x / 2))*/
            ) {
            handleSwipeFromRightKbd();
            startedSwipeFromRightKbd = false;
            isSwiping = false;
            cursorController.isRealtimeSwipe = false;
            return;
        }

        new Thread(() -> {
            try {
                int action = MotionEvent.ACTION_UP;
                int[] cursorCoords = cursorPosition;

                // Cancel swipe if it ends too close to the right edge
                if (cursorCoords[0] >= screenSize.x - 5) {
                    action = MotionEvent.ACTION_CANCEL;
                }
                // if current cursor position is outside of keyboard bounds, use last valid coords
                else if (!canInjectEvent(cursorPosition[0],  cursorPosition[1])) {
                    cursorCoords = lastValidCoords;
                }

                MotionEvent event = MotionEvent.obtain(
                        startUptime,
                        endUptime,
                        action,
                        cursorCoords[0],
                        cursorCoords[1],
                        0
                );
                injectMotionEvent(event);

                debugText[0] = "Swiping";
                debugText[1] = "X, Y: (" + cursorCoords[0] + ", " + cursorCoords[1] + ")";
                Log.d(TAG, "MotionEvent.ACTION_UP @ (" + cursorCoords[0] + ", " + cursorCoords[1] + ")");
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

    public void handleSwipeFromRightKbd() {
        SharedPreferences preferences = getSharedPreferences(ProfileManager.getCurrentProfile(this), Context.MODE_PRIVATE);
        String eventName = preferences.getString(BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD.toString() + "_event", BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
        if (!eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
            dispatchEvent(BlendshapeEventTriggerConfig.EventType.valueOf(eventName), null);
        }
    }

    private Handler gestureHandler = new Handler(Looper.getMainLooper());
    private boolean isGestureSwiping = false;
    private final int gestureInterval = 10; // Slightly longer for smoother tracking
    private final LinkedList<int[]> gestureQueue = new LinkedList<>();
    private float lastX, lastY;
    private long gestureStartTime = 0;
    private final long MAX_GESTURE_DURATION = 9000;
    private boolean isGestureInProgress = false; // Tracks if a gesture is still running

    public void startSwipeGesture() {
        if (isGestureSwiping) return;
        isGestureSwiping = true;
        gestureQueue.clear();
        gestureStartTime = SystemClock.uptimeMillis();
        isGestureInProgress = false;

        int[] pos = getCursorPosition();
        lastX = pos[0];
        lastY = pos[1];

        simulateTouchDown(lastX, lastY);

        gestureHandler.post(updateSwipeRunnable);
    }

    private Runnable updateSwipeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isGestureSwiping) return;

            // Add latest cursor position to the queue
            gestureQueue.add(getCursorPosition());

            if (!isGestureInProgress && gestureQueue.size() > 1) {
                dispatchQueuedGestures();
            }

            // Keep adding points while a gesture is in progress
            gestureHandler.post(this);
        }
    };

    private void dispatchQueuedGestures() {
        if (gestureQueue.isEmpty() || isGestureInProgress) return;
        isGestureInProgress = true;

        Path path = new Path();
        path.moveTo(lastX, lastY);

        // Use all points collected in the queue to form a smooth segment
        while (!gestureQueue.isEmpty()) {
            int[] next = gestureQueue.poll();
            path.lineTo(next[0], next[1]);
            lastX = next[0];
            lastY = next[1];
        }

        long newStartTime = lastStroke == null ? 0 : lastStroke.getStartTime() + lastStroke.getDuration();

        GestureDescription.Builder builder = new GestureDescription.Builder();

        if (lastStroke == null || !lastStroke.willContinue()) {
            lastStroke = new GestureDescription.StrokeDescription(path, 0, gestureInterval, true);
        } else {
            lastStroke = lastStroke.continueStroke(path, newStartTime, gestureInterval, true);
        }

        builder.addStroke(lastStroke);
        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gesture) {
                isGestureInProgress = false;
                if (isGestureSwiping) {
                    dispatchQueuedGestures(); // Immediately dispatch next segment if points exist
                }
            }
        }, null);
    }

    // 🔄 Restart Gesture if near 10s limit
//    private void restartGesture() {
//        Log.d("GESTURE", "Restarting gesture to avoid 10s limit.");
//
//        int[] pos = getCursorPosition();
//        stopSwipeGesture();
//
//        gestureHandler.postDelayed(() -> startSwipeGesture(), 15);
//    }

    public void stopSwipeGesture() {
        if (!isGestureSwiping) return;
        isGestureSwiping = false;

        int[] pos = getCursorPosition();
        gestureQueue.add(pos);

        dispatchQueuedGestures();
        gestureHandler.removeCallbacks(updateSwipeRunnable);

        if (lastStroke != null && lastStroke.willContinue()) {
            Path path = new Path();
            path.moveTo(lastX, lastY);
            path.lineTo(pos[0], pos[1]);

            long newStartTime = lastStroke.getStartTime() + lastStroke.getDuration();

            GestureDescription.Builder builder = new GestureDescription.Builder();
            lastStroke = lastStroke.continueStroke(path, newStartTime, gestureInterval, false);

            builder.addStroke(lastStroke);
            dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gesture) {
                    Log.d("GESTURE", "ACTION_UP Completed");
                }
            }, null);
        }
    }

    private GestureDescription.StrokeDescription lastStroke;
//    private float lastX, lastY;  // Track last touch position
//
//
    // Start the gesture (ACTION_DOWN)
    public void simulateTouchDown(float x, float y) {
        if (isGestureSwiping) return; // Prevent multiple calls
        isGestureSwiping = true;
        gestureQueue.clear(); // Ensure old movements are discarded
        lastX = x;
        lastY = y;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        lastStroke = new GestureDescription.StrokeDescription(path, 0, gestureInterval, true); // First stroke, will continue

        builder.addStroke(lastStroke);
        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gesture) {
                Log.d("GESTURE", "ACTION_DOWN Completed at " + x + ", " + y);
            }
        }, null);
    }

    // Unified MotionEvent injection method
    private void injectMotionEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) { // Android 12 (API 31)
            Log.d(TAG, "[666] Sending MotionEvent to IME");
            sendMotionEventToIME(event);
        } else {
            try {
                instrumentation.sendPointerSync(event);
                Log.d(TAG, "MotionEvent sent: (" + event.getX() + ", " + event.getY() + ", action=" + event.getAction() + ")");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send MotionEvent(" + event.getX() + ", " + event.getY() + ", action=" + event.getAction() + ")", e);
            }
        }
    }

    /**
     * Send motion event to OpenBoard IME to simulate touch events
     * @param event The motion event to send.
     */
    private void sendMotionEventToIME(MotionEvent event) {
        Log.d(TAG, "[openboard] Sending MotionEvent to IME");
        Intent intent = new Intent("com.headswype.ACTION_SEND_EVENT");
        intent.setPackage("org.dslul.openboard.inputmethod.latin"); // Target the IME app
        intent.putExtra("x", event.getX());
        intent.putExtra("y", event.getY());
        intent.putExtra("action", event.getAction());
        intent.putExtra("downTime", event.getDownTime());
        intent.putExtra("eventTime", event.getEventTime());
        sendBroadcast(intent, "com.headswype.permission.SEND_EVENT"); // Ensure only apps with the correct permission can send
    }

    /**
     * Send key event to OpenBoard IME to simulate virtual keyboard key presses
     * @param keyCode The key code to send.
     * @param isDown Whether the key is pressed down or released.
     * @param isLongPress Whether the key is a long press.
     */
    private void sendKeyEventToIME(int keyCode, boolean isDown, boolean isLongPress) {
        Log.d(TAG, "[openboard] Sending keyEvent to IME");
        Intent intent = new Intent("com.headswype.ACTION_SEND_KEY_EVENT");
        intent.setPackage("org.dslul.openboard.inputmethod.latin"); // Target the IME app
        intent.putExtra("keyCode", keyCode);
        intent.putExtra("isDown", isDown);
        intent.putExtra("isLongPress", isLongPress);
        sendBroadcast(intent, "com.headswype.permission.SEND_EVENT"); // Ensure only apps with the correct permission can send
    }

    /**
     * Send gesture trail color to OpenBoard IME.
     * @param color The color to send. ("green", "red", "orange")
     */
    private void sendGestureTrailColorToIME(String color) {
        Log.d(TAG, "[openboard] Sending getsure trail color to IME");
        Intent intent = new Intent("com.headswype.ACTION_CHANGE_TRAIL_COLOR");
        intent.setPackage("org.dslul.openboard.inputmethod.latin"); // Target the IME app
        intent.putExtra("color", color);
        sendBroadcast(intent, "com.headswype.permission.SEND_EVENT"); // Ensure only apps with the correct permission can send
    }

    private void sendLongPressDelayToIME(int delay) {
        Log.d(TAG, "[openboard] Sending long press delay to IME");
        Intent intent = new Intent("com.headswype.ACTION_SET_LONG_PRESS_DELAY");
        intent.setPackage("org.dslul.openboard.inputmethod.latin"); // Target the IME app
        intent.putExtra("delay", delay);
        sendBroadcast(intent, "com.headswype.permission.SEND_EVENT"); // Ensure only apps with the correct permission can send
    }

    public int getNavigationBarHeight(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        }
        return 0; // Return 0 if no navigation bar is present
    }

    private boolean checkForSwipingFromRightKbd = false;
    private boolean startedSwipeFromRightKbd = false;
    private int navbarHeight = 0;
    // Check if events can be injected into the window at (x, y)
    public boolean canInjectEvent(float x, float y) {
        for (AccessibilityWindowInfo window : getWindows()) {
            // Get the bounds of the window
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);

            // Check if the coordinates fall within this window
            if (isInjectableWindow(window)) {
                if (bounds.contains((int) x, (int) y)) {
                    navbarHeight = getNavigationBarHeight(this);
                    Log.d(TAG, "Injectable window found at (" + x + ", " + y + "). bounds" + bounds + " _keyboardbounds: " + _keyboardBounds + " keyboardBounds: " + keyboardBounds);
                    checkForSwipingFromRightKbd = false;
                    return true;
                } else if (isKeyboardOpen && y >= keyboardBounds.top) {
                    if (x == 0) {
                        x = 1;
                    } else if (x > screenSize.x - 1) {
                        x = screenSize.x - 1;
                        checkForSwipingFromRightKbd = true;
                    }
                    if (bounds.contains((int) x, (int) y)) {
                        navbarHeight = getNavigationBarHeight(this);
                        Log.d(TAG, "Injectable window found at (" + x + ", " + y + "). bounds" + bounds + " _keyboardbounds: " + _keyboardBounds + " keyboardBounds: " + keyboardBounds);
                        return true;
                    } else {
                        checkForSwipingFromRightKbd = false;
                    }
                }
            }
        }

        Log.d(TAG, "No window found at (" + x + ", " + y + ").");
        checkForSwipingFromRightKbd = false;
        return false;
    }

    // Helper method to check if a window is injectable
    private boolean isInjectableWindow(AccessibilityWindowInfo window) {
        if (window == null) {
//            Log.e(TAG, "isInjectableWindow: Window is null.");
            return false;
        }

        AccessibilityNodeInfo rootNode = window.getRoot();
        if (rootNode == null) {
//            Log.e(TAG, "isInjectableWindow: Root node is null for the window.");
            return false;
        }

        CharSequence packageName = rootNode.getPackageName();
        if (packageName == null) {
//            Log.e(TAG, "isInjectableWindow: Package name is null for the root node.");
            return false;
        }

//        Log.d(TAG, "isInjectableWindow: Found package " + packageName);
        return isMyAppPackage(packageName.toString());
    }

    // Helper method to check if a package belongs to your apps
    private boolean isMyAppPackage(String packageName) {
        String[] myApps = {
//                "com.google.projectgameface",
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
