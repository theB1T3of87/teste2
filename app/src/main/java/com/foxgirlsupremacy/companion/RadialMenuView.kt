package com.foxgirlsupremacy.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class RadialAction { PET, FEED, NORMAL, OFF, BIGGER, SMALLER }

class RadialMenuView(
    context: Context,
    private val centerX: Float,
    private val centerY: Float,
    private val items: List<Item>,
) : View(context) {

    data class Item(val action: RadialAction, val label: String, val iconRes: Int)

    private val density = resources.displayMetrics.density
    private val radiusPx = 130 * density
    private val itemRadiusPx = 34 * density
    private val hitRadiusPx = 46 * density

    private var highlighted = -1
    private val loc = IntArray(2)

    private val scrimPaint = Paint().apply { color = 0x66000000 }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = menuColor }
    private val itemHotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lighten(menuColor) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13 * density
    }

    private fun lighten(color: Int): Int = Color.rgb(\n        (Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2,\n    )\n\n    private fun itemCenter(index: Int): PointF {
        getLocationOnScreen(loc)
        val localCenterX = centerX - loc[0]
        val localCenterY = centerY - loc[1]
        val n = items.size

        val pointingUp = localCenterY > height / 2f
        val baseAngle = if (pointingUp) -90.0 else 90.0
        val stepDeg = 55.0
        val startDeg = baseAngle - (n - 1) * stepDeg / 2.0
        val angle = Math.toRadians(startDeg + index * stepDeg)
        val x = localCenterX + radiusPx * cos(angle).toFloat()
        val y = localCenterY + radiusPx * sin(angle).toFloat()
        return PointF(x, y)
    }

    fun highlightAt(x: Float, y: Float) {
        val previous = highlighted
        highlighted = indexAt(x, y)
        if (highlighted != previous) invalidate()
    }

    fun selectionAt(x: Float, y: Float): RadialAction? {
        val index = indexAt(x, y)
        return if (index >= 0) items[index].action else null
    }

    private fun indexAt(screenX: Float, screenY: Float): Int {
        getLocationOnScreen(loc)
        val localX = screenX - loc[0]
        val localY = screenY - loc[1]
        for (i in items.indices) {
            val c = itemCenter(i)
            if (hypot((localX - c.x).toDouble(), (localY - c.y).toDouble()) <= hitRadiusPx) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        for (i in items.indices) {
            val c = itemCenter(i)
            val hot = i == highlighted
            val r = if (hot) itemRadiusPx * 1.15f else itemRadiusPx
            canvas.drawCircle(c.x, c.y, r, if (hot) itemHotPaint else itemPaint)

            ContextCompat.getDrawable(context, items[i].iconRes)?.let { icon ->
                val half = (18 * density).toInt()
                icon.setBounds(
                    (c.x - half).toInt(), (c.y - half).toInt(),
                    (c.x + half).toInt(), (c.y + half).toInt(),
                )
                icon.draw(canvas)
            }

            canvas.drawText(items[i].label, c.x, c.y + r + 18 * density, labelPaint)
        }
    }
}
