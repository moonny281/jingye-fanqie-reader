package io.legado.app.fanqie

import android.text.Html
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Random
import java.util.UUID
import java.util.zip.GZIPInputStream

class FanqieAppApi(
    private val cacheFile: File,
    private val timeoutMs: Int = 25000,
    var intervalSec: Int = 6,
    private val batchSize: Int = 30,
    private val maxDevices: Int = 3
) {
    companion object {
        private const val APP_BASE = "https://reading.snssdk.com/reading"
        private const val FQNOVEL_API_BASE = "https://api.fqnovel.com/reading"
        private const val APP_UA = "com.dragon.read"
        private const val REGISTER_URL = "https://i.snssdk.com/service/2/device_register/?tt_data=a"

        private val ANDROID_VERSIONS = listOf("9" to 29, "10" to 30, "11" to 31, "12" to 32, "13" to 33, "14" to 34)
        private val DEVICE_MODELS = listOf(
            "RMX1931", "MI8", "Honor10", "P30", "V1921A", "Redmi Note 7", "Redmi K20 Pro",
            "MI 9", "Mi 10 Pro", "SM-G9750", "Pixel 6", "HD1910", "M2011K2C", "LIO-AN00",
            "VOG-TL00", "PCLM10", "GM1900", "Pixel 7 Pro", "Pixel 6a", "SM-N9760", "POCO F1"
        )
        private val DEVICE_BRANDS = listOf(
            "realme", "Xiaomi", "Huawei", "OPPO", "vivo", "samsung", "OnePlus",
            "google", "Redmi", "HONOR", "motorola", "POCO"
        )

        private val CHAPTER_TAG_RE = Regex("<[^>]+>")
        private val BR_RE = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val HR_RE = Regex("<hr\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val LINE_DIV_RE = Regex(
            "<(?:div|p)\\b[^>]*class\\s*=\\s*[\"'][^\"']*(?:line|divider|split|scene-break)[^\"']*[\"'][^>]*>.*?</(?:div|p)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val H1_RE = Regex("<h1\\b[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TITLE_TAG_RE = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val H2_H3_RE = Regex("<h([2-4])\\b[^>]*>(.*?)</h\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val BLOCKQUOTE_RE = Regex("<blockquote\\b[^>]*>(.*?)</blockquote>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val AUTHOR_SAY_RE = Regex(
            "<div\\b[^>]*class\\s*=\\s*[\"'][^\"']*(?:author-say|author-words|writer-say|author_say)[^\"']*[\"'][^>]*>(.*?)</div>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val IMG_ALT_RE = Regex("<img\\b[^>]*alt\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
        private val P_OR_BLK_RE = Regex("<(p|blk)(\\s[^>]*)?>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val STYLE_SCRIPT_RE = Regex("<(style|script|head)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ZERO_WIDTH_RE = Regex("[\\u200b\\u200c\\u200d\\u2060\\ufeff]")
        private val WS_RE = Regex("[ \\t\\r\\f\\u000B\\u00a0]+")
        private val NORMALIZE_TITLE_CMP_RE = Regex("[\\s\\p{Punct}【】〖〗〘〙《》「」『』、，。！？：；“”‘’（）()\\[\\]—\\-_·]+")

        const val SCENE_BREAK_MARKER = "* * *"

        fun cleanInlineText(fragment: String): String {
            val noTags = CHAPTER_TAG_RE.replace(fragment, "")
            val unescaped = try {
                Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString()
            } catch (_: Throwable) {
                noTags
                    .replace("&nbsp;", " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
            }
            return ZERO_WIDTH_RE.replace(unescaped, "")
                .replace("\u3000", " ")
                .replace(WS_RE, " ")
                .trim()
        }

        /**
         * 判断段落是否与章节标题重复（例如正文第一段再次写了「第1章 惊蛰」或「第一章 惊蛰」）
         */
        fun isDuplicateTitleLine(paragraph: String, chapterTitle: String): Boolean {
            if (paragraph.isBlank() || chapterTitle.isBlank()) return false
            val pTrim = paragraph.trim()
            val tTrim = chapterTitle.trim()
            if (pTrim.equals(tTrim, ignoreCase = true)) return true
            if (pTrim.length > tTrim.length + 16) return false
            val pNorm = NORMALIZE_TITLE_CMP_RE.replace(pTrim, "").lowercase()
            val tNorm = NORMALIZE_TITLE_CMP_RE.replace(tTrim, "").lowercase()
            if (pNorm.isNotEmpty() && pNorm == tNorm) return true
            // 兼容目录标题为「第1章 惊蛰」而正文首行仅写「惊蛰」或「1. 惊蛰」的情况
            val tSuffix = tTrim.replace(Regex("^第[0-9零一二三四五六七八九十百千万〇两]+[章节回卷集部篇]\\s*"), "")
                .replace(Regex("^\\d+[.、\\s]+"), "")
                .trim()
            if (tSuffix.length >= 2 && pTrim == tSuffix) return true
            return false
        }

        /**
         * 深度解析番茄原生 XHTML 章节内容，完整保留原生排版结构：
         * 1. 提取原生标题（<h1> 或 <title>），并自动剔除正文首段重复标题；
         * 2. 识别小节副标题（<h2>/<h3>）、引文/系统面板（<blockquote>）、场景分割线（<hr>/空段）；
         * 3. 保留段落内 <br/> 换行（诗词、书信、系统属性面板、聊天记录不粘连）；
         * 4. 提取章末「作家的话 / 作者有话说」并独立排版。
         */
        fun parseChapterXhtml(xhtml: String, expectedTitle: String = ""): Pair<String, List<String>> {
            if (xhtml.isBlank()) return "" to emptyList()

            // 先从 <h1...> 或 <title> 提取原生章节名
            val h1Title = H1_RE.find(xhtml)?.groupValues?.getOrNull(1)?.let { cleanInlineText(it) }.orEmpty()
            val headTitle = TITLE_TAG_RE.find(xhtml)?.groupValues?.getOrNull(1)?.let { cleanInlineText(it) }.orEmpty()
            val extractedTitle = h1Title.ifBlank { headTitle }
            val refTitle = expectedTitle.ifBlank { extractedTitle }

            // 提取章末「作家的话」独立区块
            val authorNotes = ArrayList<String>()
            val withoutAuthorDiv = AUTHOR_SAY_RE.replace(xhtml) { m ->
                val rawNote = m.groupValues[1]
                val lines = BR_RE.replace(rawNote, "\n")
                    .let { P_OR_BLK_RE.replace(it) { pm -> pm.groupValues[3] + "\n" } }
                    .lines()
                    .map { cleanInlineText(it) }
                    .filter { it.isNotEmpty() }
                authorNotes.addAll(lines)
                ""
            }

            // 移除 <style>/<script>/<head> 及已提取的 <h1>
            var working = STYLE_SCRIPT_RE.replace(withoutAuthorDiv, "")
            working = H1_RE.replace(working, "")

            // 将结构化排版标签转换为带语义标记的 <p> 节点，便于按顺序流式还原
            working = HR_RE.replace(working, "<p>__FQ_SCENE_BREAK__</p>")
            working = LINE_DIV_RE.replace(working, "<p>__FQ_SCENE_BREAK__</p>")
            working = H2_H3_RE.replace(working) { m ->
                val sub = cleanInlineText(m.groupValues[2])
                if (sub.isNotEmpty()) "<p>〖$sub〗</p>" else ""
            }
            working = BLOCKQUOTE_RE.replace(working) { m ->
                val inner = BR_RE.replace(m.groupValues[1], "\n")
                    .let { P_OR_BLK_RE.replace(it) { pm -> pm.groupValues[3] + "\n" } }
                    .lines()
                    .map { cleanInlineText(it) }
                    .filter { it.isNotEmpty() }
                if (inner.isEmpty()) ""
                else inner.joinToString("") { "<p>「$it」</p>" }
            }
            working = IMG_ALT_RE.replace(working) { m ->
                val alt = cleanInlineText(m.groupValues[1])
                if (alt.isNotEmpty() && alt != "插图" && alt != "image") "<p>[插图：$alt]</p>" else ""
            }

            val rawParagraphs = ArrayList<String>()
            val matches = P_OR_BLK_RE.findAll(working).toList()
            if (matches.isNotEmpty()) {
                for (m in matches) {
                    val attrs = m.groupValues[2]
                    val innerHtml = m.groupValues[3]
                    if (innerHtml.contains("__FQ_SCENE_BREAK__")) {
                        rawParagraphs.add(SCENE_BREAK_MARKER)
                        continue
                    }
                    // 检查是否为作者说或副标题属性段落
                    val isAuthorSayAttr = attrs.contains("author", ignoreCase = true)
                    val isTitleAttr = attrs.contains("chapter-title", ignoreCase = true) ||
                        attrs.contains("title", ignoreCase = true)

                    // 保留段落内部 <br/> 多行结构（如系统面板、书信、诗句）
                    val subLines = BR_RE.replace(innerHtml, "\n")
                        .split('\n')
                        .map { cleanInlineText(it) }
                        .filter { it.isNotEmpty() }

                    if (subLines.isEmpty()) {
                        // 原生空段落 `<p><br/></p>` 或 `<p>&nbsp;</p>` 代表场景切换空行
                        if (rawParagraphs.isNotEmpty() && rawParagraphs.last() != SCENE_BREAK_MARKER) {
                            rawParagraphs.add(SCENE_BREAK_MARKER)
                        }
                    } else if (isAuthorSayAttr) {
                        authorNotes.addAll(subLines)
                    } else {
                        for ((idx, line) in subLines.withIndex()) {
                            if (isTitleAttr && idx == 0 && rawParagraphs.isEmpty() &&
                                (isDuplicateTitleLine(line, refTitle) || isDuplicateTitleLine(line, extractedTitle))
                            ) {
                                continue
                            }
                            rawParagraphs.add(line)
                        }
                    }
                }
            } else {
                // 无 <p>/<blk> 时的纯文本换行兜底
                val plainLines = BR_RE.replace(working, "\n")
                    .lines()
                    .map { cleanInlineText(it) }
                    .filter { it.isNotEmpty() }
                rawParagraphs.addAll(plainLines)
            }

            // 去除首尾多余的场景分割符，以及正文首段与章节标题重复的行
            while (rawParagraphs.isNotEmpty() && rawParagraphs.first() == SCENE_BREAK_MARKER) {
                rawParagraphs.removeAt(0)
            }
            while (rawParagraphs.isNotEmpty() && rawParagraphs.last() == SCENE_BREAK_MARKER) {
                rawParagraphs.removeAt(rawParagraphs.lastIndex)
            }
            if (rawParagraphs.isNotEmpty()) {
                val firstLine = rawParagraphs.first()
                if (isDuplicateTitleLine(firstLine, refTitle) || isDuplicateTitleLine(firstLine, extractedTitle)) {
                    rawParagraphs.removeAt(0)
                }
            }
            while (rawParagraphs.isNotEmpty() && rawParagraphs.first() == SCENE_BREAK_MARKER) {
                rawParagraphs.removeAt(0)
            }

            // 合并连续的 SCENE_BREAK_MARKER，避免出现多个连续星号行
            val normalized = ArrayList<String>(rawParagraphs.size + authorNotes.size + 2)
            for (p in rawParagraphs) {
                if (p == SCENE_BREAK_MARKER) {
                    if (normalized.isNotEmpty() && normalized.last() != SCENE_BREAK_MARKER) {
                        normalized.add(SCENE_BREAK_MARKER)
                    }
                } else {
                    normalized.add(p)
                }
            }
            if (normalized.lastOrNull() == SCENE_BREAK_MARKER) {
                normalized.removeAt(normalized.lastIndex)
            }

            // 附加章末「作家的话」
            if (authorNotes.isNotEmpty()) {
                val cleanNotes = authorNotes.filterNot { it == "作家的话" || it == "作者有话说" || it == "作者的话" }
                if (cleanNotes.isNotEmpty()) {
                    normalized.add("—— 作家的话 ——")
                    normalized.addAll(cleanNotes)
                }
            }

            return extractedTitle to normalized
        }
    }

    data class DeviceInfo(
        val deviceId: String,
        val installId: String,
        val deviceType: String,
        val deviceBrand: String
    )

    private var device: DeviceInfo? = null
    private var key: ByteArray? = null
    private var keyVersion: Int? = null
    private var devicesMade = 0
    private var lastRequestMs = 0L
    private val random = Random()

    private fun randHex(len: Int): String {
        val hex = "0123456789abcdef"
        return buildString(len) {
            repeat(len) { append(hex[random.nextInt(16)]) }
        }
    }

    private fun ipv6UniqueLocal(): String {
        return buildString {
            append("fd")
            append(randHex(2))
            repeat(7) {
                append(":")
                append(String.format("%04x", random.nextInt(1 shl 16)))
            }
        }
    }

    private fun buildRegisterBody(): Pair<JSONObject, Pair<String, String>> {
        val (osVersion, osApi) = ANDROID_VERSIONS[random.nextInt(ANDROID_VERSIONS.size)]
        val brand = DEVICE_BRANDS[random.nextInt(DEVICE_BRANDS.size)]
        val model = DEVICE_MODELS[random.nextInt(DEVICE_MODELS.size)]
        val genTime = System.currentTimeMillis()
        val romVersion = "coloros__${randHex(4).uppercase()}.${String.format("%06d", random.nextInt(1_000_000))}." +
            "${String.format("%08d", random.nextInt(100_000_000))} release-keys"

        val ipv6Arr = JSONArray()
            .put(JSONObject().put("type", "client_anpi").put("value", "::1"))
            .put(JSONObject().put("type", "client_anpi").put("value", ipv6UniqueLocal()))
            .put(JSONObject().put("type", "client_anpi").put("value", ipv6UniqueLocal()))

        val header = JSONObject()
            .put("display_name", "番茄免费小说")
            .put("aid", 1967)
            .put("channel", "43536163a")
            .put("package", "com.dragon.read")
            .put("sdk_version", "3.7.0-rc.25-fanqie-xiaoshuo")
            .put("sdk_target_version", 29)
            .put("git_hash", "711d1a7")
            .put("density_dpi", 240)
            .put("display_density", "hdpi")
            .put("resolution", "720x1280")
            .put("language", "zh")
            .put("timezone", 8)
            .put("access", "wifi")
            .put("not_request_sender", 0)
            .put("carrier", "CHINA MOBILE")
            .put("mcc_mnc", "46000")
            .put("region", "CN")
            .put("tz_name", "Asia/Shanghai")
            .put("tz_offset", 28800)
            .put("sim_region", "cn")
            .put("sim_serial_number", JSONArray())
            .put("oaid_may_support", false)
            .put("device_platform", "android")
            .put("custom", JSONObject().put("host_bit", 32).put("dragon_device_type", 0))
            .put("pre_installed_channel", "")
            .put("is_system_app", 0)
            .put("sdk_flavor", "china")
            .put("guest_mode", 0)
            .put("os", "Android")
            .put("os_version", osVersion)
            .put("os_api", osApi)
            .put("device_model", model)
            .put("device_brand", brand)
            .put("device_manufacturer", brand)
            .put("cpu_abi", "arm64-v8a")
            .put("release_build", randHex(7))
            .put("cdid", UUID.randomUUID().toString())
            .put("sig_hash", "a4a27c2633195374c15651ffc3c4a497")
            .put("openudid", randHex(20))
            .put("clientudid", UUID.randomUUID().toString())
            .put("req_id", UUID.randomUUID().toString())
            .put("rom", randHex(14).uppercase())
            .put("rom_version", romVersion)
            .put("apk_first_install_time", genTime - random.nextInt(365).toLong() * 86_400_000L)
            .put("ipv6_list", ipv6Arr)

        val root = JSONObject()
            .put("magic_tag", "ss_app_log")
            .put("header", header)
            .put("_gen_time", genTime)

        return root to (model to brand)
    }

    private fun buildQuery(dev: DeviceInfo, extra: Map<String, String> = emptyMap()): String {
        val params = LinkedHashMap<String, String>()
        params["iid"] = dev.installId
        params["device_id"] = dev.deviceId
        params["ac"] = "wifi"
        params["channel"] = "43536163a"
        params["aid"] = FanqieCrypto.AID
        params["app_name"] = "novelapp"
        params["version_code"] = "70132"
        params["version_name"] = "7.0.1.32"
        params["device_platform"] = "android"
        params["os"] = "android"
        params["ssmix"] = "a"
        params["os_version"] = "10"
        params["device_type"] = dev.deviceType.ifBlank { "P30" }
        params["device_brand"] = dev.deviceBrand.ifBlank { "realme" }
        params["update_version_code"] = "70132"
        params["manifest_version_code"] = "70132"
        params["pv_player"] = "70132"
        params.putAll(extra)

        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun reverseHex(deviceId: String): String {
        val hex = BigInteger(deviceId).toString(16).padStart(32, '0')
        val sb = StringBuilder(32)
        var i = hex.length
        while (i > 0) {
            sb.append(hex.substring(i - 2, i))
            i -= 2
        }
        return sb.toString()
    }

    private fun loadCache(): Boolean {
        if (!cacheFile.isFile) return false
        return try {
            val obj = JSONObject(cacheFile.readText(Charsets.UTF_8))
            val devObj = obj.optJSONObject("device") ?: return false
            val devId = devObj.optString("device_id")
            val iid = devObj.optString("install_id")
            if (devId.isBlank() || iid.isBlank()) return false
            device = DeviceInfo(
                deviceId = devId,
                installId = iid,
                deviceType = devObj.optString("device_type", "P30"),
                deviceBrand = devObj.optString("device_brand", "realme")
            )
            val keyHex = obj.optString("key", "")
            if (keyHex.isNotBlank()) {
                key = FanqieCrypto.hexToBytes(keyHex)
                keyVersion = obj.optInt("key_version")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun saveCache() {
        val dev = device ?: return
        try {
            val payload = JSONObject()
                .put(
                    "device",
                    JSONObject()
                        .put("device_id", dev.deviceId)
                        .put("install_id", dev.installId)
                        .put("device_type", dev.deviceType)
                        .put("device_brand", dev.deviceBrand)
                )
                .put("key", key?.let { FanqieCrypto.bytesToHex(it) } ?: "")
                .put("key_version", keyVersion ?: 0)
                .put("updated_at", System.currentTimeMillis() / 1000L)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(payload.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun registerDevice(): DeviceInfo {
        val (bodyJson, modelBrand) = buildRegisterBody()
        val plain = bodyJson.toString().toByteArray(Charsets.UTF_8)
        val encrypted = FanqieCrypto.ttEncrypt(plain)

        val payload = try {
            val conn = (URL(REGISTER_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", "okhttp/4.10.0")
                setRequestProperty("Content-Type", "application/octet-stream; tt-data=a")
            }
            conn.outputStream.use { it.write(encrypted) }
            val respText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            JSONObject(respText)
        } catch (e: Exception) {
            throw FanqieException("匿名设备注册失败：${e.message}", e)
        }

        val devId = payload.optString("device_id_str")
        val iid = payload.optString("install_id_str")
        if (devId.isBlank() || iid.isBlank()) {
            throw FanqieException("匿名设备注册失败：响应缺少 device_id / install_id")
        }
        devicesMade++
        return DeviceInfo(devId, iid, modelBrand.first, modelBrand.second)
    }

    private fun registerKey() {
        val dev = device ?: throw FanqieException("尚未注册设备")
        val url = "$APP_BASE/crypt/registerkey?${buildQuery(dev)}"
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val ivStr = buildString(16) { repeat(16) { append(chars[random.nextInt(chars.length)]) } }
        val iv = ivStr.toByteArray(Charsets.UTF_8)
        val devBytes = FanqieCrypto.hexToBytes(reverseHex(dev.deviceId)).copyOfRange(0, 8)
        val encContent = Base64.encodeToString(
            iv + FanqieCrypto.aesCbcEncrypt(FanqieCrypto.SHARED_KEY, iv, devBytes),
            Base64.NO_WRAP
        )
        val body = JSONObject().put("content", encContent).toString().toByteArray(Charsets.UTF_8)
        val headers = FanqieCrypto.signRequest(url, body)

        val payload = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", APP_UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body) }
            val respText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            JSONObject(respText)
        } catch (e: Exception) {
            throw FanqieException("正文密钥注册失败：${e.message}", e)
        }

        val dataObj = payload.optJSONObject("data")
        val keyB64 = dataObj?.optString("key") ?: ""
        if (keyB64.isBlank()) {
            throw FanqieException("正文密钥注册失败：响应缺少 key")
        }
        val raw = Base64.decode(keyB64, Base64.DEFAULT)
        key = FanqieCrypto.aesCbcDecrypt(
            FanqieCrypto.SHARED_KEY,
            raw.copyOfRange(0, 16),
            raw.copyOfRange(16, raw.size)
        )
        keyVersion = dataObj.optInt("keyver")
    }

    fun ensureReady() {
        if (device != null && key != null) return
        if (device == null && !loadCache()) {
            device = registerDevice()
        }
        if (key == null) {
            registerKey()
        }
        saveCache()
    }

    private fun recoverDevice() {
        if (devicesMade >= maxDevices) {
            throw FanqieException("APP 接口持续返回空响应，设备注册次数已达上限，请稍后重试")
        }
        device = registerDevice()
        key = null
        registerKey()
        saveCache()
    }

    private fun throttle(shouldStop: (() -> Boolean)? = null) {
        val waitMs = intervalSec * 1000L - (System.currentTimeMillis() - lastRequestMs)
        if (waitMs > 0 && lastRequestMs > 0L) {
            var slept = 0L
            while (slept < waitMs) {
                if (shouldStop?.invoke() == true) return
                val step = minOf(250L, waitMs - slept)
                Thread.sleep(step)
                slept += step
            }
        }
    }

    /**
     * APP 原生完整目录接口兜底：
     * 请求 /reading/bookapi/directory/all_items/v/ 获取含分卷名、章节字数、item_id 的完整原生目录。
     */
    fun fetchAppDirectory(bookId: String): Pair<List<FanqieVolume>, List<FanqieChapterItem>> {
        val queryExtra = mapOf(
            "book_id" to bookId,
            "need_version" to "true",
            "aid" to FanqieCrypto.AID
        )
        val urls = ArrayList<Pair<String, Map<String, String>>>()
        try {
            ensureReady()
            val dev = device
            if (dev != null) {
                val signedUrl = "$APP_BASE/bookapi/directory/all_items/v/?${buildQuery(dev, queryExtra)}"
                urls.add(signedUrl to FanqieCrypto.signRequest(signedUrl))
            }
        } catch (_: Exception) {
        }
        val publicUrl = "$FQNOVEL_API_BASE/bookapi/directory/all_items/v/?book_id=${URLEncoder.encode(bookId, "UTF-8")}&need_version=true&aid=1967"
        urls.add(publicUrl to emptyMap())

        for ((url, extraHeaders) in urls) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", APP_UA)
                    setRequestProperty("Accept", "application/json")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = conn.responseCode
                val text = if (code == 200) conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else ""
                conn.disconnect()
                if (text.isBlank()) continue
                val root = JSONObject(text)
                val data = root.optJSONObject("data") ?: continue
                val itemList = data.optJSONArray("item_data_list") ?: continue
                if (itemList.length() == 0) continue

                val chapters = ArrayList<FanqieChapterItem>(itemList.length())
                val volumeCounter = LinkedHashMap<String, Int>()
                val volumeIndexMap = LinkedHashMap<String, Int>()

                for (i in 0 until itemList.length()) {
                    val item = itemList.optJSONObject(i) ?: continue
                    val itemId = item.optString("item_id").ifBlank { item.optString("itemId") }
                    if (itemId.isBlank()) continue
                    val rawTitle = item.optString("title").trim()
                    val volName = item.optString("volume_name", "").trim()
                    val volIdx = if (volName.isNotEmpty()) {
                        volumeIndexMap.getOrPut(volName) { volumeIndexMap.size }
                    } else 0
                    if (volName.isNotEmpty()) {
                        volumeCounter[volName] = (volumeCounter[volName] ?: 0) + 1
                    }
                    val wordCnt = item.optInt("chapter_word_number", item.optInt("word_count", 0))
                    val passTime = item.optLong("first_pass_time", 0L)
                    chapters.add(
                        FanqieChapterItem(
                            index = chapters.size + 1,
                            itemId = itemId,
                            title = rawTitle.ifBlank { "第${chapters.size + 1}章" },
                            locked = false,
                            volumeIndex = volIdx,
                            volumeName = volName,
                            wordCount = wordCnt,
                            firstPassTime = passTime
                        )
                    )
                }
                if (chapters.isNotEmpty()) {
                    val volumes = volumeCounter.entries.mapIndexed { idx, (name, count) ->
                        FanqieVolume(index = idx, name = name, chapterCount = count)
                    }
                    return volumes to chapters
                }
            } catch (_: Exception) {
            }
        }
        return emptyList<FanqieVolume>() to emptyList()
    }

    private fun fetchBatch(
        itemIds: List<String>,
        bookId: String,
        shouldStop: (() -> Boolean)? = null
    ): Map<String, String> {
        ensureReady()
        throttle(shouldStop)
        if (shouldStop?.invoke() == true) return emptyMap()
        val dev = device!!
        val aesKey = key!!
        val url = "$APP_BASE/reader/batch_full/v?" + buildQuery(
            dev,
            mapOf(
                "item_ids" to itemIds.joinToString(","),
                "book_id" to bookId.ifBlank { "0" },
                "novel_text_type" to "1",
                "req_type" to "1"
            )
        )
        val headers = FanqieCrypto.signRequest(url)

        val (statusCode, respBytes) = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", APP_UA)
                setRequestProperty("Accept", "*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val bytes = if (code == 200) conn.inputStream.use { it.readBytes() } else ByteArray(0)
            conn.disconnect()
            code to bytes
        } catch (e: Exception) {
            throw FanqieException("章节接口请求失败：${e.message}", e)
        }

        lastRequestMs = System.currentTimeMillis()
        if (statusCode != 200) throw FanqieException("章节接口返回 HTTP $statusCode")
        if (respBytes.isEmpty()) throw FanqieException("章节接口返回空响应（设备可能被作废）")

        val payload = try {
            JSONObject(String(respBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw FanqieException("章节接口返回了非 JSON 数据", e)
        }
        val data = payload.optJSONObject("data")
        if (data == null || data.length() == 0) {
            throw FanqieException("章节接口返回空响应（设备可能被作废）")
        }

        val result = LinkedHashMap<String, String>()
        var invalid = 0
        val keysIter = data.keys()
        while (keysIter.hasNext()) {
            val itemId = keysIter.next()
            val item = data.optJSONObject(itemId) ?: continue
            val content = item.optString("content", "")
            if (content.isBlank() || content == "Invalid") {
                if (content == "Invalid") invalid++
                continue
            }
            val raw = Base64.decode(content, Base64.DEFAULT)
            var plain = FanqieCrypto.aesCbcDecrypt(
                aesKey,
                raw.copyOfRange(0, 16),
                raw.copyOfRange(16, raw.size)
            )
            if (item.optInt("compress_status") == 1) {
                plain = GZIPInputStream(ByteArrayInputStream(plain)).use { it.readBytes() }
            }
            result[itemId] = String(plain, Charsets.UTF_8)
        }
        if (result.isEmpty() && invalid > 0) {
            throw FanqieException("正文密钥失效，需要重新注册")
        }
        return result
    }

    fun fetchChapters(
        itemIds: List<String>,
        bookId: String,
        onBatch: ((List<String>, Map<String, String>) -> Unit)? = null,
        shouldStop: (() -> Boolean)? = null,
        maxRounds: Int = 2
    ): Map<String, String> {
        var pending = itemIds.filter { it.isNotBlank() }
        val result = LinkedHashMap<String, String>()
        if (pending.isEmpty()) return result

        for (rnd in 0 until maxRounds.coerceAtLeast(1)) {
            if (pending.isEmpty() || shouldStop?.invoke() == true) break
            if (rnd > 0) {
                Thread.sleep(maxOf(intervalSec * 1000L, 6000L))
            }
            val stillMissing = ArrayList<String>()
            var start = 0
            while (start < pending.size) {
                if (shouldStop?.invoke() == true) break
                val batch = pending.subList(start, minOf(start + batchSize, pending.size))
                val got = try {
                    fetchBatch(batch, bookId, shouldStop)
                } catch (exc: FanqieException) {
                    if (shouldStop?.invoke() == true) break
                    val msg = exc.message ?: ""
                    when {
                        "空响应" in msg -> {
                            Thread.sleep(10000L)
                            recoverDevice()
                            fetchBatch(batch, bookId, shouldStop)
                        }
                        "密钥失效" in msg -> {
                            key = null
                            registerKey()
                            saveCache()
                            fetchBatch(batch, bookId, shouldStop)
                        }
                        else -> throw exc
                    }
                }
                result.putAll(got)
                for (id in batch) {
                    if (id !in got) stillMissing.add(id)
                }
                onBatch?.invoke(batch, got)
                start += batchSize
            }
            pending = stillMissing
        }
        return result
    }
}
