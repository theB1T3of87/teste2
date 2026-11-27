package com.foxgirlsupremacy.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
                        val analysis = ImageAnalysis.Builder()
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
        if (faceLandmarks().isEmpty()) return null

        val categories = faceBlendshapes().orElse(emptyList()).firstOrNull().orEmpty()
        val scores = categories.associate { it.categoryName() to it.score() }
        fun score(name: String): Float = (scores[name] ?: 0f).coerceIn(0f, 1f)

        val matrix = facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
        val (angleX, angleY, angleZ) = matrix?.let(FacePoseMath::fromColumnMajor) ?: Triple(0f, 0f, 0f)

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
        private const val BLINK_DEAD_ZONE = 0.10f
        private const val BLINK_RESPONSE_RANGE = 0.62f
        private const val JAW_DEAD_ZONE = 0.035f
        private const val JAW_RESPONSE_RANGE = 0.45f
    }
}

typealias FaceTrackingData = FaceTrackingController.FaceTrackingData
