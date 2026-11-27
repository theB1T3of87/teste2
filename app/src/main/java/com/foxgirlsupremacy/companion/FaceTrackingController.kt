package com.foxgirlsupremacy.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceContour
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** CameraX + ML Kit adapter. Frames are processed on-device and never persisted. */
class FaceTrackingController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onTracking: (FaceTrackingData?) -> Unit,
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build(),
    )
    @Volatile private var stopped = false
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var smoothedTrackingData: FaceTrackingData? = null
    private var lastValidTrackingAtMs = 0L

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (stopped) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Exception) {
                onTracking(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null || stopped) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                publishTracking(face?.toTrackingData(input.width, input.height))
            }
            .addOnFailureListener { publishTracking(null) }
            .addOnCompleteListener { proxy.close() }
    }

    fun stop() {
        stopped = true
        cameraProvider?.unbindAll()
        detector.close()
        cameraExecutor.shutdown()
        smoothedTrackingData = null
        lastValidTrackingAtMs = 0L
        onTracking(null)
    }

    /** Smooth frame-to-frame detector noise without making blinks feel sluggish. */
    private fun publishTracking(data: FaceTrackingData?) {
        if (data == null) {
            val previous = smoothedTrackingData
            // Brief detector dropouts should not hand control back to idle animation.
            if (previous != null && SystemClock.uptimeMillis() - lastValidTrackingAtMs < 350L) {
                onTracking(previous)
                return
            }
            smoothedTrackingData = null
            onTracking(null)
            return
        }
        lastValidTrackingAtMs = SystemClock.uptimeMillis()
        val previous = smoothedTrackingData
        val smoothed = if (previous == null) data else FaceTrackingData(
            angleX = lerp(previous.angleX, data.angleX, 0.35f),
            angleY = lerp(previous.angleY, data.angleY, 0.35f),
            angleZ = lerp(previous.angleZ, data.angleZ, 0.35f),
            eyeLOpen = lerp(previous.eyeLOpen, data.eyeLOpen, 0.65f),
            eyeROpen = lerp(previous.eyeROpen, data.eyeROpen, 0.65f),
            mouthForm = lerp(previous.mouthForm, data.mouthForm, 0.35f),
            mouthOpen = lerp(previous.mouthOpen, data.mouthOpen, 0.55f),
            faceX = lerp(previous.faceX, data.faceX, 0.35f),
            faceY = lerp(previous.faceY, data.faceY, 0.35f),
        )
        smoothedTrackingData = smoothed
        onTracking(smoothed)
    }

    private fun lerp(from: Float, to: Float, amount: Float): Float =
        from + (to - from) * amount

    private fun Face.toTrackingData(frameWidth: Int, frameHeight: Int): FaceTrackingData {
        val box = boundingBox
        val centerX = ((box.centerX() / frameWidth.toFloat()) * 2f - 1f).coerceIn(-1f, 1f)
        val centerY = (1f - (box.centerY() / frameHeight.toFloat()) * 2f).coerceIn(-1f, 1f)
        val upperLip = getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty()
        val lowerLip = getContour(FaceContour.LOWER_LIP_TOP)?.points.orEmpty()
        val upperY = upperLip.map { it.y }.average()
        val lowerY = lowerLip.map { it.y }.average()
        val mouthGap = if (upperLip.isNotEmpty() && lowerLip.isNotEmpty() && upperY.isFinite() && lowerY.isFinite()) {
            (lowerY - upperY).coerceAtLeast(0.0).toFloat()
        } else 0f
        val mouthOpen = (mouthGap / box.height().coerceAtLeast(1) * 22f).coerceIn(0f, 1f)
        return FaceTrackingData(
            angleX = (-headEulerAngleY).coerceIn(-30f, 30f),
            angleY = (-headEulerAngleX).coerceIn(-25f, 25f),
            angleZ = (-headEulerAngleZ).coerceIn(-30f, 30f),
            eyeLOpen = leftEyeOpenProbability ?: 0.8f,
            eyeROpen = rightEyeOpenProbability ?: 0.8f,
            mouthForm = (smilingProbability ?: 0f).coerceIn(0f, 1f),
            mouthOpen = mouthOpen,
            faceX = centerX,
            faceY = centerY,
        )
    }
}

data class FaceTrackingData(
    val angleX: Float,
    val angleY: Float,
    val angleZ: Float,
    val eyeLOpen: Float,
    val eyeROpen: Float,
    val mouthForm: Float,
    val mouthOpen: Float,
    val faceX: Float,
    val faceY: Float,
)
