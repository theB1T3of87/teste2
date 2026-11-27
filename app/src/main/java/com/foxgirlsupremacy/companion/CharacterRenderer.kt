package com.foxgirlsupremacy.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator

enum class CharacterState { IDLE, WALK, PET, EAT, TALK }

interface CharacterRenderer {

    fun createView(context: Context): View

    fun setState(state: CharacterState)

    fun setScale(scale: Float)

    fun setAnimationsEnabled(enabled: Boolean)

    fun setFacing(facing: Int)

    fun setSnowFox(snow: Boolean)

    fun update(dtMillis: Long)

    fun playMotion(group: String, index: Int) {}

    fun playExpression(name: String) {}

    fun dispose() {}
}

class PlaceholderRenderer : CharacterRenderer {

    private var view: PlaceholderView? = null
    private var state: CharacterState = CharacterState.IDLE
    private var scale: Float = 1.0f
    private var animationsEnabled: Boolean = true
    private var facing: Int = 1
    private var snow: Boolean = false

    override fun createView(context: Context): View =
        PlaceholderView(context).also {
            it.state = state
            it.scale = scale
            it.animationsEnabled = animationsEnabled
            it.facing = facing
            it.snow = snow
            view = it
        }

    override fun setState(state: CharacterState) {
        this.state = state
        view?.state = state
    }

    override fun setScale(scale: Float) {
        this.scale = scale
        view?.scale = scale
    }

    override fun setAnimationsEnabled(enabled: Boolean) {
        this.animationsEnabled = enabled
        view?.animationsEnabled = enabled
    }

    override fun setFacing(facing: Int) {
        this.facing = facing
        view?.facing = facing
    }

    override fun setSnowFox(snow: Boolean) {
        this.snow = snow
        view?.snow = snow
    }

    override fun update(dtMillis: Long) {

    }

    private class PlaceholderView(context: Context) : View(context) {

        private val baseSizePx = 96 * resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val nosePath = Path()

        private var breath = 1.0f

        private val breathAnimator = ValueAnimator.ofFloat(0.92f, 1.0f).apply {
            duration = 1800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                breath = it.animatedValue as Float
                invalidate()
            }
        }

        var state: CharacterState = CharacterState.IDLE
            set(value) {
                field = value
                invalidate()
            }

        var scale: Float = 1.0f
            set(value) {
                field = value
                requestLayout()
            }

        var animationsEnabled: Boolean = true
            set(value) {
                field = value
                applyAnimationState()
            }

        var facing: Int = 1
            set(value) {
                field = value
                invalidate()
            }

        var snow: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            applyAnimationState()
        }

        override fun onDetachedFromWindow() {
            breathAnimator.cancel()
            super.onDetachedFromWindow()
        }

        private fun applyAnimationState() {
            if (animationsEnabled && isAttachedToWindow) {
                if (!breathAnimator.isStarted) breathAnimator.start()
            } else {
                breathAnimator.cancel()
                breath = 1.0f
                invalidate()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = (baseSizePx * scale).toInt()
            setMeasuredDimension(size, size)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = (width / 2f - 8f) * breath

            fill.color = bodyColor()
            stroke.color = if (snow) Color.parseColor("#4FC3F7") else Color.WHITE
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, stroke)

            nosePaint.color = stroke.color
            val dir = if (facing < 0) -1f else 1f
            val tip = cx + dir * r
            val baseX = cx + dir * (r * 0.45f)
            nosePath.reset()
            nosePath.moveTo(tip, cy)
            nosePath.lineTo(baseX, cy - r * 0.28f)
            nosePath.lineTo(baseX, cy + r * 0.28f)
            nosePath.close()
            canvas.drawPath(nosePath, nosePaint)
        }

        private fun bodyColor(): Int = if (snow) {
            when (state) {
                CharacterState.IDLE -> Color.parseColor("#ECF6FB")
                CharacterState.WALK -> Color.parseColor("#CDE9F7")
                CharacterState.PET -> Color.parseColor("#B39DDB")
                CharacterState.EAT -> Color.parseColor("#A5D6A7")
                CharacterState.TALK -> Color.parseColor("#90CAF9")
            }
        } else {
            when (state) {
                CharacterState.IDLE -> Color.parseColor("#FF7043")
                CharacterState.WALK -> Color.parseColor("#FFA726")
                CharacterState.PET -> Color.parseColor("#EC407A")
                CharacterState.EAT -> Color.parseColor("#66BB6A")
                CharacterState.TALK -> Color.parseColor("#42A5F5")
            }
        }
    }
}
