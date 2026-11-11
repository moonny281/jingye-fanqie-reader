package app.jingye.reader.reader

import android.content.Context
import android.graphics.Bitmap
import app.jingye.reader.data.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReaderEngine(
    private val source: BookSource,
    private val bookId: String,
    private val ctx: Context? = null
) {

    data class Frame(
        val bitmap: Bitmap,
        val title: String,
        val chapter: Int,
        val chapterCount: Int,
        val pageIndex: Int,
        val pageCount: Int,
        val charOffset: Int
    )

    private var w = 0
    private var h = 0
    private lateinit var style: ReaderStyle
    private var chapterCount = 0
    private var chapter = 0
    private var title = ""
    private var pages: List<PageSlice> = emptyList()
    private var renderer: PageRenderer? = null
    private var index = 0

    suspend fun open(w: Int, h: Int, style: ReaderStyle, chapter: Int, charOffset: Int): Frame {
        this.w = w
        this.h = h
        this.style = style
        chapterCount = source.chapterCount(bookId).coerceAtLeast(1)
        loadChapter(chapter.coerceIn(0, chapterCount - 1), charOffset)
        return frame()
    }

    suspend fun relayout(newStyle: ReaderStyle): Frame {
        val offset = pages.getOrNull(index)?.start ?: 0
        style = newStyle
        loadChapter(chapter, offset)
        return frame()
    }

    suspend fun jump(targetChapter: Int): Frame {
        loadChapter(targetChapter.coerceIn(0, (chapterCount - 1).coerceAtLeast(0)), 0)
        return frame()
    }

    suspend fun next(): Frame? {
        when {
            index + 1 < pages.size -> index++
            chapter + 1 < chapterCount -> loadChapter(chapter + 1, 0)
            else -> return null
        }
        return frame()
    }

    suspend fun prev(): Frame? {
        when {
            index > 0 -> index--
            chapter > 0 -> loadChapter(chapter - 1, Int.MAX_VALUE)
            else -> return null
        }
        return frame()
    }

    suspend fun prefetch() = withContext(Dispatchers.Default) {
        val r = renderer ?: return@withContext
        listOf(index + 1, index - 1).filter { it in pages.indices }.forEach {
            r.render(it, pages[it])
        }
    }

    private suspend fun loadChapter(c: Int, charOffset: Int) {
        val text = source.chapterText(bookId, c)
        val t = source.chapterTitle(bookId, c)
        val p = withContext(Dispatchers.Default) { Paginator(style, w, h, ctx).paginate(text) }
        chapter = c
        title = t
        pages = p
        renderer = PageRenderer(text, style, w, h, ctx)
        index = p.indexOfLast { it.start <= charOffset }.coerceAtLeast(0)
    }

    private suspend fun frame(): Frame = withContext(Dispatchers.Default) {
        val bmp = renderer!!.render(index, pages[index])
        Frame(bmp, title, chapter, chapterCount, index, pages.size, pages[index].start)
    }
}
