package org.dslul.openboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.google.projectgameface.IHeadBoardCallback;
import com.google.projectgameface.IHeadBoardService;
import com.google.projectgameface.KeyBounds;
import com.google.projectgameface.KeyInfo;

/**
 * Service connection class for connecting to the HeadBoard service
 */
public class HeadBoardServiceConnection implements ServiceConnection {
    private static final String TAG = "HeadBoardServiceConnection";
    
    private IHeadBoardService mService;
    private boolean mIsConnected = false;
    private Context mContext;
    private HeadBoardServiceListener mListener;
    
    public interface HeadBoardServiceListener {
        void onServiceConnected();
        void onServiceDisconnected();
        void onKeyInfo(KeyInfo keyInfo);
        void onKeyBounds(KeyBounds keyBounds);
        void onError(int errorCode, String errorMessage);
    }
    
    private final IHeadBoardCallback.Stub mCallback = new IHeadBoardCallback.Stub() {
        @Override
        public void onKeyInfo(KeyInfo keyInfo) throws RemoteException {
            Log.d(TAG, "Received key info: " + keyInfo);
            if (mListener != null) {
                mListener.onKeyInfo(keyInfo);
            }
        }
        
        @Override
        public void onKeyBounds(KeyBounds keyBounds) throws RemoteException {
            Log.d(TAG, "Received key bounds: " + keyBounds);
            if (mListener != null) {
                mListener.onKeyBounds(keyBounds);
            }
        }
        
        @Override
        public void onConnectionChanged(boolean connected) throws RemoteException {
            Log.d(TAG, "Connection changed: " + connected);
            mIsConnected = connected;
            if (mListener != null) {
                if (connected) {
                    mListener.onServiceConnected();
                } else {
                    mListener.onServiceDisconnected();
                }
            }
        }
        
        @Override
        public void onError(int errorCode, String errorMessage) throws RemoteException {
            Log.e(TAG, "Received error: " + errorCode + " - " + errorMessage);
            if (mListener != null) {
                mListener.onError(errorCode, errorMessage);
            }
        }
    };
    
    public HeadBoardServiceConnection(Context context, HeadBoardServiceListener listener) {
        mContext = context;
        mListener = listener;
    }
    
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        Log.d(TAG, "Service connected: " + name);
        mService = IHeadBoardService.Stub.asInterface(service);
        mIsConnected = true;
        
        try {
            // Register our callback
            mService.registerCallback(mCallback);
            Log.d(TAG, "Callback registered with service");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register callback", e);
        }
        
        if (mListener != null) {
            mListener.onServiceConnected();
        }
    }
    
    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.d(TAG, "Service disconnected: " + name);
        mService = null;
        mIsConnected = false;
        
        if (mListener != null) {
            mListener.onServiceDisconnected();
        }
    }
    
    /**
     * Connect to the HeadBoard service
     */
    public void connect() {
        if (mIsConnected) {
            Log.d(TAG, "Already connected to service");
            return;
        }
        
        Intent intent = new Intent("com.google.projectgameface.HeadBoardService");
        intent.setPackage("com.google.projectgameface");
        
        boolean bound = mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
        if (bound) {
            Log.d(TAG, "Service bind request sent");
        } else {
            Log.e(TAG, "Failed to bind to service");
        }
    }
    
    /**
     * Disconnect from the HeadBoard service
     */
    public void disconnect() {
        if (mService != null) {
            try {
                mService.unregisterCallback(mCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister callback", e);
            }
        }
        
        mContext.unbindService(this);
        mService = null;
        mIsConnected = false;
        Log.d(TAG, "Disconnected from service");
    }
    
    /**
     * Send a motion event to the HeadBoard service
     */
    public void sendMotionEvent(float x, float y, int action, long downTime, long eventTime) {
        if (mService == null || !mIsConnected) {
            Log.w(TAG, "Service not connected, cannot send motion event");
            return;
        }
        
        try {
            mService.sendMotionEvent(x, y, action, downTime, eventTime);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send motion event", e);
        }
    }
    
    /**
     * Send a key event to the HeadBoard service
     */
    public void sendKeyEvent(int keyCode, boolean isDown, boolean isLongPress) {
        if (mService == null || !mIsConnected) {
            Log.w(TAG, "Service not connected, cannot send key event");
            return;
        }
        
        try {
            mService.sendKeyEvent(keyCode, isDown, isLongPress);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send key event", e);
        }
    }
    
    /**
     * Set the long press delay
     */
    public void setLongPressDelay(int delay) {
        if (mService == null || !mIsConnected) {
            Log.w(TAG, "Service not connected, cannot set long press delay");
            return;
        }
        
        try {
            mService.setLongPressDelay(delay);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set long press delay", e);
        }
    }
    
    /**
     * Set the gesture trail color
     */
    public void setGestureTrailColor(int color) {
        if (mService == null || !mIsConnected) {
            Log.w(TAG, "Service not connected, cannot set gesture trail color");
            return;
        }
        
        try {
            mService.setGestureTrailColor(color);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set gesture trail color", e);
        }
    }
    
    /**
     * Get key information at specific coordinates
     */
    public void getKeyInfo(float x, float y) {
        if (mService == null || !mIsConnected) {
            Log.w(TAG, "Service not connected, cannot get key info");
            return;
        }
        
        try {
            mService.getKeyInfo(x, y);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get key info", e);
        }
    }
    
    /**
     * Get key bounds for a specific key code
     */
    public void getKeyBounds(int keyCode) {
        if (mService == null || !mIsConnected) {
            Log.w(TAG, "Service not connected, cannot get key bounds");
            return;
        }
        
        try {
            mService.getKeyBounds(keyCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get key bounds", e);
        }
    }
    
    /**
     * Show or hide key popup
     */
    public void showOrHideKeyPopup(int x, int y, boolean showKeyPreview, boolean withAnimation, boolean isLongPress) {
        if (mService == null || !mIsConnected) {
            Log.w(TAG, "Service not connected, cannot show/hide key popup");
            return;
        }
        
        try {
            mService.showOrHideKeyPopup(x, y, showKeyPreview, withAnimation, isLongPress);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to show/hide key popup", e);
        }
    }
    
    /**
     * Check if the service is connected
     */
    public boolean isConnected() {
        return mIsConnected && mService != null;
    }
}
