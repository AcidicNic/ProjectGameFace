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

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for managing request-response broadcast communication patterns.
 * 
 * This helper enables two-way communication via broadcasts by:
 * 1. Generating unique request IDs for each request
 * 2. Tracking pending requests with callbacks
 * 3. Matching responses to requests and invoking callbacks
 * 4. Handling timeouts for requests that don't receive responses
 * 
 * Usage:
 * - Call sendRequest() to send a request and register a callback
 * - Register a response receiver that calls handleResponse() when a response is received
 * - Responses should include the request ID in the response intent
 */
public class BroadcastHelper {
    private static final String TAG = "BroadcastHelper";
    
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_RESPONSE_ID = "response_id";
    private static final long DEFAULT_TIMEOUT_MS = 5000L; // 5 seconds default timeout
    
    public interface ResponseCallback {
        void onResponse(Intent responseIntent);
    }
    
    private static class PendingRequest {
        final String requestId;
        final ResponseCallback callback;
        final Handler timeoutHandler;
        final Runnable timeoutRunnable;
        
        PendingRequest(String requestId, ResponseCallback callback, Handler timeoutHandler, Runnable timeoutRunnable) {
            this.requestId = requestId;
            this.callback = callback;
            this.timeoutHandler = timeoutHandler;
            this.timeoutRunnable = timeoutRunnable;
        }
    }
    
    private final Context context;
    private final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    public BroadcastHelper(Context context) {
        this.context = context;
    }
    
    /**
     * Generate a unique request ID
     */
    public String generateRequestId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Send a request broadcast with a callback for the response.
     * 
     * @param intent The intent to send (will have request ID added automatically)
     * @param callback Callback to invoke when response is received (null if timeout)
     * @param timeoutMs Timeout in milliseconds (default: 5000ms)
     * @return The generated request ID
     */
    public String sendRequest(Intent intent, ResponseCallback callback, long timeoutMs) {
        String requestId = generateRequestId();
        intent.putExtra(EXTRA_REQUEST_ID, requestId);
        
        // Create timeout runnable
        Runnable timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                PendingRequest request = pendingRequests.remove(requestId);
                if (request != null) {
                    // Timeout occurred - callback with null
                    Log.w(TAG, "Request timeout for requestId: " + requestId);
                    callback.onResponse(null);
                }
            }
        };
        
        Handler timeoutHandler = new Handler(Looper.getMainLooper());
        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs);
        
        // Store pending request
        pendingRequests.put(requestId, new PendingRequest(
            requestId,
            callback,
            timeoutHandler,
            timeoutRunnable
        ));
        
        // Send the broadcast
        context.sendBroadcast(intent);
        
        return requestId;
    }
    
    /**
     * Send a request broadcast with default timeout (5 seconds)
     */
    public String sendRequest(Intent intent, ResponseCallback callback) {
        return sendRequest(intent, callback, DEFAULT_TIMEOUT_MS);
    }
    
    /**
     * Handle a response broadcast by matching it to a pending request.
     * 
     * @param responseIntent The response intent (should contain response_id matching a request_id)
     * @return true if the response was matched to a pending request, false otherwise
     */
    public boolean handleResponse(Intent responseIntent) {
        String responseId = responseIntent.getStringExtra(EXTRA_RESPONSE_ID);
        if (responseId == null) {
            return false;
        }
        
        PendingRequest request = pendingRequests.remove(responseId);
        if (request == null) {
            return false;
        }
        
        // Cancel timeout
        request.timeoutHandler.removeCallbacks(request.timeoutRunnable);
        
        // Invoke callback
        request.callback.onResponse(responseIntent);
        
        return true;
    }
    
    /**
     * Cancel a pending request (useful for cleanup)
     * 
     * @param requestId The request ID to cancel
     * @return true if the request was found and cancelled, false otherwise
     */
    public boolean cancelRequest(String requestId) {
        PendingRequest request = pendingRequests.remove(requestId);
        if (request == null) {
            return false;
        }
        request.timeoutHandler.removeCallbacks(request.timeoutRunnable);
        return true;
    }
    
    /**
     * Cancel all pending requests (useful for cleanup on destroy)
     */
    public void cancelAllRequests() {
        for (PendingRequest request : pendingRequests.values()) {
            request.timeoutHandler.removeCallbacks(request.timeoutRunnable);
        }
        pendingRequests.clear();
    }
    
    /**
     * Get the number of pending requests
     */
    public int getPendingRequestCount() {
        return pendingRequests.size();
    }
}

