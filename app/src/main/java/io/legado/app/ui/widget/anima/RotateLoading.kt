package io.legado.app.ui.widget.anima

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 墨水屏静态 1-Bit 线性加载指示框（已彻底移除旋转重绘动画与灰色阴影，防止墨水屏残影）
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class RotateLoading @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var isStarted = false
        private set

    var loadingColor: Int = Color.BLACK

    private val borderPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val dotPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        if (!isStarted) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return
        canvas.drawRect(2f, 2f, w - 2f, h - 2f, borderPaint)
        val cx = w / 2f
        val cy = h / 2f
        canvas.drawRect(cx - 2f, cy - 2f, cx + 2f, cy + 2f, dotPaint)
    }

    fun visible() {
        isStarted = true
        visibility = VISIBLE
        invalidate()
    }

    fun inVisible() {
        isStarted = false
        visibility = INVISIBLE
    }

    fun gone() {
        isStarted = false
        visibility = GONE
    }
}
