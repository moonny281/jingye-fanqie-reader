package app.jingye.reader.backend

import java.net.HttpURLConnection
import java.net.URL

/**
 * 番茄小说分享文案 / 短链 / PC 网页链接解析器（对应 core/parser.py）。
 */
object FanqieParser {
    private val URL_RE = Regex("https?://[^\\s<>\"'）)】\\]，,。；;、]+", RegexOption.IGNORE_CASE)
    private val URL_BOOK_ID_RE = Regex("(?:book_id|bookId|bookid|series_id)=(\\d{10,25})", RegexOption.IGNORE_CASE)
    private val PATH_BOOK_ID_RE = Regex("fanqienovel\\.com/(?:page|reader)/(\\d{10,25})", RegexOption.IGNORE_CASE)
    private val BARE_ID_RE = Regex("^\\s*(\\d{15,25})\\s*$")

    private val HTML_BOOK_ID_RES = listOf(
        Regex("\"book_id\"\\s*:\\s*\"?(\\d{10,25})"),
        Regex("\"bookId\"\\s*:\\s*\"?(\\d{10,25})"),
        Regex("book_id=(\\d{10,25})"),
        Regex("/page/(\\d{15,25})")
    )

    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun extractFirstUrl(text: String): String? =
        URL_RE.find(text)?.value?.trim()

    fun extractBookIdFromText(text: String): String? {
        BARE_ID_RE.matchEntire(text)?.groupValues?.getOrNull(1)?.let { return it }
        for (regex in listOf(PATH_BOOK_ID_RE, URL_BOOK_ID_RE)) {
            regex.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun bookIdFromHtml(html: String): String? {
        for (regex in HTML_BOOK_ID_RES) {
            regex.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    fun resolveBookId(rawInput: String, timeoutMs: Int = 15000): String {
        val text = rawInput.trim()
        if (text.isEmpty()) throw FanqieException("请输入番茄小说链接或分享文案")

        extractBookIdFromText(text)?.let { return it }

        val initialUrl = extractFirstUrl(text)
            ?: throw FanqieException("没有找到有效链接，请粘贴包含 http(s) 链接的分享文案或纯数字书籍 ID")

        var currentUrl = initialUrl
        var htmlBody = ""
        try {
            for (hop in 0 until 8) {
                extractBookIdFromText(currentUrl)?.let { return it }
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", MOBILE_UA)
                    setRequestProperty("Referer", "https://changdunovel.com/")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: break
                    extractBookIdFromText(location)?.let {
                        conn.disconnect()
                        return it
                    }
                    currentUrl = URL(URL(currentUrl), location).toString()
                    conn.disconnect()
                    continue
                }
                htmlBody = try {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (_: Exception) {
                    ""
                } finally {
                    conn.disconnect()
                }
                break
            }
        } catch (e: Exception) {
            throw FanqieException("链接访问失败：${e.message ?: e.javaClass.simpleName}", e)
        }

        extractBookIdFromText(currentUrl)?.let { return it }
        bookIdFromHtml(htmlBody)?.let { return it }

        if ("/reader/" in currentUrl) {
            try {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", DESKTOP_UA)
                }
                val pageHtml = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                bookIdFromHtml(pageHtml)?.let { return it }
            } catch (_: Exception) {
            }
        }

        throw FanqieException("未能从链接中提取 book_id，请换一条链接或直接粘贴书籍 ID")
    }
}
