package com.foxgirlsupremacy.companion

import android.content.Context
import java.io.File

/** JNI bridge to the official Live2D Cubism Native Framework (OpenGL ES 2). */
object NativeCubismBridge {
    @Volatile private var appContext: Context? = null
    @Volatile private var activeModelDirectory: String = "live2d/hiyori/"
    @Volatile private var activeModelJson: String = "Hiyori.model3.json"

    init { System.loadLibrary("FoxCubism") }

    fun configure(context: Context, modelDirectory: String, modelJson: String) {
        appContext = context.applicationContext
        activeModelDirectory = modelDirectory
        activeModelJson = modelJson
    }

    @JvmStatic fun getAssetList(path: String): Array<String> =
        appContext?.assets?.list(path) ?: emptyArray()

    @JvmStatic fun loadFile(path: String): ByteArray? = try {
        if (path.startsWith("/")) File(path).takeIf { it.isFile }?.readBytes()
        else appContext?.assets?.open(path)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    @JvmStatic fun getModelDirectory(): String = activeModelDirectory
    @JvmStatic fun getModelJsonFile(): String = activeModelJson
    @JvmStatic fun moveTaskToBack() = Unit

    @JvmStatic external fun nativeOnStart()
    @JvmStatic external fun nativeOnPause()
    @JvmStatic external fun nativeOnStop()
    @JvmStatic external fun nativeOnDestroy()
    @JvmStatic external fun nativeOnSurfaceCreated()
    @JvmStatic external fun nativeOnSurfaceChanged(width: Int, height: Int)
    @JvmStatic external fun nativeOnDrawFrame()
    @JvmStatic external fun nativeOnTouchesBegan(x: Float, y: Float)
    @JvmStatic external fun nativeOnTouchesEnded(x: Float, y: Float)
    @JvmStatic external fun nativeOnTouchesMoved(x: Float, y: Float)
    @JvmStatic external fun nativeSetFaceTrackingData(
        enabled: Boolean,
        angleX: Float, angleY: Float, angleZ: Float,
        eyeLOpen: Float, eyeROpen: Float, mouthForm: Float, mouthOpen: Float,
        faceX: Float, faceY: Float,
    )
    @JvmStatic external fun nativePlayMotion(group: String, index: Int)
    @JvmStatic external fun nativeSetExpression(expression: String)
}
