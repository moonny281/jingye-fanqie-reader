package app.jingye.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class LocalTxtSource(private val library: LibraryStore) : BookSource {

    private val stdChapterLineRegex = Regex(
        "^第[0-9零〇一二三四五六七八九十百千两]+[章节回卷集部篇]([ \\t　:：._-].*|$)"
    )
    private val specialChapterLineRegex = Regex(
        "^(楔子|序章|序言|引子|尾声|番外|后记)([ \\t　:：0-9一二三四五六七八九十].*|$)"
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
        val rawText = if (file.isFile) {
            decode(file.readBytes())
        } else {
            ""
        }
        val list = splitLinesFast(rawText)
        if (book.totalChapters != list.size) {
            library.updateTotalChapters(bookId, list.size)
        }
        synchronized(cache) { cache[bookId] = list }
        list
    }

    override suspend fun chapterCount(bookId: String): Int = chapters(bookId).size

    override suspend fun chapterTitle(bookId: String, index: Int): String {
        val list = chapters(bookId)
        if (list.isEmpty()) return "正文"
        return list[index.coerceIn(0, list.lastIndex)].first
    }

    override suspend fun chapterText(bookId: String, index: Int): CharSequence {
        val list = chapters(bookId)
        if (list.isEmpty()) return ""
        return list[index.coerceIn(0, list.lastIndex)].second
    }

    private fun decode(b: ByteArray): String {
        if (b.isEmpty()) return ""
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()
        } catch (_: CharacterCodingException) {
            String(b, Charset.forName("GB18030"))
        }
    }

    private fun extractChapterHeader(trimmedLine: String): String? {
        if (trimmedLine.length < 2 || trimmedLine.length > 65) return null
        if (trimmedLine.startsWith("【") && trimmedLine.endsWith("】")) {
            val inner = trimmedLine.substring(1, trimmedLine.length - 1).trim()
            if (inner.isNotEmpty() && inner != "内容简介") {
                return inner
            }
            return null
        }
        if (stdChapterLineRegex.matches(trimmedLine) || specialChapterLineRegex.matches(trimmedLine)) {
            return trimmedLine
        }
        return null
    }

    private fun isSeparatorOrFooterLine(trimmed: String): Boolean {
        if (trimmed.length >= 20 && (trimmed.all { it == '=' } || trimmed.all { it == '-' })) return true
        if (trimmed == "由 fanqie-novel-downloader 导出") return true
        return false
    }

    private fun splitLinesFast(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return listOf("正文" to "【正文】\n　　（本书内容为空）")

        val rawChapters = ArrayList<Pair<String, List<String>>>()
        var currentTitle: String? = null
        var currentParas = ArrayList<String>()

        for (rawLine in text.lineSequence()) {
            val trimmed = rawLine.trim().replace("\u200b", "").replace("\ufeff", "")
            if (trimmed.isEmpty() || isSeparatorOrFooterLine(trimmed)) continue

            val headerTitle = extractChapterHeader(trimmed)
            if (headerTitle != null) {
                if (currentTitle != null || currentParas.isNotEmpty()) {
                    rawChapters.add((currentTitle ?: "书籍信息") to currentParas)
                }
                currentTitle = headerTitle
                currentParas = ArrayList()
            } else {
                val cleanPara = trimmed.removePrefix("　　").trim()
                if (cleanPara.isNotEmpty()) {
                    currentParas.add("　　$cleanPara")
                }
            }
        }
        if (currentTitle != null || currentParas.isNotEmpty()) {
            rawChapters.add((currentTitle ?: "全文") to currentParas)
        }

        if (rawChapters.isEmpty()) {
            return listOf("正文" to "【正文】\n　　（暂无可读正文）")
        }

        val finalChapters = ArrayList<Pair<String, String>>(rawChapters.size)
        val maxSegmentChars = 9000

        for ((title, paras) in rawChapters) {
            if (paras.isEmpty()) {
                finalChapters.add(title to "【$title】\n　　（本章无正文）")
                continue
            }
            var segIndex = 1
            val sb = StringBuilder()
            sb.append("【").append(title).append("】\n")

            for (p in paras) {
                if (sb.length + p.length + 1 > maxSegmentChars && sb.length > 200) {
                    val segTitle = if (segIndex == 1 && rawChapters.size > 1) title else if (rawChapters.size == 1) "第 $segIndex 段" else "$title ($segIndex)"
                    finalChapters.add(segTitle to sb.toString().trimEnd())
                    segIndex++
                    sb.setLength(0)
                    val nextHeader = if (rawChapters.size == 1) "第 $segIndex 段" else "$title ($segIndex)"
                    sb.append("【").append(nextHeader).append("】\n")
                }
                sb.append(p).append("\n")
            }
            if (sb.isNotEmpty()) {
                val segTitle = if (segIndex == 1) title else if (rawChapters.size == 1) "第 $segIndex 段" else "$title ($segIndex)"
                finalChapters.add(segTitle to sb.toString().trimEnd())
            }
        }

        return if (finalChapters.isEmpty()) listOf("正文" to "") else finalChapters
    }
}
