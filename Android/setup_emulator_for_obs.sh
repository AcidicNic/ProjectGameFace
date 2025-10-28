#!/bin/bash
# Setup script for M3 Pro MacBook with OBS Virtual Camera

echo "Setting up Android Emulator for OBS Virtual Camera..."
echo ""

# 1. Create a new AVD optimized for x86_64 and M3 Pro
echo "Creating new x86_64 AVD..."
avdmanager create avd -n "GameFace_Emulator" \
  -k "system-images;android-34;google_apis_playstore;x86_64" \
  -d "pixel_6" \
  -c 2048M

if [ $? -eq 0 ]; then
  echo "✅ AVD created successfully"
  echo ""
  echo "Configuring the AVD..."
  
  # The config.ini will be created in:
  AVD_PATH="$HOME/.android/avd/GameFace_Emulator.avd"
  
  # Wait for AVD to be created
  sleep 2
  
  if [ -f "$AVD_PATH/config.ini" ]; then
    # Update config for virtual camera
    sed -i '' 's/^hw.camera.front=.*/hw.camera.front=virtualscene/' "$AVD_PATH/config.ini"
    sed -i '' 's/^hw.accelerometer=yes/hw.accelerometer=yes/' "$AVD_PATH/config.ini"
    sed -i '' 's/^hw.gpu.enabled=yes/hw.gpu.enabled=yes/' "$AVD_PATH/config.ini"
    sed -i '' 's/^hw.gpu.mode=.*/hw.gpu.mode=host/' "$AVD_PATH/config.ini"
    
    echo "✅ AVD configured for virtual camera"
  fi
else
  echo "❌ Failed to create AVD. You may need to install the system image first:"
  echo "   sdkmanager 'system-images;android-34;google_apis_playstore;x86_64'"
fi

echo ""
echo "================================="
echo "Next steps:"
echo "1. Start OBS and enable Virtual Camera"
echo "2. In OBS, add your physical camera as a source"
echo "3. Start the emulator: emulator -avd GameFace_Emulator"
echo "4. The emulator will use the OBS Virtual Camera"
echo "================================="

