package com.foxgirlsupremacy.companion

import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Converts MediaPipe's column-major 4x4 facial transformation matrix into
 * Cubism head angles. The matrix rotation is decomposed in Y-X-Z order:
 * horizontal turn (Y), vertical nod (X), and head tilt (Z).
 */
internal object FacePoseMath {
    fun fromColumnMajor(matrix: FloatArray): Triple<Float, Float, Float> {
        if (matrix.size < 16) return Triple(0f, 0f, 0f)

        // Column-major indices: r02=8, r12=9, r10=1, r11=5, r22=10.
        // For Y-X-Z decomposition, these entries independently recover the
        // three axes. The previous implementation used r21/r22 for pitch,
        // which is zero for a pure X rotation and made nodding unresponsive.
        val r02 = matrix[8]
        val r12 = matrix[9]
        val r10 = matrix[1]
        val r11 = matrix[5]
        val r22 = matrix[10]

        val pitchX = asin((-r12).coerceIn(-1f, 1f))
        val yawY = atan2(r02, r22)
        val rollZ = atan2(r10, r11)
        val toDegrees = 180f / Math.PI.toFloat()

        val angleX = (-yawY * toDegrees * 1.35f).coerceIn(-30f, 30f)
        val angleY = (-pitchX * toDegrees * 1.30f).coerceIn(-25f, 25f)
        val angleZ = (-rollZ * toDegrees * 1.15f).coerceIn(-30f, 30f)
        return Triple(angleX, angleY, angleZ)
    }
}
