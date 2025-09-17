package com.google.projectgameface;

import com.google.projectgameface.KeyInfo;
import com.google.projectgameface.KeyBounds;
import com.google.projectgameface.IHeadBoardCallback;

/**
 * AIDL interface for bi-directional communication between HeadBoard accessibility service
 * and OpenBoard IME. This provides low-latency communication for gesture events and
 * keyboard interactions.
 */
interface IHeadBoardService {
    /**
     * Register a callback to receive events from the HeadBoard service
     * @param callback The callback interface to register
     */
    void registerCallback(IHeadBoardCallback callback);
    
    /**
     * Unregister a previously registered callback
     * @param callback The callback interface to unregister
     */
    void unregisterCallback(IHeadBoardCallback callback);
    
    /**
     * Send a motion event to the IME
     * @param x X coordinate of the motion event
     * @param y Y coordinate of the motion event
     * @param action Motion event action (ACTION_DOWN, ACTION_MOVE, ACTION_UP, etc.)
     * @param downTime Time when the event was first pressed down
     * @param eventTime Time when this specific event occurred
     */
    void sendMotionEvent(float x, float y, int action, long downTime, long eventTime);
    
    /**
     * Send a key event to the IME
     * @param keyCode The key code to send
     * @param isDown Whether the key is being pressed down
     * @param isLongPress Whether this is a long press event
     */
    void sendKeyEvent(int keyCode, boolean isDown, boolean isLongPress);
    
    /**
     * Set the long press delay for the keyboard
     * @param delay Delay in milliseconds
     */
    void setLongPressDelay(int delay);
    
    /**
     * Change the gesture trail color
     * @param color The color value (ARGB format)
     */
    void setGestureTrailColor(int color);
    
    /**
     * Get key information at specific coordinates
     * @param x X coordinate
     * @param y Y coordinate
     */
    void getKeyInfo(float x, float y);
    
    /**
     * Get key bounds for a specific key code
     * @param keyCode The key code to get bounds for
     */
    void getKeyBounds(int keyCode);
    
    /**
     * Show or hide key popup
     * @param x X coordinate
     * @param y Y coordinate
     * @param showKeyPreview Whether to show the key preview
     * @param withAnimation Whether to animate the popup
     * @param isLongPress Whether this is a long press popup
     */
    void showOrHideKeyPopup(int x, int y, boolean showKeyPreview, boolean withAnimation, boolean isLongPress);
    
    /**
     * Check if the service is connected and ready
     * @return true if connected, false otherwise
     */
    boolean isConnected();
}
