package app.jingye.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class LocalTxtSource(private val library: LibraryStore) : BookSource {

    // 单行章节标题匹配（仅对 <= 65 字的单行做匹配，绝不在整本数百万字字符串上做多行回溯）
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

    /** UTF-8 (含 BOM) 优先，严格解码失败则按 GB18030 解码。 */
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

    /** 判断单行是否为章节标题，若是则返回干净的章节标题，否则返回 null。 */
    private fun extractChapterHeader(trimmedLine: String): String? {
        if (trimmedLine.length < 2 || trimmedLine.length > 65) return null
        // 1. fanqie-novel-downloader 导出的【章节名】格式（排除【内容简介】）
        if (trimmedLine.startsWith("【") && trimmedLine.endsWith("】")) {
            val inner = trimmedLine.substring(1, trimmedLine.length - 1).trim()
            if (inner.isNotEmpty() && inner != "内容简介") {
                return inner
            }
            return null
        }
        // 2. 常规小说章节标题：第X章 / 楔子 / 序章 / 番外
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

    /** O(N) 单遍扫描按行分章，并对超长单章自动切分为 <= 9000 字的分段，保证 StaticLayout 秒开。 */
    private fun splitLinesFast(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return listOf("正文" to "　　（本书内容为空）")

        val rawChapters = ArrayList<Pair<String, List<String>>>()
        var currentTitle: String? = null
        var currentParas = ArrayList<String>()

        for (rawLine in text.lineSequence()) {
            val trimmed = rawLine.trim().replace("\u200b", "").replace("\ufeff", "")
            if (trimmed.isEmpty() || isSeparatorOrFooterLine(trimmed)) continue

            val headerTitle = extractChapterHeader(trimmed)
            if (headerTitle != null) {
                if (currentTitle != null || currentParas.isNotEmpty()) {
                    rawChapters.add((currentTitle ?: "前言") to currentParas)
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
            return listOf("正文" to "　　（暂无可读正文）")
        }

        // 将每一章组装为正文，若某章超过 9000 字（如未分章的纯文本大文件），按段落安全切分
        val finalChapters = ArrayList<Pair<String, String>>(rawChapters.size)
        val maxSegmentChars = 9000

        for ((title, paras) in rawChapters) {
            if (paras.isEmpty()) {
                finalChapters.add(title to "【$title】\n\n　　（本章无正文）")
                continue
            }
            var segIndex = 1
            val sb = StringBuilder()
            sb.append("【").append(title).append("】\n\n")

            for (p in paras) {
                if (sb.length + p.length + 2 > maxSegmentChars && sb.length > 200) {
                    val segTitle = if (segIndex == 1 && rawChapters.size > 1) title else if (rawChapters.size == 1) "第 $segIndex 段" else "$title ($segIndex)"
                    finalChapters.add(segTitle to sb.toString().trimEnd())
                    segIndex++
                    sb.setLength(0)
                    val nextHeader = if (rawChapters.size == 1) "第 $segIndex 段" else "$title ($segIndex)"
                    sb.append("【").append(nextHeader).append("】\n\n")
                }
                sb.append(p).append("\n\n")
            }
            if (sb.isNotEmpty()) {
                val segTitle = if (segIndex == 1) title else if (rawChapters.size == 1) "第 $segIndex 段" else "$title ($segIndex)"
                finalChapters.add(segTitle to sb.toString().trimEnd())
            }
        }

        return if (finalChapters.isEmpty()) listOf("正文" to "") else finalChapters
    }
}
