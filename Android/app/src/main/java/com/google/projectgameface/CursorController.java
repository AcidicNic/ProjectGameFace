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


    /** How much you need to move the head to trigger teleport */
    private static final int TELEPORT_TRIGGER_THRESHOLD = 200;

    private static final int TELEPORT_MARGIN_TOP = 60;
    private static final int TELEPORT_MARGIN_BOTTOM = 60;

    private static final int TELEPORT_MARGIN_LEFT = 30;
    private static final int TELEPORT_MARGIN_RIGHT = 30;

    /** How fast cursor can go in teleport mode*/
    private static final double TELEPORT_LERP_SPEED = 0.15;


    // Cursor velocity.
    private float velX = 0.f;
    private float velY = 0.f;

    /** Cursor position.*/
    private double cursorPositionX;

    /** Cursor position.*/
    private double cursorPositionY;


    /** Invisible cursor when using teleport mode.*/
    private double teleportShadowX;
    private double teleportShadowY;

    /** Teleport mode helps user quickly jump to screen edge with small head turning.*/
    private boolean isTeleportMode = false;

    public boolean isDragging = false;

    private static final int MAX_BUFFER_SIZE = 100;

    /** Array for storing user face coordinate x coordinate (detected from FaceLandmarks). */
    ArrayList<Float> faceCoordXBuffer;

    /** Array for storing user face coordinate y coordinate (detected from FaceLandmarks). */
    ArrayList<Float> faceCoordYBuffer;

    private float prevX = 0.f;
    private float prevY = 0.f;
    private float prevSmallStepX = 0.0f;
    private float prevSmallStepY = 0.0f;

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

    private boolean tempBoundsSet = false;
    private static final int POP_OUT_THRESHOLD_DISTANCE = 200;
    private static final int POP_OUT_THRESHOLD_VELOCITY = 50;
    private static final int POP_OUT_MAX_DISTANCE = 300;
    private long lastBoundaryHitTime = 0;
    private long boundaryHitCooldown = 1000; // milliseconds
    private boolean isCursorOutsideBounds = false;
    private boolean isCursorBoosted = false;
    private long cursorBoostStartTime = 0;
    private static final int BOOST_DURATION = 300; // milliseconds
    private static final float BOOST_FACTOR = 1.5f; // Boost factor

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
    private boolean isRealtimeSwipe = true;
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
//        maxHeadCoordX = screenWidth / 2;
//        minHeadCoordY = screenHeight / 2;
//        maxHeadCoordY = screenHeight / 2;
//        minHeadCoordX = screenWidth / 2;
        Log.d(TAG, "OnCreate: Screen size: " + screenWidth + "x" + screenHeight);
        Log.d(TAG, "OnCreate: Head Coord: " + maxHeadCoordX + "x" + minHeadCoordY + "x" + maxHeadCoordY + "x" + minHeadCoordX);

        parentContext = context;
        faceCoordXBuffer = new ArrayList<>();
        faceCoordYBuffer = new ArrayList<>();

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
    private void updateVelocity(float[] faceCoordXy) {
        float faceCoordX = faceCoordXy[0];
        float faceCoordY = faceCoordXy[1];

        faceCoordXBuffer.add(faceCoordX);
        faceCoordYBuffer.add(faceCoordY);

        // Calculate speed
        float tempVelX = faceCoordX - prevX;
        float tempVelY = faceCoordY - prevY;

        float[] result = asymmetryScaleXy(tempVelX, tempVelY);

        this.velX = result[0];
        this.velY = result[1];

        // History
        prevX = faceCoordX;
        prevY = faceCoordY;

        if (faceCoordXBuffer.size() > MAX_BUFFER_SIZE) {
            faceCoordXBuffer.remove(0);
            faceCoordYBuffer.remove(0);
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

        float smallStepX = (smooth * prevSmallStepX + velX / (float) gapFrames) / (smooth + 1);
        float smallStepY = (smooth * prevSmallStepY + velY / (float) gapFrames) / (smooth + 1);

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

    private float maxHeadCoordX = -1;
    private float minHeadCoordY = -1;
    private float maxHeadCoordY = -1;
    private float minHeadCoordX = -1;
    private static final float SMOOTHING_FACTOR = 0.1f; // Adjust this value for more or less smoothing
    private float smoothedCursorPositionX = 0;
    private float smoothedCursorPositionY = 0;

    public void resetHeadCoord() {
        maxHeadCoordX = -1;
        minHeadCoordY = -1;
        maxHeadCoordY = -1;
        minHeadCoordX = -1;
    }

    public void updateHeadCoordMinMax(float[] headCoordXY) {
        if (headCoordXY[0] == 0 && headCoordXY[1] == 0) {
            return;
        }
        if (maxHeadCoordX == -1 || headCoordXY[0] > maxHeadCoordX) {
            Log.d(TAG, "MIN (X, Y): " + minHeadCoordY + ", " + minHeadCoordX);
            Log.d(TAG, "MAX (X, Y): " + maxHeadCoordY + ", " + maxHeadCoordX);
            maxHeadCoordX = headCoordXY[0];
        }
        if (minHeadCoordX == -1 || headCoordXY[0] < minHeadCoordX) {
            Log.d(TAG, "MIN (X, Y): " + minHeadCoordY + ", " + minHeadCoordX);
            Log.d(TAG, "MAX (X, Y): " + maxHeadCoordY + ", " + maxHeadCoordX);
            minHeadCoordX = headCoordXY[0];
        }
        if (maxHeadCoordY == -1 || headCoordXY[1] > maxHeadCoordY) {
            Log.d(TAG, "MIN (X, Y): " + minHeadCoordY + ", " + minHeadCoordX);
            Log.d(TAG, "MAX (X, Y): " + maxHeadCoordY + ", " + maxHeadCoordX);
            maxHeadCoordY = headCoordXY[1];
        }
        if (minHeadCoordY == -1 || headCoordXY[1] < minHeadCoordY) {
            minHeadCoordY = headCoordXY[1];
            Log.d(TAG, "MIN (X, Y): " + minHeadCoordY + ", " + minHeadCoordX);
            Log.d(TAG, "MAX (X, Y): " + maxHeadCoordY + ", " + maxHeadCoordX);
        }
    }

    /**
     * Update internal cursor position.
     * @param headCoordXY User head coordinate.
     * @param gapFrames How many frames we use to wait for the FaceLandmarks model.
     * @param screenWidth Screen size for prevent cursor move out of of the screen.
     * @param screenHeight Screen size for prevent cursor move out of of the screen.
     */
    public void updateInternalCursorPosition(float[] headCoordXY, int gapFrames, int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;

        if (isDirectMappingEnabled()) {

            updateHeadCoordMinMax(headCoordXY);

            float normalizedX = 0.5f;
            float normalizedY = 0.5f;

            if (maxHeadCoordX != minHeadCoordX) {
                normalizedX = (headCoordXY[0] - minHeadCoordX) / (maxHeadCoordX - minHeadCoordX);
            }
            if (maxHeadCoordY != minHeadCoordY) {
                normalizedY = (headCoordXY[1] - minHeadCoordY) / (maxHeadCoordY - minHeadCoordY);
            }

            float headCoordScaleFactorX = getHeadCoordScaleFactorX();
            float headCoordScaleFactorY = getHeadCoordScaleFactorY();
            float scaledX = normalizedX * screenWidth * headCoordScaleFactorX;
            float scaledY = normalizedY * screenHeight * headCoordScaleFactorY;


            // Center the scaled coordinates on the screen
            float centeredX = (scaledX - ((float) screenWidth / 2 * headCoordScaleFactorX)) + ((float) screenWidth / 2);
            float centeredY = (scaledY - ((float) screenHeight / 2 * headCoordScaleFactorY)) + ((float) screenHeight / 2);

//            Log.d("CursorController", "Centered Coordinates: (" + centeredX + ", " + centeredY + ")");

            cursorPositionX = centeredX;
            cursorPositionY = centeredY;

            smoothedCursorPositionX += (float) (SMOOTHING_FACTOR * (cursorPositionX - smoothedCursorPositionX));
            smoothedCursorPositionY += (float) (SMOOTHING_FACTOR * (cursorPositionY - smoothedCursorPositionY));

            cursorPositionX = smoothedCursorPositionX;
            cursorPositionY = smoothedCursorPositionY;

//            Log.d("CursorController", "Cursor Position: (" + cursorPositionX + ", " + cursorPositionY + ")");

            if (tempBoundsSet) {
                handleBoundingLogic();
            }

            // Clamp cursor position to screen bounds
            cursorPositionX = clamp(cursorPositionX, 0, screenWidth);
            cursorPositionY = clamp(cursorPositionY, 0, screenHeight);

//            Log.d("CursorController", "Clamped Cursor Position: (" + cursorPositionX + ", " + cursorPositionY + ")");
        } else {
            // How far we should move this frame.
            float[] offsetXY = this.getCursorTranslateXY(headCoordXY, gapFrames);

            // Update cursor position with clamping to screen bounds
            cursorPositionX += offsetXY[0];
            cursorPositionY += offsetXY[1];

            // Handle bounding logic
            if (tempBoundsSet) {
                handleBoundingLogic();
            }

            // Clamp cursor position to screen bounds
            cursorPositionX = clamp(cursorPositionX, 0, screenWidth);
            cursorPositionY = clamp(cursorPositionY, 0, screenHeight);
        }

        if (isRealtimeSwipe) {
            updateRealtimeSwipe((float) cursorPositionX, (float) cursorPositionY);
        }
        if (isSwiping) {
            updateSwipe((float) cursorPositionX, (float) cursorPositionY);
        }
        updateTrail((float) cursorPositionX, (float) cursorPositionY);
    }

    private void handleBoundingLogic() {
        long currentTime = System.currentTimeMillis();
        boolean durationPopOut = isDurationPopOutEnabled();

        boolean touchingLeftEdge = cursorPositionX <= tempMinX;
        boolean touchingRightEdge = cursorPositionX >= tempMaxX;
        boolean touchingTopEdge = cursorPositionY <= tempMinY;
        boolean touchingBottomEdge = cursorPositionY >= tempMaxY;

        if (!isCursorOutsideBounds) {
            if (durationPopOut) {
                if ((touchingLeftEdge || touchingRightEdge || touchingTopEdge || touchingBottomEdge) &&
                        (cursorPositionX > 0 && cursorPositionX < screenWidth && cursorPositionY > 0 && cursorPositionY < screenHeight)) {

                    if (edgeHoldStartTime == 0) {
                        edgeHoldStartTime = currentTime;
                    }

                    if (currentTime - edgeHoldStartTime > getHoldDuration()) {
                        Log.d(TAG, "Edge hold duration " + getHoldDuration() + "ms reached. Pop out cursor.");
                        isCursorOutsideBounds = true;
                        isCursorBoosted = true;
                        cursorBoostStartTime = currentTime;
                        edgeHoldStartTime = 0;
                    } else {
                        // Clamp cursor to bounds while holding against the edge
                        cursorPositionX = clamp(cursorPositionX, tempMinX, tempMaxX);
                        cursorPositionY = clamp(cursorPositionY, tempMinY, tempMaxY);
                    }
                } else {
                    edgeHoldStartTime = 0;
                    // Clamp cursor to bounds
                    cursorPositionX = clamp(cursorPositionX, tempMinX, tempMaxX);
                    cursorPositionY = clamp(cursorPositionY, tempMinY, tempMaxY);
                }
            } else {
                if ((cursorPositionX < tempMinX || cursorPositionX > tempMaxX || cursorPositionY < tempMinY || cursorPositionY > tempMaxY)) {
                    if (currentTime - lastBoundaryHitTime > boundaryHitCooldown) {
                        float distanceX = (float) Math.abs(cursorPositionX - (cursorPositionX < tempMinX ? tempMinX : tempMaxX));
                        float distanceY = (float) Math.abs(cursorPositionY - (cursorPositionY < tempMinY ? tempMinY : tempMaxY));
                        float velocityX = Math.abs(velX);
                        float velocityY = Math.abs(velY);

                        float dynamicVelocityThreshold = getDynamicVelocityThreshold(velX, velY);
                        float dynamicPopOutThresholdDistanceX = getDynamicPopOutThresholdDistance(cursorPositionX, tempMinX, tempMaxX, screenWidth, velX < 0);
                        float dynamicPopOutThresholdDistanceY = getDynamicPopOutThresholdDistance(cursorPositionY, tempMinY, tempMaxY, screenHeight, velY < 0);

                        if ((distanceX > dynamicPopOutThresholdDistanceX && velocityX > dynamicVelocityThreshold) ||
                                (distanceY > dynamicPopOutThresholdDistanceY && velocityY > dynamicVelocityThreshold)) {
                            isCursorOutsideBounds = true;
                            isCursorBoosted = true;
                            cursorBoostStartTime = currentTime;
                            lastBoundaryHitTime = currentTime;
                        } else {
                            cursorPositionX = clamp(cursorPositionX, tempMinX, tempMaxX);
                            cursorPositionY = clamp(cursorPositionY, tempMinY, tempMaxY);
                        }
                    } else {
                        cursorPositionX = clamp(cursorPositionX, tempMinX, tempMaxX);
                        cursorPositionY = clamp(cursorPositionY, tempMinY, tempMaxY);
                    }
                }
            }
        } else {
            if (cursorPositionX >= tempMinX && cursorPositionX <= tempMaxX && cursorPositionY >= tempMinY && cursorPositionY <= tempMaxY) {
                isCursorOutsideBounds = false;
            }
        }

        if (isCursorBoosted) {
            long elapsedTime = currentTime - cursorBoostStartTime;
            if (elapsedTime < BOOST_DURATION) {
                cursorPositionX += velX * BOOST_FACTOR;
                cursorPositionY += velY * BOOST_FACTOR;
            } else {
                isCursorBoosted = false;
            }
        }
    }

    public void setTemporaryBounds(Rect bounds) {
        this.tempMinX = bounds.left;
        this.tempMinY = bounds.top;
        this.tempMaxX = bounds.right;
        this.tempMaxY = bounds.bottom;
        this.tempBoundsSet = true;
//        resetCursorToCenter(true);
        Log.d(TAG, "Set temporary bounds: " + bounds);
    }

    private float getDynamicPopOutThresholdDistance() {
        return Math.min(screenHeight, screenWidth) * 0.18f; // Adjust the factor as needed
    }

    private float getDynamicPopOutThresholdDistance(double cursorPosition, int boundMin, int boundMax, int screenSize, boolean isMovingTowardsMin) {
        float thresholdDistance = Math.min(screenHeight, screenWidth) * 0.18f; // Adjust the factor as needed

        // Check if the bound is flush against the edge of the screen
        if ((isMovingTowardsMin && boundMin <= 0) || (!isMovingTowardsMin && boundMax >= screenSize)) {
            // Ignore attempts to escape if the bound is flush against the edge of the screen
            return Float.MAX_VALUE;
        }

        return thresholdDistance;
    }

    private float getDynamicVelocityThreshold(float velX, float velY) {
        double speedScale = 0.2;
        float rightSpeed = (float) ((cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.RIGHT_SPEED) * speedScale) + speedScale);
        float leftSpeed = (float) ((cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.LEFT_SPEED) * speedScale) + speedScale);
        float downSpeed = (float) ((cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.DOWN_SPEED) * speedScale) + speedScale);
        float upSpeed = (float) ((cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.UP_SPEED) * speedScale) + speedScale);

        return upSpeed;
    }

    public Rect getTemporaryBounds() {
        return new Rect(this.tempMinX, this.tempMinY, this.tempMaxX, this.tempMaxY);
    }

    public void clearTemporaryBounds() {
        this.tempBoundsSet = false;
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

    public void updateRealtimeSwipe(float x, float y) {
        swipePathPoints.add(new Point((int) x, (int) y));
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

    public float getHeadCoordScaleFactorX() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_X);
    }
    public float getHeadCoordScaleFactorY() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HEAD_COORD_SCALE_FACTOR_Y);
    }

    public float getSmoothingFactor() {
        return cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.SMOOTH_POINTER);
    }

}