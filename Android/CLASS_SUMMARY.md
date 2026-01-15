# Project GameFace - Class Summary / Cheat Sheet

This document provides a quick reference guide for all classes in the Project GameFace Android app.

## Core Service & Accessibility

### `CursorAccessibilityService`
**Purpose**: Main accessibility service that runs in the background. Handles face tracking, cursor control, gesture recognition, and system-wide touch event injection.
- Extends `AccessibilityService` to inject gestures/touches
- Manages camera feed and face detection
- Coordinates cursor movement and event dispatching
- Handles service state (ENABLE, DISABLE, PAUSE, GLOBAL_STICK)
- Processes blendshape events and triggers actions

### `HeadBoardService` ⚠️ **UNUSED**
**Purpose**: Bound service providing low-latency bi-directional communication between the accessibility service and OpenBoard IME (keyboard).
- Handles motion events and key events from keyboard
- Manages callbacks for keyboard integration
- **Status**: Currently unused - declared in manifest but never started/bound in production code
- Only referenced in `HeadBoardServiceTest` (test class)

---

## Main Activities

### `MainActivity`
**Purpose**: Main entry point and settings hub for the app.
- Profile management (add/remove/switch profiles)
- Service toggle (enable/disable GameFace)
- Permission requests (camera, accessibility)
- Navigation to other settings activities
- First launch tutorial flow

### `HeadBoardSettings`
**Purpose**: Advanced cursor movement and gesture settings activity.
- Cursor movement configuration (speed, smoothing, direct mapping)
- Real-time swipe settings
- Path cursor configuration
- Keyboard switching
- Debugging stats access
- Edge hold duration, drag toggle settings
- **Note**: Replaces the deprecated `CursorSpeed` activity - consolidates all cursor and movement settings in one place

### `CursorSpeed` ⚠️ **DEPRECATED/UNUSED**
**Purpose**: Activity for adjusting cursor movement speed in different directions.
- Individual speed controls for up/down/left/right
- Blendshape sensitivity settings
- Gesture hold delay configuration
- **Status**: Replaced by `HeadBoardSettings`. Still declared in manifest and referenced in `MainActivity`, but functionality has been moved to `HeadBoardSettings`

### `CursorBinding`
**Purpose**: Activity for mapping facial gestures (blendshapes) to actions.
- Maps facial expressions to events (tap, swipe, home, back, etc.)
- Configures which blendshape triggers which action
- Shows current bindings and allows reconfiguration

### `CalibrationActivity`
**Purpose**: Activity for calibrating head movement ranges.
- Measures min/max pitch and yaw values
- Visual feedback with progress bars
- Sets movement thresholds for cursor control

### `TutorialActivity`
**Purpose**: Onboarding tutorial for new users.
- Introduces app features
- Guides through initial setup

### `TutorialPhoneStandActivity`
**Purpose**: Tutorial specifically for phone stand setup.

### `GrantPermissionActivity`
**Purpose**: Helper activity to guide users to grant required permissions.

### `DebuggingStatsActivity`
**Purpose**: Activity displaying debugging statistics and performance metrics.

### `ChooseGestureActivity`
**Purpose**: Activity for selecting which gesture to configure.

### `GestureSizeActivity`
**Purpose**: Activity for adjusting gesture size/sensitivity thresholds.

---

## Core Logic & Control

### `CursorController`
**Purpose**: Core controller for cursor movement calculation and state management.
- Calculates cursor position from face coordinates
- Manages velocity and smoothing
- Handles edge detection and boundary constraints
- Tracks keyboard bounds and navigation bar
- Manages blendshape event triggering
- Implements path cursor logic
- Handles drag operations and touch states

### `FaceLandmarkerHelper`
**Purpose**: Handles MediaPipe face detection and landmark processing.
- Processes camera frames through MediaPipe
- Extracts face landmarks, blendshapes, and head pose (pitch/yaw)
- Calculates head position coordinates
- Manages face detection lifecycle (init, pause, resume, destroy)
- Runs on background thread for performance

### `DispatchEventHelper`
**Purpose**: Helper class that dispatches events based on blendshape triggers.
- Routes events to appropriate handlers (tap, swipe, touch, etc.)
- Handles different event types (CURSOR_TAP, SWIPE_LEFT, HOME, BACK, etc.)
- Coordinates with accessibility service for gesture injection

### `GestureStreamController`
**Purpose**: Controller for streaming continuous touch gestures.
- Manages high-frequency gesture streaming for continuous touch operations
- Handles gesture queueing and dispatch to avoid duplicate gestures
- Used by `CursorAccessibilityService` for continuous touch and keyboard gesture handling

### `BlendshapeEventTriggerConfig`
**Purpose**: Configuration manager for event trigger mappings (facial gestures, Bluetooth switches, and other input sources).
- Maps triggers to actions/events (tap, swipe, home, back, etc.)
- Supports multiple trigger types:
  - **GESTURE**: Facial gestures (blendshapes like open mouth, raise eyebrow, etc.)
  - **KEY_EVENT**: Bluetooth switches (KEYCODE_1, KEYCODE_2, KEYCODE_3), joysticks, keyboards, controllers
  - **INTERNAL**: Events triggered by internal app logic
- Manages trigger thresholds and sensitivity
- Persists bindings to SharedPreferences
- Supports profile-specific configurations

### `CursorMovementConfig`
**Purpose**: Configuration manager for cursor movement parameters.
- Stores speed, smoothing, and movement settings
- Manages boolean flags (realtime swipe, direct mapping, etc.)
- Handles profile-specific configurations
- Provides default values and validation

---

## UI Components

### `ServiceUiManager`
**Purpose**: Manages all floating UI elements shown by the accessibility service.
- Floating camera preview window (minimize/maximize)
- Cursor view positioning and visibility
- Path cursor view management
- Full-screen canvas for drag lines and touch indicators
- Status icons (face found, paused, etc.)
- Window animations and positioning

### `CursorView`
**Purpose**: Custom view that displays the cursor on screen.
- Animated cursor with color states (white, green, red, orange)
- Circular cursor with center dot
- Color transitions and animations
- Show/hide animations

### `FullScreenCanvas`
**Purpose**: Full-screen overlay for drawing drag lines and touch feedback.
- Draws drag lines during drag operations
- Shows touch circles at tap locations
- Displays hold radius circles
- Can show preview bitmaps

### `CameraBoxOverlay`
**Purpose**: Overlay view on top of camera preview showing debug info.
- Displays frame processing times
- Shows head position indicator (white dot)
- Debug text overlay
- Pause indicator

---

## Keyboard Integration

The app integrates with keyboards, primarily **OpenBoard IME** (a custom keyboard designed for head-controlled input). See the "OpenBoard IME Integration" section below for more details.

### `KeyboardManager`
**Purpose**: Manages keyboard detection and interaction with OpenBoard and other keyboards.
- Detects keyboard bounds and visibility
- Identifies keyboard type (GBoard, OpenBoard)
- Checks if events can be injected at coordinates
- Sends motion/key events to OpenBoard IME via broadcasts
- Manages key popup display and highlighting
- Handles keyboard-specific features for head-controlled typing

### `KeyboardEventReceiver`
**Purpose**: BroadcastReceiver for keyboard events from OpenBoard IME.
- Receives swipe start events from OpenBoard
- Handles long press animations
- Processes keyboard state changes
- **Note**: Currently uses broadcast receivers, not AIDL

### AIDL Interfaces ⚠️ **UNUSED**
The following AIDL interfaces are defined but not used in production code:
- `IHeadBoardService.aidl` - Service interface for AIDL communication
- `IHeadBoardCallback.aidl` - Callback interface for service events
- `KeyInfo.aidl` - Data class for keyboard key information (Parcelable)
- `KeyBounds.aidl` - Data class for keyboard key bounds (Parcelable)
- **Status**: These appear to be prepared for future use but currently unused. Keyboard integration uses `KeyboardEventReceiver` (broadcasts) instead.

---

## Camera & Media

### `CameraHelper`
**Purpose**: Utility class for camera setup and configuration.
- Binds camera preview to PreviewView
- Configures camera resolution and aspect ratio
- Checks front camera orientation
- Sets up ImageAnalysis pipeline

---

## Profile & Configuration

### `ProfileManager`
**Purpose**: Manages user profiles for different configurations.
- Creates, removes, and switches profiles
- Stores profile list in SharedPreferences
- Each profile has separate settings storage

---

## Utilities

### `CursorUtils` (utils package)
**Purpose**: Utility functions for cursor-related operations.
- Creates swipe gestures
- Helper methods for gesture construction

### `Config` (utils package, Kotlin)
**Purpose**: Constants and default configuration values.
- Contains default settings, file paths, and configuration constants
- Used throughout the app for configuration values

### `Colors` (utils package, Kotlin)
**Purpose**: Color definitions for UI elements.

### `DebuggingStats` (utils package, Kotlin)
**Purpose**: Tracks and stores debugging statistics.
- Used by `KeyboardManager` and `DebuggingStatsActivity` to track typing performance metrics
- Implements `TimestampAware` interface for automatic timestamp updates

### `Session` (utils package, Kotlin)
**Purpose**: Session management and tracking.
- Used by `DebuggingStats` to track typing sessions and calculate session-based statistics

### `SwipePoint` (utils package, Kotlin) ⚠️ **UNUSED**
**Purpose**: Data class for swipe point coordinates with timestamp.
- **Status**: Defined but never used in production code

### `WordSwiped` (utils package, Kotlin)
**Purpose**: Data class for tracking swiped words.
- Used by `DebuggingStats` to track swiped words and calculate statistics

### `ActionStatus` (utils package, Kotlin) ⚠️ **UNUSED**
**Purpose**: Enum/class for action status tracking.
- **Status**: Defined but never used in production code

### `TimestampDelegate` (utils package, Kotlin)
**Purpose**: Delegate for timestamp management.
- Used by `DebuggingStats` (which implements `TimestampAware`) to automatically update timestamps

### `WriteToFile` (utils package, Kotlin)
**Purpose**: Utility for writing data to files.
- Used by `CursorAccessibilityService` and `DebuggingStatsActivity` for logging and stats persistence

### `OpenBoardKeyCodes` (utils package, Kotlin) ⚠️ **UNUSED**
**Purpose**: Key code constants for OpenBoard keyboard.
- **Status**: Defined but never used in production code. `Config.kt` contains the same constants and is used instead

---

## Testing

### `HeadBoardServiceTest`
**Purpose**: Test class for HeadBoardService functionality.
- **Note**: Tests the unused HeadBoardService and AIDL interfaces

---

## Architecture Overview

```
MainActivity (Entry Point)
    ↓
CursorAccessibilityService (Background Service)
    ├── FaceLandmarkerHelper (Face Detection)
    ├── CursorController (Cursor Logic)
    ├── ServiceUiManager (UI Management)
    ├── KeyboardManager (Keyboard Integration)
    └── DispatchEventHelper (Event Routing)
        ↓
    BlendshapeEventTriggerConfig (Gesture Mapping)
    CursorMovementConfig (Movement Settings)
    ProfileManager (Profile Management)
```

---

## Key Data Flow

1. **Face Detection**: Camera → `FaceLandmarkerHelper` → Face coordinates/blendshapes
2. **Cursor Movement**: Face coordinates → `CursorController` → Cursor position
3. **Event Triggers** (multiple input sources):
   - **Facial Gestures**: Blendshapes → `BlendshapeEventTriggerConfig` → Event type
   - **Bluetooth Switches**: Key events (KEYCODE_1, KEYCODE_2, KEYCODE_3, etc.) → `BlendshapeEventTriggerConfig` → Event type
   - **Other Input Sources**: Joysticks, keyboards, controllers, etc. (via KEY_EVENT trigger type)
   - **Internal Actions**: App logic can trigger events internally (INTERNAL trigger type)
   - **Note**: Some trigger types/actions are defined but not yet fully implemented
4. **Event Dispatch**: Event type → `DispatchEventHelper` → Accessibility gesture
5. **UI Update**: Cursor position → `ServiceUiManager` → `CursorView` update

---

## OpenBoard IME Integration

**OpenBoard** is a custom IME (Input Method Editor) keyboard located in the `openboard/` directory. It's designed to work alongside the main Project GameFace app for advanced head-controlled typing features.

### Integration Points

- **Purpose**: Provides real-time gesture typing and advanced keyboard features optimized for head-controlled input
- **Communication**: The main app communicates with OpenBoard via:
  - **Broadcast Receivers**: `KeyboardEventReceiver` receives events from OpenBoard (swipe start, long press animations, keyboard state changes)
  - **Broadcast Intents**: `KeyboardManager` sends motion events, key events, and UI updates to OpenBoard via broadcasts
  - **Package Name**: `org.dslul.openboard.inputmethod.latin`
- **Features**: 
  - Real-time gesture trail visualization
  - Custom key highlighting and popup management
  - Swipe gesture recognition optimized for head control
  - Long press delay configuration
  - Keyboard state synchronization

### Note
The OpenBoard codebase is separate and extensive. This summary focuses only on the main Project GameFace app and its integration points with OpenBoard, not the internal implementation of OpenBoard itself.

---

## Notes

- Most settings are profile-specific and stored in SharedPreferences
- The app uses MediaPipe for face detection (face_landmarker.task model)
- Accessibility service is required for system-wide gesture injection
- **Unused/Deprecated Components**: 
  - `CursorSpeed` - deprecated, replaced by `HeadBoardSettings` (still in manifest but functionality moved)
  - `HeadBoardService` and AIDL interfaces (`IHeadBoardService`, `IHeadBoardCallback`, `KeyInfo`, `KeyBounds`) - defined but not used in production code
  - `SwipePoint` (utils) - data class defined but never used
  - `ActionStatus` (utils) - enum defined but never used
  - `OpenBoardKeyCodes` (utils) - duplicate of `Config.kt`, not used (Config is used instead)
  - These appear to be experimental/prepared for future use or legacy code
- OpenBoard keyboard integration currently uses broadcast receivers (`KeyboardEventReceiver`) rather than AIDL
- Path cursor is an alternative cursor mode that follows a predicted path

