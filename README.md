# Live2D Vtuber Studio

An Android Live2D VTuber companion that renders an imported Cubism model in a floating overlay above other apps.

## Features

- Native Live2D Cubism rendering through the Cubism SDK for Native 5 R5.
- Import and switch between user-provided `.model3.json` models.
- Floating overlay with touch interactions, movement, animations and expressions.
- Optional front-camera face tracking using **Google MediaPipe Tasks Vision Face Landmarker**.
- Local face processing; camera frames are not saved or uploaded by the app.

## Face tracking

The active implementation is `app/src/main/java/com/foxgirlsupremacy/companion/FaceTrackingController.kt`. It uses CameraX ImageAnalysis and MediaPipe Face Landmarker in LIVE_STREAM mode. It maps facial transformation matrix angles, eye-blink blendshapes, eye gaze, jaw opening and smile/frown blendshapes into conventional Cubism parameters.

The separate `tracking-integration/` directory contains an experimental MNN-based integration payload. It is not the active tracking path currently started by `OverlayService`.

See [docs/FACE_TRACKING.md](docs/FACE_TRACKING.md) for the active pipeline and parameter mapping.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 35
- Android NDK `26.3.11579264`
- CMake `3.22.1`

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Expected output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions can build the APK using the **Build Live2D Vtuber Studio APK** workflow. Run it manually from Actions or push to the configured branch. The APK is uploaded as the `Live2D-Vtuber-Studio-APK` artifact.

## SDK notices

The Cubism SDK files retain their upstream license and notice files under `FoxGirlSupremacy/CubismSdkForNative-5-r.5/`. The default Hiyori model remains subject to Live2D's sample-model terms.

## License

Project code is under the MIT License; see [LICENSE](LICENSE). Third-party SDKs, libraries and sample assets retain their own licenses.
