#!/bin/bash
echo "Available cameras on your Mac:"
echo "================================"
system_profiler SPCameraDataType | grep -A 3 "Camera"

echo ""
echo "To find the exact camera name for OBS Virtual Camera:"
echo "Run this command:"
echo '   system_profiler SPCameraDataType | grep -A 3 "Camera"'
echo ""
echo "Common camera names:"
echo "- Built-in camera: FaceTime HD Camera"
echo "- OBS Virtual Camera: OBS Virtual Camera"
echo "- Other sources: check in System Settings > Camera"

