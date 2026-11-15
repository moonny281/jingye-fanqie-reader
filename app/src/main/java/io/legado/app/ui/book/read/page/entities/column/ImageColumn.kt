package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.Keep
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.utils.dpToPx

/**
 * 墨水屏纯黑白线性插图占位列（v0.4.3 不加载灰阶位图图片，绘制干净的线性方框避免位图抖动残影）
 */
@Keep
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String,
    var click: String? = null
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val height = textLine.height
        val rectF = RectF(start + 2f, 2f, end - 2f, (height - 2f).coerceAtLeast(4f))
        val strokePaint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(rectF, strokePaint)
    }

    override fun isTouch(x: Float): Boolean {
        return x > start && x < end + 20.dpToPx()
    }
}
