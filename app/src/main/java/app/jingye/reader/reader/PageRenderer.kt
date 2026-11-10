package app.jingye.reader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache

class PageRenderer(
    private val text: CharSequence,
    private val style: ReaderStyle,
    private val viewW: Int,
    private val viewH: Int
) {
    private val paint = style.newPaint()
    private val cache = LruCache<Int, Bitmap>(3) // 当前页 + 前后各一页

    fun render(index: Int, slice: PageSlice): Bitmap =
        cache.get(index) ?: draw(slice).also { cache.put(index, it) }

    private fun draw(s: PageSlice): Bitmap {
        val safeW = viewW.coerceAtLeast(1)
        val safeH = viewH.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val contentW = (safeW - style.marginPx * 2).coerceAtLeast(1)
        val layout = buildLayout(text, s.start, s.end, paint, contentW, style.lineSpacingMult)
        c.translate(style.marginPx.toFloat(), style.marginPx.toFloat())
        layout.draw(c)
        return bmp
    }
}
