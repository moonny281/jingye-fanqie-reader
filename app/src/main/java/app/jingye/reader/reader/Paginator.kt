package app.jingye.reader.reader

import android.content.Context

class Paginator(
    private val style: ReaderStyle,
    private val viewW: Int,
    private val viewH: Int,
    private val ctx: Context? = null
) {
    fun paginate(text: CharSequence): List<PageSlice> {
        if (text.isEmpty()) return listOf(PageSlice(0, 0))
        val safeW = if (viewW > 120) viewW else 720
        val safeH = if (viewH > 120) viewH else 1280
        val contentW = (safeW - style.marginPx * 2).coerceAtLeast(100)
        val contentH = (safeH - style.marginVPx * 2).coerceAtLeast(100)
        val styledText = formatChapterSpannable(text, style, ctx)
        val layout = buildLayout(
            styledText,
            0,
            styledText.length,
            style.newPaint(ctx),
            contentW,
            style.lineSpacingMult
        )

        val pages = ArrayList<PageSlice>()
        var startLine = 0
        var pageTop = layout.getLineTop(0)
        for (i in 0 until layout.lineCount) {
            if (i > startLine && layout.getLineBottom(i) - pageTop > contentH) {
                pages += PageSlice(layout.getLineStart(startLine), layout.getLineStart(i))
                startLine = i
                pageTop = layout.getLineTop(i)
            }
        }
        pages += PageSlice(layout.getLineStart(startLine), styledText.length)
        return pages
    }
}
