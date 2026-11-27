# Face tracking — Android + Cubism Native SDK

## Architecture

- `FaceTrackingController.kt`: CameraX `ImageAnalysis` + ML Kit Face Detection.
- `OverlayService` is a `LifecycleService`, so CameraX can bind to the service lifecycle.
- `Live2DRenderer.kt` owns a transparent `TextureView` and an EGL/OpenGL ES 2 render thread.
- `NativeCubismBridge.kt` exposes the Java/Kotlin callbacks used by the Native SDK and the JNI entry points.
- `app/src/main/cpp/JniBridgeC.cpp` connects the Android host to the official Cubism Native Framework.
- `LAppModel.cpp` applies the tracked pose to the Cubism model after motion/physics updates.

The pipeline stays on-device: camera frames are processed locally, are not saved, and are not uploaded. `STRATEGY_KEEP_ONLY_LATEST` avoids queuing old frames when analysis falls behind.

## Cubism parameters

| Measurement | Cubism parameter |
|---|---|
| Horizontal head rotation | `ParamAngleX` |
| Vertical head rotation | `ParamAngleY` |
| Head roll | `ParamAngleZ` |
| Left/right eye openness | `ParamEyeLOpen`, `ParamEyeROpen` |
| Approximate face position / gaze | `ParamEyeBallX`, `ParamEyeBallY` |
| Smile probability | `ParamMouthForm` |

Missing parameters are ignored by the Cubism model. Eye and smile values are ML Kit classifications and work best when the face is nearly frontal. Smile probability is not a measurement of mouth opening. Face position is only an approximation of gaze direction.

## Android permissions

The Face tracking switch requests `CAMERA`. The service declares `specialUse|camera` foreground-service types and `FOREGROUND_SERVICE_CAMERA`. Camera use must be started while the app is in a state permitted by Android's foreground-service camera restrictions.

## Native SDK and build

This project now uses the uploaded **Cubism SDK for Native 5 R5** package, not the Java SDK. The Android NDK builds `libFoxCubism.so` through CMake and links the SDK's static Core library for the selected ABI. The SDK source and notices are kept under `CubismSdkForNative-5-r.5/`.

Build requirements:

- JDK 17 or newer
- Android Studio / Android SDK Platform 35
- Android NDK `26.3.11579264`
- CMake `3.22.1`
- Gradle wrapper distribution `8.14.3`

Build a debug APK with:

```bash
./gradlew assembleDebug
```

Expected output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The build and runtime still need verification on a machine with the Android SDK/NDK installed and network access for the Gradle distribution and Maven dependencies.
