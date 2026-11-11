package app.jingye.reader.ui.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class TestPatterns @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var patternIndex: Int = 0
        set(value) {
            field = value % 3
            invalidate()
        }

    var flashColor: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val blackPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 40f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fc = flashColor
        if (fc != null) {
            canvas.drawColor(fc)
            return
        }
        canvas.drawColor(Color.WHITE)
        when (patternIndex) {
            0 -> drawCheckerboard(canvas)
            1 -> drawStripes(canvas)
            else -> drawContrastText(canvas)
        }
    }

    private fun drawCheckerboard(canvas: Canvas) {
        val cell = 64
        var y = 0
        var row = 0
        while (y < height) {
            var x = 0
            var col = 0
            while (x < width) {
                if ((row + col) % 2 == 0) {
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + cell).toFloat(),
                        (y + cell).toFloat(),
                        blackPaint
                    )
                }
                x += cell
                col++
            }
            y += cell
            row++
        }
    }

    private fun drawStripes(canvas: Canvas) {
        val bar = 24
        var y = 0
        var idx = 0
        while (y < height) {
            if (idx % 2 == 0) {
                canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + bar).toFloat(), blackPaint)
            }
            y += bar
            idx++
        }
    }

    private fun drawContrastText(canvas: Canvas) {
        val sizes = listOf(28f, 36f, 44f, 54f, 64f)
        var y = 90f
        for (sz in sizes) {
            textPaint.textSize = sz
            canvas.drawText("净页墨水屏残影与锐度测试 ${sz.toInt()}px", 36f, y, textPaint)
            y += sz + 36f
        }
    }
}
