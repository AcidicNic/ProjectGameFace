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

import static androidx.core.math.MathUtils.clamp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.projectgameface.utils.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class CursorController {
    private static final String TAG = "CursorController";

    private float velX = 0.f;
    private float velY = 0.f;
    private double cursorPositionX = Double.NaN;
    private double cursorPositionY = Double.NaN;
    private double pathCursorPositionX = Double.NaN;
    private double pathCursorPositionY = Double.NaN;
    
    // Rolling average data structures
    private ArrayDeque<CursorPositionEntry> cursorPositionHistory = new ArrayDeque<>();
    private double rollingSumX = 0.0;
    private double rollingSumY = 0.0;
    private int rollingCount = 0;

    // Inner class to store cursor position with timestamp
    private static class CursorPositionEntry {
        final long timestamp;
        final double x;
        final double y;
        
        CursorPositionEntry(long timestamp, double x, double y) {
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
        }
    }
    public boolean isDragging = false;
    private static final int MAX_BUFFER_SIZE = 100;
    /** Array for storing user face coordinate x coordinate (detected from FaceLandmarks). */
    ArrayList<Float> rawCoordXBuffer;
    /** Array for storing user face coordinate y coordinate (detected from FaceLandmarks). */
    ArrayList<Float> rawCoordYBuffer;
    private float prevX = 0.f;
    private float prevY = 0.f;
    private double prevSmallStepX = 0.0f;
    private double prevSmallStepY = 0.0f;
    public float dragStartX = 0.f;
    public float dragStartY = 0.f;
    public float dragEndX = 0.f;
    public float dragEndY = 0.f;
    private int screenWidth;
    private int screenHeight;
    private Rect keyboardBounds;
    private Rect navBarBounds;
    private Rect activeCursorRegion;
    private String activeCursorRegionStr;
    public CursorMovementConfig cursorMovementConfig;
    /** A Config define which face shape should trigger which event */
    BlendshapeEventTriggerConfig blendshapeEventTriggerConfig;
    /** Keep tracking if any event is triggered. */
    private final HashMap<BlendshapeEventTriggerConfig.EventType, Boolean> blendshapeEventTriggeredTracker = new HashMap<>();
    private long edgeHoldStartTime = 0;
    public boolean isRealtimeSwipe = false;
    public boolean isCursorTap = false;
    public boolean isCursorTouch = false;
    private BroadcastReceiver profileChangeReceiver;
    private Context parentContext;
    private KeyboardManager mKeyboardManager;

    public boolean isSwiping = false;
    public boolean continuousTouchActive = false;
    public boolean smartTouchActive = false;
    public boolean swipeToggleActive = false;
    public boolean dragToggleActive = false;
    public boolean checkForSwipingFromRightKbd = false;
    public boolean startedSwipeFromRightKbd = false;
    private boolean isPathCursorVisible = false;

    /**
     * Calculate cursor movement and keeping track of face action events.
     *
     * @param context Context for open SharedPreference
     */
    public CursorController(Context context, int width, int height) {
        screenWidth = width;
        screenHeight = height;
        resetCursorToCenter();
        Log.d(TAG, "OnCreate() -  Cursor Position = (" + cursorPositionX + "x, " + cursorPositionY + "y)");
        Log.d(TAG, "OnCreate() - Screen Size = " + screenWidth + "x" + screenHeight);
        Log.d(
            TAG,
            "OnCreate() - Min/Max Head Coord = x: " + minRawCoordX + " - " + maxRawCoordX + "; y: " +
            minRawCoordY + " - " + maxRawCoordY);

        parentContext = context;
        rawCoordXBuffer = new ArrayList<>();
        rawCoordYBuffer = new ArrayList<>();

        // Create cursor movement config and initialize;
        cursorMovementConfig = new CursorMovementConfig(context);
        cursorMovementConfig.updateAllConfigFromSharedPreference();

        // Create blendshape event trigger config and initialize;
        blendshapeEventTriggerConfig = new BlendshapeEventTriggerConfig(context);
        blendshapeEventTriggerConfig.updateAllConfigFromSharedPreference();

        // Register profile change receiver
        IntentFilter filter = new IntentFilter("PROFILE_CHANGED");
        profileChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String profileName = ProfileManager.getCurrentProfile(context);
                cursorMovementConfig.updateProfile(context, profileName);
                blendshapeEventTriggerConfig.updateProfile(context, profileName);
            }
        };
        ContextCompat.registerReceiver(
            context,
            profileChangeReceiver,
            new IntentFilter("PROFILE_CHANGED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );

        // Init blendshape event tracker
        for (BlendshapeEventTriggerConfig.EventType eventType: BlendshapeEventTriggerConfig.EventType.values()) {
            blendshapeEventTriggeredTracker.put(eventType, false);
        }
    }

    public void cleanup() {
        cursorMovementConfig.unregisterReceiver(parentContext);
    }

    /**
     * Scale cursor velocity X, Y with different multiplier in each axis.
     */
    private float[] asymmetryScaleXy(float velX, float velY) {
        // Speed multiplier in X axis.
        double speedScale = 0.2;
        float rightSpeed = (float) (
            (cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.RIGHT_SPEED) *
             speedScale) + speedScale);
        float leftSpeed = (float) (
            (cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.LEFT_SPEED) *
             speedScale) + speedScale);
        float downSpeed = (float) (
            (cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.DOWN_SPEED) *
             speedScale) + speedScale);
        float upSpeed = (float) (
            (cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.UP_SPEED) * speedScale) +
            speedScale);

        float multiplierX = (velX > 0) ? rightSpeed : leftSpeed;

        // Speed multiplier in Y axis.
        float multiplierY = (velY > 0) ? downSpeed : upSpeed;

        return new float[]{velX * multiplierX, velY * multiplierY};
    }

    /**
     * Calculate cursor velocity from face coordinate location. Use getVelX() and get getVelY() to
     * receive it.
     */
    private void updateVelocity(float[] rawCoordsXY) {
        float rawCoordX = rawCoordsXY[0];
        float rawCoordY = rawCoordsXY[1];

        rawCoordXBuffer.add(rawCoordX);
        rawCoordYBuffer.add(rawCoordY);

        // Calculate speed
        float tempVelX = rawCoordX - prevX;
        float tempVelY = rawCoordY - prevY;

        float[] result = asymmetryScaleXy(tempVelX, tempVelY);

        this.velX = result[0];
        this.velY = result[1];

        // History
        prevX = rawCoordX;
        prevY = rawCoordY;

        if (rawCoordXBuffer.size() > MAX_BUFFER_SIZE) {
            rawCoordXBuffer.remove(0);
            rawCoordYBuffer.remove(0);
        }
    }

    /**
     * Create performable event from blendshapes array if its threshold value reach the threshold.
     *
     * @param blendshapes The blendshapes array from MediaPipe FaceLandmarks model.
     * @return EventType that should be trigger. Will be {@link BlendshapeEventTriggerConfig.EventType#NONE} if no valid event.
     */
    public BlendshapeEventTriggerConfig.EventDetails createCursorEvent(float[] blendshapes) {
        // Loop over registered event-blendshape-threshold pairs.
        for (Map.Entry<BlendshapeEventTriggerConfig.EventType, BlendshapeEventTriggerConfig.BlendshapeAndThreshold> entry: blendshapeEventTriggerConfig.getAllConfig().entrySet()) {
            BlendshapeEventTriggerConfig.EventType eventType = entry.getKey();
            BlendshapeEventTriggerConfig.BlendshapeAndThreshold blendshapeAndThreshold = entry.getValue();
            BlendshapeEventTriggerConfig.Blendshape blendshape = blendshapeAndThreshold.shape();

            if (blendshape.value < 0) {
                continue;
            }
            if (blendshapeEventTriggeredTracker.get(eventType) == null) {
                continue;
            }
            float score = blendshapes[blendshape.value];

            boolean eventTriggered = Boolean.TRUE.equals(blendshapeEventTriggeredTracker.get(eventType));

            // new event triggered
            if (!eventTriggered && (score > blendshapeAndThreshold.threshold())) {
                blendshapeEventTriggeredTracker.put(eventType, true);

                return new BlendshapeEventTriggerConfig.EventDetails(
                    eventType,
                    blendshape,
                    true);

            //
            } else if (eventTriggered && (score <= blendshapeAndThreshold.threshold())) {
                // Reset the trigger.
                blendshapeEventTriggeredTracker.put(eventType, false);

                if (eventType == BlendshapeEventTriggerConfig.EventType.CONTINUOUS_TOUCH ||
                    eventType == BlendshapeEventTriggerConfig.EventType.SMART_TOUCH ||
                    eventType == BlendshapeEventTriggerConfig.EventType.CURSOR_TAP) {
                    return new BlendshapeEventTriggerConfig.EventDetails(
                        eventType,
                        blendshape,
                        false);
                }

            }
        }

        // No action.
        return new BlendshapeEventTriggerConfig.EventDetails();
    }

    /**
     * Calculate cursor's translation XY and smoothing.
     *
     * @param faceCoordXy User head coordinate x,y from FaceLandmarks tracker.
     * @param gapFrames   How many screen frame with no update from FaceLandmarks. Used when calculate
     *                    smoothing.
     */
    public float[] getCursorTranslateXY(float[] faceCoordXy, int gapFrames) {
        this.updateVelocity(faceCoordXy);
        int smooth = 9 - getSmoothing();

        float smallStepX = (float) ((smooth * prevSmallStepX + velX / (float) gapFrames) / (smooth + 1));
        float smallStepY = (float) ((smooth * prevSmallStepY + velY / (float) gapFrames) / (smooth + 1));

        prevSmallStepX = smallStepX;
        prevSmallStepY = smallStepY;

        return new float[]{smallStepX, smallStepY};
    }

    /**
     * Set start point for drag action.
     *
     * @param x coordinate x of cursor.
     * @param y coordinate y of cursor.
     */
    public void prepareDragStart(float x, float y) {
        dragStartX = x;
        dragStartY = y;
        isDragging = true;
    }

    /**
     * Set end point for drag action.
     *
     * @param x coordinate x of cursor.
     * @param y coordinate y of cursor.
     */
    public void prepareDragEnd(float x, float y) {
        dragEndX = x;
        dragEndY = y;
        isDragging = false;
    }

    private float maxRawCoordX = -1;
    private float minRawCoordY = -1;
    private float maxRawCoordY = -1;
    private float minRawCoordX = -1;

    public void resetRawCoordMinMax() {
        maxRawCoordX = -1;
        minRawCoordY = -1;
        maxRawCoordY = -1;
        minRawCoordX = -1;
    }

    public void updateRawCoordMinMax(float[] rawCoordXY) {
        if (rawCoordXY[0] == 0 && rawCoordXY[1] == 0) return;
        if (maxRawCoordX == -1 || rawCoordXY[0] > maxRawCoordX) {
            maxRawCoordX = rawCoordXY[0];
        }
        if (minRawCoordX == -1 || rawCoordXY[0] < minRawCoordX) {
            minRawCoordX = rawCoordXY[0];
        }
        if (maxRawCoordY == -1 || rawCoordXY[1] > maxRawCoordY) {
            maxRawCoordY = rawCoordXY[1];
        }
        if (minRawCoordY == -1 || rawCoordXY[1] < minRawCoordY) {
            minRawCoordY = rawCoordXY[1];
        }
    }

    private float targetOffsetX = 0f;
    private float targetOffsetY = 0f;
    private float appliedOffsetX = 0f;
    private float appliedOffsetY = 0f;
    // Time in milliseconds over which to apply the offset smoothly
    private final long offsetTransitionDuration = 200; // 200ms
    private long lastOffsetUpdateTime = System.currentTimeMillis();

    /**
     * Update internal cursor position.
     *
     * @param headTiltXY User head coordinate.
     *                   headTiltXY[0] = x coordinate.
     *                   headTiltXY[1] = y coordinate.
     * @param noseTipXY  User nose tip coordinate.
     *                   noseTipXY[0] = x coordinate.
     *                   noseTipXY[1] = y coordinate.
     * @param inputSize  Input size of FaceLandmarks model.
     *                   inputSize[0] = width.
     *                   inputSize[1] = height.
     * @param screenSize Screen size.
     *                   screenSize[0] = width.
     *                   screenSize[1] = height.
     */
    public void updateInternalCursorPosition(
        float[] headTiltXY,
        float[] noseTipXY,
        float[] pitchYawXY,
        int[] inputSize,
        int[] screenSize) {

        this.screenWidth = screenSize[0];
        this.screenHeight = screenSize[1];

        boolean isPitchYawEnabled = isPitchYawEnabled();
        boolean isNoseTipEnabled = isNoseTipEnabled();
        float[] coordsXY;
        float normalizedX = 0.5f;
        float normalizedY = 0.5f;
        float headCoordScaleFactorX = getHeadCoordScaleFactorX();
        float headCoordScaleFactorY = getHeadCoordScaleFactorY();

        if (isPitchYawEnabled && isNoseTipEnabled) { // Combined
//            Log.d(TAG, "handleCenterOffsetUpdate() - pitchYawXY: " + pitchYawXY[0] + ", " + pitchYawXY[1] +
//            "; noseTipXY: " + noseTipXY[0] + ", " + noseTipXY[1] + "; headTiltXY: " + headTiltXY[0] + ", " + headTiltXY[1]);
            coordsXY = noseTipXY;
            handleCenterOffsetUpdate(pitchYawXY, noseTipXY, inputSize);
            coordsXY[0] += appliedOffsetX;
            coordsXY[1] += appliedOffsetY;
            normalizedX = coordsXY[0] / inputSize[0];
            normalizedY = coordsXY[1] / inputSize[1];
            headCoordScaleFactorX *= 2;
            headCoordScaleFactorY *= 6;
        } else {
            if (isPitchYawEnabled) { // Only Pitch+Yaw
                coordsXY = headTiltXY;
            } else { // Only Nose Tip
                coordsXY = noseTipXY;
            }
            updateRawCoordMinMax(coordsXY);
            if (maxRawCoordX != minRawCoordX) {
                normalizedX = (coordsXY[0] - minRawCoordX) / (maxRawCoordX - minRawCoordX);
            }
            if (maxRawCoordY != minRawCoordY) {
                normalizedY = (coordsXY[1] - minRawCoordY) / (maxRawCoordY - minRawCoordY);
            }
        }

        if (noseTipXY[0] == 0 && noseTipXY[1] == 0 && pitchYawXY[0] == 0 && pitchYawXY[1] == 0) {
            normalizedX = 0.5f;
            normalizedY = 0.5f;
        }

        int regionMinX = 0;
        int regionMaxX = screenWidth;
        int regionMinY = 0;
        int regionMaxY = screenHeight;
        if (activeCursorRegion != null && !activeCursorRegion.isEmpty()) {
//            int WIGGLE_ROOM = screenHeight / 20;
            regionMaxY = clamp(activeCursorRegion.bottom, 0, screenHeight);
            regionMinY = clamp(activeCursorRegion.top, 0, screenHeight);
        }

        // Center the normalized coordinates within the region
        float centeredX = (normalizedX - 0.5f) * (regionMaxX - regionMinX) * headCoordScaleFactorX +
            (float) (regionMaxX + regionMinX) / 2;
        float centeredY = (normalizedY - 0.5f) * (regionMaxY - regionMinY) * headCoordScaleFactorY +
            (float) (regionMaxY + regionMinY) / 2;

        // Smoothing
        float smoothingFactor = getSmoothFactor(Config.MIN_SMOOTHING_FACTOR, Config.MAX_SMOOTHING_FACTOR);

        if (Double.isNaN(cursorPositionX)) cursorPositionX = centeredX;
        if (Double.isNaN(cursorPositionY)) cursorPositionY = centeredY;

        cursorPositionX += smoothingFactor * (centeredX - cursorPositionX);
        cursorPositionY += smoothingFactor * (centeredY - cursorPositionY);

        if (isPathCursorEnabled()) {
            if (Double.isNaN(pathCursorPositionX)) pathCursorPositionX = cursorPositionX;
            if (Double.isNaN(pathCursorPositionY)) pathCursorPositionY = cursorPositionY;

            // the path cursor is modofied down by a percentage to slowly catch up to the position of the cursor
            // the higher the perecntage float is the faster it catches up
            float percentage = getPathCursorPercentage();
            pathCursorPositionX = cursorPositionX * percentage + pathCursorPositionX * (1 - percentage);
            pathCursorPositionY = cursorPositionY * percentage + pathCursorPositionY * (1 - percentage);
        }

        // Cursor Regon Bounding
        if (activeCursorRegion != null) {
            handleBoundingLogic();
            // Ensure cursor stays within the bounds of the active region
            cursorPositionY = clamp(cursorPositionY, activeCursorRegion.top, activeCursorRegion.bottom);
            pathCursorPositionY = clamp(pathCursorPositionY, activeCursorRegion.top, activeCursorRegion.bottom);
        } else {
            cursorPositionY = clamp(cursorPositionY, 0, screenHeight);
            pathCursorPositionY = clamp(pathCursorPositionY, 0, screenHeight);
        }

        // Clamp cursor position to screen bounds
        cursorPositionX = clamp(cursorPositionX, 0, screenWidth);
        pathCursorPositionX = clamp(pathCursorPositionX, 0, screenWidth);

        // Add current cursor position to history for rolling average calculation
        addCursorPositionToHistory(cursorPositionX, cursorPositionY);
        
        // Calculate rolling average and optionally use it
        double[] rollingAverage = calculateRollingAverage();
    }

    /**
     * Normalize the nose tip coordinates to a range of 0 to 1 based on the input size.
     * This is used to ensure that the nose tip coordinates are within a consistent range for cursor
     * movement calculations.
     *
     * @param coordsXY  The x and y coordinates of the nose tip.
     * @param inputSize The size of the input image (width and height).
     * @return Normalized coordinates as an array of floats.z
     */
    private float[] normalizeOffsetNose(float[] coordsXY, int[] inputSize) {
        float AREA = 0.25f;
        float minX = (inputSize[0] / 2) - AREA * inputSize[0];
        float minY = (inputSize[0] / 2) - AREA * inputSize[1];
        float maxX = (float) (inputSize[0] / 2) + (AREA * inputSize[0]);
        float maxY = (float) (inputSize[1] / 2) + (AREA * inputSize[1]);

        float normalizedX = (coordsXY[0] - minX) / (maxX - minX);
        float normalizedY = (coordsXY[1] - minY) / (maxY - minY);

        return new float[]{normalizedX, normalizedY};
    }

    private void handleCenterOffsetUpdate(float[] pitchYawXY, float[] noseTipXY, int[] inputSize) {
        // Skip offset updates if any event is active or if we don't have valid face detection data yet
        if (isEventActive() || (noseTipXY[0] == 0 && noseTipXY[1] == 0 && pitchYawXY[0] == 0 && pitchYawXY[1] == 0)) {
            return;
        }

        // Determine if pitch and yaw are close to center (0 degrees)
        boolean isCenteredX = Math.abs(pitchYawXY[1]) < 2.0f; // Yaw close to 0 degrees
        boolean isCenteredY = Math.abs(pitchYawXY[0]) < 2.0f; // Pitch close to 0 degrees

        if (isCenteredX) {
            targetOffsetX = ((float) inputSize[0] / 2) - noseTipXY[0];
        }
        if (isCenteredY) {
            targetOffsetY = ((float) inputSize[1] / 2) - noseTipXY[1];
        }

        if (targetOffsetY == 0 && targetOffsetX == 0) {
            appliedOffsetX = 0;
            appliedOffsetY = 0;
            return;
        }

        // Smoothly apply the offset over time
        long currentTime = System.currentTimeMillis();
        float timeElapsed = (currentTime - lastOffsetUpdateTime) / (float) offsetTransitionDuration;

        if (timeElapsed < 1) {
            if (targetOffsetX != 0) {
                appliedOffsetX += (targetOffsetX - appliedOffsetX) * timeElapsed;
            }
            if (targetOffsetY != 0) {
                appliedOffsetY += (targetOffsetY - appliedOffsetY) * timeElapsed;
            }
        } else {
            appliedOffsetX = targetOffsetX;
            appliedOffsetY = targetOffsetY;
        }
        lastOffsetUpdateTime = currentTime;
    }

    /**
     * Calculate smoothing factor using FaceSwype smoothing config var.
     *
     * @param minSmoothingFactor Minimum smoothing factor (Typically 0.01f)
     * @param maxSmoothingFactor Maximum smoothing factor (Typically 0.3f)
     * @return Smoothing factor (Between minSmoothingFactor and maxSmoothingFactor)
     */
    public float getSmoothFactor(float minSmoothingFactor, float maxSmoothingFactor) {
        // get the smoothing factor from the config and invert it
        int smoothInt = 19 - getSmoothing();

        // Ensure the intValue is within the expected range [0, 19]
        smoothInt = clamp(smoothInt, 0, 19);

        boolean useExponential = cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.EXPONENTIAL_SMOOTHING);

        if (useExponential) {
            // Use exponential mapping for a more balanced feel
            float normalizedValue = (float) (Math.pow(1.6, smoothInt) - 1) / (float) (Math.pow(1.6, 19) - 1);
            return minSmoothingFactor + (maxSmoothingFactor - minSmoothingFactor) * normalizedValue;
        } else {
            // Use linear mapping
            return minSmoothingFactor + ((maxSmoothingFactor - minSmoothingFactor) / 19) * smoothInt;
        }
    }

    private void handleBoundingLogic() {
        if (activeCursorRegion == null || activeCursorRegionStr == null) {
//            Log.d(TAG, "Active cursor region is not set. Cannot handle bounding logic.");
            edgeHoldStartTime = 0;
            return;
        }
        if (isEventActive()) {
//            Log.d(TAG, "Event is active. Skipping bounding logic.");
            edgeHoldStartTime = 0;
            return;
        }

        cursorPositionY = clamp(cursorPositionY, 0, screenHeight);
        boolean isTouchingTopEdge = cursorPositionY <= activeCursorRegion.top && activeCursorRegion.top > 0;
        boolean isTouchingBottomEdge =
            cursorPositionY >= activeCursorRegion.bottom && activeCursorRegion.bottom < screenHeight;

        // Check if the cursor is touching an edge of the active region that borders another region
        if (isTouchingTopEdge || isTouchingBottomEdge) {
            long currentTime = System.currentTimeMillis();

            // Cursor is at the edge of the active region
            if (edgeHoldStartTime == 0) {
                edgeHoldStartTime = currentTime;
            }

            if (currentTime - edgeHoldStartTime > getHoldDuration()) {
                Log.d(TAG, "Edge hold duration " + getHoldDuration() + "ms reached. Pop out cursor.");
                edgeHoldStartTime = 0;
                String previousRegion = activeCursorRegionStr;

                // Pop out to the next region based on the edge touched
                if (isTouchingTopEdge && previousRegion.equals("KBD")) {
                    // Pop out to the top region
                    activeCursorRegionStr = "TOP";
                    activeCursorRegion = new Rect(0, 0, screenWidth, activeCursorRegion.top - 1);
                } else if (isTouchingBottomEdge && previousRegion.equals("KBD")) {
                    // Pop out to the bottom region
                    activeCursorRegionStr = "NAV";
                    activeCursorRegion = new Rect(
                        0,
                        activeCursorRegion.bottom + 1,
                        screenWidth,
                        screenHeight);
                } else if ((isTouchingTopEdge && previousRegion.equals("NAV")) // touching the top edge of NAV
                        || (isTouchingBottomEdge && previousRegion.equals("TOP"))) { // touching the bottom edge of TOP
                    activeCursorRegionStr = "KBD";
                    activeCursorRegion = new Rect(
                        keyboardBounds.left,
                        keyboardBounds.top,
                        keyboardBounds.right,
                        (navBarBounds == null ? screenHeight : navBarBounds.top - 1));
                }
                Log.d(
                    TAG,
                    "ActiveCursorRegion: " + previousRegion + " --> " + activeCursorRegionStr +
                    ", New Bounds: " + activeCursorRegion);
            }
        } else {
            // Reset edge hold time if cursor is not at the edge
            edgeHoldStartTime = 0;
        }
    }

    public boolean isEventActive() {
        return isCursorTap || isSwiping || continuousTouchActive || swipeToggleActive || isCursorTouch;
    }

    public String getActiveCursorRegionStr() {
        return activeCursorRegionStr;
    }

    public Rect getNavBarBounds() {
        return new Rect(navBarBounds);
    }

    public void setNavBarBounds(Rect navRect) {
        navBarBounds = new Rect(navRect);
    }

    public void clearNavBarBounds() {
        navBarBounds = null;
    }

    public Rect getKeyboardBounds() {
        return new Rect(keyboardBounds);
    }

    public void setKeyboardBounds(Rect kbdRect) {
        keyboardBounds = new Rect(kbdRect);
        int kbdBottom = screenHeight;
        if (navBarBounds != null) {
            kbdBottom = navBarBounds.top - 1;
        }
        if (cursorPositionY < keyboardBounds.top) {
            activeCursorRegionStr = "TOP";
            activeCursorRegion = new Rect(0, 0, screenWidth, keyboardBounds.top - 1);
        } else if (cursorPositionY > kbdBottom) {
            activeCursorRegionStr = "NAV";
            activeCursorRegion = new Rect(0, kbdBottom + 1, screenWidth, screenHeight);
        } else {
            activeCursorRegionStr = "KBD";
            activeCursorRegion = new Rect(
                keyboardBounds.left,
                keyboardBounds.top,
                keyboardBounds.right,
                kbdBottom);
        }

        Log.d(TAG, "ActiveCursorRegion: (" + activeCursorRegionStr + ") " + activeCursorRegion);
    }

    public void clearKeyboardBounds() {
        keyboardBounds = null;
        activeCursorRegionStr = null;
        activeCursorRegion = null;
        edgeHoldStartTime = 0;
    }

    public int[] getCursorPositionXY() {
        return new int[]{(int) cursorPositionX, (int) cursorPositionY};
    }

    public int[] getPathCursorPositionXY() {
        if (isPathCursorEnabled()) {
            return new int[]{(int) pathCursorPositionX, (int) pathCursorPositionY};
        } else {
            return getCursorPositionXY();
        }
    }

    public void resetCursorToCenter() {
        if (activeCursorRegion != null) {
            cursorPositionX = (double) (activeCursorRegion.left + activeCursorRegion.right) / 2;
            cursorPositionY = (double) (activeCursorRegion.top + activeCursorRegion.bottom) / 2;
        } else {
            cursorPositionX = (double) screenWidth / 2;
            cursorPositionY = (double) screenHeight / 2;
        }
    }

    public void resetPathCursorPosition() {
//        if (Double.isNaN(cursorPositionX) || Double.isNaN(cursorPositionY)) {
//            Log.w(TAG, "Resetting path cursor position with NaN values. Resetting to center.");
//            resetCursorToCenter();
//        }
        Log.d(TAG, "Resetting path cursor position to current cursor position: (" + cursorPositionX + ", " + cursorPositionY + ")");
        pathCursorPositionX = cursorPositionX;
        pathCursorPositionY = cursorPositionY;
    }

    /**
     * Get the percentage of the path cursor based on the path cursor config.
     * Uses default value if the path cursor config is invalid.
     *
     * @return The percentage of the path cursor as a float.
     *         Returns 0.00f for value 0
     *         Returns 0.002f to 0.04f for values 1-20
     *         Returns 0.048f to 0.20f for values 21-40
     */
    public float getPathCursorPercentage() {
        int pathCursorValue = getPathCursorConfig(); // int between 0 and 24
//        if (pathCursorValue < 0 || pathCursorValue > 24) {
//            Log.w(TAG, "Invalid path cursor config: " + pathCursorValue + ". Defaulting to default.");
//            pathCursorValue = Config.DEFAULT_PATH_CURSOR;
//        }
        return getPathCursorPercentageFrom(pathCursorValue);
    }

    /**
     * Get the percentage of the path cursor based on the path cursor config.
     * Static method that can be used without creating a CursorController instance.
     *
     * @return The percentage of the path cursor as a float. (0.01f to 0.25f)
     */
    public static float getPathCursorPercentageFrom(int pathCursorValue) {
        if (pathCursorValue < 0) {
            Log.w(TAG, "Invalid path cursor value < 0: " + pathCursorValue + ". Defaulting to 0.01f.");
            return 0.01f;
        } else if (pathCursorValue > 24) {
            Log.w(TAG, "Invalid path cursor value > 24: " + pathCursorValue + ". Defaulting to 0.25f.");
            return 0.25f;
        }

        //mapping 0-24 to 0.01f-0.25f linearly
        return 0.01f + pathCursorValue * (0.25f / 25);

//        if (pathCursorValue == 0) {
//            return 0.01f;
//        } else if (pathCursorValue <= 20) {
//            // mapping 1-20 to 0.002f-0.04f linearly
//            return 0.002f + (pathCursorValue - 1) * (0.038f / 19);
//        } else if (pathCursorValue <= 20) {
//            // mapping 21-40 to 0.04f-0.20f linearly
//            return 0.04f + (pathCursorValue - 20) * (0.16f / 20);
//        } else {
//            return 0.25f;
//        }
    }

    public boolean isDurationPopOutEnabled() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DURATION_POP_OUT);
    }

    public int getHoldDuration() {
        return (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.EDGE_HOLD_DURATION);
    }

    public boolean isDirectMappingEnabled() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DIRECT_MAPPING);
    }

    public boolean isNoseTipEnabled() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.NOSE_TIP);
    }

    public boolean isPitchYawEnabled() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.PITCH_YAW);
    }

    public float getHeadCoordScaleFactorX() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_X);
    }

    public float getHeadCoordScaleFactorY() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_Y);
    }

    public float getSmoothingFactor() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.SMOOTH_POINTER);
    }

    public int getSmoothing() {
        return (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.AVG_SMOOTHING);
    }

    public boolean isPathCursorEnabled() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.ENABLE_PATH_CURSOR);
    }

    public boolean isPathCursorVisible() {
//        if (!isPathCursorEnabled()) return false;
//        return isPathCursorVisible;
        return isPathCursorEnabled() & isPathCursorVisible;
    }

    public void setIsPathCursorVisible(boolean enable) {
        if (!isPathCursorEnabled()) {
            Log.d(TAG, "setIsPathCursorVisible: Path cursor is disabled in config. Forcing invisible.");
            isPathCursorVisible = false;
            return;
        }
        Log.d(TAG, "setIsPathCursorVisible: " + enable);
        isPathCursorVisible = enable;
    }

    public int getPathCursorConfig() {
        return (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.PATH_CURSOR);
    }

    public void setKeyboardManager(KeyboardManager keyboardManager) {
        mKeyboardManager = keyboardManager;
    }
    
    /**
     * Add a cursor position entry to the history for rolling average calculation.
     * 
     * @param x X coordinate of cursor position
     * @param y Y coordinate of cursor position
     */
    private void addCursorPositionToHistory(double x, double y) {
        long currentTime = System.currentTimeMillis();
        cursorPositionHistory.addLast(new CursorPositionEntry(currentTime, x, y));
        rollingSumX += x;
        rollingSumY += y;
        rollingCount += 1;

        // Clean up old entries outside the rolling average window
        cleanupOldPositions(currentTime);
    }
    
    /**
     * Remove cursor position entries older than the rolling average window.
     * 
     * @param currentTime Current timestamp in milliseconds
     */
    private void cleanupOldPositions(long currentTime) {
        long cutoffTime = currentTime - Config.D1A_DURATION;

        // Evict from the head while entries are older than the cutoff
        while (!cursorPositionHistory.isEmpty()) {
            CursorPositionEntry head = cursorPositionHistory.peekFirst();
            if (head.timestamp >= cutoffTime) break;
            cursorPositionHistory.pollFirst();
            rollingSumX -= head.x;
            rollingSumY -= head.y;
            rollingCount -= 1;
        }
    }
    
    /**
     * Get the rolling average cursor position over the last 800ms.
     * 
     * @return Array containing [averageX, averageY], or null if no positions in window
     */
    public int[] getRollingAverage() {
        double[] avg = calculateRollingAverage();
        if (avg == null) return null;
        return new int[]{(int) avg[0], (int) avg[1]};
    }
    
    /**
     * Clear the rolling average history. Useful for resetting the cursor state.
     */
    public void clearRollingAverageHistory() {
        cursorPositionHistory.clear();
    }
    
    /**
     * Calculate the rolling average cursor position over the last 800ms.
     * 
     * @return Array containing [averageX, averageY], or null if no positions in window
     */
    private double[] calculateRollingAverage() {
        if (rollingCount <= 0) {
            return null;
        }
        return new double[]{rollingSumX / rollingCount, rollingSumY / rollingCount};
    }

    public void setPathCursorPosition(int[] coords) {
        pathCursorPositionX = coords[0];
        pathCursorPositionY = coords[1];
    }
}
