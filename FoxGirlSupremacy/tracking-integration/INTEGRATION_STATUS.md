# FoxGirlSupremacy – Face tracking integration payload

This folder contains the Kotlin camera/detector implementation, shared data classes, MNN model assets and the available ARM64 MNN runtime libraries copied from the supplied `VirtualFaceCapture-MNN-master` archive. It also adds a neutral tracking-frame bridge and a first-pass ARKit-to-Live2D parameter mapper.

## What is ready
- Camera2 manager and MediaPipe detector Kotlin sources are staged under `source/`.
- Detector model files and `libMNN*.so` ARM64 libraries are staged under `assets/` and `native/jniLibs/`.
- `FaceTrackingFrame` and `Live2DTrackingMapper` define the data contract and initial parameter mapping.

## Why this is not yet a working APK
The supplied tracker archive does **not** include the compiled JNI library `libmediapipefacelandmark.so`. Its CMake project requires the MNN C++ headers at `ThirdParty/MNN/include`, but the ZIP only contains a `.gitmodules` pointer for that submodule; those headers are absent. The FoxGirl archive also references a local Cubism Java SDK/Core AAR path which is not included in that ZIP. Therefore an end-to-end build cannot be completed from these two archives alone, and the tracker cannot be loaded at runtime until its JNI library is built.

## Integration steps still required
1. Restore the exact Cubism SDK/Core AAR used by this FoxGirl checkout.
2. Populate `ThirdParty/MNN` with the MNN source matching the supplied `libMNN.so` version, then build `libmediapipefacelandmark.so` for `arm64-v8a`.
3. Move the Kotlin sources into the app source set (preserving packages) and add the camera permission/runtime permission flow.
4. Start/stop `Camera2Manager` with the overlay service lifecycle, feed frames into `MediaPipeFaceDetector`, call `predictBlendshapes`, and forward `FaceTrackingFrame` to the GL render thread.
5. Apply `Live2DTrackingMapper.map()` values to the model's Cubism parameters on the GL thread. Parameter IDs/ranges must be checked against the user's actual model.

No existing overlay or Live2D renderer files were replaced. This payload deliberately keeps the existing app intact while staging the tracker code and bridge.
