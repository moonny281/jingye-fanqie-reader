package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.RelativeLayout

/**
 * 墨水屏纯黑白线性边框容器（v0.4.3 彻底移除高斯模糊阴影 setShadowLayer，避免在墨水屏周围产生无法局部擦除的灰阶黑圈残影）
 */
@Suppress("unused")
class ShadowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {

    private val fillPaint = Paint().apply {
        isAntiAlias = false
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val rectF = RectF()

    init {
        setWillNotDraw(false)
        setPadding(2, 2, 2, 2)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rectF.set(1f, 1f, (w - 1).toFloat().coerceAtLeast(1f), (h - 1).toFloat().coerceAtLeast(1f))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(rectF, fillPaint)
        canvas.drawRect(rectF, borderPaint)
        super.onDraw(canvas)
    }

    fun setShadowColor(shadowColor: Int) {
        postInvalidate()
    }

    fun setShadowRadius(shadowRadius: Float) {
        postInvalidate()
    }

    companion object {
        const val ALL = 0x1111
        const val LEFT = 0x0001
        const val TOP = 0x0010
        const val RIGHT = 0x0100
        const val BOTTOM = 0x1000
        const val SHAPE_RECTANGLE = 0x0001
        const val SHAPE_OVAL = 0x0010
    }
}