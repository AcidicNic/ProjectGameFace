Android — HeadBoard Notes

What it is: Accessibility service that tracks face via MediaPipe and controls a cursor drawn on the display. While this can be used to navigate the device and do just about anything you could do by touching or tapping the touch screen with your finger, this app is primarily focused on swype/gesture typing using the on screen keyboard.

Current comms: Receive broadcasts from OpenBoard; AIDL exists in code but is not the source of truth yet.

Important components:
- `app/src/main/java/com/google/projectgameface/CursorAccessibilityService.java`
- `KeyboardEventReceiver` (manifest-registered)
- `ServiceUiManager`, `KeyboardManager`, `CursorController`

Build & run:
- `./gradlew :Android:app:assembleDebug`
- Install APK and enable the accessibility service.

Caution:
- Don’t remove broadcast receivers or custom permissions until AIDL path is fully validated.
- Be careful with `android:exported`, overlays, and camera permissions.


