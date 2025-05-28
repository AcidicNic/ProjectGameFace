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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.projectgameface.utils.CursorUtils;
import com.google.projectgameface.utils.DebuggingStats;
import com.google.projectgameface.utils.WriteToFile;
import com.google.projectgameface.utils.Config;

/** The cursor service of HeadBoard app. */
@SuppressLint("UnprotectedReceiver") // All of the broadcasts can only be sent by system.
public class CursorAccessibilityService extends AccessibilityService implements LifecycleOwner {
    private static final String TAG = "CursorAccessibilityService";

    /** Limit UI update rate to 60 fps */
    public static final int UI_UPDATE = 16;

    /** Limit the FaceLandmark detect rate. */
    private static final int MIN_PROCESS = 30;


    private static final int IMAGE_ANALYZER_WIDTH = 300;
    private static final int IMAGE_ANALYZER_HEIGHT = 400;
    private ServiceUiManager serviceUiManager;
    public CursorController cursorController;
    private FaceLandmarkerHelper facelandmarkerHelper;
    public WindowManager windowManager;
    private Handler tickFunctionHandler;
    public Point screenSize;
    private KeyboardManager keyboardManager;

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

    private int[] tapStartPosition;
    private boolean isInHoverZone = true;
    private Handler tapSequenceHandler = new Handler(Looper.getMainLooper());
    private Runnable hoverEndRunnable;
    private Runnable blueSweepEndRunnable;
    private long tapStartTime;

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
                keyboardManager.checkForKeyboardType();
                gboardDebuggingStats.load(context);
                openboardDebuggingStats.load(context);
                debuggingStats.load(context);
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

//        if (isPlatformSignedAndCanInjectEvents()) {
//            Log.d(TAG, "Platform signed and can inject events!");
//        }
        writeToFile = new WriteToFile(this);

        gboardDebuggingStats.load(this);
        openboardDebuggingStats.load(this);
        keyboardManager.checkForKeyboardType();
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

    /** Send out service state to the front-end (MainActivity). */
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

    /** Enable HeadBoard service. */
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

    /** Disable HeadBoard service. */
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

    /** Destroy HeadBoard service and unregister broadcasts. */
    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        disableService();
        disableSelf();
        handlerThread.quitSafely();
        cursorController.cleanup();

        // Unregister when the service is destroyed
        unregisterReceiver(changeServiceStateReceiver);
        unregisterReceiver(loadSharedConfigBasicReceiver);
        unregisterReceiver(loadSharedConfigGestureReceiver);
        unregisterReceiver(requestServiceStateReceiver);
        unregisterReceiver(enableScorePreviewReceiver);
        unregisterReceiver(serviceUiManager.flyInWindowReceiver);
        unregisterReceiver(serviceUiManager.flyOutWindowReceiver);
        unregisterReceiver(profileChangeReceiver);

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

    /** Check if face is visible in frame. */
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
        } else {
            keyboardManager.checkForKeyboardBounds(event);
        }
    }

    private StringBuilder typedText = new StringBuilder();
    private boolean checkForNewWord = false;
    private long checkForNewWordTimeStamp = 0;
    private String newWord;

    /**
     * Process the typed text and check for new words.
     * This method is called when an accessibility event occurs.
     * @param newText The new text that was typed.
     */
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

    /**
     * Dispatches a tap gesture at the specified cursor position.
     *
     * @param cursorPosition The cursor position in screen coordinates.
     * @param duration The duration of the tap gesture in milliseconds.
     */
    public void dispatchTapGesture(int[] cursorPosition, Integer duration) {
        if (duration == null) {
            duration = 200;
        }
        dispatchGesture(
            CursorUtils.createClick(
                    cursorPosition[0],
                    cursorPosition[1],
                    /* startTime= */ 0,
                    /* duration= */ duration),
            /* callback= */ null,
            /* handler= */ null);

        serviceUiManager.drawTouchDot(cursorPosition);
    }

    /** Dispatches a drag or hold action based on the current cursor position. */
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
                            /* duration= */ 250),
                    /* callback= */ null,
                    /* handler= */ null);
            }
        }
    }

    /**
     * Handles the toggle touch action.
     *
     * @return True if the touch is toggled.
     */
    public boolean toggleTouch() {
        Log.d(TAG, "toggleTouch()");
        int[] cursorPosition = new int[2];
        cursorPosition = getCursorPosition();

        if (cursorController.isSwiping && cursorController.swipeToggleActive) {
            Log.d(TAG, "STOP SWIPE TOGGLE KeyEvent.ACTION_DOWN");
            cursorController.swipeToggleActive = false;
            stopRealtimeSwipe();
        } else if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
            Log.d(TAG, "START SWIPE TOGGLE KeyEvent.ACTION_DOWN");
            cursorController.swipeToggleActive = true;
            startRealtimeSwipe();
        } else {
            Log.d(TAG, "DRAG TOGGLE KeyEvent.ACTION_DOWN");
            DispatchEventHelper.checkAndDispatchEvent(
                    CursorAccessibilityService.this,
                    cursorController,
                    serviceUiManager,
                    BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE,
                    null);
        }
        return true;
    }

    /** Delete the last word in the focused EditText. */
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

            Bundle setModifiedTextargs = new Bundle();
            setModifiedTextargs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, modifiedTextResult.text);

            Bundle setCursorPositionargs = new Bundle();
            setCursorPositionargs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, modifiedTextResult.newCursor);
            setCursorPositionargs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, modifiedTextResult.newCursor);

            focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.getId(), setModifiedTextargs);
            focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION.getId(), setCursorPositionargs);
        }
    }

    /** Class to hold the result of the delete operation. */
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
     * @param text The original text.
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

    private int[] dragtoggleStartPosition = new int[2];

    /** Handle continuous touch action. */
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
            if (keyCode >= 0) {
                keyStates.put(keyCode, true);
            }
            cursorController.continuousTouchActive = true;
            Log.d(TAG, "continuousTouch() SWIPE KeyEvent.ACTION_DOWN");

            if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                startRealtimeSwipe();
            } else {
                dragToggleStartTime = SystemClock.uptimeMillis();
                dragToggleCancelled = false;
                dragtoggleStartPosition = cursorPosition;
                cursorController.dragToggleActive = true;
//                Log.d(TAG, "DRAG TOGGLE DELAY " + getDragToggleDuration());
                dragToggleHandler.postDelayed(dragToggleRunnable, getDragToggleDuration());
            }
        } else if (eventAction == KeyEvent.ACTION_UP || cursorController.continuousTouchActive) {
            if (keyCode >= 0) {
                keyStates.put(keyCode, false);
            }

            cursorController.continuousTouchActive = false;

            Log.d(TAG, "continuousTouch() SWIPE KeyEvent.ACTION_UP");
            if (cursorController.isSwiping) {
                stopRealtimeSwipe();
            } else {
                long elapsedTime = SystemClock.uptimeMillis() - dragToggleStartTime;
                dragToggleHandler.removeCallbacks(dragToggleRunnable);
                if (elapsedTime < getDragToggleDuration()) {
                    dragToggleCancelled = true;
                    // Perform quick tap instead of enabling drag toggle
                    dispatchTapGesture(dragtoggleStartPosition, null);
                } else {
                    cursorController.dragToggleActive = false;
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

    /** Handle start touch action. */
    public void startTouch() {
        int[] cursorPosition = new int[2];
        cursorPosition = getCursorPosition();
        if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
            Log.d(TAG, "START SWIPE");
            cursorController.swipeToggleActive = true;
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

    /** Handle stop touch action. */
    public void stopTouch() {
        int[] cursorPosition = new int[2];
        cursorPosition = getCursorPosition();
        if (cursorController.isSwiping) {
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

    // Fields for smart touch state
    private long smartTouchStartTime;
    private int[] smartTouchStartPosition;
    private boolean smartTouchCancelled = false;
    private Handler smartTouchHandler = new Handler(Looper.getMainLooper());
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
                serviceUiManager.cursorView.animateToColor("RED", longTapThreshold - quickTapThreshold);
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
                serviceUiManager.drawTouchDot(smartTouchStartPosition);
                // Reset cursor to white
                serviceUiManager.cursorView.setColor("WHITE");
                cursorController.smartTouchActive = false;
            }
        }
    };

    /**
     * Handle combined tap action.
     *
     * @param event The key event or null.
     * @return True if the event is handled.
     */
    public boolean combinedTap(KeyEvent event) {
        Log.d(TAG, "smartTouch() KeyEvent: " + event);

        int keyCode = -1;
        int eventAction = -1;
        if (event != null) {
            eventAction = event.getAction();
            keyCode = event.getKeyCode();
        }

        if (eventAction == KeyEvent.ACTION_DOWN || !cursorController.smartTouchActive) {
            if (keyCode >= 0) {
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
            if (keyCode >= 0) {
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
                dispatchTapGesture(smartTouchStartPosition, 650);
                serviceUiManager.drawTouchDot(smartTouchStartPosition);
            } else if (elapsedTime >= quickTapThreshold) {
                // Quick touch
                dispatchTapGesture(smartTouchStartPosition, 250);
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

    /**
     * Runnable to handle delayed drag toggle start.
     * This will be executed after a delay to allow for drag toggle to be cancelled.
     * If the drag toggle is cancelled, it will dispatch a CURSOR_TOUCH event instead.
     */
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

    /** Start realtime swipe event. */
    private void startRealtimeSwipe() {
        cursorController.isSwiping = true;
        cursorController.isRealtimeSwipe = true;
        startUptime = SystemClock.uptimeMillis();
        startTime = System.currentTimeMillis();
        int[] initialPosition = getCursorPosition();

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
            if (keyboardManager.canInjectEvent(initialPosition[0],  initialPosition[1])) {
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
            while (cursorController.isSwiping) {
                int[] cursorPosition = getCursorPosition();
                long now = SystemClock.uptimeMillis();
                try {
                    if (keyboardManager.canInjectEvent(cursorPosition[0],  cursorPosition[1])) {
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

    /** Stop realtime swipe event by sending an ACTION_UP event. */
    private void stopRealtimeSwipe() {
        endUptime = SystemClock.uptimeMillis();
        endTime = System.currentTimeMillis();
        int[] cursorPosition = getCursorPosition();
        serviceUiManager.clearPreviewBitmap();
        int keyWidth = keyboardManager.getKeyboardBounds().width() / 10;
        if (cursorController.startedSwipeFromRightKbd &&
                (cursorPosition[0] < screenSize.x) &&
                (cursorPosition[0] >= screenSize.x - (keyWidth * 2)) /*(cursorPosition[0] (screenSize.x / 2))*/
            ) {
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
                else if (!keyboardManager.canInjectEvent(cursorPosition[0],  cursorPosition[1])) {
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
            cursorController.isSwiping = false;
            cursorController.isRealtimeSwipe = false;
//            displaySwipeInfo();
        }).start();
    }

    /** Handle swipe from right keyboard event. */
    public void handleSwipeFromRightKbd() {
        SharedPreferences preferences = getSharedPreferences(ProfileManager.getCurrentProfile(this), Context.MODE_PRIVATE);
        String eventName = preferences.getString(BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD.toString() + "_event", BlendshapeEventTriggerConfig.Blendshape.NONE.toString());
        if (!eventName.equals(BlendshapeEventTriggerConfig.Blendshape.NONE.toString())) {
            dispatchEvent(BlendshapeEventTriggerConfig.EventType.valueOf(eventName), null);
        }
    }

    /**
     * Inject a motion event into the system.
     *
     * @param event The motion event to inject.
     */
    private void injectMotionEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) { // Android 12 (API 31)
            Log.d(TAG, "[666] Sending MotionEvent to IME");
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
     * @param event The motion event to send.
     */
    private void sendMotionEventToIME(int x, int y, int action) {
        keyboardManager.sendMotionEventToIME(x, y, action);
    }

    /**
     * Send key event to OpenBoard IME to simulate virtual keyboard key presses
     *
     * @param keyCode The key code to send.
     * @param isDown Whether the key is pressed down or released.
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
            Method injectInputEventMethod = inputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
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

    public long getDragToggleDuration() {
        return (long) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.DRAG_TOGGLE_DURATION);
    }

    public int getQuickTapThreshold() {
        return (int) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.QUICK_TAP_THRESHOLD);
    }

    public int getLongTapThreshold() {
        return (int) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.LONG_TAP_THRESHOLD);
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

    /** Check if the app is platform signed and has INJECT_EVENTS permission. */
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

    /** Check if the app is platform signed. */
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

    /** Set image property to match the MediaPipe model. - Using RGBA 8888. - Lowe the resolution. */
    private ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder()
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
     * Handles tap events when the switch is pressed or released.
     * 
     * @param isSwitchDown true if the switch is being pressed down, false if released
     */
    private boolean tapInsideKbd;
    public boolean isTapEventHandled = false;
    public void handleTapEvent() {
        if (!isTapEventHandled) {
            Log.d(TAG, "handleTapEvent Switch pressed");
            int[] cursorPosition = getCursorPosition();

            if (!keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                tapInsideKbd = true;
//                return;
            }

            startTapSequence(cursorPosition);
        } else {
            Log.d(TAG, "handleTapEvent Switch released");
            endTapSequence();
        }
    }

    /**
     * Starts the tap sequence when the switch is pressed down.
     * 
     * @param initialPosition The initial cursor position when switch was pressed
     */
    private void startTapSequence(int[] initialPosition) {
        // Store initial position and start time
        tapStartPosition = initialPosition;
        tapStartTime = System.currentTimeMillis();
        isInHoverZone = true;
        isTapEventHandled = true;

        // Start initial hover period (D1A)
        hoverEndRunnable = () -> {
            if (isInHoverZone) {
                // Start blue sweep animation (D1B)
                serviceUiManager.cursorView.animateToColor("BLUE", getQuickTapThreshold());
                // Show primary key popup
                keyboardManager.showOrHideKeyPopupIME(tapStartPosition[0], tapStartPosition[1], true, true, false);

                // Schedule end of blue sweep
                blueSweepEndRunnable = () -> {
                    if (isInHoverZone) {
                        // Show alternate character key popup
                        keyboardManager.showOrHideKeyPopupIME(tapStartPosition[0], tapStartPosition[1], true, true, true);
                        // Start yellow sweep animation (D2A)
                        serviceUiManager.cursorView.animateToColor("YELLOW", getLongTapThreshold());
                    }
                };
                tapSequenceHandler.postDelayed(blueSweepEndRunnable, getQuickTapThreshold());
            }
        };
        tapSequenceHandler.postDelayed(hoverEndRunnable, Config.D1A_DURATION);

        // Start monitoring cursor position for hover zone
        startHoverZoneMonitoring();
    }

    /**
     * Starts monitoring the cursor position to check if it stays within the hover zone.
     */
    private void startHoverZoneMonitoring() {
        new Thread(() -> {
            while (isInHoverZone) {
                int[] currentPosition = getCursorPosition();
                int[] startPos = tapStartPosition; // Get local copy to avoid NPE
                
                // Skip if either position is null
                if (currentPosition == null || startPos == null) {
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                double distance = Math.sqrt(
                    Math.pow(currentPosition[0] - startPos[0], 2) +
                    Math.pow(currentPosition[1] - startPos[1], 2)
                );

                if (distance > Config.HOVER_ZONE_RADIUS) {
                    isInHoverZone = false;
                    // Post UI updates to main thread
                    tapSequenceHandler.post(() -> {
                        // Cancel any ongoing animations
                        endTapSequence();
                    });
                    break;
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
        isTapEventHandled = false;
        if (hoverEndRunnable != null) {
            tapSequenceHandler.removeCallbacks(hoverEndRunnable);
        }
        if (blueSweepEndRunnable != null) {
            tapSequenceHandler.removeCallbacks(blueSweepEndRunnable);
        }

        // Cancel any ongoing animations
        serviceUiManager.cursorView.cancelAnimation();

        // If cursor left hover zone, turn red for 500ms then white
        if (!isInHoverZone) {
            serviceUiManager.cursorView.setColor("RED");
            tapSequenceHandler.postDelayed(() -> {
                serviceUiManager.cursorView.setColor("WHITE");
                resetTapSequence();
            }, 500);
            return;
        }

        // Get current animation state
        boolean isBlueSweepComplete = System.currentTimeMillis() - tapStartTime >= (Config.D1A_DURATION + getQuickTapThreshold());
        boolean isYellowSweepComplete = System.currentTimeMillis() - tapStartTime >= (Config.D1A_DURATION + getQuickTapThreshold() + getLongTapThreshold());

        if (isYellowSweepComplete) {
            // Output alternate character
//            if (tapInsideKbd) {
////                int keyCode = keyboardManager.getKeyCodeAtPosition(tapStartPosition[0], tapStartPosition[1]);
////                keyboardManager.showOrHideKeyPopupIME(tapStartPosition[0], tapStartPosition[1], true, true, true);
//            }
//            else
                outputAlternateCharacter();
        } else if (isBlueSweepComplete) {
            // Output primary character
//            if (tapInsideKbd) {
//                keyboardManager.pressKeyAtPosition(tapStartPosition[0], tapStartPosition[1], true, false);
//            }
//            else
                outputPrimaryCharacter();
        } else { // early release
//            if (tapInsideKbd) {
//            }
            // else: do nothing, just don't tap
        }

        // hide key popup
        if (tapInsideKbd) {
            keyboardManager.showOrHideKeyPopupIME(tapStartPosition[0], tapStartPosition[1], false, false, false);
        }

        // Reset cursor to white
        serviceUiManager.cursorView.setColor("WHITE");
        resetTapSequence();
    }

    /**
     * Resets all tap sequence state variables.
     */
    private void resetTapSequence() {
        tapStartPosition = null;
        isInHoverZone = true;
        hoverEndRunnable = null;
        blueSweepEndRunnable = null;
    }

    private void cancelMotionEvent() {
        MotionEvent event = MotionEvent.obtain(
                startUptime,
                endUptime,
                MotionEvent.ACTION_CANCEL,
                lastValidCoords[0],
                lastValidCoords[1],
                0
        );
        injectMotionEvent(event);
    }

    /**
     * Outputs the primary character for the current key position.
     * This is a placeholder that will be implemented later.
     */
    private void outputPrimaryCharacter() {
        // TODO: Implement primary character output
        Log.d(TAG, "Outputting primary character");
        // Execute quick tap
        dispatchTapGesture(tapStartPosition, 250);
        serviceUiManager.drawTouchDot(tapStartPosition);
        // Reset cursor to white
        serviceUiManager.cursorView.setColor("WHITE");

    }

    /**
     * Outputs the alternate character for the current key position.
     * This is a placeholder that will be implemented later.
     */
    private void outputAlternateCharacter() {
        // TODO: Implement alternate character output
        Log.d(TAG, "Outputting alternate character");
        // Execute long tap
        dispatchTapGesture(tapStartPosition, 650);
        serviceUiManager.drawTouchDot(tapStartPosition);
        // Reset cursor to white
        serviceUiManager.cursorView.setColor("WHITE");
    }

    /**
     * Handles the case when the switch is pressed but cursor is not over a valid key.
     */
    private void handleInvalidTapPosition() {
        // TODO: Implement invalid position handling
    }
}
