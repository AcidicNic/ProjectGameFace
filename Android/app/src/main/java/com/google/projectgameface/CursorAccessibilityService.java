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

import android.accessibilityservice.AccessibilityService;
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
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SparseBooleanArray;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
import com.google.projectgameface.utils.Config;
import com.google.projectgameface.utils.CursorUtils;
import com.google.projectgameface.utils.DebuggingStats;
import com.google.projectgameface.utils.WriteToFile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The cursor service of HeadBoard app.
 */
@SuppressLint("UnprotectedReceiver")
// All of the broadcasts can only be sent by system.
public class CursorAccessibilityService extends AccessibilityService implements LifecycleOwner {
    private static final String TAG = "CursorAccessibilityService";

    /**
     * Limit UI update rate to 60 fps
     */
    public static final int UI_UPDATE = 16;

    /**
     * Limit the FaceLandmark detect rate.
     */
    private static final int MIN_PROCESS = 30;

    private static final int IMAGE_ANALYZER_WIDTH = 300;
    private static final int IMAGE_ANALYZER_HEIGHT = 400;
    private ServiceUiManager serviceUiManager;
    public CursorController cursorController;
    private FaceLandmarkerHelper facelandmarkerHelper;
    public WindowManager windowManager;
    private Handler tickFunctionHandler;
    public Point screenSize;
    private HeadBoardService headBoardService;
    private KeyboardManager keyboardManager;
    private GestureStreamController gestureStreamController;

    private ProcessCameraProvider cameraProvider;

    /**
     * Blocking ML operations are performed using this executor
     */
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
    private BroadcastReceiver resetDebuggingStatsReceiver;
    private long startUptime;
    private long startTime;
    private long endUptime;
    private long endTime;
    private Instrumentation instrumentation;
    private HandlerThread handlerThread;
    private Handler handler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SparseBooleanArray keyStates = new SparseBooleanArray();
    private DebuggingStats gboardDebuggingStats = new DebuggingStats("GBoard");
    private DebuggingStats openboardDebuggingStats = new DebuggingStats("OpenBoard");
    private DebuggingStats debuggingStats = gboardDebuggingStats;
    private WriteToFile writeToFile;

    /**
     * This is state of cursor.
     */
    public enum ServiceState {
        ENABLE, DISABLE,
        /**
         * User cannot move cursor but can still perform event from face gesture.
         */
        PAUSE,
        /**
         * For user to see themself in config page. Remove buttons and make camera feed static.
         */
        GLOBAL_STICK
    }

    private ServiceState serviceState = ServiceState.DISABLE;

    /**
     * The setting app may request the float blendshape score.
     */
    private String requestedScoreBlendshapeName = "";

    /**
     * Should we send blendshape score to front-end or not.
     */
    private boolean shouldSendScore = false;
    private String[] debugText = {"", ""};

    private static final int HOVER_DETECTION_SAMPLE_RATE = 16; // ms between samples
    private static final int HOVER_DETECTION_WINDOW = 500; // ms to analyze movement
    private static final double HOVER_MOVEMENT_THRESHOLD = 5.0; // pixels
    private static final double SWIPE_VELOCITY_THRESHOLD = 0.5; // pixels per ms

    private class MovementSample {
        long timestamp;
        int[] position;
        double velocity;

        MovementSample(long timestamp, int[] position, double velocity) {
            this.timestamp = timestamp;
            this.position = position;
            this.velocity = velocity;
        }
    }

    private List<MovementSample> movementSamples = new ArrayList<>();
    private boolean isIntentionalMovement = false;
    private boolean canStartSwipe = false;

    /**
     * Analyzes cursor movement to determine if it's intentional (swipe) or hovering (tap/long tap)
     *
     * @return true if movement appears intentional (swipe), false if hovering (tap/long tap)
     */
    private boolean analyzeMovement() {
        if (movementSamples.size() < 2) {
            return false;
        }

        // Calculate average velocity
        double totalVelocity = 0;
        int validSamples = 0;

        for (int i = 1; i < movementSamples.size(); i++) {
            MovementSample current = movementSamples.get(i);
            MovementSample previous = movementSamples.get(i - 1);

            float dpThreshold = 20f;
            float density = this.getResources().getDisplayMetrics().density;
            float pxThreshold = dpThreshold * density;

            double distance = Math.hypot(
                current.position[0] - previous.position[0],
                current.position[1] - previous.position[1]);

            if (distance > pxThreshold) {
                // distance is greater than 20dp
            }

            double timeDelta = current.timestamp - previous.timestamp;
            if (timeDelta > 0) {
                double velocity = distance / timeDelta;
                if (velocity > 0) {
                    totalVelocity += velocity;
                    validSamples++;
                }
            }
        }

        // Calculate movement consistency
        double avgVelocity = validSamples > 0 ? totalVelocity / validSamples : 0;
        boolean hasConsistentMovement = avgVelocity > SWIPE_VELOCITY_THRESHOLD;

        // Check for movement pattern
        boolean hasDirectionalMovement = false;
        if (movementSamples.size() >= 3) {
            int[] startPos = movementSamples.get(0).position;
            int[] endPos = movementSamples.get(movementSamples.size() - 1).position;
            double totalDistance = Math.sqrt(
                Math.pow(endPos[0] - startPos[0], 2) + Math.pow(endPos[1] - startPos[1], 2));
            hasDirectionalMovement = totalDistance > HOVER_MOVEMENT_THRESHOLD;
        }

        return hasConsistentMovement && hasDirectionalMovement;
    }

    /**
     * Starts monitoring cursor movement for hover detection
     */
    private void startMovementMonitoring() {
        movementSamples.clear();
        isIntentionalMovement = false;

        new Thread(() -> {
            long startTime = System.currentTimeMillis();

            while (swipeEventStarted && !swipeEventEnding) {
                int[] currentPosition = getPathCursorPosition();
                if (currentPosition == null) {
                    try {
                        Thread.sleep(HOVER_DETECTION_SAMPLE_RATE);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                long currentTime = System.currentTimeMillis();

                // Calculate velocity if we have previous samples
                double velocity = 0;
                if (!movementSamples.isEmpty()) {
                    MovementSample lastSample = movementSamples.get(movementSamples.size() - 1);
                    double distance = Math.sqrt(Math.pow(currentPosition[0] - lastSample.position[0], 2) +
                                                Math.pow(currentPosition[1] - lastSample.position[1], 2));
                    velocity = distance / (currentTime - lastSample.timestamp);
                }

                // Add new sample
                movementSamples.add(new MovementSample(currentTime, currentPosition, velocity));

                // Remove old samples outside our analysis window
                while (!movementSamples.isEmpty() && currentTime - movementSamples.get(0).timestamp > HOVER_DETECTION_WINDOW) {
                    movementSamples.remove(0);
                }

                // Analyze movement if we have enough samples
                if (currentTime - startTime >= HOVER_DETECTION_WINDOW) {
                    boolean isAttemptingSwipe = analyzeMovement();
                    isIntentionalMovement = isAttemptingSwipe;
                    if (isAttemptingSwipe && canStartSwipe && !cursorController.isSwiping && !swipeEventEnding) {
                        mainHandler.post(() -> {
                            startSwipe();
                        });
                        break; // Exit monitoring if swipe is detected
                    }
                }

                try {
                    Thread.sleep(HOVER_DETECTION_SAMPLE_RATE);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void defineAndRegisterBroadcastMessageReceivers() {
        loadSharedConfigBasicReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String configName = intent.getStringExtra("configName");
                if (CursorMovementConfig.isBooleanConfig(configName)) {
                    cursorController.cursorMovementConfig.updateOneBooleanConfigFromSharedPreference(
                        configName);
                } else {
                    cursorController.cursorMovementConfig.updateOneConfigFromSharedPreference(configName);
                    // Check if this is the LONG_TAP_THRESHOLD setting being updated
                    if (configName.equals("QUICK_TAP_THRESHOLD")) {
                        sendLongPressDelayToIME(getQuickTapThreshold());
                    }
                }
            }
        };

        loadSharedConfigGestureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String configName = intent.getStringExtra("configName");
                cursorController.blendshapeEventTriggerConfig.updateOneConfigFromSharedPreference(
                    configName);
            }
        };

        changeServiceStateReceiver = new BroadcastReceiver() {
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

        requestServiceStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String state = intent.getStringExtra("state");
                sendBroadcastServiceState(state);
            }
        };

        enableScorePreviewReceiver = new BroadcastReceiver() {
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

        resetDebuggingStatsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                keyboardManager.checkForKeyboardType();
                gboardDebuggingStats.load(context);
                openboardDebuggingStats.load(context);
                debuggingStats.load(context);
            }
        };

        keyboardEventReceiver = new KeyboardEventReceiver();
        IntentFilter kbdFilter = new IntentFilter();
        kbdFilter.addAction(KeyboardEventReceiver.ACTION_SWIPE_START);
        kbdFilter.addAction(KeyboardEventReceiver.ACTION_LONGPRESS_ANIMATION);

        ContextCompat.registerReceiver(this, changeServiceStateReceiver, new IntentFilter("CHANGE_SERVICE_STATE"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, requestServiceStateReceiver, new IntentFilter("REQUEST_SERVICE_STATE"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, loadSharedConfigBasicReceiver, new IntentFilter("LOAD_SHARED_CONFIG_BASIC"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, loadSharedConfigGestureReceiver, new IntentFilter("LOAD_SHARED_CONFIG_GESTURE"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, enableScorePreviewReceiver, new IntentFilter("ENABLE_SCORE_PREVIEW"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, serviceUiManager.flyInWindowReceiver, new IntentFilter("FLY_IN_FLOAT_WINDOW"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, serviceUiManager.flyOutWindowReceiver, new IntentFilter("FLY_OUT_FLOAT_WINDOW"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, resetDebuggingStatsReceiver, new IntentFilter("RESET_DEBUGGING_STATS"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, keyboardEventReceiver, kbdFilter,
            ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * Get current service state.
     */
    public ServiceState getServiceState() {
        return serviceState;
    }

    /**
     * One-time service setup. This will run immediately after user toggle grant Accessibility permission.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();
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
        keyboardManager = new KeyboardManager(this, cursorController, serviceUiManager);
        cursorController.setKeyboardManager(keyboardManager);
        gestureStreamController = new GestureStreamController(this);

        lifecycleRegistry = new LifecycleRegistry(this::getLifecycle);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        defineAndRegisterBroadcastMessageReceivers();

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor();

        backgroundExecutor.execute(() -> {
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

//        if (isPlatformSignedAndCanInjectEvents()) {
//            Log.d(TAG, "Platform signed and can inject events!");
//        }
        writeToFile = new WriteToFile(this);

        gboardDebuggingStats.load(this);
        openboardDebuggingStats.load(this);
        keyboardManager.checkForKeyboardType();

        sendLongPressDelayToIME(getQuickTapThreshold());
    }

    /**
     * Tick function of the service. This function runs every {@value UI_UPDATE}
     *
     * <p>Milliseconds. 1. Update cursor location on screen. 2. Dispatch event. 2. Change status icon.
     */
    private final Runnable tick = new Runnable() {
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
                        serviceUiManager.updateDragLine(
                            cursorController.getPathCursorPositionXY());
                    }

                    // Use for smoothing.
//                        int gapFrames =
//                                round(max(((float) facelandmarkerHelper.gapTimeMs / (float) UI_UPDATE), 1.0f));

                    if (cursorController.isPathCursorEnabled()) {
                        if (cursorController.isEventActive()) {
                            if (!isPathCursorActive) {
                                // Display path cursor if it's still hidden.
                                cursorController.resetPathCursorPosition();
                                serviceUiManager.showPathCursor();
                                isPathCursorActive = true;
                            }
                            serviceUiManager.updatePathCursorImagePositionOnScreen(
                                cursorController.getPathCursorPositionXY());
                        } else if (isPathCursorActive) {
                            // When the path cursor is still visible after an event has ended, hide it.
                            Log.d(TAG, "Hiding path cursor after event ended.");
                            serviceUiManager.hidePathCursor();
                            isPathCursorActive = false;
                        }
                    }

                    if (checkKeyboardBoundsAgain && !cursorController.isEventActive()) {
                        Log.d(TAG, "Re-checking keyboard bounds after event ended.");
                        keyboardManager.checkForKeyboardBounds();
                        checkKeyboardBoundsAgain = false;
                    }

                    cursorController.updateInternalCursorPosition(
                        facelandmarkerHelper.getHeadCoordXY(),
                        facelandmarkerHelper.getNoseCoordXY(),
                        facelandmarkerHelper.getPitchYaw(),
                        new int[]{facelandmarkerHelper.mpInputWidth, facelandmarkerHelper.frameHeight},
                        new int[]{screenSize.x, screenSize.y});

                    // Actually update the UI cursor image.
                    serviceUiManager.updateCursorImagePositionOnScreen(cursorController.getCursorPositionXY());
                    
                    // Update gesture stream if it's active
                    if (gestureStreamController != null && gestureStreamController.isActive()) {
                        updateGestureStream();
                    }
                    
                    dispatchEvent(null, null);

                    if (isPitchYawEnabled() && isNoseTipEnabled()) {
                        serviceUiManager.drawHeadCenter(
                            facelandmarkerHelper.getNoseCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);
                        serviceUiManager.drawSecondDot(
                            facelandmarkerHelper.getHeadCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);
                    } else if (isPitchYawEnabled()) {
                        serviceUiManager.drawHeadCenter(
                            facelandmarkerHelper.getHeadCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);
                    } else {
                        serviceUiManager.drawHeadCenter(
                            facelandmarkerHelper.getNoseCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);
                    }

//                    if (isDebugSwipeEnabled()) {
//                        serviceUiManager.updateDebugTextOverlay(
//                                debugText[0],
//                                debugText[1],
//                                serviceState == ServiceState.PAUSE
//                        );
//                    } else {
//                        serviceUiManager.updateDebugTextOverlay(
//                                "pre: " + facelandmarkerHelper.preprocessTimeMs + "ms",
//                                "med: " + facelandmarkerHelper.mediapipeTimeMs + "ms",
//                                serviceState == ServiceState.PAUSE
//                        );
//                    }
//                    serviceUiManager.updateDebugTextOverlay(
//                        "x: " + pos[0],
//                        "y: " + pos[1],
//                        serviceState == ServiceState.PAUSE);
                    break;

                case PAUSE:
                    // TODO: temporarily pause camera and stop processing.
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

                    serviceUiManager.updateDebugTextOverlay("", "", getServiceState() == ServiceState.PAUSE);
                    break;

                default:
                    break;
            }

            serviceUiManager.updateStatusIcon(serviceState == ServiceState.PAUSE, checkFaceVisibleInFrame());

            tickFunctionHandler.postDelayed(this, CursorAccessibilityService.UI_UPDATE);
        }
    };

    /**
     * Assign function to image analyzer to send it to MediaPipe
     */
    private void setImageAnalyzer() {
        imageAnalyzer.setAnalyzer(
            backgroundExecutor, imageProxy -> {
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

    /**
     * Send out blendshape score for visualize in setting page.
     */
    private void sendBroadcastScore() {
        if (!shouldSendScore) {
            return;
        }

        // Get float score of the requested blendshape.
        if (requestedScoreBlendshapeName != null) {
            try {
                BlendshapeEventTriggerConfig.Blendshape enumValue = BlendshapeEventTriggerConfig.Blendshape.valueOf(
                    requestedScoreBlendshapeName);

                float score = facelandmarkerHelper.getBlendshapes()[enumValue.value];
                Intent intent = new Intent(requestedScoreBlendshapeName);
                intent.putExtra("score", score);
                sendBroadcast(intent);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "No Blendshape named " + requestedScoreBlendshapeName);
            }
        } else {
            try {
                Intent intent = getPitchAndYawIntent();
                sendBroadcast(intent);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "err while retrieving pitch & yaw " + e);
            }
        }
    }

    private Intent getPitchAndYawIntent() {
        Intent intent = new Intent("PITCH_YAW");
        float[] pitchYaw = facelandmarkerHelper.getPitchYaw();
        float[] currHeadXY = facelandmarkerHelper.getNormalizedHeadCoordXY();
        float[] currNoseXY = facelandmarkerHelper.getNormalizedNoseCoordXY();
        intent.putExtra("PITCH", pitchYaw[0]);
        intent.putExtra("YAW", pitchYaw[1]);
        intent.putExtra("CLEARHEADED", currHeadXY);
        intent.putExtra("CONCURRENCY", currNoseXY);
        return intent;
    }

    /**
     * Send out service state to the front-end (MainActivity).
     */
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

    /**
     * Called from startService in MainActivity. After user click the "Start" button.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        serviceUiManager.cameraBoxView.findViewById(R.id.popBtn).setBackground(null);

        return START_STICKY;
    }

    /**
     * Toggle between Pause <-> ENABLE.
     */
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

    /**
     * Enable HeadBoard service.
     */
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
                                cameraProvider,
                                serviceUiManager.innerCameraImageView,
                                imageAnalyzer,
                                this);
                        } catch (ExecutionException | InterruptedException e) {
                            Log.e(TAG, "cameraProvider failed to get provider future: " + e.getMessage());
                        }
                    }, ContextCompat.getMainExecutor(this));

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

    /**
     * Disable HeadBoard service.
     */
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
                    }, ContextCompat.getMainExecutor(this));

                serviceState = ServiceState.DISABLE;
                break;
            default:
                break;
        }
    }

    /**
     * Destroy HeadBoard service and unregister broadcasts.
     */
    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        disableService();
        disableSelf();
        handlerThread.quitSafely();
        cursorController.cleanup();
        
        // Cleanup gesture stream controller
        if (gestureStreamController != null) {
            gestureStreamController.shutdown();
        }

        // Unregister when the service is destroyed
        try { unregisterReceiver(changeServiceStateReceiver); } catch (Exception e) {}
        try { unregisterReceiver(requestServiceStateReceiver); } catch (Exception e) {}
        try { unregisterReceiver(loadSharedConfigBasicReceiver); } catch (Exception e) {}
        try { unregisterReceiver(loadSharedConfigGestureReceiver); } catch (Exception e) {}
        try { unregisterReceiver(enableScorePreviewReceiver); } catch (Exception e) {}
        try { unregisterReceiver(serviceUiManager.flyInWindowReceiver); } catch (Exception e) {}
        try { unregisterReceiver(serviceUiManager.flyOutWindowReceiver); } catch (Exception e) {}
        try { unregisterReceiver(profileChangeReceiver); } catch (Exception e) {}
        try { unregisterReceiver(resetDebuggingStatsReceiver); } catch (Exception e) {}
        try { unregisterReceiver(keyboardEventReceiver); } catch (Exception e) {}

        super.onDestroy();
    }

    /**
     * Function for perform {@link BlendshapeEventTriggerConfig.EventType} actions.
     */
    private void dispatchEvent(BlendshapeEventTriggerConfig.EventDetails inputEvent, KeyEvent keyEvent) {
        // Check what inputEvent to dispatch.
        if (inputEvent == null && keyEvent == null) {
            inputEvent = cursorController.createCursorEvent(facelandmarkerHelper.getBlendshapes());
        }

        switch (inputEvent.eventType) {
            case NONE:
                return;
            case DRAG_TOGGLE:
            case TOGGLE_TOUCH:
            case CONTINUOUS_TOUCH:
            case END_TOUCH:
            case BEGIN_TOUCH:
            case CURSOR_TAP:
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
                    inputEvent);
                break;

            case PAUSE:
                // In PAUSE state user can only perform togglePause
                // with face gesture.
                if (inputEvent.eventType == BlendshapeEventTriggerConfig.EventType.CURSOR_PAUSE) {
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

    /**
     * Check if face is visible in frame.
     */
    private Boolean checkFaceVisibleInFrame() {
        if (facelandmarkerHelper == null) return false;

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
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (cursorController.isEventActive()) {
                checkKeyboardBoundsAgain = true;
                Log.d(TAG, "onAccessibilityEvent: Failed to get keyboard bounds because event actions is active. Will try again later.");
            } else {
                checkKeyboardBoundsAgain = false;
                keyboardManager.checkForKeyboardBounds();
            }
        }
    }
    private boolean checkKeyboardBoundsAgain = false;

    private StringBuilder typedText = new StringBuilder();
    private boolean checkForNewWord = false;
    private long checkForNewWordTimeStamp = 0;
    private String newWord;

    /**
     * Process the typed text and check for new words.
     * This method is called when an accessibility event occurs.
     *
     * @param newText The new text that was typed.
     */
    private void processTypedText(CharSequence newText) {
        typedText.append(newText);
        String[] words = typedText.toString().split("\\s+");
        Log.d(TAG, "processTypedText(): [words==" + words + "] [words.length==" + words.length + "]");
        if (words.length > 0) {
            Long now = System.currentTimeMillis();
            newWord = words[words.length - 1];
            Log.d(
                TAG,
                "processTypedText(): [newWord==" + newWord + "] [checkForNewWord==" + checkForNewWord +
                "] [(checkForNewWordTimeStamp + 1000 >= now)==" + (checkForNewWordTimeStamp + 1000 >= now) + "]");
            if (checkForNewWord && (checkForNewWordTimeStamp + 1000 >= now)) {
                if (newWord != null) {
                    keyboardManager.getCurrentDebuggingStats().addWordSwiped(newWord, startTime, endTime);
                    keyboardManager.getCurrentDebuggingStats().save(this);
                    checkForNewWord = false;
                }
            } else {
                Log.d(TAG, "processTypedText(): checkForNewWord is false, not adding word to stats.");
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override @NonNull
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service connected");
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

    /**
     * Handles a key event by dispatching it's corresponding action if applicable.
     *
     * @param event The key event.
     * @return True if the key event is handled.
     */
    private boolean handleKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.PAUSE) {
            return false;
        }
        SharedPreferences preferences = getSharedPreferences(
            ProfileManager.getCurrentProfile(this),
            Context.MODE_PRIVATE);
        String eventName = "NONE";
        BlendshapeEventTriggerConfig.Blendshape blendshape;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_1) {
            blendshape = BlendshapeEventTriggerConfig.Blendshape.SWITCH_ONE;
            eventName = preferences.getString(blendshape.toString() + "_event", BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
            if (eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
                return false;
            }
        } else if (keyCode == KeyEvent.KEYCODE_2) {
            blendshape = BlendshapeEventTriggerConfig.Blendshape.SWITCH_TWO;
            eventName = preferences.getString(blendshape + "_event", BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
            if (eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
                return false;
            }
        } else if (keyCode == KeyEvent.KEYCODE_3) {
            blendshape = BlendshapeEventTriggerConfig.Blendshape.SWITCH_THREE;
            eventName = preferences.getString(blendshape.toString() + "_event", BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
            if (eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
                return false;
            }
        } else {
//            wrong key pressed
            return false;
        }
        BlendshapeEventTriggerConfig.EventType eventType = BlendshapeEventTriggerConfig.EventType.valueOf(eventName);
        Log.d(TAG, "handleKeyEvent: name " + eventName + "; + type " + eventType);
        dispatchEvent(new BlendshapeEventTriggerConfig.EventDetails(eventType, blendshape, event.getAction() == KeyEvent.ACTION_DOWN), event);
        return true;
    }

    /**
     * Dispatches a tap gesture at the specified cursor position.
     *
     * @param cursorPosition The cursor position in screen coordinates.
     * @param duration       The duration of the tap gesture in milliseconds.
     */
    public void dispatchTapGesture(int[] cursorPosition, Integer duration) {

        if (duration == null) {
            duration = 200;
        }
        dispatchGesture(
            CursorUtils.createClick(
                cursorPosition[0], cursorPosition[1],
                /* startTime= */ 0,
                /* duration= */ duration),
            /* callback= */ null,
            /* handler= */ null);

        serviceUiManager.drawTouchDot(cursorPosition);
    }

    /**
     * Dispatches a drag or hold action based on the current cursor position.
     */
    public void dispatchDragOrHold() {
        Log.d("dispatchDragOrHold", "dispatchDragOrHold");
        int[] cursorPosition = cursorController.getPathCursorPositionXY();

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
                (Math.abs(xOffset) < cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HOLD_RADIUS)) &&
                (Math.abs(yOffset) <  cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HOLD_RADIUS));

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
                        /* duration= */
                        250
                    ),
                    /* callback= */ null,
                    /* handler= */ null
                );
            }
        }
    }

    /**
     * Handles the toggle touch action.
     *
     * @return True if the touch is toggled.
     */
    public void toggleTouch() {
        Log.d(TAG, "toggleTouch()");
        int[] cursorPosition = getCursorPosition();

        if (cursorController.isSwiping && cursorController.swipeToggleActive) {
            Log.d(TAG, "STOP SWIPE TOGGLE KeyEvent.ACTION_DOWN");
            cursorController.swipeToggleActive = false;
            stopRealtimeSwipe();
        } else if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
            Log.d(TAG, "START SWIPE TOGGLE KeyEvent.ACTION_DOWN");
            cursorController.swipeToggleActive = true;
            startRealtimeSwipe(cursorPosition);
        } else {
            Log.d(TAG, "DRAG TOGGLE KeyEvent.ACTION_DOWN");
            dispatchDragOrHold();
        }
    }

    /**
     * Delete the last word in the focused EditText.
     */
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
            if (cursorPosition <= 0) return;
            DeleteResult modifiedTextResult = removeLastWord(text, cursorPosition);

            Bundle setModifiedTextArgs = new Bundle();
            setModifiedTextArgs.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                modifiedTextResult.text);

            Bundle setCursorPositionArgs = new Bundle();
            setCursorPositionArgs.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                modifiedTextResult.newCursor);
            setCursorPositionArgs.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                modifiedTextResult.newCursor);

            focusedNode.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.getId(),
                setModifiedTextArgs);
            focusedNode.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION.getId(),
                setCursorPositionArgs);
        }
    }

    /**
     * Class to hold the result of the delete operation.
     */
    private static class DeleteResult {
        String text;
        int newCursor;
    }

    /**
     * Find the focused EditText node in the accessibility tree.
     *
     * @param rootNode The root node of the accessibility tree.
     * @return The focused EditText node, or null if not found.
     */
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

    /**
     * Remove the last word from the given text and return the modified text and new cursor position.
     *
     * @param text   The original text.
     * @param cursor The current cursor position.
     * @return A DeleteResult object containing the modified text and new cursor position.
     */
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

    private int[] dragToggleStartPosition = new int[2];

    /**
     * Handle continuous touch action.
     */
    public void continuousTouch(boolean isStarting) {
        Log.d(TAG, "continuousTouch() SWIPE isStarting: " + isStarting);

        int[] cursorPosition;
        cursorPosition = getCursorPosition();

        if (isStarting && !cursorController.continuousTouchActive) {
            cursorController.continuousTouchActive = true;
            Log.d(TAG, "continuousTouch() SWIPE KeyEvent.ACTION_DOWN");

            if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                // Use GestureStreamController for keyboard gestures
                startGestureStream(cursorPosition);
            } else {
                // Use drag toggle for non-keyboard areas
                dragToggleStartTime = SystemClock.uptimeMillis();
                dragToggleCancelled = false;
                dragToggleStartPosition = cursorPosition;
                cursorController.dragToggleActive = true;
                dragToggleHandler.postDelayed(dragToggleOrTapOnCancelRunnable, getQuickTapThreshold());
            }
        } else if (cursorController.continuousTouchActive) {
            cursorController.continuousTouchActive = false;

            Log.d(TAG, "continuousTouch() GESTURE STREAM KeyEvent.ACTION_UP");
            if (cursorController.isSwiping) {
                // End gesture stream if it's active
                endGestureStream();
            } else {
                // Handle drag toggle logic
                long elapsedTime = SystemClock.uptimeMillis() - dragToggleStartTime;
                dragToggleHandler.removeCallbacks(dragToggleOrTapOnCancelRunnable);
                if (elapsedTime < getQuickTapThreshold()) {
                    dragToggleCancelled = true;
                    // Perform quick tap instead of enabling drag toggle
                    // Use shorter duration for non-keyboard areas (suggestion strip) 
                    // to avoid triggering long press handler
                    dispatchTapGesture(dragToggleStartPosition, Config.QUICK_TAP_DURATION);
                } else {
                    cursorController.dragToggleActive = false;
                    if (cursorController.isDragging) {
                        dispatchDragOrHold();
                    }
                }
            }
        }
    }

    /**
     * Handle start touch action.
     */
    public void startTouch() {
        int[] cursorPosition = new int[2];
        cursorPosition = getPathCursorPosition();
        if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
            Log.d(TAG, "START SWIPE");
            cursorController.swipeToggleActive = true;
            startRealtimeSwipe(cursorPosition);
        } else if (!cursorController.isDragging) {
            Log.d(TAG, "START DRAG");
            dispatchDragOrHold();
        }
    }

    /**
     * Handle stop touch action.
     */
    public void stopTouch() {
        int[] cursorPosition = new int[2];
        cursorPosition = getPathCursorPosition();
        if (cursorController.isSwiping) {
            Log.d(TAG, "STOP SWIPE");
            stopRealtimeSwipe();
        } else if (cursorController.isDragging) {
            Log.d(TAG, "STOP DRAG");
            dispatchDragOrHold();
        }
    }

    // Fields for smart touch state
    private long smartTouchStartTime;
    private int[] smartTouchStartPosition;
    private boolean smartTouchCancelled = false;
    private final Handler smartTouchHandler = new Handler(Looper.getMainLooper());
    private int quickTapThreshold = CursorMovementConfig.InitialRawValue.QUICK_TAP_THRESHOLD;
    private int longTapThreshold = CursorMovementConfig.InitialRawValue.LONG_TAP_THRESHOLD;

    /**
     * Runnable for quick touch action.
     * This will be executed after a delay to allow for quick touch to be cancelled.
     */
    private final Runnable quickTouchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!smartTouchCancelled && cursorController.smartTouchActive) {
                // Start animating to red when we hit quick delay
                serviceUiManager.cursorAnimateToColor("RED", longTapThreshold - quickTapThreshold);
            }
        }
    };

    /**
     * Runnable for long touch action.
     * This will be executed after a delay to allow for long touch to be cancelled.
     */
    private final Runnable longTouchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!smartTouchCancelled && cursorController.smartTouchActive) {
                // Execute long tap
                dispatchTapGesture(smartTouchStartPosition, 650);
                // Reset cursor to white
                serviceUiManager.cursorSetColor("WHITE");
                cursorController.smartTouchActive = false;
            }
        }
    };

    public boolean combinedTap(KeyEvent event) {
        Log.d(TAG, "smartTouch() KeyEvent: " + event);

        int eventAction = -1;
        if (event != null) {
            eventAction = event.getAction();
        }

        if (eventAction == KeyEvent.ACTION_DOWN && !cursorController.smartTouchActive) {

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

        } else if (eventAction == KeyEvent.ACTION_UP && cursorController.smartTouchActive) {

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
                dispatchTapGesture(smartTouchStartPosition, getSystemLongpressDelay());
            } else if (elapsedTime <= quickTapThreshold) {
                // Quick touch
                dispatchTapGesture(smartTouchStartPosition, 250);
            }

            // Reset cursor to white after any action
            serviceUiManager.cursorSetColor("WHITE");
        }

        return true;
    }

    private final Handler dragToggleHandler = new Handler(Looper.getMainLooper());
    private boolean dragToggleCancelled = false;
    private long dragToggleStartTime;

    /**
     * Runnable to handle delayed drag toggle start.
     * This will be executed after a delay to allow for drag toggle to be cancelled.
     * If the drag toggle is cancelled, it will dispatch a CURSOR_TOUCH event instead.
     */
    private final Runnable dragToggleOrTapOnCancelRunnable = new Runnable() {
        @Override
        public void run() {
            if (!dragToggleCancelled) {
                dispatchDragOrHold();
            } else {
                Log.d(TAG, "Drag toggle cancelled");
                int[] cursorPosition = dragToggleStartPosition;
                // Use shorter duration for non-keyboard areas (suggestion strip)
                // to avoid triggering long press handler
                dispatchTapGesture(cursorPosition, Config.QUICK_TAP_DURATION);
            }
        }
    };

    int[] lastValidCoords = new int[2];

    /**
     * Start realtime swipe event.
     */
    private void startRealtimeSwipe(int[] startCoords) {
        cursorController.isSwiping = true;
        cursorController.isRealtimeSwipe = true;
        startUptime = SystemClock.uptimeMillis();
        startTime = System.currentTimeMillis();
        int[] initialPosition = getCursorPosition();
        if (startCoords != null && startCoords.length == 2) {
            initialPosition[0] = startCoords[0];
            initialPosition[1] = startCoords[1];
        }

        if (cursorController.checkForSwipingFromRightKbd) {
            lastValidCoords = initialPosition;
            cursorController.startedSwipeFromRightKbd = true;

            // Correct the cursor position to start swipe
//            initialPosition[0] = initialPosition[0] - 1;
            return;
        } else {
            cursorController.startedSwipeFromRightKbd = false;
        }

        new Thread(() -> {
            if (keyboardManager.canInjectEvent(initialPosition[0], initialPosition[1])) {
                lastValidCoords = initialPosition;
                MotionEvent event = MotionEvent.obtain(
                    startUptime,
                    startUptime,
                    MotionEvent.ACTION_DOWN,
                    initialPosition[0],
                    initialPosition[1],
                    0);
                injectMotionEvent(event);
                debugText[0] = "Swiping";
                debugText[1] = "X, Y: (" + initialPosition[0] + ", " + initialPosition[1] + ")";
            } else {
                Log.d(TAG, "Coords do not belong to either sender app or IME. TODO: Implement for 3rd party apps.");
            }

            long lastCheckTime = System.currentTimeMillis();
            while (cursorController.isSwiping) {
                int[] cursorPosition = getPathCursorPosition();
                long now = SystemClock.uptimeMillis();
                try {
                    if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                        lastValidCoords = cursorPosition;
                        MotionEvent event = MotionEvent.obtain(
                            startUptime,
                            now,
                            MotionEvent.ACTION_MOVE,
                            cursorPosition[0],
                            cursorPosition[1],
                            0);
                        injectMotionEvent(event);
                        debugText[0] = "Swiping";
                        debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";
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

    /**
     * Stop realtime swipe event by sending an ACTION_UP event.
     */
    private void stopRealtimeSwipe() {
        endUptime = SystemClock.uptimeMillis();
        endTime = System.currentTimeMillis();
        int[] cursorPosition = getPathCursorPosition();
        serviceUiManager.clearPreviewBitmap();
        int keyWidth = keyboardManager.getKeyboardBounds().width() / 10;
        if (cursorController.startedSwipeFromRightKbd && (cursorPosition[0] < screenSize.x) &&
            (cursorPosition[0] >= screenSize.x - (keyWidth * 2)) /*(cursorPosition[0] (screenSize.x / 2))*/) {
            handleSwipeFromRightKbd();
            cursorController.startedSwipeFromRightKbd = false;
            cursorController.isSwiping = false;
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
                else if (!keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                    cursorCoords = lastValidCoords;
                }

                MotionEvent event = MotionEvent.obtain(
                    startUptime,
                    endUptime,
                    action,
                    cursorCoords[0],
                    cursorCoords[1],
                    0);
                injectMotionEvent(event);

                debugText[0] = "Swiping";
                debugText[1] = "X, Y: (" + cursorCoords[0] + ", " + cursorCoords[1] + ")";
                Log.d(TAG, "MotionEvent.ACTION_UP @ (" + cursorCoords[0] + ", " + cursorCoords[1] + ")");
            } catch (Exception e) {
                writeToFile.logError(TAG, "ERROR WHILE ENDING SWIPE!!!: sendPointerSync cannot be called from the main thread." + e);
                Log.e(TAG, "sendPointerSync cannot be called from the main thread.", e);
            }
            cursorController.isSwiping = false;
            cursorController.isRealtimeSwipe = false;
//            displaySwipeInfo();
        }).start();
    }

    /**
     * Handle swipe from right keyboard event.
     */
    public void handleSwipeFromRightKbd() {
        SharedPreferences preferences = getSharedPreferences(
            ProfileManager.getCurrentProfile(this),
            Context.MODE_PRIVATE);
        String eventName = preferences.getString(
            BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD.toString() + "_event",
            BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
        if (!eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
            dispatchEvent(
                new BlendshapeEventTriggerConfig.EventDetails(
                    BlendshapeEventTriggerConfig.EventType.valueOf(eventName),
                    BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD,
                    false),
                null);
        }
    }

    /**
     * Start gesture stream for continuous touch using GestureStreamController.
     * This method uses the accessibility service's dispatchGesture instead of motion events.
     *
     * @param startCoords The starting coordinates for the gesture
     */
    private void startGestureStream(int[] startCoords) {
        Log.d(TAG, "startGestureStream() - Starting gesture stream at (" + startCoords[0] + ", " + startCoords[1] + ")");
        
        cursorController.isSwiping = true;
        cursorController.isRealtimeSwipe = true;
        startUptime = SystemClock.uptimeMillis();
        startTime = System.currentTimeMillis();
        
        // Start the gesture stream
        boolean started = gestureStreamController.start(startCoords[0], startCoords[1]);
        if (started) {
            Log.d(TAG, "Gesture stream started successfully");
            debugText[0] = "Gesture Stream";
            debugText[1] = "X, Y: (" + startCoords[0] + ", " + startCoords[1] + ")";
        } else {
            Log.w(TAG, "Failed to start gesture stream");
            cursorController.isSwiping = false;
            cursorController.isRealtimeSwipe = false;
        }
    }

    /**
     * Update gesture stream with current cursor position.
     * This should be called continuously while the gesture is active.
     */
    private void updateGestureStream() {
        if (gestureStreamController.isActive()) {
            int[] cursorPosition = getPathCursorPosition();
            if (cursorPosition != null) {
                gestureStreamController.update(cursorPosition[0], cursorPosition[1]);
                debugText[0] = "Gesture Stream";
                debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";
            }
        }
    }

    /**
     * End gesture stream for continuous touch using GestureStreamController.
     */
    private void endGestureStream() {
        Log.d(TAG, "endGestureStream() - Ending gesture stream");
        
        endUptime = SystemClock.uptimeMillis();
        endTime = System.currentTimeMillis();
        
        if (gestureStreamController.isActive()) {
            gestureStreamController.end();
            Log.d(TAG, "Gesture stream ended successfully");
        }
        
        cursorController.isSwiping = false;
        cursorController.isRealtimeSwipe = false;
        serviceUiManager.clearPreviewBitmap();
    }

    /**
     * Cancel gesture stream for continuous touch using GestureStreamController.
     */
    private void cancelGestureStream() {
        Log.d(TAG, "cancelGestureStream() - Cancelling gesture stream");
        
        if (gestureStreamController.isActive()) {
            gestureStreamController.cancel();
            Log.d(TAG, "Gesture stream cancelled");
        }
        
        cursorController.isSwiping = false;
        cursorController.isRealtimeSwipe = false;
        serviceUiManager.clearPreviewBitmap();
    }

    /**
     * Inject a motion event into the system.
     *
     * @param event The motion event to inject.
     */
    private void injectMotionEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) { // Android 12 (API 31)
//            Log.d(TAG, "[666] Sending MotionEvent to IME");
            sendMotionEventToIME((int) event.getX(), (int) event.getY(), event.getAction());
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
     *
     * @param x      The x coordinate of the touch event.
     * @param y      The y coordinate of the touch event.
     * @param action The action of the touch event (e.g., MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).
     */
    private void sendMotionEventToIME(int x, int y, int action) {
        keyboardManager.sendMotionEventToIME(x, y, action);
    }

    /**
     * Send key event to OpenBoard IME to simulate virtual keyboard key presses
     *
     * @param keyCode     The key code to send.
     * @param isDown      Whether the key is pressed down or released.
     * @param isLongPress Whether the key is a long press.
     */
    private void sendKeyEventToIME(int keyCode, boolean isDown, boolean isLongPress) {
        keyboardManager.sendKeyEventToIME(keyCode, isDown, isLongPress);
    }

    /**
     * Send gesture trail color to OpenBoard IME.
     *
     * @param color The color to send. ("green", "red", "orange")
     */
    private void sendGestureTrailColorToIME(String color) {
        keyboardManager.sendGestureTrailColorToIME(color);
    }

    /**
     * Send long press delay to OpenBoard IME.
     *
     * @param delay The long press delay in milliseconds.
     */
    private void sendLongPressDelayToIME(int delay) {
        keyboardManager.sendLongPressDelayToIME(delay);
    }

    /**
     * [OLD MOTION EVENT INJECTION METHOD, requires platform signed app and INJECT_EVENTS perm.]
     * Injects the given input event into the system.
     *
     * @param event The input event to inject.
     */
    private void injectInputEvent(MotionEvent event) {
        try {
            InputManager inputManager = (InputManager) this.getSystemService(Context.INPUT_SERVICE);
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method injectInputEventMethod = inputManagerClass.getMethod(
                "injectInputEvent",
                InputEvent.class,
                int.class);
            injectInputEventMethod.setAccessible(true);

            // INJECT_INPUT_EVENT_MODE_ASYNC is 0
            injectInputEventMethod.invoke(inputManager, event, 0);
        } catch (Exception e) {
            Log.e(TAG, "Error while injecting input event: " + e);
            e.printStackTrace();
        }
    }

    /* Get settings from cursorMovementConfig */

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

    public long getDragToggleDelay() {
        return (long) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.DRAG_TOGGLE_DURATION);
    }

    public int getQuickTapThreshold() {
        return (int) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.QUICK_TAP_THRESHOLD);
    }

    public int getLongTapThreshold() {
        return (int) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.LONG_TAP_THRESHOLD);
    }

    public int getUiFeedbackDelay() {
        return (int) ((cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.UI_FEEDBACK_DELAY) / 10) *
            cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.QUICK_TAP_THRESHOLD));
    }

    /**
     * Check if the given coordinates are on the suggestion strip.
     * The suggestion strip is at the top of the keyboard window, typically 40-44dp high.
     * @param x X coordinate
     * @param y Y coordinate
     * @return true if the coordinates are on the suggestion strip, false otherwise
     */
    private boolean isOnSuggestionStrip(int x, int y) {
        if (!keyboardManager.isKeyboardOpen()) {
            return false;
        }
        Rect keyboardBounds = keyboardManager.getKeyboardBounds();
        if (keyboardBounds.isEmpty()) {
            return false;
        }
        // Check if point is within keyboard bounds
        if (!keyboardBounds.contains(x, y)) {
            return false;
        }
        // Suggestion strip is at the top of the keyboard window, typically 40-44dp (~120-132px at mdpi)
        // Use a conservative estimate of 150px to account for different screen densities
        int suggestionStripHeight = 150; // pixels
        int distanceFromTop = y - keyboardBounds.top;
        return distanceFromTop <= suggestionStripHeight;
    }

    /**
     * Get the height of the navigation bar.
     *
     * @param context The context of the application.
     * @return The height of the navigation bar in pixels.
     */
    public int getNavigationBarHeight(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        }
        return 0; // Return 0 if no navigation bar is present
    }

    /**
     * Get the current cursor position.
     *
     * @return An array containing the x and y coordinates of the cursor.
     */
    private int[] getCursorPosition() {
        return cursorController.getCursorPositionXY();
    }

    /**
     * Get the current cursor position.
     *
     * @return An array containing the x and y coordinates of the cursor.
     */
    private int[] getPathCursorPosition() {
        return cursorController.getPathCursorPositionXY();
    }

    /**
     * Check if the app is platform signed and has INJECT_EVENTS permission.
     */
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

    /**
     * Check if the app is platform signed.
     */
    private boolean isPlatformSigned() {
        try {
            PackageManager pm = getPackageManager();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28 and above
                PackageInfo packageInfo = pm.getPackageInfo(
                    getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo platformPackageInfo = pm.getPackageInfo(
                    "android",
                    PackageManager.GET_SIGNING_CERTIFICATES);

                if (packageInfo.signingInfo != null && platformPackageInfo.signingInfo != null) {
                    Signature[] appSignatures = packageInfo.signingInfo.getApkContentsSigners();
                    Signature[] platformSignatures = platformPackageInfo.signingInfo.getApkContentsSigners();

                    for (Signature appSignature: appSignatures) {
                        for (Signature platformSignature: platformSignatures) {
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

                for (Signature appSignature: appSignatures) {
                    for (Signature platformSignature: platformSignatures) {
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

    /**
     * Set image property to match the MediaPipe model. - Using RGBA 8888. - Lowe the resolution.
     */
    private final ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setResolutionSelector(
            new ResolutionSelector.Builder().setResolutionStrategy(
                new ResolutionStrategy(new Size(IMAGE_ANALYZER_WIDTH, IMAGE_ANALYZER_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            ).build())
        .build();

    /* ------------------------------ START OF TAP ACTION HANDLING ------------------------------ */
    private int uiFeedbackDelay = 500;
    private int[] tapStartPosition;
    private boolean isInHoverZone = true;
    private boolean wasInHoverZone = true; // Track previous hover state
    private long hoverZoneExitTime = 0; // Track when cursor left hover zone
    private boolean tapEventStarted = false;
    private boolean tapEventEnding = false;
    private long tapStartTime;
    private boolean tapInsideKbd = false;

    /**
     * Handles tap events when the switch is pressed or released.
     *
     * @param isStarting true if the switch is being pressed down, false if released
     */
    public void handleTapEvent(boolean isStarting) {
        // if (!tapEventStarted && !tapEventEnding) {
        if (isStarting && !tapEventStarted && !tapEventEnding) {
            Log.d(TAG, "handleTapEvent Switch pressed");
            cursorController.isCursorTap = true;
            int[] cursorPosition = getCursorPosition();
            startTapSequence(cursorPosition);
        } else if (!isStarting && tapEventStarted && !tapEventEnding) {
            Log.d(TAG, "handleTapEvent Switch released");
            endTapSequence();
        } else {
            Log.d(TAG, "handleTapEvent: Tap event already handled and ending");
        }
    }

    private Runnable showAltPopupRunnable = () -> {
        if (tapInsideKbd && tapEventStarted && !tapEventEnding) {
            Log.d(TAG, "startTapSequence() - Show long press key popup");
            keyboardManager.showAltKeyPopupIME(tapStartPosition[0], tapStartPosition[1]);
        }
    };
    private Runnable animateCursorTapRunnable = () -> {
        if (tapEventEnding) return;
        if (tapInsideKbd) {
            mainHandler.postDelayed(showAltPopupRunnable, getQuickTapThreshold() - uiFeedbackDelay);
        }
        serviceUiManager.cursorSetColor("YELLOW");
        serviceUiManager.cursorAnimateToColor("BLUE", getQuickTapThreshold(), uiFeedbackDelay);
        if (!isInHoverZone) serviceUiManager.cursorHideAnimation("RED");
    };

    /**
     * Starts the tap sequence when the switch is pressed down.
     *
     * @param initialPosition The initial cursor position when switch was pressed
     */
    private void startTapSequence(int[] initialPosition) {
        if (initialPosition == null || initialPosition.length < 2 || initialPosition[0] < 0 || initialPosition[1] < 0) {
            Log.e(TAG, "startTapSequence() - initialPosition is invalid");
            return;
        }
        // Store initial position and start time
        tapStartPosition = new int[]{initialPosition[0], initialPosition[1]};
        tapStartTime = System.currentTimeMillis();
        isInHoverZone = true;
        tapEventStarted = true;
        tapEventEnding = false;
        cursorController.isCursorTap = true;
        uiFeedbackDelay = getUiFeedbackDelay();

        tapInsideKbd = keyboardManager.canInjectEvent(tapStartPosition[0], tapStartPosition[1]);
        Log.d(TAG, "startTapSequence() - tapStartPosition: (" + tapStartPosition[0] +
            ", " + tapStartPosition[1] + "), tapInsideKbd: " + tapInsideKbd);

        if (tapInsideKbd && !tapEventEnding) {
            Log.d(TAG, "startTapSequence() - Show key popup & send long press delay to IME");
            keyboardManager.showKeyPopupIME(tapStartPosition[0], tapStartPosition[1], true);
            keyboardManager.sendLongPressDelayToIME(100);

            /** TODO: KILL YOURSELF
             * what the fuck bro what the actual fuck i hate my life so fuckign much bro wtf ugh
             * j figure out how to get the fucking webcam to work for the emulator's front cam pls
             * i fucking hate it here bro
             **/
        }

        mainHandler.postDelayed(animateCursorTapRunnable, uiFeedbackDelay);

        // Start monitoring cursor position for hover zone
        startHoverZoneMonitoring();
    }

    /**
     * Starts monitoring the cursor position to check if it stays within the hover zone.
     */
    private void startHoverZoneMonitoring() {
        new Thread(() -> {
            while (tapEventStarted && !tapEventEnding) {
                int[] currentPosition = getPathCursorPosition();
                int[] startPos = tapStartPosition;

                // Skip if either position is null
                if (currentPosition == null || startPos == null) {
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                double distance = Math.sqrt(Math.pow(currentPosition[0] - startPos[0], 2) +
                                            Math.pow(currentPosition[1] - startPos[1], 2));

                boolean newHoverState = distance <= Config.HOVER_ZONE_RADIUS;

                // Only update UI if hover state changed
                if (newHoverState != isInHoverZone) {
                    isInHoverZone = newHoverState;
                    Log.d(TAG, "HOVER ZONE " + (isInHoverZone ? "ENTERED" : "EXITED") + "; Cursor Distance: "
                        + distance + "px; Hover Zone Radius: " + Config.HOVER_ZONE_RADIUS + "px");

                    if (!isInHoverZone) {
                        // Cursor left hover zone
                        hoverZoneExitTime = System.currentTimeMillis();
                        mainHandler.post(() -> {
                            // Hide current animations and show red
                            serviceUiManager.cursorHideAnimation("RED");
                        });
                    } else {
                        // Cursor returned to hover zone
                        mainHandler.post(() -> {
                            // Show animations again
                            serviceUiManager.cursorShowAnimation();
                        });
                    }
                }

                try {
                    Thread.sleep(16); // ~60fps
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    /**
     * Ends the tap sequence when the switch is released.
     */
    private void endTapSequence() {
        // Cancel any pending runnables
        tapEventEnding = true;
        mainHandler.removeCallbacks(showAltPopupRunnable);
        mainHandler.removeCallbacks(animateCursorTapRunnable);

        // Cancel any ongoing animations
        serviceUiManager.cursorCancelAnimation();

        keyboardManager.hideAltKeyPopupIME(tapStartPosition[0], tapStartPosition[1]);

        // If cursor is not in hover zone, cancel the tap
        if (!isInHoverZone) {
            resetTapSequence();
            return;
        }

        int duration = 0;

        // Get current state
        long totalTapDuration = System.currentTimeMillis() - tapStartTime;
        if (totalTapDuration < getQuickTapThreshold()) {
            if (tapInsideKbd) {
                Log.d(TAG, "endTapSequence() isTap, outputting quick tap");
                duration = 100;
            } else {
                // Use shorter duration for non-keyboard areas (suggestion strip)
                // to avoid triggering long press handler
                duration = Config.QUICK_TAP_DURATION;
            }
        } else {
            if (tapInsideKbd) {
                Log.d(TAG, "endTapSequence() isLongTap, outputting long tap");
                duration = 105;
            } else {
                duration = getSystemLongpressDelay();
            }
        }

        dispatchTapGesture(tapStartPosition, duration);

        mainHandler.postDelayed(
            () -> {
                // Reset after tap
                resetTapSequence();
            }, duration);
    }

    /**
     * Resets all tap sequence state variables.
     */
    private void resetTapSequence() {
        if (tapInsideKbd) {
            Log.d(TAG, "resetTapSequence() sending long press delay to IME");
            sendLongPressDelayToIME(getQuickTapThreshold());
        }
        serviceUiManager.cursorShowAnimation();
        serviceUiManager.cursorSetColor("WHITE");
        cursorController.isCursorTap = false;
        tapInsideKbd = false;
        tapEventStarted = false;
        tapEventEnding = false;
        tapStartPosition = null;
        isInHoverZone = true;
        wasInHoverZone = true;
        hoverZoneExitTime = 0;
//        animateCursorTapRunnable = null;
//        showAltPopupRunnable = null;
    }
    /* ------------------------------- END OF TAP ACTION HANDLING ------------------------------- */


    // Gesture debounce + coalescing helpers to reduce UI-thread stalls
    private final Object gestureLock = new Object();
    private volatile long lastGestureTimestamp = 0;
    private static final long GESTURE_DEBOUNCE_MS = 50; // ignore duplicate events within 50ms
    private Runnable gestureEndRunnable = null;


    public void gestureDescription(boolean isStartingEvent) {
        if (isStartingEvent) {
            // Coalesce: ignore starts if already started or ending
            if (swipeEventStarted || swipeEventEnding) return;

            // Mark swipe/path state similar to swipe flow
            swipeStartPosition = cursorController.getRollingAverage();
            cursorController.setPathCursorPosition(swipeStartPosition);
            swipeStartTime = System.currentTimeMillis();
            isInHoverZone = true;
            swipeEventStarted = true;
            swipeEventEnding = false;
            cursorController.isCursorTouch = true;
            uiFeedbackDelay = getUiFeedbackDelay();
            canStartSwipe = false;

            // Show path cursor and provide quick visual feedback
            mainHandler.post(() -> {
                try {
                    serviceUiManager.showPathCursor();
                    isPathCursorActive = true;
                    serviceUiManager.pathCursorSetColor("YELLOW");
                    // Delegate low-level streaming to the controller (runs on its own thread)
                    startGestureStream(swipeStartPosition);
                } catch (Exception e) {
                    Log.w(TAG, "gestureDescription start error: " + e);
                }
            });
        } else {
            // end event: coalesce multiple rapid end events into a single finalizer
            synchronized (gestureLock) {
                // If there's already a pending end runnable, cancel and reschedule slightly later to coalesce bursts
                if (gestureEndRunnable != null) {
                    mainHandler.removeCallbacks(gestureEndRunnable);
                }

                // Briefly mark that ending is in progress to ignore duplicate end triggers
                swipeEventEnding = true;

                gestureEndRunnable = () -> {
                    // offload heavier prep to background executor, but ensure UI finalization runs on main
                    backgroundExecutor.execute(() -> {
                        try {
                            mainHandler.post(() -> {
                                try {
                                    // End the streaming gesture
                                    endGestureStream();

                                    // Reset swipe/path state consistently with swipe flow
                                    resetSwipeSequence();

                                    // Hide path cursor in case it was shown
                                    serviceUiManager.hidePathCursor();
                                    isPathCursorActive = false;
                                } catch (Exception e) {
                                    Log.w(TAG, "gestureDescription end UI error: " + e);
                                }
                            });
                        } catch (Exception e) {
                            Log.w(TAG, "gestureDescription end error: " + e);
                        }
                    });
                    // clear the runnable reference after execution
                    synchronized (gestureLock) {
                        gestureEndRunnable = null;
                    }
                };

                // small delay to allow transient fluctuations to settle (coalescing)
                mainHandler.postDelayed(gestureEndRunnable, 30);
            }
        }
    }

    /* ----------------------------- START OF SWIPE ACTION HANDLING ----------------------------- */
    private boolean startedInsideKbd;
    private boolean swipeEventStarted = false;
    private boolean swipeEventEnding = false;
    private int[] swipeStartPosition = null;
    private long swipeStartTime = 0;
    private boolean isTap = false;
    private boolean isSwipe = false;
    private boolean isLongTap = false;
    private Rect swipeKeyBounds = null;

    private boolean isPathCursorActive = false;

    /**
     * Handles swipe actions based on version 3.0 specs
     *
     * @param isStarting true if the action is starting, false if it is ending
     */
    public void handleSwipeEvent(boolean isStarting) {
        if (isStarting && !swipeEventStarted && !swipeEventEnding) {
            Log.d(TAG, "handleSwipeEvent Start");
            int[] cursorPosition = getCursorPosition();
            cursorController.isCursorTouch = true;
            startSwipeSequence(cursorPosition);
        } else if (!isStarting && swipeEventStarted && !swipeEventEnding) {
            Log.d(TAG, "handleSwipeEvent End");
            endSwipeSequence();
        }
    }

//    private Runnable endTouchAnimationRunnable = () -> {
//        // Cursor is blue, indicating long tap is ready to start
//        Log.d(TAG, "endTouchAnimationRunnable called");
//        if (swipeEventEnding || cursorController.isSwiping) return;
//        canStartSwipe = false;
//        isLongTap = true;
//        startSwipeHoverZoneMonitoring();
//        serviceUiManager.pathCursorSetColor("BLUE");
//    };

//    private Runnable touchGreenToBlueRunnable = () -> {
//        // Cursor is green, indicating swipe is ready to start
//        Log.d(TAG, "touchGreenToBlueRunnable called");
//        if (swipeEventEnding) return;
//
//        mainHandler.postDelayed(endTouchAnimationRunnable, getLongTapThreshold());z
//        serviceUiManager.pathCursorSetColor("GREEN");
//        serviceUiManager.pathCursorAnimateToColor("BLUE", getLongTapThreshold());
// //        if (!isInHoverZone) serviceUiManager.pathCursorHideAnimation("RED");
//
//        if (startedInsideKbd) {
//            sendMotionEventToIME(swipeStartPosition[0], swipeStartPosition[1], MotionEvent.ACTION_CANCEL);
//        }
//
//        // start swipe if intentional movement was previously detected.
//        if (isIntentionalMovement) {
//            startSwipe();
//        }
//        else canStartSwipe = true;
//    };

    private KeyboardEventReceiver keyboardEventReceiver;

    public void onKeyboardSwipeStart() {
        Log.d(TAG, "onKeyboardSwipeStart");
        if (swipeEventStarted && !swipeEventEnding) {
            serviceUiManager.pathCursorSetColor("GREEN");
        }
    }

    public void onKeyboardLongpressAnimation() {
        Log.d(TAG, "onKeyboardLongpressAnimation");
        if (!swipeEventEnding) {
            serviceUiManager.pathCursorAnimateToColor("BLUE", getLongTapThreshold());
        }
    }

    public void onKeyboardStateChanged() {
        Log.d(TAG, "onKeyboardStateChanged");
    }

    private Runnable animateCursorTouchRunnable = () -> {
        Log.d(TAG, "animateCursorTouchRunnable called");
        if (swipeEventEnding) return;

        // if this is a keyboard swype,
        if (startedInsideKbd) {
            if (Config.SHOW_KEY_POPUP) { // and key popup is enabled,
                // show long press popup after user feedback delay
//                keyboardManager.showKeyPopupIME(swipeStartPosition[0], swipeStartPosition[1], true);
            }
        }
        serviceUiManager.pathCursorAnimateToColor("BLUE", getLongTapThreshold(), uiFeedbackDelay);
//        mainHandler.postDelayed(touchGreenToBlueRunnable, getQuickTapThreshold() - uiFeedbackDelay);

//        if (!isInHoverZone) serviceUiManager.pathCursorHideAnimation("RED");
    };

    /**
     * Starts the swipe sequence
     * @param initialPosition The initial cursor position when swipe sequence started
     */
    private void startSwipeSequence(int[] initialPosition) {
        if (initialPosition == null || initialPosition.length < 2 || initialPosition[0] < 0 || initialPosition[1] < 0) {
            Log.e(TAG, "startSwipeSequence: initialPosition is invalid");
            return;
        }

//        swipeStartPosition = new int[]{initialPosition[0], initialPosition[1]}; // actual raw start pos
        swipeStartPosition = cursorController.getRollingAverage(); // rolling avg from last D1A ms
        cursorController.setPathCursorPosition(swipeStartPosition);
        swipeStartTime = System.currentTimeMillis();
        isInHoverZone = true;
        swipeEventStarted = true;
        swipeEventEnding = false;
        cursorController.isCursorTouch = true;
        uiFeedbackDelay = getUiFeedbackDelay();
        canStartSwipe = false;
        serviceUiManager.pathCursorSetColor("YELLOW");

        startedInsideKbd = keyboardManager.canInjectEvent(swipeStartPosition[0], swipeStartPosition[1]);
        Log.d(TAG, "startSwipeSequence() swipeStartPosition: (" + swipeStartPosition[0] +
            ", " + swipeStartPosition[1] + "), startedInsideKbd: " + startedInsideKbd);

        if (startedInsideKbd && !swipeEventEnding) {
            if (Config.HIGHLIGHT_KEY_ON_TOUCH) {
//                keyboardManager.highlightKeyAt(swipeStartPosition[0], swipeStartPosition[1]);
            }
            keyboardManager.sendLongPressDelayToIME(getQuickTapThreshold());
            startSwipe(); // start sending touch events immediately for keyboard swype
        }

//        swipeKeyBounds = keyboardManager.getKeyBounds(swipeStartPosition);

        // Start initial hover period (D1A)
        mainHandler.postDelayed(animateCursorTouchRunnable, uiFeedbackDelay);

//        if (!startedInsideKbd && !swipeEventEnding) {
//            startSwipeHoverZoneMonitoring(); // Start monitoring cursor position for hover zone
//            startMovementMonitoring(); // Start movement monitoring
//        }
    }

    private void endSwipe() {
        cursorController.isSwiping = false;
        if (startedInsideKbd) {
            Log.d(TAG, "cancelSwipe() called, stopping swipe");
            endUptime = SystemClock.uptimeMillis();
            endTime = System.currentTimeMillis();
            int[] cursorPosition = getPathCursorPosition();
            serviceUiManager.clearPreviewBitmap();
            // TODO: if kbd bounds is null, use screen (or active bound) width as default.
            //       AND set actual key width in keyboardManager via searching node tree.
            int keyWidth = keyboardManager.getKeyboardBounds().width() / 10;
            if (cursorController.startedSwipeFromRightKbd && (cursorPosition[0] < screenSize.x) &&
                (cursorPosition[0] >= screenSize.x - (keyWidth * 2))) {
                handleSwipeFromRightKbd();
                cursorController.startedSwipeFromRightKbd = false;
                cursorController.isSwiping = false;
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
                    else if (!keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                        cursorCoords = lastValidCoords;
                    }

                    MotionEvent event = MotionEvent.obtain(
                        startUptime,
                        endUptime,
                        action,
                        cursorCoords[0],
                        cursorCoords[1],
                        0);
                    injectMotionEvent(event);

                    debugText[0] = "Swiping";
                    debugText[1] = "X, Y: (" + cursorCoords[0] + ", " + cursorCoords[1] + ")";
                    Log.d(TAG, "MotionEvent.ACTION_UP @ (" + cursorCoords[0] + ", " + cursorCoords[1] + ")");
                } catch (Exception e) {
                    writeToFile.logError(TAG, "ERROR WHILE ENDING SWIPE!!!: sendPointerSync cannot be called from the main thread." + e);
                    Log.e(TAG, "sendPointerSync cannot be called from the main thread.", e);
                }
                cursorController.isSwiping = false;
                cursorController.isRealtimeSwipe = false;
//            displaySwipeInfo();
            }).start();
        } else {
            // Handle non-realtime swipe logic here
            Log.d(TAG, "cancelSwipe() drag toggle");
            cursorController.dragToggleActive = false;
            dispatchDragOrHold();
        }
    }

    private void startSwipe() {
        cursorController.isSwiping = true;
//        mainHandler.removeCallbacks(endTouchAnimationRunnable);

        // disabling to set cursor green when the kbd indicates a swipe has started.

        if (startedInsideKbd) {
            cursorController.isRealtimeSwipe = true;
            startUptime = SystemClock.uptimeMillis();
            startTime = System.currentTimeMillis();
            int[] initialPosition = swipeStartPosition;

            if (cursorController.checkForSwipingFromRightKbd) {
                lastValidCoords = initialPosition;
                cursorController.startedSwipeFromRightKbd = true;
                return;
            } else {
                cursorController.startedSwipeFromRightKbd = false;
            }

            new Thread(() -> {
                if (keyboardManager.canInjectEvent(initialPosition[0], initialPosition[1])) {
                    lastValidCoords = initialPosition;
                    MotionEvent event = MotionEvent.obtain(
                        startUptime,
                        startUptime,
                        MotionEvent.ACTION_DOWN,
                        initialPosition[0],
                        initialPosition[1],
                        0);
                    injectMotionEvent(event);
                    debugText[0] = "Swiping";
                    debugText[1] = "X, Y: (" + initialPosition[0] + ", " + initialPosition[1] + ")";
                } else {
                    Log.d(TAG, "Coords do not belong to either sender app or IME. TODO: Implement for 3rd party apps.");
                }

                while (cursorController.isSwiping) {
                    int[] cursorPosition = getPathCursorPosition();
                    long now = SystemClock.uptimeMillis();
                    try {
                        if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                            lastValidCoords = cursorPosition;
                            MotionEvent event = MotionEvent.obtain(
                                startUptime,
                                now,
                                MotionEvent.ACTION_MOVE,
                                cursorPosition[0],
                                cursorPosition[1],
                                0);
                            injectMotionEvent(event);
                            debugText[0] = "Swiping";
                            debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";
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
        } else {
            // Handle non-realtime swipe logic here
            Log.d(TAG, "startSwipe() drag toggle");
            cursorController.dragToggleActive = true;
            serviceUiManager.pathCursorSetColor("GREEN");
            dispatchDragOrHold();
        }
    }

    /**
     * Starts monitoring the cursor position to check if it stays within the hover zone.
     * This is used specifically for swipe events to provide visual feedback.
     */
    private void startSwipeHoverZoneMonitoring() {
        new Thread(() -> {
            while (swipeEventStarted && !swipeEventEnding) {
                int[] currentPosition = getPathCursorPosition();
                int[] startPos = swipeStartPosition; // Get local copy to avoid NPE

                // Skip if either position is null
                if (currentPosition == null || startPos == null) {
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                double distance = Math.sqrt(Math.pow(currentPosition[0] - startPos[0], 2) +
                                            Math.pow(currentPosition[1] - startPos[1], 2));

                boolean newHoverState = distance <= Config.HOVER_ZONE_RADIUS;

                // Only update UI if hover state changed
                if (newHoverState != isInHoverZone) {
                    isInHoverZone = newHoverState;
                    Log.d(TAG, "HOVER ZONE " + (isInHoverZone ? "ENTERED" : "EXITED") + "; Cursor Distance: "
                        + distance + "px; Hover Zone Radius: " + Config.HOVER_ZONE_RADIUS + "px");

                    if (!isInHoverZone) {
                        // Cursor left hover zone
                        hoverZoneExitTime = System.currentTimeMillis();
                        mainHandler.post(() -> {
                            // Hide current animations and show red
                            serviceUiManager.pathCursorHideAnimation("RED");
                        });
                    } else {
                        // Cursor returned to hover zone
                        mainHandler.post(() -> {
                            // Show animations again
                            serviceUiManager.pathCursorShowAnimation();
                        });
                    }
                }

                try {
                    Thread.sleep(16); // ~60fps
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    /**
     * Ends the swipe sequence when the switch is released
     */
    private void endSwipeSequence() {
        // Cancel any pending runnables
        swipeEventEnding = true;
        canStartSwipe = false;
        mainHandler.removeCallbacks(animateCursorTouchRunnable);
//        mainHandler.removeCallbacks(touchGreenToBlueRunnable);
//        mainHandler.removeCallbacks(endTouchAnimationRunnable);

        // Cancel any ongoing animations
        serviceUiManager.pathCursorCancelAnimation();

        // If cursor is not in hover zone, cancel the action
//        if (!isInHoverZone && !startedInsideKbd) {
//            resetSwipeSequence();
//            return;
//        }

        int duration = 200;
        try {
            if (cursorController.isSwiping) {
                Log.d(TAG, "endSwipeSequence() - Ending swipe");
                endSwipe();
            }
            else if (isLongTap) { // long tap
                serviceUiManager.pathCursorSetColor("BLUE");
                if (startedInsideKbd) {
                    Log.d(TAG, "endSwipeSequence() isLongTap, sending long press to IME");
//                    duration = 105;
//                    dispatchTapGesture(swipeStartPosition, duration);

                } else {
                    Log.d(TAG, "endSwipeSequence() isLongTap, outputting long press to system");
                    duration = getSystemLongpressDelay() + 5;
                    dispatchTapGesture(swipeStartPosition, duration);
                }
            } else { // quick tap
                serviceUiManager.pathCursorSetColor("YELLOW");
                if (startedInsideKbd) {
                    Log.d(TAG, "endSwipeSequence() isQuickTap, sending quick tap to IME");
//                    duration = 95;
//                    dispatchTapGesture(swipeStartPosition, duration);

                } else {
                    Log.d(TAG, "endSwipeSequence() isQuickTap, outputting quick tap to system");
                    // Use shorter duration for suggestion strip to avoid triggering long press handler
                    // Keep normal duration for other non-keyboard areas
                    duration = isOnSuggestionStrip(swipeStartPosition[0], swipeStartPosition[1]) 
                        ? Config.QUICK_TAP_DURATION : 250;
                    dispatchTapGesture(swipeStartPosition, duration);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while ending swipe sequence: " + e);
            writeToFile.logError(TAG, "Error while ending swipe sequence: " + e);
            if (startedInsideKbd) {
                Log.d(TAG, "endSwipeSequence() err, sending ACTION_CANCEL to IME");
                sendMotionEventToIME(swipeStartPosition[0], swipeStartPosition[1], MotionEvent.ACTION_CANCEL);
            }
        }

        // Reset after tap
        mainHandler.postDelayed( () -> { resetSwipeSequence(); }, duration);
    }

    /**
     * Resets all swipe sequence state variables.
     */
    private void resetSwipeSequence() {
        if (startedInsideKbd) {
            Log.d(TAG, "resetSwipeSequence() sending long press delay to IME");
            sendLongPressDelayToIME(getQuickTapThreshold());
        }
        serviceUiManager.pathCursorShowAnimation();
        serviceUiManager.pathCursorSetColor("WHITE");
        swipeStartPosition = null;
        isInHoverZone = true;
        wasInHoverZone = true;
        hoverZoneExitTime = 0;
        startedInsideKbd = false;
        swipeEventStarted = false;
        swipeEventEnding = false;
        cursorController.isCursorTouch = false;
        canStartSwipe = false;
    }
    /* ------------------------------ END OF SWIPE ACTION HANDLING ------------------------------ */


    private int getSystemLongpressDelay() {
        return ViewConfiguration.getLongPressTimeout();
    }

    private void cancelMotionEvent(int[] coords) {
        if (coords == null || coords.length < 2) {
            Log.e(TAG, "Invalid coordinates for cancelMotionEvent. " + "Fallback to last valid coordinates");
            coords = lastValidCoords; // Fallback to last valid coordinates
        }
        MotionEvent event = MotionEvent.obtain(
            startUptime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            coords[0],
            coords[1],
            0);
        injectMotionEvent(event);
    }

    /**
     * Outputs the primary character for the current key position.
     * This is a placeholder that will be implemented later.
     */
    private void outputQuickTap(int[] coords) {
        if (coords == null) {
            Log.e(TAG, "outputPrimaryCharacter: coords is null");
            return;
        }
        Log.d(TAG, "Outputting primary character");
        // Execute quick tap
        dispatchTapGesture(coords, 250);
    }

    /**
     * Outputs the alternate character for the current key position.
     * This is a placeholder that will be implemented later.
     */
    private void outputLongTap(int[] coords) {
        if (coords == null) {
            Log.e(TAG, "outputAlternateCharacter: coords is null");
            return;
        }
        Log.d(TAG, "Outputting alternate character");
        // Execute long tap
        dispatchTapGesture(coords, getSystemLongpressDelay());
    }

    /*--------------------------------- AIDL ---------------------------------*/

    /**
     * Set the HeadBoard service reference
     * @param service The HeadBoard service instance
     */
    public void setHeadBoardService(HeadBoardService service) {
        this.headBoardService = service;
        Log.d(TAG, "HeadBoard service reference set");
    }

    /**
     * Handle motion event from the IME
     * @param x X coordinate
     * @param y Y coordinate
     * @param action Motion event action
     * @param downTime Time when the event was first pressed down
     * @param eventTime Time when this specific event occurred
     */
    public void handleMotionEvent(float x, float y, int action, long downTime, long eventTime) {
        Log.d(TAG, "Handling motion event from IME: (" + x + ", " + y + ", action=" + action + ")");
        
        // Convert to screen coordinates if needed
        // The IME coordinates might be relative to the IME window
        // You may need to adjust this based on your coordinate system
        
        // Create and dispatch the motion event
        MotionEvent motionEvent = MotionEvent.obtain(
            downTime, eventTime, action, x, y, 0, 0, 0, 0, 0, 0, 0
        );
        
        // Dispatch the event through the accessibility service
//        dispatchGesture(createGestureDescription(motionEvent), null, null);
    }

    /**
     * Handle key event from the IME
     * @param keyCode The key code
     * @param isDown Whether the key is being pressed down
     * @param isLongPress Whether this is a long press event
     */
    public void handleKeyEvent(int keyCode, boolean isDown, boolean isLongPress) {
        Log.d(TAG, "Handling key event from IME: keyCode=" + keyCode + ", isDown=" + isDown + ", isLongPress=" + isLongPress);
        
        int action = isDown ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        long eventTime = SystemClock.uptimeMillis();
        
        KeyEvent keyEvent = new KeyEvent(eventTime, eventTime, action, keyCode, isLongPress ? 1 : 0);
        
        // Dispatch the key event
//        dispatchKeyEvent(keyEvent);
    }

    /**
     * Set the long press delay
     * @param delay Delay in milliseconds
     */
    public void setLongPressDelay(int delay) {
        Log.d(TAG, "Setting long press delay: " + delay + "ms");
        // Store the delay in preferences or update the system setting
        // This would depend on your implementation
    }

    /**
     * Set the gesture trail color
     * @param color The color value (ARGB format)
     */
    public void setGestureTrailColor(int color) {
        Log.d(TAG, "Setting gesture trail color: " + color);
        // Update the gesture trail color in your UI
        if (serviceUiManager != null) {
            // Update the UI manager with the new color
            // This would depend on your UI implementation
        }
    }

    /**
     * Get key information at specific coordinates
     * @param x X coordinate
     * @param y Y coordinate
     */
    public void getKeyInfo(float x, float y) {
        Log.d(TAG, "Getting key info at: (" + x + ", " + y + ")");
        
        // Find the key at the specified coordinates
        // This would depend on your keyboard implementation
        // For now, we'll create a basic KeyInfo object
        KeyInfo keyInfo = new KeyInfo();
        keyInfo.x = x;
        keyInfo.y = y;
        keyInfo.isVisible = true;
        
        // Send the key info back to the IME
        if (headBoardService != null) {
            headBoardService.sendKeyInfo(keyInfo);
        }
    }

    /**
     * Get key bounds for a specific key code
     * @param keyCode The key code to get bounds for
     */
    public void getKeyBounds(int keyCode) {
        Log.d(TAG, "Getting key bounds for keyCode: " + keyCode);
        
        // Find the key bounds for the specified key code
        // This would depend on your keyboard implementation
        // For now, we'll create a basic KeyBounds object
        KeyBounds keyBounds = new KeyBounds(0, 0, 100, 100, keyCode);
        
        // Send the key bounds back to the IME
        if (headBoardService != null) {
            headBoardService.sendKeyBounds(keyBounds);
        }
    }

    /**
     * Show or hide key popup
     * @param x X coordinate
     * @param y Y coordinate
     * @param showKeyPreview Whether to show the key preview
     * @param withAnimation Whether to animate the popup
     * @param isLongPress Whether this is a long press popup
     */
    public void showOrHideKeyPopup(int x, int y, boolean showKeyPreview, boolean withAnimation, boolean isLongPress) {
        Log.d(TAG, "Show/hide key popup: (" + x + ", " + y + "), show=" + showKeyPreview);
        
        // Handle the key popup display
        // This would depend on your UI implementation
        if (serviceUiManager != null) {
            // Update the UI manager to show/hide the popup
            // This would depend on your UI implementation
        }
    }
}
