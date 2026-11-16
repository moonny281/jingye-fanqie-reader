package io.legado.app.ui.widget.checkbox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.widget.Checkable
import io.legado.app.utils.dpToPx
import kotlin.math.min

/**
 * 1-Bit 纯黑白线性方形复选框 (v0.4.3)：
 * 去除圆角灰底与缩放动画，未选中为白底黑框方形，选中为黑底白勾方形。
 */
class SmoothCheckBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Checkable {

    private var mChecked = false
    var onCheckedChangeListener: ((checkBox: SmoothCheckBox, isChecked: Boolean) -> Unit)? = null

    private val fillPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f.dpToPx()
    }

    private val tickPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.2f.dpToPx()
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }

    private val tickPath = Path()

    init {
        setOnClickListener { toggle() }
    }

    override fun isChecked(): Boolean = mChecked

    override fun setChecked(checked: Boolean) {
        mChecked = checked
        invalidate()
        onCheckedChangeListener?.invoke(this, mChecked)
    }

    fun setChecked(checked: Boolean, animate: Boolean) {
        setChecked(checked)
    }

    override fun toggle() {
        isChecked = !mChecked
    }

    private fun measureSize(measureSpec: Int): Int {
        val defSize: Int = DEF_DRAW_SIZE.dpToPx()
        val specSize = MeasureSpec.getSize(measureSpec)
        return when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.UNSPECIFIED, MeasureSpec.AT_MOST -> min(defSize, specSize)
            MeasureSpec.EXACTLY -> specSize
            else -> defSize
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(measureSize(widthMeasureSpec), measureSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return
        val inset = (w * 0.12f).coerceAtLeast(2f)
        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset

        if (mChecked) {
            fillPaint.color = Color.BLACK
            canvas.drawRect(left, top, right, bottom, fillPaint)
            val boxW = right - left
            val boxH = bottom - top
            tickPath.reset()
            tickPath.moveTo(left + boxW * 0.22f, top + boxH * 0.52f)
            tickPath.lineTo(left + boxW * 0.43f, top + boxH * 0.74f)
            tickPath.lineTo(left + boxW * 0.78f, top + boxH * 0.28f)
            canvas.drawPath(tickPath, tickPaint)
        } else {
            fillPaint.color = Color.WHITE
            canvas.drawRect(left, top, right, bottom, fillPaint)
            canvas.drawRect(left, top, right, bottom, borderPaint)
        }
    }

    companion object {
        private const val DEF_DRAW_SIZE = 22
    }
}
