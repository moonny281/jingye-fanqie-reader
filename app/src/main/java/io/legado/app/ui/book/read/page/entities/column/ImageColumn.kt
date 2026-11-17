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
 * 阅读页正文插图渲染列 (v0.5.0)：
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
        /** 0.4.9 墨水屏插图画笔：转灰度 + 轻度提高对比度 + 抖动，避免彩色图在墨水屏上发灰发糊 */
        private val einkImagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            val gray = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            val c = 1.18f
            val t = (-0.5f * c + 0.5f) * 255f
            gray.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            colorFilter = android.graphics.ColorMatrixColorFilter(gray)
        }
        private val hintPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 13f.dpToPx()
            textAlign = Paint.Align.CENTER
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
                canvas.drawBitmap(bitmap, null, rectF, einkImagePaint)
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
            // 0.4.7：图片未就绪/失败时在图框内给出明确提示（含失败原因），不再只是一个空框
            if (textLine.isImage && rectF.width() > 120f) {
                kotlin.runCatching {
                    val reason = io.legado.app.fanqie.FanqieLegadoBridge.lastImageError[src]
                    val msg = if (reason == null) "插图加载中…" else "插图加载失败（稍后自动重试）"
                    val cx = rectF.centerX()
                    var cy = rectF.centerY()
                    canvas.drawText(msg, cx, cy, hintPaint)
                    if (reason != null) {
                        val small = Paint(hintPaint).apply { textSize = 10f.dpToPx() }
                        val maxChars = ((rectF.width() - 16f) / small.textSize).toInt().coerceAtLeast(8)
                        reason.chunked(maxChars).take(6).forEach { line ->
                            cy += small.textSize * 1.3f
                            canvas.drawText(line, cx, cy, small)
                        }
                    }
                }
            }
            ImageProvider.triggerAsyncReloadIfNeeded(book, src, view)
        }
    }

    override fun isTouch(x: Float): Boolean {
        return x > start && x < end + 20.dpToPx()
    }
}
