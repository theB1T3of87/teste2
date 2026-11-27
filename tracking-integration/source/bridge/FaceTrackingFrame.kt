package com.foxgirlsupremacy.companion.tracking

/** Immutable tracking sample. Blendshape indices follow the ARKit 52 order
 * used by VirtualFaceCapture-MNN. Angles are degrees. */
data class FaceTrackingFrame(
    val blendShapes: FloatArray = FloatArray(52),
    val pitch: Float = 0f,
    val yaw: Float = 0f,
    val roll: Float = 0f,
    val confidence: Float = 0f,
    val timestampNanos: Long = System.nanoTime()
) {
    fun value(index: Int): Float = blendShapes.getOrElse(index) { 0f }.coerceIn(0f, 1f)
    val isValid: Boolean get() = confidence >= 0.5f
}
