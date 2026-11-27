package com.foxgirlsupremacy.companion.tracking

/** Maps MediaPipe/ARKit tracking values to conventional Live2D parameter IDs.
 * Models may use different IDs; keep this mapping configurable per model. */
object Live2DTrackingMapper {
    data class Parameter(val id: String, val value: Float)

    // MediaPipe ARKit indices: brow 1..5, eye blink 9..10, eye look 11..18,
    // jawOpen 25, mouth smile 44..45.
    fun map(frame: FaceTrackingFrame): List<Parameter> {
        if (!frame.isValid) return emptyList()
        fun v(i: Int) = frame.value(i)
        return listOf(
            Parameter("ParamAngleX", (-frame.yaw).coerceIn(-30f, 30f)),
            Parameter("ParamAngleY", (-frame.pitch).coerceIn(-30f, 30f)),
            Parameter("ParamAngleZ", (-frame.roll).coerceIn(-30f, 30f)),
            Parameter("ParamEyeLOpen", 1f - v(9)),
            Parameter("ParamEyeROpen", 1f - v(10)),
            Parameter("ParamMouthOpenY", v(25)),
            Parameter("ParamMouthForm", ((v(44) + v(45)) * 0.5f * 2f - 1f).coerceIn(-1f, 1f)),
            Parameter("ParamBrowLY", (v(4) + v(3) - v(1)).coerceIn(-1f, 1f)),
            Parameter("ParamBrowRY", (v(5) + v(3) - v(2)).coerceIn(-1f, 1f))
        )
    }
}
