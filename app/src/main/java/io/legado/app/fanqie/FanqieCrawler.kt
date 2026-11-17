package io.legado.app.fanqie

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class FanqieCrawler(
    private val cacheFile: File,
    private val appApiIntervalSec: Int = 6,
    private val timeoutMs: Int = 20000
) {
    data class DownloadOutcome(
        val metadata: FanqieMetadata,
        val txtBytes: ByteArray,
        val builtToc: List<FanqieBuiltTocItem>,
        val downloadedChapters: Int,
        val remoteTotalChapters: Int,
        val latestChapterTitle: String,
        val isPartial: Boolean,
        val updatedCache: Map<String, CachedChapterEntry>
    )

    companion object {
        private const val LANDING_API = "https://api.fqnovel.com/novel_ug/share/landing_page"
        private const val DIRECTORY_API = "https://fanqienovel.com/api/reader/directory/detail"
        private const val READER_PAGE = "https://fanqienovel.com/reader/%s"
        private const val PC_PAGE = "https://fanqienovel.com/page/%s"

        private val SEARCH_ENDPOINTS = listOf(
            "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/?device_platform=android&parent_enterfrom=novel_channel_search.tab.&offset=0&aid=1967&q=%s",
            "https://api-lf.fanqiesdk.com/api/novel/channel/homepage/search/search/v1/?device_platform=android&parent_enterfrom=novel_channel_search.tab.&offset=0&aid=1967&q=%s",
            "https://fanqienovel.com/api/author/search/search_book/v1?filter=127,127,127,127&page_count=18&page_index=0&query_type=0&query_word=%s"
        )

        private val TAG_RE = Regex("<[^>]+>")
        private val ZERO_WIDTH_RE = Regex("[\\u200b\\u200c\\u200d\\u2060\\ufeff]")
        private val WS_RE = Regex("[ \\t\\r\\f\\u000B\\u00a0]+")

        private fun cleanHighlightText(raw: String): String =
            TAG_RE.replace(raw, "").replace("《", "").replace("》", "").trim()

        fun normalizeCoverUrl(rawUrl: String?): String {
            return FanqieAppApi.normalizeImageUrl(rawUrl)
        }

        fun extractCoverUrl(obj: JSONObject?): String {
            if (obj == null) return ""
            val directKeys = listOf(
                "thumb_url", "cover_url", "expand_thumb_url",
                "detail_page_thumb_url", "audio_thumb_url_hd",
                "horiz_thumb_url", "avatar_url", "thumb_uri", "uri"
            )
            for (k in directKeys) {
                val v = normalizeCoverUrl(obj.optString(k, ""))
                if (v.startsWith("http")) return v
            }
            val nestedObjects = listOfNotNull(
                obj.optJSONObject("book_info"),
                obj.optJSONObject("book_data"),
                obj.optJSONObject("data")
            )
            for (nested in nestedObjects) {
                for (k in directKeys) {
                    val v = normalizeCoverUrl(nested.optString(k, ""))
                    if (v.startsWith("http")) return v
                }
            }
            return ""
        }

        private val HAS_CHAPTER_PREFIX_RE = Regex(
            "^(?:第[零一二三四五六七八九十百千万〇两0-9]+[章节回集部篇幕卷]|Chapter\\s*\\d+|\\d+[.、\\s:：]|序[章言幕]?|楔子|引子|尾声|番外|后记|终章)",
            RegexOption.IGNORE_CASE
        )
        private val DUP_CHAPTER_PREFIX_RE = Regex(
            "^(第[零一二三四五六七八九十百千万〇两0-9]+[章节回集部篇幕])[\\s:：·—-]+\\1[\\s:：·—-]*"
        )
        private val HAS_VOLUME_PREFIX_RE = Regex(
            "^第[零一二三四五六七八九十百千万〇两0-9]+[卷部集篇册][\\s:：·—-]*"
        )

        private val INLINE_IMG_LINE_RE = Regex("<img\\s+[^>]*src\\s*=\\s*\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)

        /**
         * 提取章节段落列表中的所有内嵌图片 URL
         */
        fun extractImageUrls(paragraphs: List<String>): List<String> {
            val urls = ArrayList<String>()
            for (p in paragraphs) {
                val m = INLINE_IMG_LINE_RE.find(p)
                if (m != null) {
                    val u = FanqieAppApi.normalizeImageUrl(m.groupValues[1])
                    if (u.isNotEmpty() && u !in urls) {
                        urls.add(u)
                    }
                }
            }
            return urls
        }

        /**
         * 严格还原番茄小说官方原生章节命名 (v0.4.5)：
         * 1. 清除零宽字符、换行与多余空白；
         * 2. 清理接口偶发的重复前缀（如「第1章 第1章 惊蛰」->「第1章 惊蛰」）；
         * 3. 100% 保留番茄目录中的原始章节命名（如「楔子」「番外」「001 惊蛰」「第1章 惊蛰」），不再强行拼接前缀；仅在标题为空时以「第X章」兜底。
         */
        fun normalizeChapterTitle(rawTitle: String, fallbackIndex: Int): String {
            var cleaned = ZERO_WIDTH_RE.replace(rawTitle, "")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace(WS_RE, " ")
                .trim()
            if (cleaned.isEmpty()) return "第${fallbackIndex}章"
            cleaned = DUP_CHAPTER_PREFIX_RE.replace(cleaned, "$1 ")
            return cleaned
        }

        /**
         * 严格还原番茄小说官方原生分卷命名 (v0.4.5)：
         * 100% 保留番茄目录中的原始分卷名（如「第一卷 灾厄降临」「正文卷」「番外卷」），仅当分卷名为空时默认使用「正文卷」。
         */
        fun normalizeVolumeTitle(rawVolumeName: String, volumeIndex: Int): String {
            val cleaned = ZERO_WIDTH_RE.replace(rawVolumeName, "")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace(WS_RE, " ")
                .trim()
            if (cleaned.isEmpty()) return if (volumeIndex <= 0) "正文卷" else "第${volumeIndex + 1}卷"
            return cleaned
        }

        /**
         * 深度清洗并还原 HTML 片段的原生排版（复用 FanqieAppApi.parseChapterXhtml 的结构化解析能力）。
         */
        fun cleanParagraphs(htmlFragment: String, expectedTitle: String = ""): List<String> {
            if (htmlFragment.isBlank()) return emptyList()
            val (_, structured) = FanqieAppApi.parseChapterXhtml(htmlFragment, expectedTitle)
            if (structured.isNotEmpty()) return structured

            val withNl = htmlFragment.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            val noTags = TAG_RE.replace(withNl, "")
            val unescaped = try {
                Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString()
            } catch (_: Throwable) {
                noTags
            }
            val paragraphs = ArrayList<String>()
            for (line in unescaped.lines()) {
                val clean = ZERO_WIDTH_RE.replace(line, "")
                    .replace("\u3000", " ")
                    .replace(WS_RE, " ")
                    .trim()
                if (clean.isNotEmpty() && !FanqieAppApi.isDuplicateTitleLine(clean, expectedTitle)) {
                    paragraphs.add(clean)
                }
            }
            return paragraphs
        }

        fun extractReaderParagraphs(pageHtml: String, expectedTitle: String = ""): List<String> {
            if (pageHtml.isBlank()) return emptyList()
            var index = pageHtml.indexOf("muye-reader-content noselect")
            if (index < 0) index = pageHtml.indexOf("muye-reader-content")
            if (index < 0) return emptyList()
            var segment = pageHtml.substring(index, minOf(index + 600_000, pageHtml.length))
            val end = segment.indexOf("</div></div>")
            if (end > 0) segment = segment.substring(0, end + "</div>".length)
            return cleanParagraphs(segment, expectedTitle)
        }

        /**
         * 格式化单章正文段落（保留场景分割线、小节副标题、引文及章末作家的话）。
         */
        fun formatChapterBody(paragraphs: List<String>): String {
            val sb = StringBuilder()
            val cleanList = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
            for ((idx, p) in cleanList.withIndex()) {
                when {
                    p.startsWith("<img ") && p.contains("src=") -> {
                        // 正文插图独立成段，不加全角缩进，供阅读器 TextChapterLayout 原生居中排版
                        sb.append(p)
                    }
                    p == FanqieAppApi.SCENE_BREAK_MARKER -> {
                        sb.append("　　* * *")
                    }
                    p == "—— 作家的话 ——" -> {
                        sb.append("　　────────────────────\n\n　　〖作家的话〗")
                    }
                    p.startsWith("〖") && p.endsWith("〗") -> {
                        sb.append("　　").append(p)
                    }
                    else -> {
                        sb.append("　　").append(p)
                    }
                }
                if (idx < cleanList.lastIndex) {
                    sb.append("\n\n")
                }
            }
            return sb.toString()
        }

        /**
         * 二级章节标题采用 `### 标题 ###`，避免与网文正文中的 `【叮！系统面板】` 冲突。
         */
        fun formatChapter(title: String, paragraphs: List<String>): String {
            val body = formatChapterBody(paragraphs)
            return "### $title ###\n\n$body\n\n"
        }

        /**
         * 核心构建器：合成含【一级分卷目录 + 二级章节目录】与精排正文的 UTF-8 TXT 字节流，
         * 同时精确计算每个一级分卷（isVolume = true）与每个二级章节（isVolume = false）在 UTF-8 文件中的起止字节偏移（startByte / endByte）。
         */
        fun buildBookTxtWithToc(
            metadata: FanqieMetadata,
            chapters: List<FanqieChapterItem>,
            cacheMap: Map<String, CachedChapterEntry>,
            downloaded: Int,
            total: Int,
            failedCount: Int,
            includeTocIndexInText: Boolean = false
        ): Pair<ByteArray, List<FanqieBuiltTocItem>> {
            val out = ByteArrayOutputStream(256 * 1024)
            // 写入 UTF-8 BOM (3 字节)，与 Legado TextFile 默认 UTF-8 BOM 检测保持一致
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

            fun writeUtf8(str: String) {
                val bytes = str.toByteArray(Charsets.UTF_8)
                out.write(bytes)
            }

            // 建立按 index 兜底映射，兼容旧版缓存或首页预加载缓存
            val cacheByIndex = HashMap<Int, CachedChapterEntry>(cacheMap.size * 2)
            for (entry in cacheMap.values) {
                if (entry.index > 0 && (entry.formattedText.isNotBlank() || entry.paragraphs.isNotEmpty())) {
                    cacheByIndex.putIfAbsent(entry.index, entry)
                }
            }
            fun resolveCached(ch: FanqieChapterItem): CachedChapterEntry? {
                return cacheMap[ch.cacheKey()] ?: cacheByIndex[ch.index]
            }

            val exported = if (failedCount > 0 && downloaded < total) {
                "$downloaded/$total 章（$failedCount 章待续下）"
            } else {
                "$downloaded/$total 章"
            }

            // 头部书籍扉页信息
            val headerLines = ArrayList<String>()
            headerLines += "=".repeat(46)
            headerLines += "书名：${metadata.title}"
            headerLines += "作者：${metadata.author}"
            if (metadata.score.isNotBlank() && metadata.score != "暂无") {
                headerLines += "评分：${metadata.score}"
            }
            headerLines += "字数：${metadata.formattedWordCount()}"
            headerLines += "状态：${metadata.serialStatus}"
            if (metadata.volumes.isNotEmpty()) {
                headerLines += "一级分卷：共 ${metadata.volumes.size} 卷"
            }
            headerLines += "二级章节：共 $total 章，已缓存 $exported"
            if (metadata.effectiveLatestChapter().isNotBlank()) {
                headerLines += "最新：${metadata.effectiveLatestChapter()}"
            }
            headerLines += "=".repeat(46)
            headerLines += ""
            if (metadata.intro.isNotBlank()) {
                headerLines += "〖内容简介〗"
                for (introLine in metadata.intro.trim().lines()) {
                    val trimmed = introLine.trim()
                    if (trimmed.isNotEmpty()) {
                        headerLines += "　　$trimmed"
                    }
                }
                headerLines += ""
                headerLines += "-".repeat(46)
                headerLines += ""
            }

            // 筛选出已下载有正文的二级章节项
            val availableEntries = ArrayList<Pair<FanqieChapterItem, CachedChapterEntry>>()
            for (ch in chapters) {
                val cached = resolveCached(ch)
                if (cached != null && (cached.formattedText.isNotBlank() || cached.paragraphs.isNotEmpty())) {
                    availableEntries.add(ch to cached)
                }
            }

            // 统计每个一级分卷下的章节总数与字数
            val volChapterCounts = HashMap<Int, Int>()
            val volWordCounts = HashMap<Int, Int>()
            for ((ch, cached) in availableEntries) {
                val vIdx = ch.volumeIndex.coerceAtLeast(0)
                volChapterCounts[vIdx] = (volChapterCounts[vIdx] ?: 0) + 1
                val wc = cached.resolvedWordCount().takeIf { it > 0 } ?: ch.wordCount
                volWordCounts[vIdx] = (volWordCounts[vIdx] ?: 0) + wc
            }

            // 若为外部导出模式，生成完整的原生【一级分卷 / 二级章节目录索引】
            if (includeTocIndexInText && availableEntries.isNotEmpty()) {
                headerLines += "〖原生多级目录索引（一级分卷 / 二级章节）〗"
                var lastIdxVol = -1
                var lastIdxVolName = ""
                for ((ch, cached) in availableEntries) {
                    val vIdx = ch.volumeIndex.coerceAtLeast(0)
                    val rawVol = ch.volumeName.ifBlank { cached.volumeName }.trim().ifEmpty { "正文卷" }
                    val normVol = normalizeVolumeTitle(rawVol, vIdx)
                    if (vIdx != lastIdxVol || normVol != lastIdxVolName) {
                        lastIdxVol = vIdx
                        lastIdxVolName = normVol
                        val vCnt = volChapterCounts[vIdx] ?: 0
                        headerLines += ""
                        headerLines += "◆ [一级目录] $normVol（共 $vCnt 章）"
                    }
                    val cTitle = normalizeChapterTitle(cached.title.ifBlank { ch.title }, ch.index)
                    headerLines += "  ├─ $cTitle"
                }
                headerLines += ""
                headerLines += "=".repeat(46)
                headerLines += ""
            }

            writeUtf8(headerLines.joinToString("\n") + "\n")

            val builtToc = ArrayList<FanqieBuiltTocItem>(availableEntries.size + 16)
            var currentVolIdx = -1
            var currentVolumeName = ""

            for ((ch, cached) in availableEntries) {
                val vIdx = ch.volumeIndex.coerceAtLeast(0)
                val rawVol = ch.volumeName.ifBlank { cached.volumeName }.trim().ifEmpty { "正文卷" }
                val normVol = normalizeVolumeTitle(rawVol, vIdx)

                // 当进入新的一级分卷时，写入一级分卷标题块并生成 isVolume = true 的一级目录项（确保 startByte < endByte）
                if (vIdx != currentVolIdx || normVol != currentVolumeName) {
                    currentVolIdx = vIdx
                    currentVolumeName = normVol
                    val vCnt = volChapterCounts[vIdx] ?: 0
                    val vWords = volWordCounts[vIdx] ?: 0
                    writeUtf8("=== $normVol ===\n")
                    val volStart = out.size().toLong()
                    writeUtf8("　　【本卷共 $vCnt 章】\n\n")
                    val volEnd = out.size().toLong()
                    builtToc.add(
                        FanqieBuiltTocItem(
                            index = builtToc.size,
                            title = normVol,
                            isVolume = true,
                            startByte = volStart,
                            endByte = volEnd,
                            wordCount = vWords,
                            volumeIndex = vIdx,
                            volumeName = "共${vCnt}章",
                            chapterIndex = 0,
                            indexInVolume = 0,
                            itemId = "vol_$vIdx"
                        )
                    )
                }

                val cTitle = normalizeChapterTitle(cached.title.ifBlank { ch.title }, ch.index)
                val paras = cached.resolvedParagraphs()
                val bodyText = if (paras.isNotEmpty()) {
                    formatChapterBody(paras)
                } else {
                    cached.formattedText
                        .removePrefix("### $cTitle ###")
                        .removePrefix("### ${cached.title} ###")
                        .removePrefix("【$cTitle】")
                        .removePrefix("【${cached.title}】")
                        .trim()
                        .let { if (it.startsWith("　　")) it else "　　$it" }
                }

                // 写入二级章节标题行
                writeUtf8("### $cTitle ###\n\n")
                // 记录二级章节正文起始字节偏移（紧跟在章节标题之后，避免阅读器正文重复渲染标题）
                val startByte = out.size().toLong()
                writeUtf8("$bodyText\n\n")
                val endByte = out.size().toLong()

                val wordCnt = cached.resolvedWordCount().let {
                    if (it > 0) it else bodyText.replace(WS_RE, "").length
                }

                builtToc.add(
                    FanqieBuiltTocItem(
                        index = builtToc.size,
                        title = cTitle,
                        isVolume = false,
                        startByte = startByte,
                        endByte = endByte,
                        wordCount = wordCnt,
                        volumeIndex = vIdx,
                        volumeName = currentVolumeName,
                        chapterIndex = ch.index,
                        indexInVolume = ch.indexInVolume.coerceAtLeast(1),
                        itemId = ch.itemId
                    )
                )
            }

            if (includeTocIndexInText) {
                val footer = buildString {
                    append("=".repeat(46)).append("\n")
                    append("全书导出完毕 · 共 ${metadata.volumes.size.coerceAtLeast(1)} 卷 $downloaded 章\n")
                    append("=".repeat(46)).append("\n")
                }
                writeUtf8(footer)
            }

            return out.toByteArray() to builtToc
        }

        /**
         * 兼容旧调用方的 TXT 格式化入口
         */
        fun formatBookTxt(
            metadata: FanqieMetadata,
            chapters: List<String>,
            downloaded: Int,
            total: Int,
            failedCount: Int
        ): ByteArray {
            val dummyItems = chapters.mapIndexed { idx, raw ->
                val firstLine = raw.lineSequence().firstOrNull()?.trim().orEmpty()
                val title = when {
                    firstLine.startsWith("###") && firstLine.endsWith("###") ->
                        firstLine.removeSurrounding("###").trim()
                    firstLine.startsWith("【") && firstLine.endsWith("】") ->
                        firstLine.removeSurrounding("【", "】").trim()
                    else -> "第${idx + 1}章"
                }
                FanqieChapterItem(index = idx + 1, itemId = "idx_${idx + 1}", title = title)
            }
            val dummyCache = LinkedHashMap<String, CachedChapterEntry>()
            for ((idx, raw) in chapters.withIndex()) {
                val item = dummyItems[idx]
                dummyCache[item.cacheKey()] = CachedChapterEntry(
                    key = item.cacheKey(),
                    index = item.index,
                    title = item.title,
                    formattedText = raw
                )
            }
            return buildBookTxtWithToc(
                metadata = metadata,
                chapters = dummyItems,
                cacheMap = dummyCache,
                downloaded = downloaded,
                total = total,
                failedCount = failedCount,
                includeTocIndexInText = false
            ).first
        }
    }

    private val bookCache = HashMap<String, JSONObject>()

    private fun httpGetText(url: String, mobile: Boolean = false): Pair<Int, String> {
        var lastErr: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", if (mobile) FanqieParser.MOBILE_UA else FanqieParser.DESKTOP_UA)
                    setRequestProperty("Referer", if (mobile) "https://changdunovel.com/" else "https://fanqienovel.com/")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                conn.disconnect()
                if ("验证码中间页" in text || ("middle_page_loading" in text && "muye-reader-content" !in text)) {
                    throw FanqieException("触发番茄风控验证码，请稍后重试")
                }
                return code to text
            } catch (e: FanqieException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                Thread.sleep(400L * (attempt + 1))
            }
        }
        throw FanqieException("网络请求失败：${lastErr?.message ?: "未知错误"}", lastErr)
    }

    fun searchBooks(keyword: String): List<FanqieSearchItem> {
        val cleanKw = keyword.trim()
        if (cleanKw.isEmpty()) throw FanqieException("请输入要搜索的书名或作者名")
        val encoded = URLEncoder.encode(cleanKw, "UTF-8")

        val seenIds = HashSet<String>()
        val results = ArrayList<FanqieSearchItem>()
        var lastError: Exception? = null

        for (tpl in SEARCH_ENDPOINTS) {
            val url = String.format(tpl, encoded)
            try {
                val (code, text) = httpGetText(url, mobile = "snssdk" in tpl || "fanqiesdk" in tpl)
                if (code != 200 || text.isBlank()) continue
                val root = JSONObject(text)
                val data = root.optJSONObject("data") ?: root
                parseSearchJsonCandidates(data, seenIds, results)
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (results.isEmpty() && lastError != null) {
            throw FanqieException("搜索请求失败：${lastError.message}")
        }
        return results
    }

    private fun parseSearchJsonCandidates(
        data: JSONObject,
        seenIds: MutableSet<String>,
        out: MutableList<FanqieSearchItem>
    ) {
        val candidateArrays = listOfNotNull(
            data.optJSONArray("ret_data"),
            data.optJSONArray("search_book_data_list"),
            data.optJSONArray("book_list"),
            data.optJSONArray("data")
        )
        for (arr in candidateArrays) {
            extractBooksFromArray(arr, seenIds, out)
        }
        val tabs = data.optJSONArray("search_tabs")
        if (tabs != null) {
            for (t in 0 until tabs.length()) {
                val tabObj = tabs.optJSONObject(t) ?: continue
                val tabData = tabObj.optJSONArray("data") ?: continue
                extractBooksFromArray(tabData, seenIds, out)
            }
        }
    }

    private fun extractBooksFromArray(
        arr: JSONArray,
        seenIds: MutableSet<String>,
        out: MutableList<FanqieSearchItem>
    ) {
        for (i in 0 until arr.length()) {
            val rawItem = arr.optJSONObject(i) ?: continue
            val bookObj = rawItem.optJSONObject("book_info")
                ?: rawItem.optJSONArray("book_data")?.optJSONObject(0)
                ?: rawItem

            val bookId = bookObj.optString("book_id")
                .ifBlank { bookObj.optString("bookId") }
                .trim()
            if (bookId.length < 10 || !seenIds.add(bookId)) continue

            val rawTitle = bookObj.optString("book_name")
                .ifBlank { bookObj.optString("title") }
            val title = cleanHighlightText(rawTitle)
            if (title.isBlank()) continue

            val rawAuthor = bookObj.optString("author")
                .ifBlank { bookObj.optJSONObject("author_info")?.optString("name") ?: "" }
            val author = cleanHighlightText(rawAuthor).ifBlank { "未知作者" }

            val category = cleanHighlightText(bookObj.optString("category", ""))
            val score = bookObj.optString("score", "").takeIf { it.isNotBlank() && it != "0" } ?: ""
            val wordCount = bookObj.optInt("word_number", 0).let {
                if (it > 0) it else bookObj.optInt("word_count", 0)
            }
            val creationStatus = bookObj.optInt("creation_status", -1)
            val serialStatus = when (creationStatus) {
                0 -> "已完结"
                1 -> "连载中"
                else -> bookObj.optString("CreationStatus", "")
            }
            val intro = cleanHighlightText(
                bookObj.optString("abstract").ifBlank { bookObj.optString("intro", "") }
            )
            val coverUrl = extractCoverUrl(bookObj).ifBlank { extractCoverUrl(rawItem) }

            out.add(
                FanqieSearchItem(
                    bookId = bookId,
                    title = title,
                    author = author,
                    category = category,
                    score = score,
                    wordCount = wordCount,
                    serialStatus = serialStatus,
                    intro = intro,
                    coverUrl = coverUrl
                )
            )
        }
    }

    private fun fetchLandingData(bookId: String, forceRefresh: Boolean = false): JSONObject {
        if (!forceRefresh) {
            bookCache[bookId]?.let { return it }
        }
        val url = "$LANDING_API?aid=1967&series_id=0&performance_optimization=1&share_type=11&book_id=$bookId"
        val (_, text) = httpGetText(url, mobile = true)
        val payload = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw FanqieException("接口返回了非 JSON 数据，可能触发了风控")
        }
        val errNo = payload.optInt("err_no", 0)
        if (errNo != 0) {
            throw FanqieException("书籍信息获取失败（err_no=$errNo）：${payload.optString("err_tips")}")
        }
        val bookData = payload.optJSONObject("data")?.optJSONObject("book_data")
        if (bookData == null || bookData.optString("title").isBlank()) {
            throw FanqieException("没有找到这本书，可能已下架或 book_id 不正确")
        }
        bookCache[bookId] = bookData
        return bookData
    }

    private fun checkPcAvailable(bookId: String): Boolean {
        return try {
            val (code, text) = httpGetText(String.format(PC_PAGE, bookId), mobile = false)
            code == 200 && "chapter-item" in text
        } catch (_: Exception) {
            false
        }
    }

    fun fetchMetadata(bookId: String, forceRefresh: Boolean = false): FanqieMetadata {
        val bookData = fetchLandingData(bookId, forceRefresh = forceRefresh)
        val authorObj = bookData.optJSONObject("author")
        val landingChapterCount = bookData.optInt("serial_count", 0)
        val wordCount = bookData.optInt("word_count", 0)
        val creationStatus = bookData.optInt("creation_status", -1)
        val serialStatus = when (creationStatus) {
            0 -> "已完结"
            1 -> "连载中"
            else -> "未知状态"
        }
        val firstChapters = bookData.optJSONArray("first_chapters_content")
        val firstCount = firstChapters?.length() ?: 0

        val titlesArr = bookData.optJSONArray("item_names")
        val titles = ArrayList<String>()
        if (titlesArr != null) {
            for (i in 0 until titlesArr.length()) {
                titles.add(normalizeChapterTitle(titlesArr.optString(i), i + 1))
            }
        }

        // 拉取完整原生一级分卷与二级章节目录
        val (rawVolumes, dirChapters) = try {
            fetchDirectoryWithVolumes(bookId)
        } catch (_: Exception) {
            emptyList<FanqieVolume>() to emptyList()
        }

        // 关键修复：当官方目录接口返回了真实可下载章节列表 (dirChapters) 时，
        // 严格以 dirChapters.size 作为权威章节总数，剔除 serial_count 中已删除/未过审的幽灵章节！
        val totalChapterCount = when {
            dirChapters.isNotEmpty() -> dirChapters.size
            titles.isNotEmpty() -> titles.size
            else -> landingChapterCount
        }
        val availableChapters = if (totalChapterCount > 0) totalChapterCount else firstCount
        val latestTitle = dirChapters.lastOrNull()?.title?.takeIf { it.isNotBlank() }
            ?: titles.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: bookData.optString("last_chapter_title", "")

        val resolvedVolumes = if (rawVolumes.isNotEmpty()) {
            rawVolumes
        } else if (dirChapters.isNotEmpty()) {
            listOf(FanqieVolume(index = 0, name = normalizeVolumeTitle("正文卷", 0), chapterCount = dirChapters.size))
        } else {
            emptyList()
        }

        val meta = FanqieMetadata(
            bookId = bookId,
            title = bookData.optString("title", "未知书名"),
            author = authorObj?.optString("name")?.ifBlank { "未知作者" } ?: "未知作者",
            score = bookData.optString("score", "暂无").ifBlank { "暂无" },
            coverUrl = extractCoverUrl(bookData)
                .ifBlank { FanqieChapterCacheStore.loadMetadata(bookId)?.coverUrl?.let { normalizeCoverUrl(it) }.orEmpty() }
                .ifBlank {
                    try {
                        val titleStr = bookData.optString("title", "").trim()
                        if (titleStr.isNotEmpty()) {
                            val searchMatches = searchBooks(titleStr)
                            val matched = searchMatches.firstOrNull { it.bookId == bookId && it.coverUrl.isNotBlank() }
                                ?: searchMatches.firstOrNull { it.title == titleStr && it.coverUrl.isNotBlank() }
                            normalizeCoverUrl(matched?.coverUrl)
                        } else ""
                    } catch (_: Exception) {
                        ""
                    }
                },
            intro = bookData.optString("intro", "").trim(),
            wordCount = wordCount,
            chapterCount = totalChapterCount,
            serialStatus = serialStatus,
            availableChapters = availableChapters,
            pcAvailable = checkPcAvailable(bookId),
            chapterTitles = if (dirChapters.isNotEmpty()) dirChapters.map { it.title } else titles,
            latestChapterTitle = latestTitle,
            volumes = resolvedVolumes,
            structuredChapters = dirChapters
        )
        FanqieChapterCacheStore.saveMetadata(meta)
        return meta
    }

    /**
     * 双通道抓取番茄原生【一级目录（分卷）】与【二级目录（章节）】：
     * 1. 优先解析 Web 目录详情接口 `/api/reader/directory/detail` 中的 `volumeNameList` 与 `chapterListWithVolume`；
     * 2. 若 Web 接口受限或为空，自动调用 `FanqieAppApi.fetchAppDirectory` 原生 APP 目录接口兜底；
     * 3. 完整保留每一章所属的一级分卷 0-based 序号、规范化分卷名、卷内二级序号、原生章节标题與字数。
     */
    fun fetchDirectoryWithVolumes(bookId: String): Pair<List<FanqieVolume>, List<FanqieChapterItem>> {
        val chapters = ArrayList<FanqieChapterItem>()
        val volumes = ArrayList<FanqieVolume>()
        val seenItemIds = HashSet<String>()

        try {
            val (_, text) = httpGetText("$DIRECTORY_API?bookId=$bookId", mobile = false)
            val data = JSONObject(text).optJSONObject("data")
            val volNamesArr = data?.optJSONArray("volumeNameList")
                ?: data?.optJSONArray("allVolumeNames")
            val volChaptersArr = data?.optJSONArray("chapterListWithVolume")

            if (volChaptersArr != null && volChaptersArr.length() > 0) {
                for (v in 0 until volChaptersArr.length()) {
                    val volArr = volChaptersArr.optJSONArray(v) ?: continue
                    val listVolName = volNamesArr?.optString(v, "")?.trim().orEmpty()
                    var resolvedVolName = listVolName
                    for (i in 0 until volArr.length()) {
                        val item = volArr.optJSONObject(i) ?: continue
                        val itemVolName = item.optString("volume_name", item.optString("volumeName", "")).trim()
                        if (resolvedVolName.isEmpty() && itemVolName.isNotEmpty()) {
                            resolvedVolName = itemVolName
                            break
                        }
                    }
                    val normVolName = normalizeVolumeTitle(
                        resolvedVolName.ifEmpty { if (volChaptersArr.length() > 1) "第${v + 1}卷" else "正文卷" },
                        v
                    )
                    var volChapterCount = 0

                    for (i in 0 until volArr.length()) {
                        val item = volArr.optJSONObject(i) ?: continue
                        val itemId = item.optString("itemId", "").ifBlank { item.optString("item_id", "") }.trim()
                        if (itemId.isBlank() || !seenItemIds.add(itemId)) continue
                        val locked = item.optBoolean("isChapterLock") ||
                            item.optBoolean("needPay") ||
                            item.optBoolean("isPaidPublication")
                        val cIdx = chapters.size + 1
                        volChapterCount++
                        val rawTitle = item.optString("title", "")
                        val wordCnt = item.optInt("chapterWordNumber", item.optInt("wordCount", 0))
                        val passTime = item.optLong("firstPassTime", 0L)

                        chapters.add(
                            FanqieChapterItem(
                                index = cIdx,
                                itemId = itemId,
                                title = normalizeChapterTitle(rawTitle, cIdx),
                                locked = locked,
                                volumeIndex = v,
                                volumeName = normVolName,
                                indexInVolume = volChapterCount,
                                wordCount = wordCnt,
                                firstPassTime = passTime
                            )
                        )
                    }

                    if (volChapterCount > 0) {
                        volumes.add(
                            FanqieVolume(
                                index = v,
                                name = normVolName,
                                chapterCount = volChapterCount
                            )
                        )
                    }
                }
            }

            if (chapters.isEmpty() && data != null) {
                val allIds = data.optJSONArray("allItemIds")
                val landing = bookCache[bookId]
                val titles = landing?.optJSONArray("item_names")
                val defaultVolName = normalizeVolumeTitle("正文卷", 0)
                if (allIds != null && allIds.length() > 0) {
                    for (i in 0 until allIds.length()) {
                        val idStr = allIds.optString(i).trim()
                        if (idStr.isBlank() || !seenItemIds.add(idStr)) continue
                        val cIdx = chapters.size + 1
                        val t = if (titles != null && i < titles.length()) titles.optString(i) else "第${cIdx}章"
                        chapters.add(
                            FanqieChapterItem(
                                index = cIdx,
                                itemId = idStr,
                                title = normalizeChapterTitle(t, cIdx),
                                locked = false,
                                volumeIndex = 0,
                                volumeName = defaultVolName,
                                indexInVolume = cIdx
                            )
                        )
                    }
                    if (chapters.isNotEmpty()) {
                        volumes.add(FanqieVolume(index = 0, name = defaultVolName, chapterCount = chapters.size))
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 通道二：APP 原生目录接口兜底（解决部分书籍网页端目录不返回或标题缺失问题）
        if (chapters.isEmpty()) {
            try {
                val api = FanqieAppApi(cacheFile = cacheFile, intervalSec = appApiIntervalSec)
                val (appVols, appChs) = api.fetchAppDirectory(bookId)
                if (appChs.isNotEmpty()) {
                    return appVols to appChs
                }
            } catch (_: Exception) {
            }
        }

        // 通道三：本地持久化目录元数据兜底
        if (chapters.isEmpty()) {
            val savedMeta = FanqieChapterCacheStore.loadMetadata(bookId)
            if (savedMeta != null && savedMeta.structuredChapters.isNotEmpty()) {
                return savedMeta.volumes to savedMeta.structuredChapters
            }
        }

        // 通道四：落地页 item_names 兜底（仅当所有目录接口均不可用时）
        if (chapters.isEmpty()) {
            val landing = fetchLandingData(bookId)
            val titles = landing.optJSONArray("item_names")
            val defaultVolName = normalizeVolumeTitle("正文卷", 0)
            if (titles != null) {
                for (i in 0 until titles.length()) {
                    val rawT = titles.optString(i, "第${i + 1}章")
                    chapters.add(
                        FanqieChapterItem(
                            index = i + 1,
                            itemId = null,
                            title = normalizeChapterTitle(rawT, i + 1),
                            locked = false,
                            volumeIndex = 0,
                            volumeName = defaultVolName,
                            indexInVolume = i + 1
                        )
                    )
                }
                if (chapters.isNotEmpty()) {
                    volumes.add(FanqieVolume(index = 0, name = defaultVolName, chapterCount = chapters.size))
                }
            }
        }

        return volumes to chapters
    }

    fun fetchChapterList(bookId: String): List<FanqieChapterItem> =
        fetchDirectoryWithVolumes(bookId).second

    private fun fetchChapterWeb(itemId: String, expectedTitle: String = ""): List<String> {
        val (code, html) = httpGetText(String.format(READER_PAGE, itemId), mobile = false)
        if (code == 404) throw FanqieException("PC 端未上架（可能为 App 独占章节）")
        if (code != 200) throw FanqieException("章节页返回 $code")
        if ("muye-to-vip" in html || "扫码下载APP免费读" in html || "会员登录后" in html) {
            throw FanqieException("会员/付费章节，网页端无法获取全文")
        }
        val rawParas = extractReaderParagraphs(html, expectedTitle)
        if (rawParas.isEmpty()) throw FanqieException("章节正文为空")
        val plain = FanqieDecryptor.decryptText(rawParas.joinToString("\n"))
        return plain.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !FanqieAppApi.isDuplicateTitleLine(it, expectedTitle) }
    }

    /**
     * 支持断点续下、原生一级分卷/二级章节目录还原与精确完成度检测的增量下载方法：
     * - 严格以真实章节列表为准，绝不用 serial_count 填充无 itemId 的幽灵章节；
     * - 同时兼容按 itemId 和按 index 匹配本地已缓存章节；
     * - 对经过 AppAPI + Web 多轮拉取仍为空内容的章节（如作者空章/已撤回公告章）自动写入占位归档，彻底消除「实际已下载完但检测仍有更新却没东西可下」的问题。
     */
    fun downloadBookIncremental(
        bookId: String,
        existingCache: Map<String, CachedChapterEntry> = emptyMap(),
        shouldStop: (() -> Boolean)? = null,
        onCheckpoint: ((
            downloadedCount: Int,
            totalCount: Int,
            latestChapterTitle: String,
            txtBytes: ByteArray,
            builtToc: List<FanqieBuiltTocItem>,
            cacheMap: Map<String, CachedChapterEntry>
        ) -> Unit)? = null,
        onProgress: (FanqieProgress) -> Unit
    ): DownloadOutcome {
        val metadata = fetchMetadata(bookId, forceRefresh = true)
        val bookData = fetchLandingData(bookId)
        val landingArr = bookData.optJSONArray("first_chapters_content")

        val chapters = if (metadata.structuredChapters.isNotEmpty()) {
            metadata.structuredChapters.toMutableList()
        } else {
            fetchChapterList(bookId).toMutableList()
        }

        val landingChapters = ArrayList<List<String>>()
        if (landingArr != null) {
            for (i in 0 until landingArr.length()) {
                val expectedT = chapters.getOrNull(i)?.title.orEmpty()
                landingChapters.add(cleanParagraphs(landingArr.optString(i, ""), expectedT))
            }
        }

        // 仅当目录接口完全为空时，才用 landingChapters 构建章节列表；绝不用 serial_count 填充无 itemId 幽灵章节！
        if (chapters.isEmpty() && landingChapters.isNotEmpty()) {
            val titles = bookData.optJSONArray("item_names")
            val defaultVolName = normalizeVolumeTitle("正文卷", 0)
            for (i in landingChapters.indices) {
                val t = if (titles != null && i < titles.length()) titles.optString(i) else "第${i + 1}章"
                chapters.add(
                    FanqieChapterItem(
                        index = i + 1,
                        itemId = null,
                        title = normalizeChapterTitle(t, i + 1),
                        locked = false,
                        volumeIndex = 0,
                        volumeName = defaultVolName,
                        indexInVolume = i + 1
                    )
                )
            }
        }

        val total = chapters.size.coerceAtLeast(1)
        val latestTitle = chapters.lastOrNull()?.title?.takeIf { it.isNotBlank() }
            ?: metadata.effectiveLatestChapter()

        // 工作缓存：同时支持按 cacheKey(itemId) 和按 index 迁移已有断点缓存，并同步最新一级分卷与二级章节标题
        val workingCache = LinkedHashMap<String, CachedChapterEntry>(maxOf(existingCache.size, total))
        val existingByIndex = HashMap<Int, CachedChapterEntry>(existingCache.size * 2)
        for (entry in existingCache.values) {
            if (entry.index > 0 && (entry.formattedText.isNotBlank() || entry.paragraphs.isNotEmpty())) {
                existingByIndex.putIfAbsent(entry.index, entry)
            }
        }
        // 0.4.7：0.4.6 及以前的缓存在解析时已丢弃 <img>，无法靠复用补出插图；
        // 对仍可重新抓取的章节(有 itemId 且未锁定)视为“待重抓”，先暂存旧正文作兜底，抓取失败时回填，避免丢书。
        val staleOld = LinkedHashMap<String, CachedChapterEntry>()
        for (ch in chapters) {
            val key = ch.cacheKey()
            val old = existingCache[key] ?: existingByIndex[ch.index] ?: continue
            if (old.formattedText.isBlank() && old.paragraphs.isEmpty()) continue
            if (old.isLegacyNoImage() && !ch.itemId.isNullOrBlank() && ch.hasDownloadableId() && !ch.locked) {
                staleOld[key] = old.copy(
                    key = key,
                    index = ch.index,
                    title = normalizeChapterTitle(ch.title.ifBlank { old.title }, ch.index),
                    volumeIndex = ch.volumeIndex.coerceAtLeast(0),
                    volumeName = ch.volumeName.ifBlank { old.volumeName }
                )
                continue
            }
            val paras = old.resolvedParagraphs()
            val newTitle = normalizeChapterTitle(ch.title.ifBlank { old.title }, ch.index)
            workingCache[key] = old.copy(
                key = key,
                index = ch.index,
                title = newTitle,
                volumeIndex = ch.volumeIndex.coerceAtLeast(0),
                volumeName = ch.volumeName.ifBlank { old.volumeName },
                formattedText = if (paras.isNotEmpty()) formatChapter(newTitle, paras) else old.formattedText,
                paragraphs = paras,
                wordCount = if (ch.wordCount > 0) ch.wordCount else old.resolvedWordCount()
            )
        }

        // 先把 landingChapters 前几章如果还没缓存的补进去
        for (ch in chapters) {
            val key = ch.cacheKey()
            if (key !in workingCache && key !in staleOld && ch.index <= landingChapters.size) {
                val paras = landingChapters[ch.index - 1]
                if (paras.isNotEmpty()) {
                    val cTitle = normalizeChapterTitle(ch.title, ch.index)
                    workingCache[key] = CachedChapterEntry(
                        key = key,
                        index = ch.index,
                        title = cTitle,
                        formattedText = formatChapter(cTitle, paras),
                        volumeIndex = ch.volumeIndex.coerceAtLeast(0),
                        volumeName = ch.volumeName,
                        paragraphs = paras,
                        wordCount = if (ch.wordCount > 0) ch.wordCount else paras.sumOf { it.length }
                    )
                }
            }
        }

        val initialCachedCount = chapters.count { it.cacheKey() in workingCache }
        val startMsg = if (staleOld.isNotEmpty()) {
            "检测到旧版缓存不含正文插图，正在重新抓取 ${staleOld.size} 章以补全插图（失败章节会保留旧正文）…"
        } else if (initialCachedCount > 0 && initialCachedCount < total) {
            "断点续下《${metadata.title}》：已缓存 $initialCachedCount/$total 章，继续拉取剩余章节…"
        } else {
            "开始下载《${metadata.title}》（共 ${metadata.volumes.size.coerceAtLeast(1)} 卷 $total 章）"
        }

        onProgress(
            FanqieProgress(
                bookId = bookId,
                status = "start",
                current = initialCachedCount,
                total = total,
                title = metadata.title,
                chapterTitle = if (initialCachedCount > 0) "已命中缓存 $initialCachedCount/$total 章…" else "正在准备一级分卷与二级章节目录…",
                percent = ((initialCachedCount * 100.0) / total).toInt().coerceIn(0, 100),
                filename = null,
                failed = 0,
                message = startMsg,
                latestChapterTitle = latestTitle,
                resumedFromCount = initialCachedCount
            )
        )

        // 筛选出尚未缓存的章节 itemId
        val missingChaptersWithItemId = chapters.filter {
            !it.itemId.isNullOrBlank() && it.cacheKey() !in workingCache
        }
        val missingItemIds = missingChaptersWithItemId.map { it.itemId!! }
        val chapterById = chapters.filter { !it.itemId.isNullOrBlank() }.associateBy { it.itemId!! }
        val attemptedIds = HashSet<String>()

        var appError: String? = null

        if (missingItemIds.isNotEmpty() && shouldStop?.invoke() != true) {
            try {
                val api = FanqieAppApi(cacheFile = cacheFile, intervalSec = appApiIntervalSec)
                api.fetchChapters(
                    itemIds = missingItemIds,
                    bookId = bookId,
                    shouldStop = shouldStop,
                    onBatch = { batch, got ->
                        attemptedIds.addAll(batch)
                        var lastChapterTitleInBatch = ""
                        for (itemId in batch) {
                            val ch = chapterById[itemId] ?: continue
                            val xhtml = got[itemId] ?: continue
                            val (titleText, paras) = FanqieAppApi.parseChapterXhtml(xhtml, ch.title)
                            if (paras.isEmpty()) continue
                            // 0.4.7 诊断：原文里有图片线索但解析结果没有 <img> 行时，把线索片段写入 App 日志，便于确认真实标签格式
                            if (paras.none { it.startsWith("<img ") }) {
                                val hit = Regex("(<img\\b|<image\\b|novel-pic|byteimg|fqnovelpic)", RegexOption.IGNORE_CASE).find(xhtml)
                                if (hit != null) {
                                    val from = (hit.range.first - 60).coerceAtLeast(0)
                                    val to = (hit.range.first + 260).coerceAtMost(xhtml.length)
                                    io.legado.app.constant.AppLog.put("番茄章节「${ch.title}」含图片线索但未解析出插图：" + xhtml.substring(from, to))
                                }
                            }
                            val cTitle = normalizeChapterTitle(
                                ch.title.ifBlank { titleText },
                                ch.index
                            )
                            lastChapterTitleInBatch = cTitle
                            workingCache[ch.cacheKey()] = CachedChapterEntry(
                                key = ch.cacheKey(),
                                index = ch.index,
                                title = cTitle,
                                formattedText = formatChapter(cTitle, paras),
                                volumeIndex = ch.volumeIndex.coerceAtLeast(0),
                                volumeName = ch.volumeName,
                                paragraphs = paras,
                                wordCount = if (ch.wordCount > 0) ch.wordCount else paras.sumOf { it.length }
                            )
                        }

                        val doneNow = chapters.count { it.cacheKey() in workingCache }
                        val pct = ((doneNow * 100.0) / total).toInt().coerceIn(0, 100)
                        onProgress(
                            FanqieProgress(
                                bookId = bookId,
                                status = "downloading",
                                current = doneNow,
                                total = total,
                                title = metadata.title,
                                chapterTitle = lastChapterTitleInBatch,
                                percent = pct,
                                filename = null,
                                failed = 0,
                                message = "已下载 $doneNow/$total 章 · $lastChapterTitleInBatch",
                                latestChapterTitle = latestTitle,
                                resumedFromCount = initialCachedCount
                            )
                        )

                        // 每批次实时保存断点与书籍 TXT + 原生目录偏移表
                        if (onCheckpoint != null && doneNow > 0) {
                            // 待重抓的旧章节仍写入断点，保证中途被杀进程时 TXT/缓存不缺章
                            val ckMap: Map<String, CachedChapterEntry> = if (staleOld.isEmpty()) {
                                workingCache
                            } else {
                                LinkedHashMap<String, CachedChapterEntry>(staleOld).also { it.putAll(workingCache) }
                            }
                            val (checkpointBytes, checkpointToc) = buildBookTxtWithToc(
                                metadata = metadata.copy(
                                    latestChapterTitle = latestTitle,
                                    structuredChapters = chapters
                                ),
                                chapters = chapters,
                                cacheMap = ckMap,
                                downloaded = doneNow,
                                total = total,
                                failedCount = (total - doneNow).coerceAtLeast(0),
                                includeTocIndexInText = false
                            )
                            onCheckpoint(doneNow, total, latestTitle, checkpointBytes, checkpointToc, ckMap)
                        }
                    }
                )
            } catch (e: Exception) {
                appError = e.message ?: e.javaClass.simpleName
            }
        }

        // 对仍然缺失且未暂停的章节尝试网页端兜底
        for (ch in chapters) {
            if (shouldStop?.invoke() == true) break
            val key = ch.cacheKey()
            if (key in workingCache) continue
            val itemId = ch.itemId
            if (itemId != null && metadata.pcAvailable && !ch.locked) {
                attemptedIds.add(itemId)
                try {
                    val paras = fetchChapterWeb(itemId, ch.title)
                    if (paras.isNotEmpty()) {
                        val cTitle = normalizeChapterTitle(ch.title, ch.index)
                        workingCache[key] = CachedChapterEntry(
                            key = key,
                            index = ch.index,
                            title = cTitle,
                            formattedText = formatChapter(cTitle, paras),
                            volumeIndex = ch.volumeIndex.coerceAtLeast(0),
                            volumeName = ch.volumeName,
                            paragraphs = paras,
                            wordCount = if (ch.wordCount > 0) ch.wordCount else paras.sumOf { it.length }
                        )
                        val doneNow = chapters.count { it.cacheKey() in workingCache }
                        val pct = ((doneNow * 100.0) / total).toInt().coerceIn(0, 100)
                        onProgress(
                            FanqieProgress(
                                bookId = bookId,
                                status = "downloading",
                                current = doneNow,
                                total = total,
                                title = metadata.title,
                                chapterTitle = cTitle,
                                percent = pct,
                                filename = null,
                                failed = 0,
                                message = "网页端补齐 $doneNow/$total 章 · $cTitle",
                                latestChapterTitle = latestTitle,
                                resumedFromCount = initialCachedCount
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }

        val wasStopped = shouldStop?.invoke() == true

        // 重抓失败/被暂停而仍缺失的旧章节：回填旧正文（仍为 legacy，下次续下会继续尝试补插图）
        for ((k, v) in staleOld) {
            if (k !in workingCache) workingCache[k] = v
        }

        // 关键修复：若未暂停且网络正常（appError == null），对经过多轮尝试仍为空内容的章节（如作者空章或下架通告章）
        // 写入占位说明并归档至缓存，彻底杜绝「实际已经下载完了但检测仍然有更新却没东西可下」的死循环！
        if (!wasStopped && appError == null && workingCache.isNotEmpty()) {
            for (ch in chapters) {
                val key = ch.cacheKey()
                if (key in workingCache) continue
                val id = ch.itemId
                if (id.isNullOrBlank() || id in attemptedIds) {
                    val cTitle = normalizeChapterTitle(ch.title, ch.index)
                    val placeholderParas = listOf("（本章为作者空章或已调整章节，暂无正文内容）")
                    workingCache[key] = CachedChapterEntry(
                        key = key,
                        index = ch.index,
                        title = cTitle,
                        formattedText = formatChapter(cTitle, placeholderParas),
                        volumeIndex = ch.volumeIndex.coerceAtLeast(0),
                        volumeName = ch.volumeName,
                        paragraphs = placeholderParas,
                        wordCount = 0
                    )
                }
            }
        }

        val downloaded = chapters.count {
            val cached = workingCache[it.cacheKey()]
            cached != null && (cached.formattedText.isNotBlank() || cached.paragraphs.isNotEmpty())
        }

        if (downloaded == 0) {
            val reason = if (appError != null) "APP 接口不可用（$appError），网页端也未获取到正文" else "未抓取到任何章节正文"
            throw FanqieException(reason)
        }

        val remainingDownloadable = chapters.count {
            it.cacheKey() !in workingCache && it.hasDownloadableId()
        }
        val isPartial = wasStopped || remainingDownloadable > 0
        val effectiveTotal = if (!isPartial) downloaded else total
        val failedCount = if (isPartial) remainingDownloadable else 0

        // 离线下载并本地持久化所有章节内的正文插图 (<img src="...">)
        for (entry in workingCache.values) {
            val imgUrls = extractImageUrls(entry.resolvedParagraphs())
            for (imgUrl in imgUrls) {
                if (shouldStop?.invoke() == true) break
                try {
                    FanqieLegadoBridge.downloadAndCacheInlineImage(bookId, imgUrl)
                } catch (_: Throwable) {
                }
            }
        }

        val updatedMeta = metadata.copy(
            chapterCount = effectiveTotal,
            latestChapterTitle = latestTitle,
            structuredChapters = chapters
        )
        FanqieChapterCacheStore.saveMetadata(updatedMeta)

        val (txtBytes, builtToc) = buildBookTxtWithToc(
            metadata = updatedMeta,
            chapters = chapters,
            cacheMap = workingCache,
            downloaded = downloaded,
            total = effectiveTotal,
            failedCount = failedCount,
            includeTocIndexInText = false
        )

        val finalStatus = if (wasStopped) "paused" else "done"
        val doneMsg = when {
            wasStopped -> "已暂停并保存断点：已下载 $downloaded/$effectiveTotal 章（下次可从第 ${downloaded + 1} 章接着下）"
            failedCount > 0 -> "已下载 $downloaded/$effectiveTotal 章（剩余 $failedCount 章可点击断点续下）"
            initialCachedCount > 0 && downloaded > initialCachedCount -> "增量续下完成：共 $downloaded 章（新增 ${downloaded - initialCachedCount} 章）"
            else -> "全本已下载完成：共 ${updatedMeta.volumes.size.coerceAtLeast(1)} 卷 $downloaded 章（含原生一级/二级目录与排版）"
        }

        onProgress(
            FanqieProgress(
                bookId = bookId,
                status = finalStatus,
                current = downloaded,
                total = effectiveTotal,
                title = metadata.title,
                chapterTitle = latestTitle,
                percent = if (!isPartial) 100 else ((downloaded * 100.0) / effectiveTotal).toInt().coerceIn(0, 99),
                filename = "${metadata.title}.txt",
                failed = failedCount,
                message = doneMsg,
                latestChapterTitle = latestTitle,
                resumedFromCount = initialCachedCount
            )
        )

        return DownloadOutcome(
            metadata = updatedMeta,
            txtBytes = txtBytes,
            builtToc = builtToc,
            downloadedChapters = downloaded,
            remoteTotalChapters = effectiveTotal,
            latestChapterTitle = latestTitle,
            isPartial = isPartial,
            updatedCache = workingCache
        )
    }

    fun downloadBook(
        bookId: String,
        onProgress: (FanqieProgress) -> Unit
    ): Pair<FanqieMetadata, ByteArray> {
        val outcome = downloadBookIncremental(
            bookId = bookId,
            existingCache = emptyMap(),
            onProgress = onProgress
        )
        return outcome.metadata to outcome.txtBytes
    }

    /**
     * 极速拉取前 [quickCount] 章以供「立即阅读」秒开阅读器，其余章节交由后台服务静默断点续下。
     */
    fun downloadQuickStarter(
        bookId: String,
        existingCache: Map<String, CachedChapterEntry> = emptyMap(),
        quickCount: Int = 8
    ): DownloadOutcome {
        val cleanId = FanqieParser.resolveBookId(bookId)
        var cachedCount = existingCache.size
        val outcome = downloadBookIncremental(
            bookId = cleanId,
            existingCache = existingCache,
            shouldStop = { cachedCount >= quickCount },
            onProgress = { progress -> cachedCount = progress.current }
        )
        // 为秒开的前几章立即同步缓存正文插图，保证首屏打开即可看到插图
        for (entry in outcome.updatedCache.values.take(quickCount)) {
            val urls = extractImageUrls(entry.resolvedParagraphs())
            for (u in urls) {
                try {
                    FanqieLegadoBridge.downloadAndCacheInlineImage(cleanId, u)
                } catch (_: Throwable) {
                }
            }
        }
        return outcome
    }
}
