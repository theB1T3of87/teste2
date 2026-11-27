# Face tracking — Live2D Vtuber Studio

## Active implementation

The app's active tracker is `app/src/main/java/com/foxgirlsupremacy/companion/FaceTrackingController.kt`. It uses CameraX `ImageAnalysis` and Google's MediaPipe Tasks Vision `FaceLandmarker` in `LIVE_STREAM` mode with the bundled `face_landmarker.task` model.

`OverlayService.updateFaceTracking()` starts and stops this controller with the overlay service lifecycle. Results are converted to `FaceTrackingData` and sent to `Live2DRenderer.setFaceTrackingData()`. The native Cubism model applies head angles, eye openness, gaze, mouth form and mouth opening after its motion/physics updates.

Frames are processed locally. `STRATEGY_KEEP_ONLY_LATEST` and the in-flight guard prevent a backlog of stale camera frames. The front camera is used; frames are rotated upright and intentionally not mirrored before inference.

## MediaPipe outputs and Cubism parameters

| MediaPipe output | Cubism parameter |
|---|---|
| Facial transformation matrix | `ParamAngleX`, `ParamAngleY`, `ParamAngleZ` |
| `eyeBlinkLeft`, `eyeBlinkRight` | `ParamEyeLOpen`, `ParamEyeROpen` |
| `eyeLook*` blendshapes | `ParamEyeBallX`, `ParamEyeBallY` |
| `jawOpen` | `ParamMouthOpenY` |
| `mouthSmile*`, `mouthFrown*` | `ParamMouthForm` |

The app requests one face and enables both face blendshapes and the facial transformation matrix. Smoothing is applied before the values reach Cubism. The Cubism model must expose the conventional parameter IDs for each movement to be visible.

## Separate experimental integration

The top-level `tracking-integration/` directory contains a separate MNN-based MediaPipe-compatible integration payload. It is not the active camera path used by `OverlayService`; do not treat its Kotlin/JNI sources as wired into the app unless the app source set and lifecycle are explicitly migrated.

## Android permissions

The face-tracking switch requires the `CAMERA` permission. The overlay service declares the `specialUse|camera` foreground-service types and `FOREGROUND_SERVICE_CAMERA`. Camera use must be started while Android permits camera access from a foreground service.

## Build

Requirements:

- JDK 17 or newer
- Android SDK Platform 35
- Android NDK `26.3.11579264`
- CMake `3.22.1`
- Gradle wrapper distribution `8.14.3`

Build a debug APK with:

```bash
./gradlew :app:assembleDebug
```

Expected output: `app/build/outputs/apk/debug/app-debug.apk`.

Runtime tracking quality still needs to be tested on the target phone with the actual Live2D model.