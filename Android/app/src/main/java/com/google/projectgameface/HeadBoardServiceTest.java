package com.google.projectgameface;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Test class to verify HeadBoard service communication
 */
public class HeadBoardServiceTest {
    private static final String TAG = "HeadBoardServiceTest";
    
    private IHeadBoardService mService;
    private boolean mIsConnected = false;
    private Context mContext;
    
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Test service connected: " + name);
            mService = IHeadBoardService.Stub.asInterface(service);
            mIsConnected = true;
            
            // Test the service methods
            testServiceMethods();
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Test service disconnected: " + name);
            mService = null;
            mIsConnected = false;
        }
    };
    
    private final IHeadBoardCallback.Stub mCallback = new IHeadBoardCallback.Stub() {
        @Override
        public void onKeyInfo(KeyInfo keyInfo) throws RemoteException {
            Log.d(TAG, "Test received key info: " + keyInfo);
        }
        
        @Override
        public void onKeyBounds(KeyBounds keyBounds) throws RemoteException {
            Log.d(TAG, "Test received key bounds: " + keyBounds);
        }
        
        @Override
        public void onConnectionChanged(boolean connected) throws RemoteException {
            Log.d(TAG, "Test connection changed: " + connected);
        }
        
        @Override
        public void onError(int errorCode, String errorMessage) throws RemoteException {
            Log.e(TAG, "Test received error: " + errorCode + " - " + errorMessage);
        }
    };
    
    public HeadBoardServiceTest(Context context) {
        mContext = context;
    }
    
    /**
     * Start the test by connecting to the service
     */
    public void startTest() {
        Log.d(TAG, "Starting HeadBoard service test");
        
        Intent intent = new Intent("com.google.projectgameface.HeadBoardService");
        intent.setPackage("com.google.projectgameface");
        
        boolean bound = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (bound) {
            Log.d(TAG, "Test service bind request sent");
        } else {
            Log.e(TAG, "Test failed to bind to service");
        }
    }
    
    /**
     * Stop the test by disconnecting from the service
     */
    public void stopTest() {
        if (mService != null) {
            try {
                mService.unregisterCallback(mCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Test failed to unregister callback", e);
            }
        }
        
        mContext.unbindService(mConnection);
        mService = null;
        mIsConnected = false;
        Log.d(TAG, "Test service disconnected");
    }
    
    /**
     * Test the service methods
     */
    private void testServiceMethods() {
        if (mService == null || !mIsConnected) {
            Log.w(TAG, "Test service not connected, skipping tests");
            return;
        }
        
        try {
            // Register callback
            mService.registerCallback(mCallback);
            Log.d(TAG, "Test callback registered");
            
            // Test connection status
            boolean connected = mService.isConnected();
            Log.d(TAG, "Test service connected: " + connected);
            
            // Test motion event
            mService.sendMotionEvent(100.0f, 200.0f, 0, System.currentTimeMillis(), System.currentTimeMillis());
            Log.d(TAG, "Test motion event sent");
            
            // Test key event
            mService.sendKeyEvent(KeyEvent.KEYCODE_A, true, false);
            Log.d(TAG, "Test key event sent");
            
            // Test long press delay
            mService.setLongPressDelay(500);
            Log.d(TAG, "Test long press delay set");
            
            // Test gesture trail color
            mService.setGestureTrailColor(0xFF00FF00); // Green
            Log.d(TAG, "Test gesture trail color set");
            
            // Test key info request
            mService.getKeyInfo(150.0f, 250.0f);
            Log.d(TAG, "Test key info request sent");
            
            // Test key bounds request
            mService.getKeyBounds(KeyEvent.KEYCODE_A);
            Log.d(TAG, "Test key bounds request sent");
            
            // Test key popup
            mService.showOrHideKeyPopup(100, 200, true, true, false);
            Log.d(TAG, "Test key popup request sent");
            
            Log.d(TAG, "All test methods completed successfully");
            
        } catch (RemoteException e) {
            Log.e(TAG, "Test failed with remote exception", e);
        }
    }
}
