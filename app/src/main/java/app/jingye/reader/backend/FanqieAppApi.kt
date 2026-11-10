package app.jingye.reader.backend

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

/**
 * 番茄 APP 正文接口客户端（对应 core/appapi.py）。
 * 免登录注册匿名设备 + 换取正文密钥 + 批量拉取全书加密 XHTML 章节。
 */
class FanqieAppApi(
    private val cacheFile: File,
    private val timeoutMs: Int = 25000,
    var intervalSec: Int = 6,
    private val batchSize: Int = 30,
    private val maxDevices: Int = 3
) {
    companion object {
        private const val APP_BASE = "https://reading.snssdk.com/reading"
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
        private val P_RE = Regex("<p(?:\\s[^>]*)?>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val BLK_RE = Regex("<blk(?:\\s[^>]*)?>(.*?)</blk>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val H1_RE = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val STYLE_RE = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val WS_RE = Regex("[ \\t\\r\\f\\u000B\\u00a0]+")

        private fun cleanFragment(fragment: String): String {
            val noTags = CHAPTER_TAG_RE.replace(fragment, "")
            val unescaped = Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString()
            return unescaped
                .replace("\u200b", "")
                .replace("\ufeff", "")
                .replace("\u3000", " ")
                .replace(WS_RE, " ")
                .trim()
        }

        fun parseChapterXhtml(xhtml: String): Pair<String, List<String>> {
            if (xhtml.isBlank()) return "" to emptyList()
            val cleaned = STYLE_RE.replace(xhtml, "")
            val title = H1_RE.find(cleaned)?.groupValues?.getOrNull(1)?.let { cleanFragment(it) } ?: ""

            val paragraphs = ArrayList<String>()
            for (m in P_RE.findAll(cleaned)) {
                val text = cleanFragment(m.groupValues[1])
                if (text.isNotEmpty()) paragraphs.add(text)
            }
            if (paragraphs.isEmpty()) {
                for (m in BLK_RE.findAll(cleaned)) {
                    val text = cleanFragment(m.groupValues[1])
                    if (text.isNotEmpty() && text != title) paragraphs.add(text)
                }
            }
            return title to paragraphs
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

    private fun throttle() {
        val waitMs = intervalSec * 1000L - (System.currentTimeMillis() - lastRequestMs)
        if (waitMs > 0 && lastRequestMs > 0L) {
            Thread.sleep(waitMs)
        }
    }

    private fun fetchBatch(itemIds: List<String>, bookId: String): Map<String, String> {
        ensureReady()
        throttle()
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
        maxRounds: Int = 3
    ): Map<String, String> {
        var pending = itemIds.filter { it.isNotBlank() }
        val result = LinkedHashMap<String, String>()
        if (pending.isEmpty()) return result

        for (rnd in 0 until maxRounds.coerceAtLeast(1)) {
            if (pending.isEmpty()) break
            if (rnd > 0) {
                Thread.sleep(maxOf(intervalSec * 1000L, 8000L))
            }
            val stillMissing = ArrayList<String>()
            var start = 0
            while (start < pending.size) {
                val batch = pending.subList(start, minOf(start + batchSize, pending.size))
                val got = try {
                    fetchBatch(batch, bookId)
                } catch (exc: FanqieException) {
                    val msg = exc.message ?: ""
                    when {
                        "空响应" in msg -> {
                            Thread.sleep(15000L)
                            recoverDevice()
                            fetchBatch(batch, bookId)
                        }
                        "密钥失效" in msg -> {
                            key = null
                            registerKey()
                            saveCache()
                            fetchBatch(batch, bookId)
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
