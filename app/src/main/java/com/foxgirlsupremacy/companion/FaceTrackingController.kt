package com.foxgirlsupremacy.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Real-time Face Landmarker adapter.
 *
 * The input image is rotated to its upright sensor orientation but is NOT mirrored.
 * MediaPipe's Left/Right blendshapes describe the subject, and mirroring would swap them.
 */
class FaceTrackingController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onTracking: (FaceTrackingData?) -> Unit,
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val inferenceInFlight = AtomicBoolean(false)

    @Volatile private var stopped = false
    @Volatile private var landmarker: FaceLandmarker? = null
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var smoothedTrackingData: FaceTrackingData? = null
    @Volatile private var lastFaceAtMs = 0L
    // Reference pose captured from the first valid face frame; tracking is relative to it.
    @Volatile private var neutralHeadRotation: FloatArray? = null
    @Volatile private var neutralLandmarkRotation: FloatArray? = null
    @Volatile private var inputFrameWidth = 640
    @Volatile private var inputFrameHeight = 480
    private var lastPoseLogAtMs = 0L
    private var lastTimestampMs = 0L

    fun start() {
        if (stopped || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            publishTracking(null)
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        cameraExecutor.execute {
            try {
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath("face_landmarker.task")
                            .build(),
                    )
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.55f)
                    .setMinFacePresenceConfidence(0.55f)
                    .setMinTrackingConfidence(0.55f)
                    .setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true)
                    .setResultListener { result, input ->
                        try {
                            if (!stopped) publishTracking(result.toTrackingData())
                        } catch (error: Exception) {
                            Log.e(TAG, "Could not map Face Landmarker result", error)
                            publishTracking(null)
                        } finally {
                            input.close()
                            inferenceInFlight.set(false)
                        }
                    }
                    .setErrorListener { error ->
                        Log.e(TAG, "Face Landmarker failed", error)
                        inferenceInFlight.set(false)
                        if (!stopped) publishTracking(null)
                    }
                    .build()

                val created = FaceLandmarker.createFromOptions(context, options)
                if (stopped) {
                    created.close()
                    return@execute
                }
                landmarker = created

                providerFuture.addListener({
                    if (stopped) return@addListener
                    try {
                        val provider = providerFuture.get()
                        cameraProvider = provider
                        // Pin CameraX's target to the current display orientation. CameraX
                        // reports the remaining sensor-to-target rotation in ImageInfo.
                        @Suppress("DEPRECATION")
                        val targetRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .defaultDisplay.rotation
                        val analysis = ImageAnalysis.Builder()
                            .setTargetRotation(targetRotation)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setTargetResolution(android.util.Size(640, 480))
                            .build()
                        analysis.setAnalyzer(cameraExecutor, ::analyze)
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            analysis,
                        )
                        Log.i(TAG, "CameraX bound: front camera, targetRotation=$targetRotation, format=RGBA_8888")
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not bind front camera", error)
                        publishTracking(null)
                    }
                }, mainExecutor)
            } catch (error: Exception) {
                Log.e(TAG, "Could not initialize Face Landmarker", error)
                mainExecutor.execute { if (!stopped) publishTracking(null) }
            }
        }
    }

    private fun analyze(proxy: ImageProxy) {
        if (stopped || !inferenceInFlight.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        var mpImage: MPImage? = null
        try {
            val bitmap = proxy.toUprightBitmap()
            inputFrameWidth = bitmap.width
            inputFrameHeight = bitmap.height
            mpImage = BitmapImageBuilder(bitmap).build()
            val now = SystemClock.uptimeMillis()
            val timestamp = maxOf(now, lastTimestampMs + 1L)
            lastTimestampMs = timestamp
            val activeLandmarker = landmarker
            if (activeLandmarker == null) {
                inferenceInFlight.set(false)
                mpImage.close()
                mpImage = null
                publishTracking(null)
            } else {
                activeLandmarker.detectAsync(mpImage, timestamp)
                // The result listener closes the MPImage when asynchronous inference completes.
                mpImage = null
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not submit camera frame", error)
            mpImage?.close()
            inferenceInFlight.set(false)
            publishTracking(null)
        } finally {
            proxy.close()
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        cameraProvider?.unbindAll()
        cameraProvider = null
        landmarker?.close()
        landmarker = null
        cameraExecutor.shutdown()
        smoothedTrackingData = null
        lastFaceAtMs = 0L
        neutralHeadRotation = null
        neutralLandmarkRotation = null
        onTracking(null)
    }

    /**
     * Keep the native tracking switch active through short detector dropouts. Otherwise
     * Cubism's idle motion starts moving the head as soon as one frame is missed.
     */
    @Synchronized
    private fun publishTracking(data: FaceTrackingData?) {
        val now = SystemClock.uptimeMillis()
        val target = if (data == null) {
            val previous = smoothedTrackingData
            if (previous != null && now - lastFaceAtMs < LOST_FACE_HOLD_MS) {
                onTracking(previous)
                return
            }
            // Neutral-but-enabled data prevents idle animation from taking control while
            // the camera and Face Landmarker are still running but no face is detected.
            FaceTrackingData.NEUTRAL
        } else {
            lastFaceAtMs = now
            data
        }

        val previous = smoothedTrackingData
        val filtered = if (previous == null) target else FaceTrackingData(
            angleX = lerp(previous.angleX, target.angleX, 0.42f),
            angleY = lerp(previous.angleY, target.angleY, 0.42f),
            angleZ = lerp(previous.angleZ, target.angleZ, 0.42f),
            eyeLOpen = smoothEye(previous.eyeLOpen, target.eyeLOpen),
            eyeROpen = smoothEye(previous.eyeROpen, target.eyeROpen),
            mouthForm = lerp(previous.mouthForm, target.mouthForm, 0.38f),
            mouthOpen = lerp(previous.mouthOpen, target.mouthOpen, 0.62f),
            gazeX = lerp(previous.gazeX, target.gazeX, 0.55f),
            gazeY = lerp(previous.gazeY, target.gazeY, 0.55f),
        )
        smoothedTrackingData = filtered
        onTracking(filtered)
    }

    private fun smoothEye(previous: Float, target: Float): Float {
        // Close quickly for a real blink, reopen smoothly, and ignore tiny score changes.
        val stableTarget = if (target > 0.94f) 1f else target.coerceIn(0f, 1f)
        val amount = if (stableTarget < previous) 0.78f else 0.46f
        return lerp(previous, stableTarget, amount)
    }

    private fun lerp(from: Float, to: Float, amount: Float): Float =
        from + (to - from) * amount

    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        val width = width
        val height = height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0 until width) {
                val offset = rowOffset + x * pixelStride
                val red = buffer.get(offset).toInt() and 0xFF
                val green = buffer.get(offset + 1).toInt() and 0xFF
                val blue = buffer.get(offset + 2).toInt() and 0xFF
                val alpha = buffer.get(offset + 3).toInt() and 0xFF
                pixels[y * width + x] = Color.argb(alpha, red, green, blue)
            }
        }

        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.setPixels(pixels, 0, width, 0, 0, width, height)
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return source

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, width, height, matrix, true).also {
            if (it !== source) source.recycle()
        }
    }

    private fun FaceLandmarkerResult.toTrackingData(): FaceTrackingData? {
        val detectedFaces = faceLandmarks()
        if (detectedFaces.isEmpty()) {
            val now = SystemClock.uptimeMillis()
            if (now - lastPoseLogAtMs >= DIAGNOSTIC_LOG_INTERVAL_MS) {
                Log.w(TAG, "MediaPipe callback has no face landmarks")
                lastPoseLogAtMs = now
            }
            return null
        }

        val categories = faceBlendshapes().orElse(emptyList()).firstOrNull().orEmpty()
        val scores = categories.associate { it.categoryName() to it.score() }
        fun score(name: String): Float = (scores[name] ?: 0f).coerceIn(0f, 1f)

        val landmarks = detectedFaces.first()
        val matrices = facialTransformationMatrixes().orElse(emptyList())
        val matrix = matrices.firstOrNull()
        val matrixAngles = matrix?.toHeadAngles()
        // The 4x4 pose matrix is the preferred signal. If the optional matrix is
        // absent/invalid, build a face-local 3D basis from stable mesh landmarks.
        val landmarkAngles = if (matrixAngles == null) landmarks.toHeadAnglesFromLandmarks() else null
        val (angleX, angleY, angleZ) = matrixAngles ?: landmarkAngles ?: Triple(0f, 0f, 0f)
        val poseSource = when {
            matrixAngles != null -> "matrix"
            landmarkAngles != null -> "landmarks-fallback"
            else -> "none"
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastPoseLogAtMs >= DIAGNOSTIC_LOG_INTERVAL_MS) {
            Log.i(TAG, "MediaPipe pose source=$poseSource, matrixSize=${matrix?.size ?: 0}, angles=($angleX,$angleY,$angleZ), landmarks=${landmarks.size}, blendshapes=${categories.size}")
            if (matrix != null) Log.d(TAG, "MediaPipe 4x4 column-major=${matrix.contentToString()}")
            lastPoseLogAtMs = now
        }

        val blinkLeft = score("eyeBlinkLeft")
        val blinkRight = score("eyeBlinkRight")
        val eyeLOpen = (1f - ((blinkLeft - BLINK_DEAD_ZONE) / BLINK_RESPONSE_RANGE))
            .coerceIn(0f, 1f)
        val eyeROpen = (1f - ((blinkRight - BLINK_DEAD_ZONE) / BLINK_RESPONSE_RANGE))
            .coerceIn(0f, 1f)

        val jawOpen = score("jawOpen")
        val mouthOpen = ((jawOpen - JAW_DEAD_ZONE) / JAW_RESPONSE_RANGE)
            .coerceIn(0f, 1f)
        val smile = score("mouthSmileLeft") + score("mouthSmileRight")
        val frown = score("mouthFrownLeft") + score("mouthFrownRight")
        val mouthForm = (smile - frown).coerceIn(-1f, 1f)

        // MediaPipe's Left/Right labels are subject-relative. Convert horizontal gaze
        // to the viewer's screen axis expected by Cubism's ParamEyeBallX.
        val gazeTowardSubjectRight = (score("eyeLookInLeft") + score("eyeLookOutRight")) * 0.5f
        val gazeTowardSubjectLeft = (score("eyeLookOutLeft") + score("eyeLookInRight")) * 0.5f
        val gazeX = ((gazeTowardSubjectLeft - gazeTowardSubjectRight) * 1.8f).coerceIn(-1f, 1f)
        val gazeY = (
            (score("eyeLookUpLeft") + score("eyeLookUpRight")) -
                (score("eyeLookDownLeft") + score("eyeLookDownRight"))
            ) * 0.9f
        return FaceTrackingData(
            angleX = angleX,
            angleY = angleY,
            angleZ = angleZ,
            eyeLOpen = eyeLOpen,
            eyeROpen = eyeROpen,
            mouthForm = mouthForm,
            mouthOpen = mouthOpen,
            gazeX = gazeX.coerceIn(-1f, 1f),
            gazeY = gazeY.coerceIn(-1f, 1f),
        )
    }

    /**
     * MediaPipe's Java FaceLandmarkerResult documents this as a flat column-major
     * 4x4 matrix. Its upper-left 3x3 block maps the canonical face orientation
     * to the detected face orientation.
     *
     * Capture the first valid orientation as the neutral pose, then extract the
     * relative rotation. This removes a permanent head-angle offset caused by
     * the user's natural resting pose and avoids treating the canonical model's
     * coordinate alignment as a movement.
     */
    @Synchronized
    private fun FloatArray.toHeadAngles(): Triple<Float, Float, Float>? {
        val current = toRotation3x3() ?: return null
        val neutral = neutralHeadRotation
        if (neutral == null) {
            neutralHeadRotation = current
            return Triple(0f, 0f, 0f)
        }
        return relativeRotationToHeadAngles(current, neutral)
    }

    /**
     * Fallback pose estimator used only when MediaPipe does not provide a valid
     * facialTransformationMatrix. It reconstructs the face-local axes from the
     * 3D landmarks (eye corners, forehead and chin), then uses the same neutral-
     * relative rotation and Cubism axis mapping as the official matrix path.
     *
     * NormalizedLandmark.z is scaled like image x and smaller values are nearer
     * the camera; negate it to use a face-front-positive local Z axis.
     */
    @Synchronized
    private fun List<NormalizedLandmark>.toHeadAnglesFromLandmarks(): Triple<Float, Float, Float>? {
        val current = toFaceRotation3x3() ?: return null
        val neutral = neutralLandmarkRotation
        if (neutral == null) {
            neutralLandmarkRotation = current
            return Triple(0f, 0f, 0f)
        }
        return relativeRotationToHeadAngles(current, neutral)
    }

    private fun relativeRotationToHeadAngles(
        current: FloatArray,
        neutral: FloatArray,
    ): Triple<Float, Float, Float> {
        // Both matrices are row-major 3x3 rotations. The relative camera-space
        // pose is current * inverse(neutral), and inverse(neutral) = transpose.
        val relative = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                var value = 0f
                for (k in 0..2) {
                    value += current[row * 3 + k] * neutral[column * 3 + k]
                }
                relative[row * 3 + column] = value
            }
        }

        // Decompose R = Rz(roll) * Ry(yaw) * Rx(pitch).
        val r00 = relative[0]
        val r10 = relative[3]
        val r20 = relative[6]
        val r21 = relative[7]
        val r22 = relative[8]

        val yaw = atan2(-r20, sqrt(r00 * r00 + r10 * r10))
        val pitch = atan2(r21, r22)
        val roll = atan2(r10, r00)
        val radiansToDegrees = 180f / Math.PI.toFloat()

        // MediaPipe canonical face front is +Z. Positive Y yaw points the nose
        // toward +X (screen-right), matching Cubism's positive ParamAngleX.
        val angleX = (yaw * radiansToDegrees * 1.35f).coerceIn(-30f, 30f)
        // Positive X pitch points the face downward in the right-handed basis,
        // while Cubism positive ParamAngleY means looking upward.
        val angleY = (-pitch * radiansToDegrees * 1.30f).coerceIn(-25f, 25f)
        // Positive Z roll is counter-clockwise in Y-up space; Cubism positive
        // ParamAngleZ is a clockwise screen-right tilt.
        val angleZ = (-roll * radiansToDegrees * 1.15f).coerceIn(-30f, 30f)
        return Triple(angleX, angleY, angleZ)
    }

    /**
     * Estimate a face-oriented rotation basis from MediaPipe's normalized 3D
     * mesh. We use pixel-scaled coordinates because x/z use image-width units,
     * while y is normalized by image height.
     */
    private fun List<NormalizedLandmark>.toFaceRotation3x3(): FloatArray? {
        if (size <= 263) return null

        fun point(index: Int): FloatArray {
            val landmark = get(index)
            return floatArrayOf(
                landmark.x() * inputFrameWidth,
                -landmark.y() * inputFrameHeight,
                -landmark.z() * inputFrameWidth,
            )
        }

        fun subtract(a: FloatArray, b: FloatArray) =
            floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

        fun dot(a: FloatArray, b: FloatArray) =
            a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        fun normalize(value: FloatArray): FloatArray? {
            val length = sqrt(dot(value, value))
            if (!length.isFinite() || length < 1e-5f) return null
            return floatArrayOf(value[0] / length, value[1] / length, value[2] / length)
        }

        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

        // 33 and 263 are the outer eye corners. 10 and 152 are forehead and chin.
        val right = normalize(subtract(point(263), point(33))) ?: return null
        val upRaw = subtract(point(10), point(152))
        val upOrthogonal = floatArrayOf(
            upRaw[0] - right[0] * dot(upRaw, right),
            upRaw[1] - right[1] * dot(upRaw, right),
            upRaw[2] - right[2] * dot(upRaw, right),
        )
        val up = normalize(upOrthogonal) ?: return null
        val forward = normalize(cross(right, up)) ?: return null
        val correctedUp = normalize(cross(forward, right)) ?: return null

        // Row-major matrix with the face's right, up and forward axes as columns.
        return floatArrayOf(
            right[0], correctedUp[0], forward[0],
            right[1], correctedUp[1], forward[1],
            right[2], correctedUp[2], forward[2],
        )
    }

    /**
     * Normalize the three basis columns before Euler extraction. MediaPipe's
     * facial transform may include scale; scale must not be mistaken for pose.
     * Returned values are row-major 3x3, despite the source being column-major.
     */
    private fun FloatArray.toRotation3x3(): FloatArray? {
        if (size < 16 || (0..10).any { !this[it].isFinite() }) return null

        val scaleX = sqrt(this[0] * this[0] + this[1] * this[1] + this[2] * this[2])
        val scaleY = sqrt(this[4] * this[4] + this[5] * this[5] + this[6] * this[6])
        val scaleZ = sqrt(this[8] * this[8] + this[9] * this[9] + this[10] * this[10])
        if (scaleX < 1e-6f || scaleY < 1e-6f || scaleZ < 1e-6f) return null

        val c0x = this[0] / scaleX
        val c0y = this[1] / scaleX
        val c0z = this[2] / scaleX
        val c1x = this[4] / scaleY
        val c1y = this[5] / scaleY
        val c1z = this[6] / scaleY
        val c2x = this[8] / scaleZ
        val c2y = this[9] / scaleZ
        val c2z = this[10] / scaleZ

        return floatArrayOf(
            c0x, c1x, c2x,
            c0y, c1y, c2y,
            c0z, c1z, c2z,
        )
    }

    data class FaceTrackingData(
        val angleX: Float,
        val angleY: Float,
        val angleZ: Float,
        val eyeLOpen: Float,
        val eyeROpen: Float,
        val mouthForm: Float,
        val mouthOpen: Float,
        val gazeX: Float,
        val gazeY: Float,
    ) {
        companion object {
            val NEUTRAL = FaceTrackingData(
                angleX = 0f,
                angleY = 0f,
                angleZ = 0f,
                eyeLOpen = 1f,
                eyeROpen = 1f,
                mouthForm = 0f,
                mouthOpen = 0f,
                gazeX = 0f,
                gazeY = 0f,
            )
        }
    }

    companion object {
        private const val TAG = "VTuberFaceTracking"
        private const val LOST_FACE_HOLD_MS = 650L
        private const val DIAGNOSTIC_LOG_INTERVAL_MS = 1_000L
        private const val BLINK_DEAD_ZONE = 0.10f
        private const val BLINK_RESPONSE_RANGE = 0.62f
        private const val JAW_DEAD_ZONE = 0.035f
        private const val JAW_RESPONSE_RANGE = 0.45f
    }
}

typealias FaceTrackingData = FaceTrackingController.FaceTrackingData
