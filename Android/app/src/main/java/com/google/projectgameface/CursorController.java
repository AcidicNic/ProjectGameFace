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


import static android.content.Context.RECEIVER_EXPORTED;
import static androidx.core.math.MathUtils.clamp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;

import android.graphics.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class CursorController {
    private static final String TAG = "CursorController";
    private float velX = 0.f;
    private float velY = 0.f;
    private double cursorPositionX = 0;
    private double cursorPositionY = 0;
    private double teleportShadowX;
    private double teleportShadowY;
    private boolean isTeleportMode = false;
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
    private int tempMinX = 0;
    private int tempMaxX = 0;
    private int tempMinY = 0;
    private int tempMaxY = 0;
    private float smoothedCursorPositionX = 0;
    private float smoothedCursorPositionY = 0;
    private boolean tempBoundsSet = false;
    private boolean isCursorOutsideBounds = false;
    public CursorMovementConfig cursorMovementConfig;
    /** A Config define which face shape should trigger which event */
    BlendshapeEventTriggerConfig blendshapeEventTriggerConfig;
    /** Keep tracking if any event is triggered. */
    private final HashMap<BlendshapeEventTriggerConfig.EventType, Boolean> blendshapeEventTriggeredTracker = new HashMap<>();
    private boolean isSwiping = false;
    private Path swipePath;
    private static final int TRAIL_MAX_POINTS = 100;
    private List<float[]> cursorTrail = new LinkedList<>();
    private long edgeHoldStartTime = 0;
    public boolean isRealtimeSwipe = false;
    private List<Point> swipePathPoints = new ArrayList<>();
    private BroadcastReceiver profileChangeReceiver;
    private Context parentContext;

    /**
     * Calculate cursor movement and keeping track of face action events.
     *
     * @param context Context for open SharedPreference
     */
    public CursorController(Context context, int width, int height) {
        screenWidth = width;
        screenHeight = height;
        Log.d(TAG, "OnCreate: Screen size: " + screenWidth + "x" + screenHeight);
        Log.d(TAG, "OnCreate: Head Coord: " + maxRawCoordX + "x" + minRawCoordY + "x" + maxRawCoordY + "x" + minRawCoordX);

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"), RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"));
        }

        // Init blendshape event tracker
        for (BlendshapeEventTriggerConfig.EventType eventType : BlendshapeEventTriggerConfig.EventType.values()) {
            blendshapeEventTriggeredTracker.put(eventType, false);
        }
    }

    public void cleanup() {
        cursorMovementConfig.unregisterReceiver(parentContext);
    }

    /** Scale cursor velocity X, Y with different multiplier in each axis. */
    private float[] asymmetryScaleXy(float velX, float velY) {
        // Speed multiplier in X axis.
        double speedScale = 0.2;
        float rightSpeed = (float) ((cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.RIGHT_SPEED) * speedScale) + speedScale);
        float leftSpeed = (float) ((cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.LEFT_SPEED) * speedScale) + speedScale);
        float downSpeed = (float) ((cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.DOWN_SPEED) * speedScale) + speedScale);
        float upSpeed = (float) ((cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.UP_SPEED) * speedScale) + speedScale);

        float multiplierX =
                (velX > 0)
                        ? rightSpeed
                        : leftSpeed;

        // Speed multiplier in Y axis.
        float multiplierY =
                (velY > 0)
                        ? downSpeed
                        : upSpeed;

        return new float[] {velX * multiplierX, velY * multiplierY};
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
    public BlendshapeEventTriggerConfig.EventType createCursorEvent(float[] blendshapes) {
        // Loop over registered event-blendshape-threshold pairs.
        for (Map.Entry<BlendshapeEventTriggerConfig.EventType, BlendshapeEventTriggerConfig.BlendshapeAndThreshold> entry :
                blendshapeEventTriggerConfig.getAllConfig().entrySet()) {
            BlendshapeEventTriggerConfig.EventType eventType = entry.getKey();
            BlendshapeEventTriggerConfig.BlendshapeAndThreshold blendshapeAndThreshold = entry.getValue();

            if (blendshapeAndThreshold.shape() == BlendshapeEventTriggerConfig.Blendshape.NONE) {
                continue;
            }
            if (blendshapeEventTriggeredTracker.get(eventType) == null) {
                continue;
            }
            float score = blendshapes[blendshapeAndThreshold.shape().value];

            boolean eventTriggered = Boolean.TRUE.equals(blendshapeEventTriggeredTracker.get(eventType));

            if (!eventTriggered && (score > blendshapeAndThreshold.threshold())) {
                blendshapeEventTriggeredTracker.put(eventType, true);
                if (eventType == BlendshapeEventTriggerConfig.EventType.SHOW_APPS) {
                    Log.i(
                            TAG,
                            eventType
                                    + " "
                                    + blendshapeAndThreshold.shape()
                                    + " "
                                    + score
                                    + " "
                                    + blendshapeAndThreshold.threshold());
                }

                // Return the correspond event (te be trigger in Accessibility service).

                if (eventType == BlendshapeEventTriggerConfig.EventType.CURSOR_RESET)
                {
                    isTeleportMode = true;
                    if (tempBoundsSet && !isCursorOutsideBounds) {
                        teleportShadowX = (double) (tempMinX + tempMaxX) / 2;
                        teleportShadowY = (double) (tempMinY + tempMaxY) / 2;
                    } else {
                        teleportShadowX = (double) this.screenWidth / 2;
                        teleportShadowY = (double) this.screenHeight / 2;
                    }
                }

                return eventType;

            } else if (eventTriggered && (score <= blendshapeAndThreshold.threshold())) {
                // Reset the trigger.
                blendshapeEventTriggeredTracker.put(eventType, false);
                if (eventType == BlendshapeEventTriggerConfig.EventType.CURSOR_RESET)
                {
                    isTeleportMode=false;
                }

            } else {
                // check next gesture.
                continue;
            }
        }

        // No action.
        return BlendshapeEventTriggerConfig.EventType.NONE;
    }

    /**
     * Calculate cursor's translation XY and smoothing.
     *
     * @param faceCoordXy User head coordinate x,y from FaceLandmarks tracker.
     * @param gapFrames How many screen frame with no update from FaceLandmarks. Used when calculate
     *     smoothing.
     */
    public float[] getCursorTranslateXY(float[] faceCoordXy, int gapFrames) {
        this.updateVelocity(faceCoordXy);
        int smooth = (int) cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.SMOOTH_POINTER);

        float smallStepX = (float) ((smooth * prevSmallStepX + velX / (float) gapFrames) / (smooth + 1));
        float smallStepY = (float) ((smooth * prevSmallStepY + velY / (float) gapFrames) / (smooth + 1));

        prevSmallStepX = smallStepX;
        prevSmallStepY = smallStepY;

        return new float[] {smallStepX, smallStepY};
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
        if (rawCoordXY[0] == 0 && rawCoordXY[1] == 0) {
            return;
        }
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
    private boolean firstOffset = true;
    // Time in milliseconds over which to apply the offset smoothly
    private final long offsetTransitionDuration = 200; // 200ms
    private long lastOffsetUpdateTime = System.currentTimeMillis();


    /**
     * Update internal cursor position.
     * @param headTiltXY User head coordinate.
     *                    headTiltXY[0] = x coordinate.
     *                    headTiltXY[1] = y coordinate.
     * @param noseTipXY User nose tip coordinate.
     *                    noseTipXY[0] = x coordinate.
     *                    noseTipXY[1] = y coordinate.
     * @param inputSize Input size of FaceLandmarks model.
     *                  inputSize[0] = width.
     *                  inputSize[1] = height.
     * @param screenSize Screen size.
     *                   screenSize[0] = width.
     *                   screenSize[1] = height.
//     * @param gapFrames How many frames we use to wait for the FaceLandmarks model.
//     * @param screenWidth Screen size for prevent cursor move out of of the screen.
//     * @param screenHeight Screen size for prevent cursor move out of of the screen.
     */
    public void updateInternalCursorPosition(float[] headTiltXY, float[] noseTipXY, float[] pitchYawXY, int[] inputSize, int[] screenSize) {
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

        int regionMinX = 0;
        int regionMaxX = screenWidth;
        int regionMinY = 0;
        int regionMaxY = screenHeight;
        if (tempBoundsSet) {
            int WIGGLE_ROOM = screenHeight / 20;
            if (isCursorOutsideBounds) {
                regionMaxY = tempMinY + WIGGLE_ROOM;
            } else {
                regionMinY = tempMinY - WIGGLE_ROOM;
            }
        }

        // Center the normalized coordinates within the region
        float centeredX = (normalizedX - 0.5f) * (regionMaxX - regionMinX) * headCoordScaleFactorX + (float) (regionMaxX + regionMinX) / 2;
        float centeredY = (normalizedY - 0.5f) * (regionMaxY - regionMinY) * headCoordScaleFactorY + (float) (regionMaxY + regionMinY) / 2;

//        Log.d(TAG, "Normalized: (" + normalizedX + ", " + normalizedY + ") Raw: (" + coordsXY[0] + ", " + coordsXY[1] + ") Centered: (" + centeredX + ", " + centeredY + ") Smoothed-PRE: (" + smoothedCursorPositionX + ", " + smoothedCursorPositionY + ")");

        // Smoothing
        float smoothingFactor = getSmoothFactor(0.01f, 0.3f);
        if (smoothedCursorPositionX != smoothedCursorPositionX || smoothedCursorPositionY != smoothedCursorPositionY) {
            smoothedCursorPositionX = centeredX;
            smoothedCursorPositionY = centeredY;
        }
        smoothedCursorPositionX += (smoothingFactor * (centeredX - smoothedCursorPositionX));
        smoothedCursorPositionY += (smoothingFactor * (centeredY - smoothedCursorPositionY));

        cursorPositionX = smoothedCursorPositionX;
        cursorPositionY = smoothedCursorPositionY;

        if (tempBoundsSet) {
            handleBoundingLogic();
        }

        // Clamp cursor position to screen bounds
        cursorPositionX = clamp(cursorPositionX, 0, screenWidth);
        cursorPositionY = clamp(cursorPositionY, 0, screenHeight);

        if (isSwiping) {
            updateSwipe((float) cursorPositionX, (float) cursorPositionY);
            updateTrail((float) cursorPositionX, (float) cursorPositionY);
        }
    }

    private float[] normalizeOffsetNose(float[] coordsXY, int[] inputSize) {
        float AREA = 0.25f;
        float minX = (inputSize[0] / 2) - AREA * inputSize[0];
        float minY = (inputSize[0] / 2) - AREA * inputSize[1];
        float maxX = (float) (inputSize[0] / 2) + (AREA * inputSize[0]);
        float maxY = (float) (inputSize[1] / 2) + (AREA * inputSize[1]);

        float normalizedX = (coordsXY[0] - minX) / (maxX - minX);
        float normalizedY = (coordsXY[1] - minY) / (maxY - minY);

        return new float[] {normalizedX, normalizedY};
    }

    private void handleCenterOffsetUpdate(float[] pitchYawXY, float[] noseTipXY, int[] inputSize) {
        // Check if swiping is active, and pause the offset updates
        if (isRealtimeSwipe) {
            return; // Skip offset updates while swiping
        }

        // Determine if pitch and yaw are close to center (0 degrees)
        boolean isCenteredX = Math.abs(pitchYawXY[1]) < 2.0f; // Yaw close to 0 degrees
        boolean isCenteredY = Math.abs(pitchYawXY[0]) < 2.0f; // Pitch close to 0 degrees
        if (isCenteredX) {
            targetOffsetX = ((float) inputSize[0] / 2) - noseTipXY[0]; // Calculate offset from head center to nose tip
        }
        if (isCenteredY) {
            targetOffsetY = ((float) inputSize[1] / 2) - noseTipXY[1]; // Calculate offset from head center to nose tip
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
            appliedOffsetX += (targetOffsetX - appliedOffsetX) * timeElapsed;
            appliedOffsetY += (targetOffsetY - appliedOffsetY) * timeElapsed;
        } else {
            appliedOffsetX = targetOffsetX;
            appliedOffsetY = targetOffsetY;
        }
        lastOffsetUpdateTime = currentTime;
    }

    /**
     * Calculate smoothing factor using FaceSwype smoothing config var.
     * @param minSmoothingFactor Minimum smoothing factor (Typically 0.01f)
     * @param maxSmoothingFactor Maximum smoothing factor (Typically 0.5f)
     * @return Smoothing factor (Between minSmoothingFactor and maxSmoothingFactor)
     */
    public float getSmoothFactor(float minSmoothingFactor, float maxSmoothingFactor) {
        // get the smoothing factor from the config and invert it
        int smoothInt = 9 - getSmoothing();

        // Ensure the intValue is within the expected range [0, 9]
        smoothInt = clamp(smoothInt, 0, 9);

        // Calculate the smoothing factor using linear interpolation
        float smoothingFactor = minSmoothingFactor + ((maxSmoothingFactor - minSmoothingFactor) / 9) * smoothInt;

//        Log.d(TAG, "Smoothing factor: " + smoothingFactor + " (int: " + getSmoothing() + ")");
        return smoothingFactor;
    }

    private void handleBoundingLogic() {
        long currentTime = System.currentTimeMillis();
        boolean touchingTopEdge = cursorPositionY <= tempMinY;

        if (!isCursorOutsideBounds) {
            // Cursor is inside the bounds
            if (touchingTopEdge && (cursorPositionY > 0 && cursorPositionY < screenHeight)) {
                if (edgeHoldStartTime == 0) {
                    edgeHoldStartTime = currentTime;
                }

                if (currentTime - edgeHoldStartTime > getHoldDuration()) {
                    Log.d(TAG, "Edge hold duration " + getHoldDuration() + "ms reached. Pop out cursor.");
                    isCursorOutsideBounds = true;
//                    isCursorBoosted = true;
//                    cursorBoostStartTime = currentTime;
                    edgeHoldStartTime = 0;
                } else {
                    // Clamp cursor to the top bound while holding against the edge
                    cursorPositionY = clamp(cursorPositionY, tempMinY, screenHeight);
                }
            } else {
                edgeHoldStartTime = 0;
                // Ensure cursor stays within the bounds
                cursorPositionY = clamp(cursorPositionY, tempMinY, screenHeight);
            }
        } else {
            // Cursor is outside the bounds
            if (cursorPositionY >= tempMinY) {
                if (edgeHoldStartTime == 0) {
                    edgeHoldStartTime = currentTime;
                }

                if (currentTime - edgeHoldStartTime > getHoldDuration()) {
                    Log.d(TAG, "Edge hold duration " + getHoldDuration() + "ms reached. Pop in cursor.");
                    isCursorOutsideBounds = false;
//                    isCursorBoosted = true;
//                    cursorBoostStartTime = currentTime;
                    edgeHoldStartTime = 0;
                } else {
                    // Clamp cursor to the area just above the bound while holding against the edge
                    cursorPositionY = clamp(cursorPositionY, 0, tempMinY);
                }
            } else {
                edgeHoldStartTime = 0;
                // Ensure cursor stays within the bounds
                cursorPositionY = clamp(cursorPositionY, 0, tempMinY);
            }
        }
    }

    public Rect getTemporaryBounds() {
        return new Rect(this.tempMinX, this.tempMinY, this.tempMaxX, this.tempMaxY);
    }

    public void setTemporaryBounds(Rect bounds) {
        this.tempMinX = bounds.left;
        this.tempMinY = bounds.top;
        this.tempMaxX = bounds.right;
        this.tempMaxY = bounds.bottom;
        this.tempBoundsSet = true;
//        resetCursorToCenter(true);
        this.isCursorOutsideBounds = this.cursorPositionY < tempMinY;
        Log.d(TAG, "Set temporary bounds: " + bounds);
    }

    public void clearTemporaryBounds() {
        this.tempBoundsSet = false;
        this.tempMinX = 0;
        this.tempMinY = 0;
        this.tempMaxX = screenWidth;
        this.tempMaxY = screenHeight;
    }

    public int[] getCursorPositionXY() {
        return new int[]{(int) cursorPositionX, (int) cursorPositionY};
    }

    public void resetCursorToCenter(boolean bound) {
        if (bound || tempBoundsSet && !isCursorOutsideBounds) {
            cursorPositionX = (double) (tempMinX + tempMaxX) / 2;
            cursorPositionY = (double) (tempMinY + tempMaxY) / 2;
        } else {
            cursorPositionX = (double) this.screenWidth / 2;
            cursorPositionY = (double) this.screenHeight / 2;
        }
    }

    public void startSwipe(float x, float y) {
        cursorTrail.clear();
        isSwiping = true;
        swipePath = new Path();
        swipePath.moveTo(x, y);
    }

    public void updateSwipe(float x, float y) {
        if (isSwiping) {
            swipePath.lineTo(x, y);
        }
    }

    public void stopSwipe() {
        cursorTrail.clear();
        isSwiping = false;
    }

    public Path getSwipePath() {
        return swipePath;
    }

    public boolean isSwiping() {
        return isSwiping;
    }

    public void updateTrail(float x, float y) {
        if (isSwiping) {
            cursorTrail.add(new float[]{x, y});
            if (cursorTrail.size() > TRAIL_MAX_POINTS) {
                cursorTrail.remove(0);
            }
        } else {
            cursorTrail.clear();
        }
    }

    public List<float[]> getCursorTrail() {
        return cursorTrail;
    }

//    public void updateRealtimeSwipe(float x, float y) {
//        swipePathPoints.add(new Point((int) x, (int) y));
//    }

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

}