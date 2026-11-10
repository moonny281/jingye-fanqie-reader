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
        val safeW = if (viewW > 120) viewW else 720
        val safeH = if (viewH > 120) viewH else 1280
        val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val contentW = (safeW - style.marginPx * 2).coerceAtLeast(100)
        val safeStart = s.start.coerceIn(0, text.length)
        val safeEnd = s.end.coerceIn(safeStart, text.length)
        val layout = buildLayout(text, safeStart, safeEnd, paint, contentW, style.lineSpacingMult)
        c.translate(style.marginPx.toFloat(), style.marginPx.toFloat())
        layout.draw(c)
        return bmp
    }
}
