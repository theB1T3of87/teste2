package com.foxgirlsupremacy.companion

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid
import java.util.concurrent.ConcurrentLinkedQueue

private const val PREVIEW_PRIORITY = 3

class Live2DRenderer(
    private val appContext: Context,
    private val modelDir: String,
    private val modelJson: String,
    private val onReady: () -> Unit = {},
) : CharacterRenderer {

    private var view: Live2DTextureView? = null

    override fun createView(context: Context): View {
        Live2DAssets.init(context)

        if (!frameworkStarted) {
            val option = CubismFramework.Option().apply {
                loadFileFunction = Live2DAssets.LoadFile()
                logFunction = Live2DAssets.Logger()
            }
            CubismFramework.cleanUp()
            CubismFramework.startUp(option)
            frameworkStarted = true
        }

        return Live2DTextureView(context, modelDir, modelJson, onReady).also { view = it }
    }

    override fun setState(state: CharacterState) {
        view?.applyState(state)
    }

    override fun playMotion(group: String, index: Int) {
        view?.playMotion(group, index)
    }

    override fun playExpression(name: String) {
        view?.playExpression(name)
    }

    override fun setScale(scale: Float) {}
    override fun setAnimationsEnabled(enabled: Boolean) {}
    override fun setFacing(facing: Int) {}
    override fun setSnowFox(snow: Boolean) {}
    override fun update(dtMillis: Long) {}

    override fun dispose() {
        view?.stop()
        view = null
    }

    private companion object {

        @Volatile
        var frameworkStarted = false
    }

    private class Live2DTextureView(
        context: Context,
        private val modelDir: String,
        private val modelJson: String,
        private val onReady: () -> Unit,
    ) : TextureView(context), TextureView.SurfaceTextureListener {

        private var renderThread: RenderThread? = null

        init {
            isOpaque = false
            surfaceTextureListener = this
        }

        fun applyState(state: CharacterState) {
            renderThread?.postState(state)
        }

        fun stop() {
            renderThread?.shutdown()
            renderThread = null
        }

        fun playMotion(group: String, index: Int) {
            renderThread?.postCommand { it.startMotion(group, index, PREVIEW_PRIORITY) }
        }

        fun playExpression(name: String) {
            renderThread?.postCommand { it.setExpression(name) }
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            renderThread = RenderThread(context.applicationContext, surface, width, height, modelDir, modelJson, onReady).also {
                it.start()
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            renderThread?.onSizeChanged(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {

            renderThread?.shutdown()
            renderThread = null
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private class RenderThread(
        private val context: Context,
        private val surfaceTexture: SurfaceTexture,
        startWidth: Int,
        startHeight: Int,
        private val modelDir: String,
        private val modelJson: String,
        private val onReady: () -> Unit,
    ) : Thread("Live2D-GL") {

        @Volatile private var running = true
        @Volatile private var width = startWidth
        @Volatile private var height = startHeight
        @Volatile private var sizeDirty = true
        private val pendingStates = ConcurrentLinkedQueue<CharacterState>()
        private val pendingCommands = ConcurrentLinkedQueue<(Live2DModel) -> Unit>()

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var model: Live2DModel? = null
        private val projection = CubismMatrix44.create()

        fun postState(state: CharacterState) = pendingStates.add(state)

        fun postCommand(command: (Live2DModel) -> Unit) = pendingCommands.add(command)

        fun onSizeChanged(w: Int, h: Int) {
            width = w
            height = h
            sizeDirty = true
        }

        fun shutdown() {
            running = false
            try {
                join(800)
            } catch (_: InterruptedException) {
            }
        }

        override fun run() {
            if (!initEgl()) {
                releaseEgl()
                return
            }

            if (!CubismFramework.isInitialized()) {
                CubismFramework.initialize()
            }

            CubismShaderAndroid.deleteInstance()

            val tm = Live2DTextureManager(context)
            model = Live2DModel(tm).apply { loadAssets(modelDir, modelJson, width, height) }
            onReady()

            while (running) {
                drainStates()
                if (sizeDirty) {
                    GLES20.glViewport(0, 0, width, height)
                    model?.setRenderTargetSize(width, height)
                    sizeDirty = false
                }
                drawFrame()
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                try {
                    sleep(FRAME_MS)
                } catch (_: InterruptedException) {
                }
            }

            model = null
            releaseEgl()
        }

        private fun drainStates() {
            val m = model ?: return
            while (true) {
                val state = pendingStates.poll() ?: break
                when (state) {
                    CharacterState.PET -> m.setExpressionFrom(arrayOf("heart_eyes", "blush", "red", "shou", "tang"))
                    CharacterState.EAT -> m.setExpressionFrom(arrayOf("blush", "red", "heart_eyes", "shou"))
                    CharacterState.TALK -> m.setExpressionFrom(arrayOf("tail", "shou", "tang", "heart_eyes"))
                    else -> {}
                }
            }
            while (true) {
                val command = pendingCommands.poll() ?: break
                command(m)
            }
        }

        private fun drawFrame() {
            val m = model ?: return
            Live2DAssets.updateTime()
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (width == 0 || height == 0) return

            projection.loadIdentity()
            val aspectRatio = width.toFloat() / height.toFloat()
            val displayRatio = height.toFloat() / width.toFloat()
            if (m.canvasRatio() < displayRatio) {
                m.fitWidth()
                projection.scale(1.0f, aspectRatio)
            } else {
                m.fitHeight()
                projection.scale(1.0f / aspectRatio, 1.0f)
            }
            m.update()
            m.draw(projection)
        }

        private fun initEgl(): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfig, 0) ||
                numConfig[0] == 0
            ) {
                return false
            }
            val config = configs[0] ?: return false

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            val surface = Surface(surfaceTexture)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            return true
        }

        private fun releaseEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)

            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        companion object {
            private const val FRAME_MS = 16L
        }
    }
}
