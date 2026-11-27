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
import java.util.concurrent.ConcurrentLinkedQueue

/** Live2D renderer backed by Cubism Native (C++/JNI) on a TextureView EGL thread. */
class Live2DRenderer(
    private val appContext: Context,
    private val modelDir: String,
    private val modelJson: String,
    private val onReady: () -> Unit = {},
) : CharacterRenderer {
    private var textureView: NativeTextureView? = null

    override fun createView(context: Context): View {
        NativeLive2DBridge.initialize(appContext)
        return NativeTextureView(context).also { textureView = it }
    }

    override fun setState(state: CharacterState) {
        when (state) {
            CharacterState.IDLE -> playMotion("Idle", 0)
            CharacterState.WALK -> playMotion("Idle", 1)
            CharacterState.PET, CharacterState.EAT, CharacterState.TALK -> playMotion("TapBody", 0)
        }
    }

    override fun playMotion(group: String, index: Int) {
        textureView?.postNative { NativeLive2DBridge.nativePlayMotion(group, index) }
    }

    override fun playExpression(name: String) {
        textureView?.postNative { NativeLive2DBridge.nativeSetExpression(name) }
    }

    override fun setScale(scale: Float) {
        textureView?.postNative { NativeLive2DBridge.nativeSetDisplayScale(scale) }
    }

    override fun setAnimationsEnabled(enabled: Boolean) {
        textureView?.postNative { NativeLive2DBridge.nativeSetAnimationsEnabled(enabled) }
    }

    override fun setFacing(facing: Int) {
        textureView?.postNative { NativeLive2DBridge.nativeSetFacing(facing) }
    }

    override fun setSnowFox(snow: Boolean) { }
    override fun update(dtMillis: Long) { }

    override fun dispose() {
        textureView?.stop()
        textureView = null
    }

    private inner class NativeTextureView(context: Context) : TextureView(context),
        TextureView.SurfaceTextureListener {
        private var renderThread: NativeRenderThread? = null
        private val pending = ConcurrentLinkedQueue<() -> Unit>()
        private val commandLock = Any()

        init {
            isOpaque = false
            surfaceTextureListener = this
        }

        fun postNative(command: () -> Unit) {
            synchronized(commandLock) {
                val thread = renderThread
                if (thread == null) pending.add(command) else thread.post(command)
            }
        }

        fun stop() {
            val thread = synchronized(commandLock) {
                val current = renderThread
                renderThread = null
                pending.clear()
                current
            }
            thread?.shutdown()
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            val thread = NativeRenderThread(surface, width, height)
            synchronized(commandLock) {
                renderThread = thread
                while (true) {
                    val command = pending.poll() ?: break
                    thread.post(command)
                }
            }
            thread.start()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            renderThread?.resize(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            stop()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            val action = event.actionMasked
            postNative {
                when (action) {
                    android.view.MotionEvent.ACTION_DOWN -> NativeLive2DBridge.nativeOnTouchesBegan(x, y)
                    android.view.MotionEvent.ACTION_MOVE -> NativeLive2DBridge.nativeOnTouchesMoved(x, y)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> NativeLive2DBridge.nativeOnTouchesEnded(x, y)
                }
            }
            return true
        }

        private inner class NativeRenderThread(
            private val texture: SurfaceTexture,
            initialWidth: Int,
            initialHeight: Int,
        ) : Thread("Live2D-Native-GL") {
            @Volatile private var running = true
            @Volatile private var width = initialWidth
            @Volatile private var height = initialHeight
            @Volatile private var sizeDirty = true
            private val commands = ConcurrentLinkedQueue<() -> Unit>()
            private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private var context: EGLContext = EGL14.EGL_NO_CONTEXT
            private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            private var windowSurface: Surface? = null
            private var nativeStarted = false

            fun post(command: () -> Unit) { commands.add(command) }

            fun resize(w: Int, h: Int) {
                width = w
                height = h
                sizeDirty = true
            }

            fun shutdown() {
                running = false
                interrupt()
                try { join(1200) } catch (_: InterruptedException) { }
            }

            override fun run() {
                if (!initializeEgl()) {
                    releaseEgl()
                    return
                }
                NativeLive2DBridge.nativeOnStart()
                nativeStarted = true
                NativeLive2DBridge.nativeOnSurfaceCreated()
                val modelName = modelJson.substringAfterLast('/').removeSuffix(".model3.json")
                NativeLive2DBridge.nativeConfigureModel(modelName, modelDir)
                GLES20.glViewport(0, 0, width, height)
                NativeLive2DBridge.nativeOnSurfaceChanged(width, height)
                sizeDirty = false
                this@NativeTextureView.post { onReady() }

                while (running) {
                    drainCommands()
                    if (sizeDirty && width > 0 && height > 0) {
                        GLES20.glViewport(0, 0, width, height)
                        NativeLive2DBridge.nativeOnSurfaceChanged(width, height)
                        sizeDirty = false
                    }
                    if (width > 0 && height > 0) {
                        NativeLive2DBridge.nativeOnDrawFrame()
                        EGL14.eglSwapBuffers(display, eglSurface)
                    }
                    try { sleep(16) } catch (_: InterruptedException) { }
                }

                if (nativeStarted) {
                    NativeLive2DBridge.nativeOnStop()
                    NativeLive2DBridge.nativeOnDestroy()
                }
                releaseEgl()
            }

            private fun drainCommands() {
                while (true) {
                    val command = commands.poll() ?: break
                    command()
                }
            }

            private fun initializeEgl(): Boolean {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) return false
                val versions = IntArray(2)
                if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return false
                val attributes = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return false
                val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
                if (context == EGL14.EGL_NO_CONTEXT) return false
                windowSurface = Surface(texture)
                eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], windowSurface, intArrayOf(EGL14.EGL_NONE), 0)
                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    windowSurface?.release()
                    windowSurface = null
                    return false
                }
                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                    windowSurface?.release()
                    windowSurface = null
                    return false
                }
                EGL14.eglSwapInterval(display, 1)
                return true
            }

            private fun releaseEgl() {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
                windowSurface?.release()
                windowSurface = null
                display = EGL14.EGL_NO_DISPLAY
                context = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            }
        }
    }
}
