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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
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
            .enableTracking()
            .setMinFaceSize(0.12f)
            .build(),
    )
    @Volatile private var stopped = false
    private var cameraProvider: ProcessCameraProvider? = null

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
                onTracking(face?.toTrackingData(input.width, input.height))
            }
            .addOnFailureListener { onTracking(null) }
            .addOnCompleteListener { proxy.close() }
    }

    fun stop() {
        stopped = true
        cameraProvider?.unbindAll()
        detector.close()
        cameraExecutor.shutdown()
        onTracking(null)
    }

    private fun Face.toTrackingData(frameWidth: Int, frameHeight: Int): FaceTrackingData {
        val box = boundingBox
        val centerX = ((box.centerX() / frameWidth.toFloat()) * 2f - 1f).coerceIn(-1f, 1f)
        val centerY = (1f - (box.centerY() / frameHeight.toFloat()) * 2f).coerceIn(-1f, 1f)
        return FaceTrackingData(
            angleX = (-headEulerAngleY).coerceIn(-30f, 30f),
            angleY = (-headEulerAngleX).coerceIn(-25f, 25f),
            angleZ = (-headEulerAngleZ).coerceIn(-30f, 30f),
            eyeLOpen = leftEyeOpenProbability ?: 0.8f,
            eyeROpen = rightEyeOpenProbability ?: 0.8f,
            mouthForm = (smilingProbability ?: 0f).coerceIn(0f, 1f),
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
    val faceX: Float,
    val faceY: Float,
)
