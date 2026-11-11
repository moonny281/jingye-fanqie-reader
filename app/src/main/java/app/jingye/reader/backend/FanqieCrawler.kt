package app.jingye.reader.backend

import android.text.Html
import app.jingye.reader.data.CachedChapterEntry
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
                "$downloaded/$total 章（$failedCount 章待续下）"
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
            if (metadata.effectiveLatestChapter().isNotBlank()) {
                lines += "最新：${metadata.effectiveLatestChapter()}"
            }
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
                titles.add(titlesArr.optString(i))
            }
        }

        // 同时尝试拉取目录接口以获得准确的最新章节名与总章数
        val dirChapters = try {
            fetchChapterList(bookId)
        } catch (_: Exception) {
            emptyList()
        }
        val totalChapterCount = maxOf(landingChapterCount, dirChapters.size, titles.size)
        val availableChapters = if (totalChapterCount > 0) totalChapterCount else firstCount
        val latestTitle = dirChapters.lastOrNull()?.title?.takeIf { it.isNotBlank() }
            ?: titles.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: bookData.optString("last_chapter_title", "")

        return FanqieMetadata(
            bookId = bookId,
            title = bookData.optString("title", "未知书名"),
            author = authorObj?.optString("name")?.ifBlank { "未知作者" } ?: "未知作者",
            score = bookData.optString("score", "暂无").ifBlank { "暂无" },
            coverUrl = bookData.optString("cover_url", ""),
            intro = bookData.optString("intro", "").trim(),
            wordCount = wordCount,
            chapterCount = totalChapterCount,
            serialStatus = serialStatus,
            availableChapters = availableChapters,
            pcAvailable = checkPcAvailable(bookId),
            chapterTitles = if (dirChapters.isNotEmpty()) dirChapters.map { it.title } else titles,
            latestChapterTitle = latestTitle
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

    /**
     * 支持断点续下与增量更新的下载方法：
     * - 自动跳过 existingCache 中已有的章节
     * - 每完成一个批次（30章）实时触发 onCheckpoint 持久化断点
     * - 支持 shouldStop 随时安全暂停并保留已下载章节
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
            cacheMap: Map<String, CachedChapterEntry>
        ) -> Unit)? = null,
        onProgress: (FanqieProgress) -> Unit
    ): DownloadOutcome {
        val metadata = fetchMetadata(bookId, forceRefresh = true)
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
        val latestTitle = chapters.lastOrNull()?.title?.takeIf { it.isNotBlank() }
            ?: metadata.effectiveLatestChapter()

        // 工作缓存：复制已有断点缓存
        val workingCache = LinkedHashMap<String, CachedChapterEntry>(existingCache)

        // 先把 landingChapters 前几章如果还没缓存的补进去
        for (ch in chapters) {
            val key = ch.cacheKey()
            if (key !in workingCache && ch.index <= landingChapters.size) {
                val paras = landingChapters[ch.index - 1]
                if (paras.isNotEmpty()) {
                    val cTitle = ch.title.ifBlank { "第${ch.index}章" }
                    workingCache[key] = CachedChapterEntry(
                        key = key,
                        index = ch.index,
                        title = cTitle,
                        formattedText = formatChapter(cTitle, paras)
                    )
                }
            }
        }

        val initialCachedCount = chapters.count { it.cacheKey() in workingCache }
        val startMsg = if (initialCachedCount > 0 && initialCachedCount < total) {
            "断点续下《${metadata.title}》：已缓存 $initialCachedCount/$total 章，从第 ${initialCachedCount + 1} 章继续…"
        } else {
            "开始下载《${metadata.title}》（共 $total 章）"
        }

        onProgress(
            FanqieProgress(
                bookId = bookId,
                status = "start",
                current = initialCachedCount,
                total = total,
                title = metadata.title,
                chapterTitle = if (initialCachedCount > 0) "从第 ${initialCachedCount + 1} 章续下…" else "正在准备下载…",
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

        var appError: String? = null

        if (missingItemIds.isNotEmpty() && shouldStop?.invoke() != true) {
            try {
                val api = FanqieAppApi(cacheFile = cacheFile, intervalSec = appApiIntervalSec)
                api.fetchChapters(
                    itemIds = missingItemIds,
                    bookId = bookId,
                    shouldStop = shouldStop,
                    onBatch = { batch, got ->
                        var lastChapterTitleInBatch = ""
                        for (itemId in batch) {
                            val ch = chapterById[itemId] ?: continue
                            val xhtml = got[itemId] ?: continue
                            val (titleText, paras) = FanqieAppApi.parseChapterXhtml(xhtml)
                            if (paras.isEmpty()) continue
                            val cTitle = ch.title.ifBlank { titleText.ifBlank { "第${ch.index}章" } }
                            lastChapterTitleInBatch = cTitle
                            workingCache[ch.cacheKey()] = CachedChapterEntry(
                                key = ch.cacheKey(),
                                index = ch.index,
                                title = cTitle,
                                formattedText = formatChapter(cTitle, paras)
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

                        // 每批次实时保存断点与书籍 TXT
                        if (onCheckpoint != null && doneNow > 0) {
                            val orderedTexts = chapters.mapNotNull { workingCache[it.cacheKey()]?.formattedText }
                            val checkpointBytes = formatBookTxt(
                                metadata = metadata.copy(latestChapterTitle = latestTitle),
                                chapters = orderedTexts,
                                downloaded = orderedTexts.size,
                                total = total,
                                failedCount = (total - orderedTexts.size).coerceAtLeast(0)
                            )
                            onCheckpoint(orderedTexts.size, total, latestTitle, checkpointBytes, workingCache)
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
                try {
                    val paras = fetchChapterWeb(itemId)
                    if (paras.isNotEmpty()) {
                        val cTitle = ch.title.ifBlank { "第${ch.index}章" }
                        workingCache[key] = CachedChapterEntry(
                            key = key,
                            index = ch.index,
                            title = cTitle,
                            formattedText = formatChapter(cTitle, paras)
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

        val orderedChapters = ArrayList<String>(chapters.size)
        for (ch in chapters) {
            val cached = workingCache[ch.cacheKey()]
            if (cached != null && cached.formattedText.isNotBlank()) {
                orderedChapters.add(cached.formattedText)
            }
        }

        val downloaded = orderedChapters.size
        val failedCount = (total - downloaded).coerceAtLeast(0)
        val wasStopped = shouldStop?.invoke() == true

        if (downloaded == 0) {
            val reason = if (appError != null) "APP 接口不可用（$appError），网页端也未获取到正文" else "未抓取到任何章节正文"
            throw FanqieException(reason)
        }

        val updatedMeta = metadata.copy(
            chapterCount = total,
            latestChapterTitle = latestTitle
        )
        val txtBytes = formatBookTxt(updatedMeta, orderedChapters, downloaded, total, failedCount)
        val isPartial = wasStopped || downloaded < total

        val finalStatus = if (wasStopped) "paused" else "done"
        val doneMsg = when {
            wasStopped -> "已暂停并保存断点：已下载 $downloaded/$total 章（下次可从第 ${downloaded + 1} 章接着下）"
            failedCount > 0 -> "已下载 $downloaded/$total 章（剩余 $failedCount 章可点击断点续下）"
            initialCachedCount > 0 -> "增量续下完成：共 $downloaded 章（新增 ${downloaded - initialCachedCount} 章）"
            else -> "全本下载完成：共 $downloaded 章"
        }

        onProgress(
            FanqieProgress(
                bookId = bookId,
                status = finalStatus,
                current = downloaded,
                total = total,
                title = metadata.title,
                chapterTitle = latestTitle,
                percent = ((downloaded * 100.0) / total).toInt().coerceIn(0, 100),
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
            downloadedChapters = downloaded,
            remoteTotalChapters = total,
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
}
