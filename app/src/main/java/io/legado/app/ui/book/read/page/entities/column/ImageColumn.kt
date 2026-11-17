package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.Keep
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.utils.dpToPx

/**
 * 阅读页正文插图渲染列 (v0.4.7)：
 * 1. 加载并绘制真实章节插图 Bitmap（解决之前只画空心黑框不显示图片内容的问题）；
 * 2. 按图片原始宽高比居中绘制，并在外围绘制 1px 1-bit 细黑线边框；
 * 3. 若图片尚未完成本地落盘，自动触发异步补抓并在完成后自动刷新当前阅读页。
 */
@Keep
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String,
    var click: String? = null
) : BaseColumn {

    companion object {
        private val borderPaint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
    }

    override var textLine: TextLine = emptyTextLine

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val book = ReadBook.book ?: return
        val height = textLine.height
        val targetW = (end - start).toInt().coerceAtLeast(1)
        val targetH = height.toInt().coerceAtLeast(1)

        val bitmap = ImageProvider.getImage(
            book,
            src,
            targetW,
            targetH
        )

        val rectF = if (textLine.isImage) {
            RectF(start, 0f, end, height)
        } else {
            val bmpW = bitmap.width.coerceAtLeast(1)
            val bmpH = bitmap.height.coerceAtLeast(1)
            val h = (end - start) / bmpW * bmpH
            val div = (height - h) / 2f
            RectF(start, div, end, height - div)
        }

        kotlin.runCatching {
            if (!bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, rectF, view.imagePaint)
            }
            if (textLine.isImage) {
                val borderRect = RectF(
                    rectF.left + 1f,
                    rectF.top + 1f,
                    (rectF.right - 1f).coerceAtLeast(rectF.left + 2f),
                    (rectF.bottom - 1f).coerceAtLeast(rectF.top + 2f)
                )
                canvas.drawRect(borderRect, borderPaint)
            }
        }

        if (ImageProvider.isErrorOrMissing(bitmap)) {
            ImageProvider.triggerAsyncReloadIfNeeded(book, src, view)
        }
    }

    override fun isTouch(x: Float): Boolean {
        return x > start && x < end + 20.dpToPx()
    }
}
