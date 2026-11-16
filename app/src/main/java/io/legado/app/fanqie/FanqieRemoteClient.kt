package io.legado.app.fanqie

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class FanqieRemoteClient(private val timeoutMs: Int = 45000) {

    fun checkHealth(baseUrl: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val conn = (URL("$cleanBase/api/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
        }
        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        if (code != 200) throw FanqieException("后端返回 HTTP $code")
        val obj = JSONObject(body)
        val ver = obj.optString("version", "1.1.0")
        val ready = obj.optBoolean("core_ready", true)
        return "连接成功 (v$ver, 核心模块: ${if (ready) "就绪" else "未就绪"})"
    }

    fun parse(baseUrl: String, shareUrlOrText: String): FanqieMetadata {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val reqBody = JSONObject().put("url", shareUrlOrText).toString().toByteArray(Charsets.UTF_8)
        val conn = (URL("$cleanBase/api/parse").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(reqBody) }
        val respText = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()

        val root = try {
            JSONObject(respText)
        } catch (e: Exception) {
            throw FanqieException("解析接口返回非 JSON 数据")
        }
        if (!root.optBoolean("success", false)) {
            throw FanqieException(root.optString("error", "解析书籍失败"))
        }
        val d = root.optJSONObject("data") ?: throw FanqieException("解析结果缺少 data 字段")
        val titlesArr = d.optJSONArray("chapter_titles")
        val titles = ArrayList<String>()
        if (titlesArr != null) {
            for (i in 0 until titlesArr.length()) titles.add(titlesArr.optString(i))
        }
        val volumes = ArrayList<FanqieVolume>()
        val volArr = d.optJSONArray("volumes")
        if (volArr != null) {
            for (i in 0 until volArr.length()) {
                val vo = volArr.optJSONObject(i) ?: continue
                val vName = vo.optString("name", "").trim()
                if (vName.isNotEmpty()) {
                    volumes.add(
                        FanqieVolume(
                            index = vo.optInt("index", i),
                            name = vName,
                            chapterCount = vo.optInt("chapter_count", 0)
                        )
                    )
                }
            }
        }
        val structuredChapters = ArrayList<FanqieChapterItem>()
        val chArr = d.optJSONArray("chapters") ?: d.optJSONArray("structured_chapters")
        if (chArr != null) {
            for (i in 0 until chArr.length()) {
                val co = chArr.optJSONObject(i) ?: continue
                structuredChapters.add(
                    FanqieChapterItem(
                        index = co.optInt("index", i + 1),
                        itemId = co.optString("item_id", "").ifBlank { null },
                        title = co.optString("title", "第${i + 1}章"),
                        locked = co.optBoolean("locked", false),
                        volumeIndex = co.optInt("volume_index", 0),
                        volumeName = co.optString("volume_name", ""),
                        indexInVolume = co.optInt("index_in_volume", i + 1),
                        wordCount = co.optInt("word_count", 0)
                    )
                )
            }
        }
        val effectiveCount = if (structuredChapters.isNotEmpty()) {
            structuredChapters.size
        } else {
            d.optInt("chapter_count", titles.size)
        }
        return FanqieMetadata(
            bookId = d.optString("book_id"),
            title = d.optString("title", "未知书名"),
            author = d.optString("author", "未知作者"),
            score = d.optString("score", "暂无"),
            coverUrl = d.optString("cover_url", ""),
            intro = d.optString("intro", ""),
            wordCount = d.optInt("word_count", 0),
            chapterCount = effectiveCount,
            serialStatus = d.optString("serial_status", "未知"),
            availableChapters = d.optInt("available_chapters", effectiveCount),
            pcAvailable = d.optBoolean("pc_available", true),
            chapterTitles = titles,
            latestChapterTitle = d.optString("latest_chapter_title", titles.lastOrNull().orEmpty()),
            volumes = volumes,
            structuredChapters = structuredChapters
        )
    }

    fun downloadViaSse(
        baseUrl: String,
        bookId: String,
        onProgress: (FanqieProgress) -> Unit
    ): ByteArray {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val sseUrl = "$cleanBase/api/download/progress?book_id=${URLEncoder.encode(bookId, "UTF-8")}"
        val conn = (URL(sseUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
        }

        var downloadedFilename: String? = null
        try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (!l.startsWith("data:")) continue
                    val jsonStr = l.removePrefix("data:").trim()
                    if (jsonStr.isEmpty()) continue
                    val ev = JSONObject(jsonStr)
                    val prog = FanqieProgress(
                        bookId = ev.optString("book_id", bookId),
                        status = ev.optString("status", "downloading"),
                        current = ev.optInt("current", 0),
                        total = ev.optInt("total", 0),
                        title = ev.optString("title", ""),
                        chapterTitle = ev.optString("chapter_title", ""),
                        percent = ev.optInt("percent", 0),
                        filename = ev.optString("filename", "").ifBlank { null },
                        failed = ev.optInt("failed", 0),
                        message = ev.optString("message", "")
                    )
                    onProgress(prog)
                    if (prog.status == "error") {
                        throw FanqieException(prog.message.ifBlank { "后端下载失败" })
                    }
                    if (prog.status == "done") {
                        downloadedFilename = prog.filename
                        break
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val fname = downloadedFilename ?: throw FanqieException("后端未返回导出的文件名")
        val encodedName = URLEncoder.encode(fname, "UTF-8").replace("+", "%20")
        val fileConn = (URL("$cleanBase/api/download/file/$encodedName").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            requestMethod = "GET"
        }
        val code = fileConn.responseCode
        if (code != 200) {
            fileConn.disconnect()
            throw FanqieException("拉取 TXT 文件失败 (HTTP $code)")
        }
        val bytes = fileConn.inputStream.use { it.readBytes() }
        fileConn.disconnect()
        return bytes
    }
}
