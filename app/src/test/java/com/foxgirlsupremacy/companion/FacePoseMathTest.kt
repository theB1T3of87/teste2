package com.foxgirlsupremacy.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FacePoseMathTest {
    private fun yxz(yawY: Float, pitchX: Float, rollZ: Float): FloatArray {
        val cy = cos(yawY); val sy = sin(yawY)
        val cx = cos(pitchX); val sx = sin(pitchX)
        val cz = cos(rollZ); val sz = sin(rollZ)

        // Row-major 3x3 for R = Ry * Rx * Rz, placed into a column-major 4x4.
        val r00 = cy * cz + sy * sx * sz
        val r01 = -cy * sz + sy * sx * cz
        val r02 = sy * cx
        val r10 = cx * sz
        val r11 = cx * cz
        val r12 = -sx
        val r20 = -sy * cz + cy * sx * sz
        val r21 = sy * sz + cy * sx * cz
        val r22 = cy * cx
        return floatArrayOf(
            r00, r10, r20, 0f,
            r01, r11, r21, 0f,
            r02, r12, r22, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    @Test fun neutralMatrixProducesNeutralPose() {
        assertEquals(Triple(0f, 0f, 0f), FacePoseMath.fromColumnMajor(yxz(0f, 0f, 0f)))
    }

    @Test fun horizontalTurnMapsToCubismAngleX() {
        val result = FacePoseMath.fromColumnMajor(yxz(Math.toRadians(20.0).toFloat(), 0f, 0f))
        assertEquals(-27f, result.first, 0.15f)
        assertEquals(0f, result.second, 0.15f)
        assertEquals(0f, result.third, 0.15f)
    }

    @Test fun verticalNodMapsToCubismAngleY() {
        val result = FacePoseMath.fromColumnMajor(yxz(0f, Math.toRadians(15.0).toFloat(), 0f))
        assertEquals(0f, result.first, 0.15f)
        assertEquals(-19.5f, result.second, 0.15f)
        assertEquals(0f, result.third, 0.15f)
    }

    @Test fun headTiltMapsToCubismAngleZ() {
        val result = FacePoseMath.fromColumnMajor(yxz(0f, 0f, Math.toRadians(10.0).toFloat()))
        assertEquals(0f, result.first, 0.15f)
        assertEquals(0f, result.second, 0.15f)
        assertEquals(-11.5f, result.third, 0.15f)
    }

    @Test fun combinedRotationKeepsAllThreeAxesIndependent() {
        val result = FacePoseMath.fromColumnMajor(
            yxz(Math.toRadians(12.0).toFloat(), Math.toRadians(-8.0).toFloat(), Math.toRadians(6.0).toFloat()),
        )
        assertEquals(-16.2f, result.first, 0.2f)
        assertEquals(10.4f, result.second, 0.2f)
        assertEquals(-6.9f, result.third, 0.2f)
    }
}
