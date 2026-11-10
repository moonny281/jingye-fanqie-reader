package app.jingye.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class LocalTxtSource(private val library: LibraryStore) : BookSource {

    // 兼容传统 TXT 章节标题（第X章/回/卷/集/部/篇）与 fanqie-novel-downloader 导出的【章节标题】格式
    private val chapterRegex = Regex(
        """^[ \t　]*(?:【(?!内容简介)([^】\r\n]{1,60})】|第[0-9零〇一二三四五六七八九十百千两]+[章节回卷集部篇].{0,40})$""",
        RegexOption.MULTILINE
    )
    private val cache = HashMap<String, List<Pair<String, String>>>()

    fun invalidate(bookId: String) {
        synchronized(cache) {
            cache.remove(bookId)
        }
    }

    private suspend fun chapters(bookId: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[bookId] }?.let { return@withContext it }
        val book = library.get(bookId) ?: error("书籍不存在: $bookId")
        val file = File(book.path)
        val text = if (file.exists()) {
            decode(file.readBytes()).replace("\r\n", "\n").replace('\r', '\n')
        } else {
            ""
        }
        val list = split(text)
        if (book.totalChapters != list.size) {
            library.updateTotalChapters(bookId, list.size)
        }
        synchronized(cache) { cache[bookId] = list }
        list
    }

    override suspend fun chapterCount(bookId: String): Int = chapters(bookId).size

    override suspend fun chapterTitle(bookId: String, index: Int): String {
        val list = chapters(bookId)
        return list.getOrNull(index.coerceIn(0, (list.size - 1).coerceAtLeast(0)))?.first ?: "正文"
    }

    override suspend fun chapterText(bookId: String, index: Int): CharSequence {
        val list = chapters(bookId)
        return list.getOrNull(index.coerceIn(0, (list.size - 1).coerceAtLeast(0)))?.second ?: ""
    }

    /** UTF-8 (含 BOM) 优先，严格解码失败则按 GB18030 解码。 */
    private fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()
        } catch (e: CharacterCodingException) {
            String(b, Charset.forName("GB18030"))
        }
    }

    private fun split(text: String): List<Pair<String, String>> {
        val marks = chapterRegex.findAll(text).toList()
        if (marks.isEmpty()) return chunk(text)
        val out = ArrayList<Pair<String, String>>(marks.size + 1)
        val head = text.substring(0, marks[0].range.first).trim()
        if (head.isNotEmpty()) {
            out += "前言" to indent(head)
        }
        marks.forEachIndexed { i, m ->
            val end = marks.getOrNull(i + 1)?.range?.first ?: text.length
            val rawTitle = m.groups[1]?.value?.trim() ?: m.value.trim()
            val bodyStart = (m.range.last + 1).coerceAtMost(end)
            val rawBody = text.substring(bodyStart, end)
            // 去掉导出文件尾部的分隔线说明
            val cleanedBody = rawBody.replace(
                Regex("""={20,}\s*由 fanqie-novel-downloader 导出\s*={20,}\s*$"""),
                ""
            )
            out += rawTitle to indent(cleanedBody)
        }
        return out
    }

    /** 找不到章节标记时，按约 12000 字在换行处切段，避免单章过大。 */
    private fun chunk(text: String, size: Int = 12000): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var start = 0
        var n = 1
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) {
                val nl = text.indexOf('\n', end)
                end = if (nl == -1) text.length else nl
            }
            out += "第 $n 段" to indent(text.substring(start, end))
            start = end
            n++
        }
        if (out.isEmpty()) out += "全文" to ""
        return out
    }

    /** 去空行，每段首行两个全角空格缩进。 */
    private fun indent(s: String): String =
        s.lineSequence()
            .map { it.trim().removePrefix("　　").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "　　$it" }
}
