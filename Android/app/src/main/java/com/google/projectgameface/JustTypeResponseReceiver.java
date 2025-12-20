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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver that handles responses from JustType IME.
 * 
 * This receiver listens for response broadcasts from JustType and matches them
 * to pending requests using the BroadcastHelper utility.
 * 
 * Response broadcasts from JustType should use the action:
 * "com.justtype.nativeapp.RESPONSE"
 * 
 * And should include EXTRA_RESPONSE_ID matching a request_id from a pending request.
 */
public class JustTypeResponseReceiver extends BroadcastReceiver {
    private static final String TAG = "JustTypeResponseReceiver";
    public static final String ACTION_JUSTTYPE_RESPONSE = "com.justtype.nativeapp.RESPONSE";
    
    private final BroadcastHelper broadcastHelper;
    
    public JustTypeResponseReceiver(BroadcastHelper broadcastHelper) {
        this.broadcastHelper = broadcastHelper;
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_JUSTTYPE_RESPONSE.equals(intent.getAction())) {
            return;
        }
        
        Log.d(TAG, "Received response from JustType");
        
        // Handle the response using BroadcastHelper
        boolean handled = broadcastHelper.handleResponse(intent);
        if (!handled) {
            Log.w(TAG, "Received response but no matching pending request found");
        }
    }
}

