# AIDL Service Implementation for HeadBoard-OpenBoard Communication

This document describes the AIDL-based bound service implementation for low-latency bi-directional communication between the HeadBoard accessibility service and the OpenBoard IME.

## Overview

The implementation provides a high-performance communication channel between two Android applications using Android Interface Definition Language (AIDL). This replaces the previous broadcast-based communication with a more efficient bound service approach.

## Architecture

### Components

1. **AIDL Interface Files** (`IHeadBoardService.aidl`, `IHeadBoardCallback.aidl`)
   - Define the communication contract between apps
   - Located in both projects' `aidl` directories

2. **Data Classes** (`KeyInfo.java`, `KeyBounds.java`)
   - Parcelable classes for data transfer
   - Shared between both applications

3. **HeadBoard Service** (`HeadBoardService.java`)
   - Bound service implementation in HeadBoard app
   - Handles incoming requests from OpenBoard IME
   - Forwards requests to accessibility service

4. **Service Connection** (`HeadBoardServiceConnection.java`)
   - Client-side connection management in OpenBoard
   - Handles service binding and callback registration

5. **Updated IME** (`LatinIME.java`)
   - Modified to use bound service instead of broadcast receiver
   - Maintains backward compatibility with broadcast receiver

6. **Updated Accessibility Service** (`CursorAccessibilityService.java`)
   - Added methods to handle service communication
   - Integrates with existing gesture and key event handling

## File Structure

```
Android/app/src/main/
├── aidl/com/google/projectgameface/
│   ├── IHeadBoardService.aidl
│   ├── IHeadBoardCallback.aidl
│   ├── KeyInfo.aidl
│   └── KeyBounds.aidl
├── java/com/google/projectgameface/
│   ├── HeadBoardService.java
│   ├── KeyInfo.java
│   ├── KeyBounds.java
│   └── HeadBoardServiceTest.java
└── AndroidManifest.xml (updated)

openboard/app/src/main/
├── aidl/com/google/projectgameface/
│   ├── IHeadBoardService.aidl
│   ├── IHeadBoardCallback.aidl
│   ├── KeyInfo.aidl
│   └── KeyBounds.aidl
├── java/com/google/projectgameface/
│   ├── KeyInfo.java
│   └── KeyBounds.java
├── java/org/dslul/openboard/
│   └── HeadBoardServiceConnection.java
├── java/org/dslul/openboard/inputmethod/latin/
│   └── LatinIME.java (updated)
└── AndroidManifest.xml (updated)
```

## Key Features

### Bi-directional Communication
- **HeadBoard → OpenBoard**: Motion events, key events, gesture trail colors
- **OpenBoard → HeadBoard**: Key information, key bounds, popup control

### Low Latency
- Direct method calls instead of broadcast intents
- Synchronous communication for immediate feedback
- Asynchronous callbacks for non-blocking operations

### Error Handling
- Connection status monitoring
- Error reporting through callbacks
- Graceful fallback to broadcast receiver

### Backward Compatibility
- Maintains existing broadcast receiver functionality
- Gradual migration path
- Fallback mechanism if service is unavailable

## API Methods

### IHeadBoardService Interface

```java
// Connection management
void registerCallback(IHeadBoardCallback callback);
void unregisterCallback(IHeadBoardCallback callback);
boolean isConnected();

// Event handling
void sendMotionEvent(float x, float y, int action, long downTime, long eventTime);
void sendKeyEvent(int keyCode, boolean isDown, boolean isLongPress);
void setLongPressDelay(int delay);
void setGestureTrailColor(int color);

// Information requests
void getKeyInfo(float x, float y);
void getKeyBounds(int keyCode);
void showOrHideKeyPopup(int x, int y, boolean showKeyPreview, boolean withAnimation, boolean isLongPress);
```

### IHeadBoardCallback Interface

```java
void onKeyInfo(KeyInfo keyInfo);
void onKeyBounds(KeyBounds keyBounds);
void onConnectionChanged(boolean connected);
void onError(int errorCode, String errorMessage);
```

## Data Classes

### KeyInfo
```java
public class KeyInfo implements Parcelable {
    public String label;
    public int keyCode;
    public float x, y;
    public int width, height;
    public boolean isVisible;
}
```

### KeyBounds
```java
public class KeyBounds implements Parcelable {
    public int left, top, right, bottom;
    public int keyCode;
}
```

## Usage

### In OpenBoard (Client)

```java
// Initialize service connection
HeadBoardServiceConnection connection = new HeadBoardServiceConnection(context, listener);
connection.connect();

// Send motion event
connection.sendMotionEvent(x, y, action, downTime, eventTime);

// Send key event
connection.sendKeyEvent(keyCode, isDown, isLongPress);

// Disconnect when done
connection.disconnect();
```

### In HeadBoard (Service)

```java
// Service automatically handles incoming requests
// Methods in CursorAccessibilityService handle the actual functionality
public void handleMotionEvent(float x, float y, int action, long downTime, long eventTime) {
    // Process motion event
}

public void handleKeyEvent(int keyCode, boolean isDown, boolean isLongPress) {
    // Process key event
}
```

## Permissions

Both applications require the following permissions:

```xml
<uses-permission android:name="android.permission.BIND_SERVICE" />
<uses-permission android:name="android.permission.INTERACT_ACROSS_USERS" />
```

## Testing

Use the `HeadBoardServiceTest` class to verify the service communication:

```java
HeadBoardServiceTest test = new HeadBoardServiceTest(context);
test.startTest(); // Connects and runs tests
test.stopTest();  // Disconnects
```

## Benefits

1. **Performance**: Direct method calls are faster than broadcast intents
2. **Reliability**: Bound services provide better error handling and connection management
3. **Type Safety**: AIDL provides compile-time type checking
4. **Scalability**: Easy to add new methods and data types
5. **Debugging**: Better logging and error reporting

## Migration Notes

- The implementation maintains backward compatibility with the existing broadcast receiver
- Both communication methods can coexist during transition
- The service connection is preferred when available
- Fallback to broadcast receiver ensures continued functionality

## Future Enhancements

1. **Connection Pooling**: Support multiple IME connections
2. **Event Batching**: Batch multiple events for better performance
3. **Compression**: Compress large data transfers
4. **Encryption**: Secure sensitive data transmission
5. **Metrics**: Add performance monitoring and analytics
