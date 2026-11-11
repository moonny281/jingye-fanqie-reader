package app.jingye.reader.backend

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
    companion object {
        private const val LANDING_API = "https://api.fqnovel.com/novel_ug/share/landing_page"
        private const val DIRECTORY_API = "https://fanqienovel.com/api/reader/directory/detail"
        private const val READER_PAGE = "https://fanqienovel.com/reader/%s"
        private const val PC_PAGE = "https://fanqienovel.com/page/%s"

        // 番茄小说免签名公开搜索端点（主线路 + 备用线路 + 网页搜索线路）
        private val SEARCH_ENDPOINTS = listOf(
            "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/?device_platform=android&parent_enterfrom=novel_channel_search.tab.&offset=0&aid=1967&q=%s",
            "https://api-lf.fanqiesdk.com/api/novel/channel/homepage/search/search/v1/?device_platform=android&parent_enterfrom=novel_channel_search.tab.&offset=0&aid=1967&q=%s",
            "https://fanqienovel.com/api/author/search/search_book/v1?filter=127,127,127,127&page_count=18&page_index=0&query_type=0&query_word=%s"
        )

        private val TAG_RE = Regex("<[^>]+>")
        private val P_RE = Regex("<p(?:\\s[^>]*)?>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val BR_RE = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val WS_RE = Regex("[ \\t\\r\\f\\u000B\\u00a0]+")

        private fun stripTags(fragment: String): String {
            val withNl = BR_RE.replace(fragment, "\n")
            val noTags = TAG_RE.replace(withNl, "")
            return Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString()
        }

        private fun cleanHighlightText(raw: String): String =
            TAG_RE.replace(raw, "").replace("《", "").replace("》", "").trim()

        fun cleanParagraphs(htmlFragment: String): List<String> {
            if (htmlFragment.isBlank()) return emptyList()
            val paragraphs = ArrayList<String>()
            for (m in P_RE.findAll(htmlFragment)) {
                val text = stripTags(m.groupValues[1])
                    .replace("\u200b", "")
                    .replace("\ufeff", "")
                    .replace("\u3000", " ")
                    .replace(WS_RE, " ")
                    .trim()
                if (text.isNotEmpty()) paragraphs.add(text)
            }
            if (paragraphs.isNotEmpty()) return paragraphs

            for (line in stripTags(htmlFragment).lines()) {
                val clean = line.replace("\u3000", " ").trim()
                if (clean.isNotEmpty()) paragraphs.add(clean)
            }
            return paragraphs
        }

        fun extractReaderParagraphs(pageHtml: String): List<String> {
            if (pageHtml.isBlank()) return emptyList()
            var index = pageHtml.indexOf("muye-reader-content noselect")
            if (index < 0) index = pageHtml.indexOf("muye-reader-content")
            if (index < 0) return emptyList()
            var segment = pageHtml.substring(index, minOf(index + 600_000, pageHtml.length))
            val end = segment.indexOf("</div></div>")
            if (end > 0) segment = segment.substring(0, end + "</div>".length)
            return cleanParagraphs(segment)
        }

        fun formatChapter(title: String, paragraphs: List<String>): String {
            val body = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("\n\n") { "　　$it" }
            return "【$title】\n\n$body\n\n"
        }

        fun formatBookTxt(
            metadata: FanqieMetadata,
            chapters: List<String>,
            downloaded: Int,
            total: Int,
            failedCount: Int
        ): ByteArray {
            val exported = if (failedCount > 0 && downloaded < total) {
                "$downloaded/$total 章（$failedCount 章不可用）"
            } else {
                "$downloaded/$total 章"
            }

            val lines = ArrayList<String>()
            lines += "=".repeat(46)
            lines += "书名：${metadata.title}"
            lines += "作者：${metadata.author}"
            lines += "评分：${metadata.score}"
            lines += "字数：${metadata.formattedWordCount()}"
            lines += "状态：${metadata.serialStatus}"
            lines += "章节：共 $total 章，已导出 $exported"
            lines += "=".repeat(46)
            lines += ""
            if (metadata.intro.isNotBlank()) {
                lines += "【内容简介】"
                lines += metadata.intro.trim()
                lines += ""
                lines += "-".repeat(46)
                lines += ""
            }
            lines.addAll(chapters)
            lines += "=".repeat(46)
            lines += "由 fanqie-novel-downloader 导出"
            lines += "=".repeat(46)

            val content = lines.joinToString("\n")
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            out.write(content.toByteArray(Charsets.UTF_8))
            return out.toByteArray()
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

    /**
     * 按书名或作者名搜索番茄小说，自动轮询 3 条免签名公开搜索通道并去重。
     */
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
        // 兼容 search_tab 接口的 search_tabs[*].data[*] 结构
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
            // 有些接口直接是书籍对象，有些嵌套在 book_data 数组或 book_info 对象中
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
            val coverUrl = bookObj.optString("thumb_url").ifBlank { bookObj.optString("cover_url", "") }

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

    private fun fetchLandingData(bookId: String): JSONObject {
        bookCache[bookId]?.let { return it }
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

    fun fetchMetadata(bookId: String): FanqieMetadata {
        val bookData = fetchLandingData(bookId)
        val authorObj = bookData.optJSONObject("author")
        val chapterCount = bookData.optInt("serial_count", 0)
        val wordCount = bookData.optInt("word_count", 0)
        val creationStatus = bookData.optInt("creation_status", -1)
        val serialStatus = when (creationStatus) {
            0 -> "已完结"
            1 -> "连载中"
            else -> "未知状态"
        }
        val firstChapters = bookData.optJSONArray("first_chapters_content")
        val firstCount = firstChapters?.length() ?: 0
        val availableChapters = if (chapterCount > 0) chapterCount else firstCount

        val titlesArr = bookData.optJSONArray("item_names")
        val titles = ArrayList<String>()
        if (titlesArr != null) {
            for (i in 0 until titlesArr.length()) {
                titles.add(titlesArr.optString(i))
            }
        }

        return FanqieMetadata(
            bookId = bookId,
            title = bookData.optString("title", "未知书名"),
            author = authorObj?.optString("name")?.ifBlank { "未知作者" } ?: "未知作者",
            score = bookData.optString("score", "暂无").ifBlank { "暂无" },
            coverUrl = bookData.optString("cover_url", ""),
            intro = bookData.optString("intro", "").trim(),
            wordCount = wordCount,
            chapterCount = chapterCount,
            serialStatus = serialStatus,
            availableChapters = availableChapters,
            pcAvailable = checkPcAvailable(bookId),
            chapterTitles = titles
        )
    }

    fun fetchChapterList(bookId: String): List<FanqieChapterItem> {
        val chapters = ArrayList<FanqieChapterItem>()
        try {
            val (_, text) = httpGetText("$DIRECTORY_API?bookId=$bookId", mobile = false)
            val data = JSONObject(text).optJSONObject("data")
            val volumes = data?.optJSONArray("chapterListWithVolume")
            if (volumes != null) {
                for (v in 0 until volumes.length()) {
                    val vol = volumes.optJSONArray(v) ?: continue
                    for (i in 0 until vol.length()) {
                        val item = vol.optJSONObject(i) ?: continue
                        val itemId = item.optString("itemId", "")
                        if (itemId.isBlank()) continue
                        val locked = item.optBoolean("isChapterLock") ||
                            item.optBoolean("needPay") ||
                            item.optBoolean("isPaidPublication")
                        chapters.add(
                            FanqieChapterItem(
                                index = chapters.size + 1,
                                itemId = itemId,
                                title = item.optString("title", "").trim(),
                                locked = locked
                            )
                        )
                    }
                }
            }
            if (chapters.isEmpty() && data != null) {
                val allIds = data.optJSONArray("allItemIds")
                val landing = bookCache[bookId]
                val titles = landing?.optJSONArray("item_names")
                if (allIds != null) {
                    for (i in 0 until allIds.length()) {
                        val idStr = allIds.optString(i)
                        val t = if (titles != null && i < titles.length()) titles.optString(i) else "第${i + 1}章"
                        chapters.add(FanqieChapterItem(i + 1, idStr, t, false))
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (chapters.isEmpty()) {
            val landing = fetchLandingData(bookId)
            val titles = landing.optJSONArray("item_names")
            if (titles != null) {
                for (i in 0 until titles.length()) {
                    chapters.add(FanqieChapterItem(i + 1, null, titles.optString(i, "第${i + 1}章"), false))
                }
            }
        }
        return chapters
    }

    private fun fetchChapterWeb(itemId: String): List<String> {
        val (code, html) = httpGetText(String.format(READER_PAGE, itemId), mobile = false)
        if (code == 404) throw FanqieException("PC 端未上架（可能为 App 独占章节）")
        if (code != 200) throw FanqieException("章节页返回 $code")
        if ("muye-to-vip" in html || "扫码下载APP免费读" in html || "会员登录后" in html) {
            throw FanqieException("会员/付费章节，网页端无法获取全文")
        }
        val rawParas = extractReaderParagraphs(html)
        if (rawParas.isEmpty()) throw FanqieException("章节正文为空")
        val plain = FanqieDecryptor.decryptText(rawParas.joinToString("\n"))
        return plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun downloadBook(
        bookId: String,
        onProgress: (FanqieProgress) -> Unit
    ): Pair<FanqieMetadata, ByteArray> {
        val metadata = fetchMetadata(bookId)
        val bookData = fetchLandingData(bookId)
        val landingArr = bookData.optJSONArray("first_chapters_content")
        val landingChapters = ArrayList<List<String>>()
        if (landingArr != null) {
            for (i in 0 until landingArr.length()) {
                landingChapters.add(cleanParagraphs(landingArr.optString(i, "")))
            }
        }

        val chapters = fetchChapterList(bookId).toMutableList()
        val total = maxOf(chapters.size, landingChapters.size, metadata.chapterCount, 1)
        if (chapters.size < total) {
            val titles = bookData.optJSONArray("item_names")
            for (i in chapters.size until total) {
                val t = if (titles != null && i < titles.length()) titles.optString(i) else "第${i + 1}章"
                chapters.add(FanqieChapterItem(i + 1, null, t, false))
            }
        }

        onProgress(
            FanqieProgress(
                bookId = bookId,
                status = "start",
                current = 0,
                total = total,
                title = metadata.title,
                chapterTitle = "正在准备下载…",
                percent = 0,
                filename = null,
                failed = 0,
                message = "开始下载《${metadata.title}》（共 $total 章）"
            )
        )

        val appParagraphs = HashMap<String, List<String>>()
        val appTitles = HashMap<String, String>()
        val appEmitted = HashSet<String>()
        var appError: String? = null

        val itemIds = chapters.mapNotNull { it.itemId?.takeIf { id -> id.isNotBlank() } }
        val chapterById = chapters.filter { !it.itemId.isNullOrBlank() }.associateBy { it.itemId!! }

        if (itemIds.isNotEmpty()) {
            try {
                val api = FanqieAppApi(cacheFile = cacheFile, intervalSec = appApiIntervalSec)
                val rawMap = api.fetchChapters(
                    itemIds = itemIds,
                    bookId = bookId,
                    onBatch = { batch, got ->
                        for (itemId in batch) {
                            val ch = chapterById[itemId] ?: continue
                            val xhtml = got[itemId] ?: continue
                            val (titleText, paras) = FanqieAppApi.parseChapterXhtml(xhtml)
                            if (paras.isEmpty()) continue
                            appParagraphs[itemId] = paras
                            if (titleText.isNotBlank()) appTitles[itemId] = titleText
                            if (appEmitted.add(itemId)) {
                                val pct = ((ch.index * 100.0) / total).toInt().coerceIn(0, 100)
                                val cTitle = ch.title.ifBlank { titleText }
                                onProgress(
                                    FanqieProgress(
                                        bookId = bookId,
                                        status = "downloading",
                                        current = ch.index,
                                        total = total,
                                        title = metadata.title,
                                        chapterTitle = cTitle,
                                        percent = pct,
                                        filename = null,
                                        failed = 0,
                                        message = "正在下载：第 ${ch.index}/$total 章 - $cTitle"
                                    )
                                )
                            }
                        }
                    }
                )
                for ((itemId, xhtml) in rawMap) {
                    if (itemId in appParagraphs) continue
                    val (titleText, paras) = FanqieAppApi.parseChapterXhtml(xhtml)
                    if (paras.isNotEmpty()) {
                        appParagraphs[itemId] = paras
                        if (titleText.isNotBlank()) appTitles[itemId] = titleText
                    }
                }
            } catch (e: Exception) {
                appError = e.message ?: e.javaClass.simpleName
            }
        }

        val formattedChapters = ArrayList<String>(chapters.size)
        var downloaded = 0
        var failedCount = 0

        for (ch in chapters) {
            val idx = ch.index
            var chTitle = ch.title.ifBlank { "第${idx}章" }
            val itemId = ch.itemId
            var paras: List<String> = emptyList()

            if (itemId != null && itemId in appParagraphs) {
                paras = appParagraphs[itemId]!!
                appTitles[itemId]?.takeIf { it.isNotBlank() }?.let { chTitle = it }
            } else if (idx <= landingChapters.size && landingChapters[idx - 1].isNotEmpty()) {
                paras = landingChapters[idx - 1]
            } else if (itemId != null && metadata.pcAvailable && !ch.locked) {
                try {
                    paras = fetchChapterWeb(itemId)
                } catch (_: Exception) {
                }
            }

            if (paras.isNotEmpty()) {
                downloaded++
                formattedChapters.add(formatChapter(chTitle, paras))
            } else {
                failedCount++
            }

            if (itemId == null || itemId !in appEmitted) {
                val pct = ((idx * 100.0) / total).toInt().coerceIn(0, 100)
                onProgress(
                    FanqieProgress(
                        bookId = bookId,
                        status = "downloading",
                        current = idx,
                        total = total,
                        title = metadata.title,
                        chapterTitle = chTitle,
                        percent = pct,
                        filename = null,
                        failed = failedCount,
                        message = "正在整理：第 $idx/$total 章 - $chTitle"
                    )
                )
            }
        }

        if (downloaded == 0) {
            val reason = if (appError != null) "APP 接口不可用（$appError），网页端也未获取到正文" else "未抓取到任何章节正文"
            throw FanqieException(reason)
        }

        val txtBytes = formatBookTxt(metadata, formattedChapters, downloaded, total, failedCount)
        val doneMsg = if (failedCount > 0) {
            "已导出 $downloaded/$total 章（$failedCount 章未获取）"
        } else {
            "下载完成，共 $downloaded 章"
        }

        onProgress(
            FanqieProgress(
                bookId = bookId,
                status = "done",
                current = downloaded,
                total = total,
                title = metadata.title,
                chapterTitle = "",
                percent = 100,
                filename = "${metadata.title}.txt",
                failed = failedCount,
                message = doneMsg
            )
        )

        return metadata to txtBytes
    }
}
