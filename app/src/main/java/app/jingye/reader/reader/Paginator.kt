package app.jingye.reader.reader

class Paginator(
    private val style: ReaderStyle,
    private val viewW: Int,
    private val viewH: Int
) {
    fun paginate(text: CharSequence): List<PageSlice> {
        if (text.isEmpty()) return listOf(PageSlice(0, 0))
        val contentW = (viewW - style.marginPx * 2).coerceAtLeast(1)
        val contentH = (viewH - style.marginPx * 2).coerceAtLeast(1)
        val layout = buildLayout(text, 0, text.length, style.newPaint(), contentW, style.lineSpacingMult)

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
        pages += PageSlice(layout.getLineStart(startLine), text.length)
        return pages
    }
}
