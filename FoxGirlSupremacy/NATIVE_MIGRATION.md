# Live2D Cubism Native (C++/NDK) integration

The Android app renders Live2D through the bundled Cubism Native 5-r.5 SDK using JNI and a dedicated EGL/OpenGL ES 2 render thread.

## Project layout

`CubismSdkForNative-5-r.5/` is kept inside this repository so a clean GitHub checkout contains everything required by CMake. Do not move it outside the repository.

- `app/src/main/cpp/`: JNI bridge and app-specific Cubism sample adaptation.
- `CubismSdkForNative-5-r.5/Core/`: official Cubism Core headers and Android static libraries.
- `CubismSdkForNative-5-r.5/Framework/`: Cubism Framework sources.
- `CubismSdkForNative-5-r.5/Samples/`: sample common code and stb image decoder.
- `app/src/main/assets/live2d/`: bundled sample model.
- Imported models are copied to the app's private `files/live2d/` directory at runtime.

## Build requirements

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android NDK 26.3.11579264
- CMake 3.22.1

Build locally from the repository root:

```bash
bash ./gradlew --no-daemon clean assembleDebug
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The `Android Debug APK` GitHub Actions workflow installs the pinned Android toolchain, validates the Cubism archives, builds the debug APK, and uploads it as a workflow artifact. It runs on pushes to `main`/`master` and can also be started manually from the Actions tab.

## Supported native ABIs

- `arm64-v8a`
- `x86_64`

The CMake configuration fails early if the matching Cubism Core archive or stb decoder is missing.

## Validation performed in this workspace

- ZIP extraction and project structure inspection: passed.
- XML resources and manifest parsing: passed.
- Bundled JSON parsing: passed.
- Bundled Hiyori model file references: all 17 referenced files found.
- Cubism Core static archives: present for both configured ABIs.
- CMake configure/generate with the host CMake: passed. This checks the CMake graph only; it is **not** an Android NDK compile.
- Gradle `assembleDebug`: blocked before Gradle configuration because this workspace cannot resolve `services.gradle.org` (DNS `UnknownHostException`).
- Android SDK/NDK are not installed in this workspace, so no Android `.so`, APK, installation, or device runtime test has been produced here.

Do not treat CMake configure as proof that the Android app compiles or runs. Use the GitHub Actions artifact or a local Android SDK/NDK environment for the full build.
