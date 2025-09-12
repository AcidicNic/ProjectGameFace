package com.google.projectgameface;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bound service that provides low-latency bi-directional communication
 * between the HeadBoard accessibility service and OpenBoard IME.
 */
public class HeadBoardService extends Service {
    private static final String TAG = "HeadBoardService";
    
    private final IBinder mBinder = new HeadBoardServiceBinder();
    private final RemoteCallbackList<IHeadBoardCallback> mCallbacks = new RemoteCallbackList<>();
    private CursorAccessibilityService mAccessibilityService;
    private boolean mIsConnected = false;
    
    /**
     * Binder class for the service
     */
    public class HeadBoardServiceBinder extends IHeadBoardService.Stub {
        @Override
        public void registerCallback(IHeadBoardCallback callback) throws RemoteException {
            if (callback != null) {
                mCallbacks.register(callback);
                Log.d(TAG, "Callback registered");
                
                // Notify the callback that it's connected
                callback.onConnectionChanged(true);
            }
        }
        
        @Override
        public void unregisterCallback(IHeadBoardCallback callback) throws RemoteException {
            if (callback != null) {
                mCallbacks.unregister(callback);
                Log.d(TAG, "Callback unregistered");
            }
        }
        
        @Override
        public void sendMotionEvent(float x, float y, int action, long downTime, long eventTime) throws RemoteException {
            Log.d(TAG, "Received motion event: (" + x + ", " + y + ", action=" + action + ")");
            
            if (mAccessibilityService != null) {
                mAccessibilityService.handleMotionEvent(x, y, action, downTime, eventTime);
            } else {
                Log.w(TAG, "Accessibility service not available");
            }
        }
        
        @Override
        public void sendKeyEvent(int keyCode, boolean isDown, boolean isLongPress) throws RemoteException {
            Log.d(TAG, "Received key event: keyCode=" + keyCode + ", isDown=" + isDown + ", isLongPress=" + isLongPress);
            
            if (mAccessibilityService != null) {
                mAccessibilityService.handleKeyEvent(keyCode, isDown, isLongPress);
            } else {
                Log.w(TAG, "Accessibility service not available");
            }
        }
        
        @Override
        public void setLongPressDelay(int delay) throws RemoteException {
            Log.d(TAG, "Setting long press delay: " + delay + "ms");
            
            if (mAccessibilityService != null) {
                mAccessibilityService.setLongPressDelay(delay);
            } else {
                Log.w(TAG, "Accessibility service not available");
            }
        }
        
        @Override
        public void setGestureTrailColor(int color) throws RemoteException {
            Log.d(TAG, "Setting gesture trail color: " + color);
            
            if (mAccessibilityService != null) {
                mAccessibilityService.setGestureTrailColor(color);
            } else {
                Log.w(TAG, "Accessibility service not available");
            }
        }
        
        @Override
        public void getKeyInfo(float x, float y) throws RemoteException {
            Log.d(TAG, "Getting key info at: (" + x + ", " + y + ")");
            
            if (mAccessibilityService != null) {
                mAccessibilityService.getKeyInfo(x, y);
            } else {
                Log.w(TAG, "Accessibility service not available");
            }
        }
        
        @Override
        public void getKeyBounds(int keyCode) throws RemoteException {
            Log.d(TAG, "Getting key bounds for keyCode: " + keyCode);
            
            if (mAccessibilityService != null) {
                mAccessibilityService.getKeyBounds(keyCode);
            } else {
                Log.w(TAG, "Accessibility service not available");
            }
        }
        
        @Override
        public void showOrHideKeyPopup(int x, int y, boolean showKeyPreview, boolean withAnimation, boolean isLongPress) throws RemoteException {
            Log.d(TAG, "Show/hide key popup: (" + x + ", " + y + "), show=" + showKeyPreview);
            
            if (mAccessibilityService != null) {
                mAccessibilityService.showOrHideKeyPopup(x, y, showKeyPreview, withAnimation, isLongPress);
            } else {
                Log.w(TAG, "Accessibility service not available");
            }
        }
        
        @Override
        public boolean isConnected() throws RemoteException {
            return mIsConnected && mAccessibilityService != null;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "HeadBoardService created");
        mIsConnected = true;
        
        // Try to get the accessibility service reference
        // This is a simplified approach - in a real implementation, you might need
        // a more sophisticated way to get the service reference
        try {
            // You might need to implement a singleton pattern or use a different approach
            // to get the accessibility service reference
            Log.d(TAG, "HeadBoardService created, accessibility service reference will be set later");
        } catch (Exception e) {
            Log.e(TAG, "Error getting accessibility service reference", e);
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "HeadBoardService bound");
        return mBinder;
    }
    
    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "HeadBoardService unbound");
        mIsConnected = false;
        return super.onUnbind(intent);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "HeadBoardService destroyed");
        mIsConnected = false;
    }
    
    /**
     * Set the accessibility service reference
     * @param accessibilityService The accessibility service instance
     */
    public void setAccessibilityService(CursorAccessibilityService accessibilityService) {
        mAccessibilityService = accessibilityService;
        Log.d(TAG, "Accessibility service set");
    }
    
    /**
     * Send key info to all registered callbacks
     * @param keyInfo The key information to send
     */
    public void sendKeyInfo(KeyInfo keyInfo) {
        final int count = mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onKeyInfo(keyInfo);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error sending key info to callback", e);
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
    }
    
    /**
     * Send key bounds to all registered callbacks
     * @param keyBounds The key bounds to send
     */
    public void sendKeyBounds(KeyBounds keyBounds) {
        final int count = mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onKeyBounds(keyBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error sending key bounds to callback", e);
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
    }
    
    /**
     * Send error to all registered callbacks
     * @param errorCode Error code
     * @param errorMessage Error message
     */
    public void sendError(int errorCode, String errorMessage) {
        final int count = mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onError(errorCode, errorMessage);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error sending error to callback", e);
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
    }
    
    /**
     * Notify all callbacks of connection status change
     * @param connected Connection status
     */
    public void notifyConnectionChanged(boolean connected) {
        mIsConnected = connected;
        final int count = mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onConnectionChanged(connected);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error notifying connection change", e);
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
    }
}
