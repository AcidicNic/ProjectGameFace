OpenBoard — IME Notes

What it is: Modified OpenBoard keyboard that cooperates with HeadBoard.

Current comms: Send broadcasts to HeadBoard. AIDL client hooks exist but are not authoritative yet.

Important components:
- `app/src/main/java/org/dslul/openboard/inputmethod/latin/LatinIME.java`
- `app/src/main/java/org/dslul/openboard/IMEEventReceiver.java`
- `app/src/main/java/org/dslul/openboard/inputmethod/keyboard/MainKeyboardView.java`
- `app/src/main/java/org/dslul/openboard/inputmethod/keyboard/PointerTracker.java`

Build & run:
- `./gradlew :openboard:app:assembleDebug`
- Install APK and select OpenBoard as the active input method.

Caution:
- Keep broadcast sends compatible with HeadBoard permissions and actions.
- Don’t remove broadcast code paths until AIDL is proven in production.


