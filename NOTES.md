Project Gameface — Root Notes

Scope: Android HeadBoard app in `Android/` and OpenBoard IME in `openboard/`. Windows is deprecated.

Integration: Use BROADCAST intents today; AIDL bound service is planned later. Keep broadcasts authoritative until AIDL ships.

Build:
- HeadBoard: `./gradlew :Android:app:assembleDebug`
- OpenBoard: `./gradlew :openboard:app:assembleDebug`

Install:
- `adb install -r Android/app/build/outputs/apk/debug/app-debug.apk`
- `adb install -r openboard/app/build/outputs/apk/debug/app-debug.apk`

Enable services:
- Turn on HeadBoard accessibility service; select OpenBoard as IME.

Key files:
- HeadBoard: `CursorAccessibilityService.java`, manifests, broadcasts
- OpenBoard: `LatinIME.java`, `IMEEventReceiver.java`, `PointerTracker.java`

Broadcasts observed: `com.headswype.ACTION_SEND_EVENT`, `com.headswype.ACTION_SWIPE_START`, `com.headswype.ACTION_LONGPRESS_ANIMATION`.
Permissions: `com.headboard.permission.SEND_EVENT`, `com.headboard.permission.UPDATE_CURSOR`.


