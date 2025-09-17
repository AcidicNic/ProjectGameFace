package com.google.projectgameface;

import com.google.projectgameface.KeyInfo;
import com.google.projectgameface.KeyBounds;

/**
 * Callback interface for receiving events from the HeadBoard service
 */
interface IHeadBoardCallback {
    /**
     * Called when key information is available
     * @param keyInfo The key information
     */
    void onKeyInfo(in KeyInfo keyInfo);
    
    /**
     * Called when key bounds are available
     * @param keyBounds The key bounds information
     */
    void onKeyBounds(in KeyBounds keyBounds);
    
    /**
     * Called when the service connection status changes
     * @param connected true if connected, false if disconnected
     */
    void onConnectionChanged(boolean connected);
    
    /**
     * Called when an error occurs
     * @param errorCode Error code
     * @param errorMessage Error message
     */
    void onError(int errorCode, String errorMessage);
}
