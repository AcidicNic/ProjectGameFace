# Project Gameface
Project Gameface helps gamers control their mouse cursor using their head movement and facial gestures.


Project Gameface available in two platforms:
- [Windows](/Windows/)
- [Android](/Android/)


# Model used
MediaPipe Face Landmark Detection API [Task Guide](https://developers.google.com/mediapipe/solutions/vision/face_landmarker)  
[MediaPipe BlazeFace Model Card](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf)  
[MediaPipe FaceMesh Model Card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf)  
[Mediapipe Blendshape V2 Model Card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf)  


# Application
- Control mouse cursor in games.
- Intended users are people who choose to use face-control and head movement for gaming purposes.

# Out-of-Scope Applications
* This project is not intended for human life-critical decisions 
* Predicted face landmarks do not provide facial recognition or identification and do not store any unique face representation.


---

if having trouble building openboard on a machine with Apple Silicon. (error is something about the NDK version (v21) not recognizing arm64 host machine)

modify the following file:
- `/Users/<your_username>/Library/Android/sdk/ndk/21.3.6528147/ndk-build`
	- - OR -
- `</path/to/your/android/sdk>/ndk/21.3.6528147/ndk-build`

so that it looks like this:

```
#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
arch -x86_64 /bin/bash $DIR/build/ndk-build "$@"
```