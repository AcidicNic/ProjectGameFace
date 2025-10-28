#!/bin/bash
# Configuration for using OBS Virtual Camera with Android Emulator on M3 Pro Mac

# For macOS, the OBS Virtual Camera appears as a standard camera
# The emulator will use it automatically when configured properly

echo "OBS Virtual Camera Configuration"
echo "================================"
echo ""
echo "Your OBS Virtual Camera is already installed!"
echo ""
echo "To use it with the emulator:"
echo ""
echo "1. In OBS Studio:"
echo "   - Add your physical camera as a source"
echo "   - Click 'Start Virtual Camera' in OBS"
echo ""
echo "2. The emulator config is already set to:"
echo "   hw.camera.front=webcam1"
echo ""
echo "   On macOS, 'webcam1' maps to the first available camera,"
echo "   which will be the OBS Virtual Camera if it's running."
echo ""
echo "3. To test which camera is being used:"
echo "   - Open the emulator"
echo "   - Open the Camera app in the emulator"
echo "   - You should see your OBS Virtual Camera feed"
echo ""
echo "4. Alternative: Explicitly set the camera:"
echo "   Edit: ~/.android/avd/Medium_Phone_API_35_2.avd/config.ini"
echo "   Change: hw.camera.front=virtualscene"
echo ""
echo "5. If issues persist, list available cameras:"
echo "   avdmanager list avd"
echo "   adb devices"
echo ""
echo "Note: ARM64 emulators work well on M3 Pro Mac!"

