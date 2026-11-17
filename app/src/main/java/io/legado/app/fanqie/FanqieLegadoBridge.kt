package io.legado.app.fanqie

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.bumptech.glide.Glide
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

data class FanqieTagInfo(
    val fanqieBookId: String,
    val downloadedChapters: Int,
    val remoteTotalChapters: Int,
    val isPartial: Boolean
) {
    val pendingCount: Int get() = (remoteTotalChapters - downloadedChapters).coerceAtLeast(0)
}

object FanqieLegadoBridge {

    private val deviceCacheFile: File
        get() = File(appCtx.filesDir, ".fanqie_device.json")

    val booksDir: File
        get() = File(appCtx.filesDir, "fanqie_books").apply { mkdirs() }

    private val remoteClient = FanqieRemoteClient()

    private val coverRepairScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val repairingCovers = ConcurrentHashMap.newKeySet<String>()

    fun localCoverFile(fanqieBookId: String): File =
        File(booksDir, "fanqie_${fanqieBookId}_cover.jpg")

    fun inlineImagesDir(fanqieBookId: String): File =
        File(booksDir, "images/fanqie_${fanqieBookId}").apply { mkdirs() }

    fun localInlineImageFile(fanqieBookId: String, src: String): File {
        val norm = FanqieAppApi.normalizeImageUrl(src).ifBlank { src.trim() }
        val ext = when {
            norm.contains(".png", ignoreCase = true) -> "png"
            norm.contains(".webp", ignoreCase = true) -> "webp"
            norm.contains(".gif", ignoreCase = true) -> "gif"
            else -> "jpg"
        }
        val hash = MD5Utils.md5Encode16(norm)
        return File(inlineImagesDir(fanqieBookId), "$hash.$ext")
    }

    /**
     * 查找已缓存的正文插图文件（支持按书籍 fanqieBookId 查找或跨书目录查找）
     */
    fun findCachedInlineImage(book: Book?, src: String): File? {
        val norm = FanqieAppApi.normalizeImageUrl(src).ifBlank { src.trim() }
        if (norm.isBlank()) return null
        if (norm.startsWith("/")) {
            val f = File(norm)
            if (isValidImageFile(f)) return f
        }
        val tag = book?.let { parseTag(it) }
        if (tag != null && tag.fanqieBookId.isNotBlank()) {
            val f = localInlineImageFile(tag.fanqieBookId, norm)
            if (isValidImageFile(f)) return f
        }
        val rootImgDir = File(booksDir, "images")
        if (rootImgDir.isDirectory) {
            val hash = MD5Utils.md5Encode16(norm)
            rootImgDir.listFiles()?.forEach { sub ->
                if (sub.isDirectory) {
                    sub.listFiles()?.firstOrNull { it.name.startsWith(hash) && it.length() > 128L }?.let {
                        return it
                    }
                }
            }
        }
        return null
    }

    /**
     * 下载并缓存章节正文内的插图到本地持久化目录，返回本地文件（若已缓存则直接返回）
     */
    fun downloadAndCacheInlineImage(fanqieBookId: String, rawSrc: String): File? {
        val normUrl = FanqieAppApi.normalizeImageUrl(rawSrc)
        if (normUrl.isBlank()) return null
        val safeBookId = fanqieBookId.ifBlank { "common" }
        val targetFile = localInlineImageFile(safeBookId, normUrl)
        if (isValidImageFile(targetFile)) {
            return targetFile
        }
        if (downloadValidImageToFile(rawSrc, targetFile)) {
            return targetFile
        }
        return null
    }

    fun localCoverFileForBook(book: Book): File {
        val tag = parseTag(book)
        if (tag != null && tag.fanqieBookId.isNotBlank()) {
            return localCoverFile(tag.fanqieBookId)
        }
        val key = MD5Utils.md5Encode16(book.bookUrl.ifBlank { "${book.name}_${book.author}" })
        return File(booksDir, "shelf_${key}_cover.jpg")
    }

    private val BYTEIMG_KEY_RE = Regex("((?:novel-pic[a-z0-9_-]*|tos-cn-[a-z0-9_-]+)/[a-zA-Z0-9_/-]+)")

    /**
     * 为番茄封面或正文插图生成多候选下载链接列表：
     * 解决 fqnovelpic 签名过期、.heic 硬件解码不支持、或单 CDN 节点受限导致图片下载/解码失败的问题。
     */
    fun buildCandidateImageUrls(rawUrl: String?): List<String> {
        val norm = FanqieAppApi.normalizeImageUrl(rawUrl)
        if (norm.isBlank()) return emptyList()
        val list = LinkedHashSet<String>()
        list.add(norm)

        // 若原链接包含 .heic，尝试替换为 .jpeg / .jpg / .webp
        if (norm.contains(".heic", ignoreCase = true)) {
            list.add(norm.replace(Regex("\\.heic(\\b|$)", RegexOption.IGNORE_CASE), ".jpeg$1"))
            list.add(norm.replace(Regex("\\.heic(\\b|$)", RegexOption.IGNORE_CASE), ".webp$1"))
        }

        // 0.4.8：带缩略图模板(~tplv-resize:200:260 等)的链接在部分节点会直接返回 HTTP 400，
        // 先尝试去掉模板/查询串的“干净”同域名链接
        val noQuery = norm.substringBefore("?")
        val noTpl = noQuery.substringBefore("~tplv")
        if (noTpl.startsWith("http") && noTpl != norm) {
            list.add(noTpl)
            list.add("$noTpl.jpeg")
        }

        // 从链接中提取底层对象存储 Key（如 novel-pic-r/xxx 或 novel-pic/xxx 或 tos-cn-i-xxx/yyy）
        val pathPart = noTpl
        var objKey = BYTEIMG_KEY_RE.find(pathPart)?.groupValues?.get(1)?.trim('/').orEmpty()
        if (objKey.isEmpty()) {
            // 通用兜底：取 URL 路径部分，去掉 origin/large 前缀
            objKey = runCatching { URL(pathPart).path }.getOrDefault("")
                .trim('/')
                .removePrefix("origin/").removePrefix("large/").removePrefix("img/")
                .substringBefore(".")
        }
        if (objKey.isNotEmpty()) {
            val hosts = listOf("p3-novel.byteimg.com", "p6-novel.byteimg.com", "p9-novel.byteimg.com")
            for (kind in listOf("origin", "large")) {
                for (host in hosts) {
                    list.add("https://$host/$kind/$objKey.jpeg")
                    list.add("https://$host/$kind/$objKey")
                }
            }
            list.add("https://p3-novel.byteimg.com/origin/$objKey.image")
            list.add("https://p3-novel.byteimg.com/origin/$objKey~tplv-noop.image")
        }
        return list.toList()
    }

    /** 0.4.7：最近一次插图下载失败的原因（key = 原始 src），供阅读页图框显示与排查 */
    val lastImageError = ConcurrentHashMap<String, String>()

    private const val IMG_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 当前一次下载过程中判定为不可达(超时/DNS失败)的主机，后续候选直接跳过，避免白等超时 */
    private fun isDeadHostError(e: Throwable): Boolean =
        e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.ConnectException

    /** 简短标注候选来源：host(去 novel 域后缀)/路径首段，用于错误摘要 */
    private fun shortTag(url: String): String = runCatching {
        val u = URL(url)
        val h = u.host.substringBefore(".")
        val seg = u.path.trim('/').substringBefore("/")
        val ext = u.path.substringAfterLast('.', "").take(5)
        "$h/$seg" + (if (u.query != null) "?q" else "") + (if (url.contains("~tplv")) "~tpl" else "") + (if (ext.isNotEmpty() && ext.length <= 5 && !ext.contains("/")) ".$ext" else "")
    }.getOrDefault(url.take(30))

    /** 先用 HttpURLConnection，失败再用 App 全局 OkHttpClient（带用户代理/DoH/自定义 DNS 配置）兜底 */
    private fun fetchImageBytes(
        candidateUrl: String,
        errors: MutableList<String>,
        deadHosts: MutableSet<String>
    ): ByteArray? {
        val host = runCatching { URL(candidateUrl).host }.getOrDefault(candidateUrl.take(40))
        if (host in deadHosts) return null
        val tag = shortTag(candidateUrl)
        try {
            val conn = (URL(candidateUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", IMG_UA)
                setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/*;q=0.8,*/*;q=0.5")
                setRequestProperty("Referer", "https://fanqienovel.com/")
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val b = conn.inputStream.use { it.readBytes() }
                    if (b.size > 128) return b
                    errors.add("$tag 内容过小(${b.size}B)")
                } else {
                    // 4xx 时带上服务端返回的前 80 字节，便于判断是签名问题还是路径问题
                    val body = runCatching {
                        conn.errorStream?.use { it.readBytes() }?.let { String(it, Charsets.UTF_8).take(80).replace(Regex("\\s+"), " ") }
                    }.getOrNull().orEmpty()
                    errors.add("$tag HTTP $code" + if (body.isNotBlank()) " [$body]" else "")
                    // 明确的客户端错误：OkHttp 重试同一 URL 没有意义
                    if (code in 400..499) return null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            errors.add("$tag ${e.javaClass.simpleName}")
            if (isDeadHostError(e)) {
                deadHosts.add(host)
                return null
            }
        }
        try {
            val req = okhttp3.Request.Builder()
                .url(candidateUrl)
                .header("User-Agent", IMG_UA)
                .header("Referer", "https://fanqienovel.com/")
                .build()
            io.legado.app.help.http.okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val b = resp.body?.bytes()
                    if (b != null && b.size > 128) return b
                    errors.add("$tag(okhttp) 内容过小")
                } else {
                    errors.add("$tag(okhttp) HTTP ${resp.code}")
                }
            }
        } catch (e: Throwable) {
            errors.add("$tag(okhttp) ${e.javaClass.simpleName}")
            if (isDeadHostError(e)) deadHosts.add(host)
        }
        return null
    }

    private fun saveBitmapAsJpeg(bmp: Bitmap, targetFile: File): Boolean {
        targetFile.parentFile?.mkdirs()
        val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        return if (tmp.length() > 128L) {
            if (targetFile.exists()) targetFile.delete()
            tmp.renameTo(targetFile)
        } else {
            tmp.delete()
            false
        }
    }

    /**
     * 下载图片字节流并解码校验为标准可渲染的 JPEG 文件（兼容 JPEG/PNG/WebP/HEIC）。
     * 0.4.7：记录每个候选 URL 的失败原因到 AppLog 与 [lastImageError]，不再静默吞掉。
     */
    private fun downloadValidImageToFile(rawUrl: String?, targetFile: File): Boolean {
        val candidates = buildCandidateImageUrls(rawUrl)
        val errKey = rawUrl.orEmpty()
        if (candidates.isEmpty()) {
            lastImageError[errKey] = "无法解析图片地址"
            return false
        }
        val errors = ArrayList<String>()
        val deadHosts = HashSet<String>()

        for (candidateUrl in candidates) {
            val bytes = fetchImageBytes(candidateUrl, errors, deadHosts) ?: continue
            // 1. 尝试直接用 BitmapFactory 解码
            var bmp: Bitmap? = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Throwable) {
                null
            }
            // 2. 若 BitmapFactory 无法直接解码（如特殊 WebP/HEIC），使用 Glide 解码器兜底转为 Bitmap
            if (bmp == null) {
                bmp = try {
                    Glide.with(appCtx).asBitmap().load(bytes).submit().get()
                } catch (_: Throwable) {
                    null
                }
            }
            if (bmp != null && bmp.width > 1 && bmp.height > 1) {
                try {
                    if (saveBitmapAsJpeg(bmp, targetFile)) {
                        lastImageError.remove(errKey)
                        return true
                    }
                    errors.add("写入缓存失败 ${targetFile.name}")
                } catch (e: Throwable) {
                    errors.add("写入缓存异常 ${e.javaClass.simpleName}: ${e.message?.take(60)}")
                }
            } else {
                val head = bytes.take(4).joinToString("") { "%02x".format(it) }
                errors.add("解码失败(${bytes.size}B, 头=$head)")
            }
        }

        // 终极兜底：直接通过 Glide 下载 URL 为 Bitmap 并编码保存为标准 JPEG
        for (candidateUrl in candidates.take(2)) {
            try {
                val bmp = Glide.with(appCtx).asBitmap().load(candidateUrl).submit().get()
                if (bmp != null && bmp.width > 1 && bmp.height > 1 && saveBitmapAsJpeg(bmp, targetFile)) {
                    lastImageError.remove(errKey)
                    return true
                }
            } catch (e: Throwable) {
                errors.add("Glide ${e.javaClass.simpleName}")
            }
        }
        val summary = errors.distinct().take(8).joinToString("；").ifBlank { "未知原因" }
        lastImageError[errKey] = summary
        io.legado.app.constant.AppLog.put("番茄插图下载失败：${errKey.take(120)}\n$summary")
        return false
    }

    private fun isValidImageFile(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() <= 128L) return false
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth > 1 && opts.outHeight > 1
        } catch (_: Throwable) {
            false
        }
    }

    fun downloadAndCacheCover(targetFile: File, rawCoverUrl: String?): String {
        val normUrl = FanqieCrawler.normalizeCoverUrl(rawCoverUrl)
        if (isValidImageFile(targetFile)) {
            return targetFile.absolutePath
        }
        if (normUrl.isBlank()) return ""
        if (downloadValidImageToFile(rawCoverUrl, targetFile)) {
            return targetFile.absolutePath
        }
        return normUrl
    }

    /**
     * 同步检查本地是否已有缓存的封面文件或元数据封面 URL
     */
    fun resolveCachedCoverSync(book: Book): String? {
        val cur = book.getDisplayCover()?.trim().orEmpty()
        if (cur.isNotEmpty()) {
            if (cur.startsWith("http")) return FanqieCrawler.normalizeCoverUrl(cur)
            val f = if (cur.startsWith("file://")) File(Uri.parse(cur).path ?: "") else File(cur)
            if (isValidImageFile(f)) return f.absolutePath
        }
        val localFile = localCoverFileForBook(book)
        if (isValidImageFile(localFile)) {
            return localFile.absolutePath
        }
        val tag = parseTag(book)
        if (tag != null) {
            val metaCover = FanqieChapterCacheStore.loadMetadata(tag.fanqieBookId)?.coverUrl
            val norm = FanqieCrawler.normalizeCoverUrl(metaCover)
            if (norm.isNotEmpty()) return norm
        }
        return null
    }

    /**
     * 异步自动补全/修复书架书籍封面（解决已有书架书籍 coverUrl 为空或未缓存到本地的问题）
     */
    fun ensureBookCoverAsync(book: Book, onRepaired: ((String) -> Unit)? = null) {
        val syncCover = resolveCachedCoverSync(book)
        if (!syncCover.isNullOrBlank() && !syncCover.startsWith("http")) {
            if (book.coverUrl != syncCover) {
                book.coverUrl = syncCover
            }
            onRepaired?.invoke(syncCover)
            return
        }
        val bookKey = book.bookUrl.ifBlank { "${book.name}_${book.author}" }
        if (!repairingCovers.add(bookKey)) return

        coverRepairScope.launch {
            try {
                val tag = parseTag(book)
                val targetFile = localCoverFileForBook(book)
                var candidateUrl = syncCover.orEmpty()

                if (candidateUrl.isBlank() && tag != null) {
                    val savedMeta = FanqieChapterCacheStore.loadMetadata(tag.fanqieBookId)
                    candidateUrl = FanqieCrawler.normalizeCoverUrl(savedMeta?.coverUrl)
                }

                val crawler = newLocalCrawler()
                if (candidateUrl.isBlank() && tag != null) {
                    try {
                        val meta = crawler.fetchMetadata(tag.fanqieBookId, forceRefresh = true)
                        candidateUrl = FanqieCrawler.normalizeCoverUrl(meta.coverUrl)
                    } catch (_: Throwable) {
                    }
                }

                if (candidateUrl.isBlank() && book.name.isNotBlank()) {
                    try {
                        val cleanName = book.name.replace(Regex("[《》\\[\\]()（）]"), "").trim()
                        if (cleanName.isNotEmpty()) {
                            val results = crawler.searchBooks(cleanName)
                            val matched = (if (tag != null) results.firstOrNull { it.bookId == tag.fanqieBookId && it.coverUrl.isNotBlank() } else null)
                                ?: results.firstOrNull { it.title == cleanName && it.coverUrl.isNotBlank() }
                                ?: results.firstOrNull { it.coverUrl.isNotBlank() }
                            candidateUrl = FanqieCrawler.normalizeCoverUrl(matched?.coverUrl)
                        }
                    } catch (_: Throwable) {
                    }
                }

                if (candidateUrl.isNotBlank()) {
                    val finalCover = downloadAndCacheCover(targetFile, candidateUrl)
                    if (finalCover.isNotBlank()) {
                        book.coverUrl = finalCover
                        try {
                            appDb.bookDao.update(book)
                        } catch (_: Throwable) {
                        }
                        mainHandler.post {
                            onRepaired?.invoke(finalCover)
                            postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
                        }
                    }
                }
            } finally {
                repairingCovers.remove(bookKey)
            }
        }
    }


    fun newLocalCrawler() = FanqieCrawler(
        cacheFile = deviceCacheFile,
        appApiIntervalSec = FanqiePrefs.appApiIntervalSec
    )

    fun isFanqieBook(book: Book): Boolean {
        return book.customTag?.startsWith("fanqie:") == true ||
            book.originName.startsWith("fanqie_")
    }

    fun parseTag(book: Book): FanqieTagInfo? {
        val raw = book.customTag ?: return null
        if (!raw.startsWith("fanqie:")) return null
        val parts = raw.removePrefix("fanqie:").split("|")
        val bookId = parts.getOrNull(0)?.trim().orEmpty()
        if (bookId.isBlank()) return null
        val dl = parts.getOrNull(1)?.toIntOrNull() ?: book.totalChapterNum
        val rem = parts.getOrNull(2)?.toIntOrNull() ?: maxOf(dl, book.totalChapterNum + book.lastCheckCount)
        val partial = parts.getOrNull(3) == "1" || dl < rem
        return FanqieTagInfo(bookId, dl, rem, partial)
    }

    fun encodeTag(fanqieBookId: String, downloaded: Int, remoteTotal: Int, isPartial: Boolean): String {
        return "fanqie:$fanqieBookId|$downloaded|$remoteTotal|${if (isPartial) 1 else 0}"
    }

    fun findBookByFanqieId(fanqieBookId: String): Book? {
        if (fanqieBookId.isBlank()) return null
        return appDb.bookDao.all.firstOrNull { b ->
            parseTag(b)?.fanqieBookId == fanqieBookId
        }
    }

    /**
     * 寻找书架中的番茄书籍：不仅检查 customTag，还验证对应的本地 TXT 文件是否真实存在。
     * 若书籍已从书架删除，返回 null。
     */
    fun findShelfBookVerified(fanqieBookId: String): Book? {
        val cleanId = FanqieParser.resolveBookId(fanqieBookId)
        if (cleanId.isBlank()) return null
        val b = findBookByFanqieId(cleanId) ?: return null
        val txtFile = File(booksDir, "fanqie_${cleanId}.txt")
        if (!txtFile.isFile || txtFile.length() == 0L) {
            return null
        }
        return b
    }

    /**
     * 统计当前有效已下载章节数：
     * 核心修复：若该书已不在书架上（用户已从书架删除该书）且当前未在后台下载中，
     * 自动清理残留的孤儿缓存文件并返回 0，彻底解决「删掉的书籍在番茄书城仍显示已下载、无法重新下载」的问题！
     */
    fun getCachedChapterCount(fanqieBookId: String): Int {
        val cleanId = FanqieParser.resolveBookId(fanqieBookId)
        if (cleanId.isBlank()) return 0
        val shelfBook = findBookByFanqieId(cleanId)
        val activeState = FanqieDownloadManager.getState(cleanId)
        if (shelfBook == null && activeState?.isRunning != true) {
            // 书架中已无此书，若有残留的旧缓存文件则自动彻底清理
            val leftoverCount = FanqieChapterCacheStore.countCached(cleanId)
            val txtFile = File(booksDir, "fanqie_${cleanId}.txt")
            if (leftoverCount > 0 || txtFile.exists() || activeState != null) {
                purgeFanqieBookData(cleanId, deleteShelfRecord = false)
            }
            return 0
        }
        val cached = FanqieChapterCacheStore.countCached(cleanId)
        if (cached > 0) return cached
        if (shelfBook != null) {
            return parseTag(shelfBook)?.downloadedChapters ?: shelfBook.totalChapterNum
        }
        return 0
    }

    /**
     * 从书籍对象提取 fanqieBookId（兼容 customTag 与 fanqie_xxx.txt 文件名）
     */
    fun extractFanqieBookId(book: Book?): String {
        if (book == null) return ""
        parseTag(book)?.fanqieBookId?.takeIf { it.isNotBlank() }?.let { return it }
        val url = book.bookUrl
        val m = Regex("fanqie_(\\d{10,})\\.txt").find(url)
        if (m != null) return m.groupValues[1]
        return ""
    }

    /**
     * 当用户在书架或详情页删除书籍时调用：彻底删除该番茄书籍的所有缓存、TXT、目录、封面与正文插图
     */
    fun purgeFanqieBookOnDelete(book: Book?, deleteShelfRecord: Boolean = false) {
        val fqId = extractFanqieBookId(book)
        if (fqId.isNotBlank()) {
            purgeFanqieBookData(fqId, book = book, deleteShelfRecord = deleteShelfRecord)
        }
    }

    /**
     * 彻底清除指定番茄书籍的全部本地数据（章节缓存 JSON、元数据 JSON、合成 TXT、伴生 .toc.json、本地封面、正文插图文件夹、内存下载状态及可选书架记录）
     */
    @Synchronized
    fun purgeFanqieBookData(
        fanqieBookId: String,
        book: Book? = null,
        deleteShelfRecord: Boolean = true
    ) {
        val cleanId = FanqieParser.resolveBookId(fanqieBookId)
        if (cleanId.isBlank()) return
        try {
            FanqieDownloadManager.clearState(cleanId)
        } catch (_: Throwable) {
        }
        try {
            FanqieChapterCacheStore.deleteBookCache(cleanId)
        } catch (_: Throwable) {
        }
        try {
            val txtFile = File(booksDir, "fanqie_${cleanId}.txt")
            val tocFile = tocFileForTxt(txtFile)
            val coverFile = localCoverFile(cleanId)
            val imgDir = inlineImagesDir(cleanId)
            if (txtFile.exists()) txtFile.delete()
            if (tocFile.exists()) tocFile.delete()
            if (coverFile.exists()) coverFile.delete()
            if (imgDir.exists()) imgDir.deleteRecursively()
        } catch (_: Throwable) {
        }
        val targetBook = book ?: findBookByFanqieId(cleanId)
        if (targetBook != null) {
            try {
                appDb.bookChapterDao.delByBook(targetBook.bookUrl)
            } catch (_: Throwable) {
            }
            if (deleteShelfRecord) {
                try {
                    if (ReadBook.book?.bookUrl == targetBook.bookUrl) {
                        ReadBook.book = null
                    }
                    appDb.bookDao.delete(targetBook)
                    postEvent(EventBus.UP_BOOKSHELF, targetBook.bookUrl)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun tocFileForTxt(txtFile: File): File =
        File(txtFile.parentFile, "${txtFile.nameWithoutExtension}.toc.json")

    /**
     * 将精确字节偏移目录表保存为伴生 .toc.json 文件，供 LocalBook.getChapterList 毫秒级直通加载。
     */
    private fun saveCompanionTocJson(txtFile: File, builtToc: List<FanqieBuiltTocItem>) {
        if (builtToc.isEmpty()) return
        try {
            val arr = JSONArray()
            for (item in builtToc) {
                arr.put(
                    JSONObject()
                        .put("index", item.index)
                        .put("title", item.title)
                        .put("is_volume", item.isVolume)
                        .put("level", item.level)
                        .put("start", item.startByte)
                        .put("end", item.endByte)
                        .put("word_count", item.wordCount)
                        .put("volume_index", item.volumeIndex)
                        .put("volume_name", item.volumeName)
                        .put("chapter_index", item.chapterIndex)
                        .put("index_in_volume", item.indexInVolume)
                        .put("item_id", item.itemId ?: "")
                )
            }
            val tocFile = tocFileForTxt(txtFile)
            val tmp = File(tocFile.parentFile, "${tocFile.name}.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (tocFile.exists()) tocFile.delete()
            tmp.renameTo(tocFile)
        } catch (_: Exception) {
        }
    }

    /**
     * 将 FanqieBuiltTocItem 列表转换为 Legado 原生 BookChapter 列表。
     */
    fun toBookChapters(book: Book, builtToc: List<FanqieBuiltTocItem>): ArrayList<BookChapter> {
        val list = ArrayList<BookChapter>(builtToc.size)
        for ((idx, item) in builtToc.withIndex()) {
            val ch = BookChapter(
                url = item.itemId?.takeIf { it.isNotBlank() }
                    ?: MD5Utils.md5Encode16("${book.originName}_${idx}_${item.title}"),
                title = item.title.ifBlank { "第${idx + 1}章" },
                isVolume = item.isVolume,
                baseUrl = book.bookUrl,
                bookUrl = book.bookUrl,
                index = idx,
                start = item.startByte,
                end = item.endByte,
                wordCount = if (item.wordCount > 0) StringUtils.wordCountFormat(item.wordCount) else null,
                tag = item.volumeName.ifBlank { null }
            )
            list.add(ch)
        }
        return list
    }

    /**
     * 供 LocalBook.getChapterList 调用：若书籍拥有番茄伴生 .toc.json，直接加载原生分卷与章节目录（无需正则扫描）。
     */
    fun loadNativeBookChapters(book: Book): ArrayList<BookChapter>? {
        return try {
            val uri = Uri.parse(book.bookUrl)
            val path = uri.path ?: return null
            val txtFile = File(path)
            if (!txtFile.isFile) return null
            val tocFile = tocFileForTxt(txtFile)
            if (!tocFile.isFile) return null
            val fileLen = txtFile.length()
            val arr = JSONArray(tocFile.readText(Charsets.UTF_8))
            if (arr.length() == 0) return null
            val items = ArrayList<FanqieBuiltTocItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val startB = o.optLong("start", 0L).coerceIn(0L, fileLen)
                val endB = o.optLong("end", startB).coerceIn(startB, fileLen)
                items.add(
                    FanqieBuiltTocItem(
                        index = o.optInt("index", i),
                        title = o.optString("title", "第${i + 1}章"),
                        isVolume = o.optBoolean("is_volume", false),
                        startByte = startB,
                        endByte = endB,
                        wordCount = o.optInt("word_count", 0),
                        volumeIndex = o.optInt("volume_index", 0),
                        volumeName = o.optString("volume_name", ""),
                        chapterIndex = o.optInt("chapter_index", i),
                        indexInVolume = o.optInt("index_in_volume", 0),
                        itemId = o.optString("item_id", "").ifBlank { null }
                    )
                )
            }
            if (items.isEmpty()) null else toBookChapters(book, items)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun searchBooks(keyword: String): List<FanqieSearchItem> = withContext(Dispatchers.IO) {
        newLocalCrawler().searchBooks(keyword)
    }

    suspend fun parseInput(rawInput: String): FanqieMetadata = withContext(Dispatchers.IO) {
        if (FanqiePrefs.useRemoteBackend) {
            remoteClient.parse(FanqiePrefs.backendUrl, rawInput)
        } else {
            val bookId = FanqieParser.resolveBookId(rawInput)
            newLocalCrawler().fetchMetadata(bookId, forceRefresh = true)
        }
    }

    /**
     * 立即阅读（秒开流式阅读）：
     * 若书架已存在该书且目录完整则直接打开；若尚未下载或目录为空，则拉取首章批次并构建原生目录直通写入书架。
     */
    suspend fun quickStartRead(
        fanqieBookId: String,
        titleHint: String = "",
        authorHint: String = "",
        coverHint: String = "",
        introHint: String = ""
    ): Book = withContext(Dispatchers.IO) {
        val cleanId = FanqieParser.resolveBookId(fanqieBookId)
        val existing = findBookByFanqieId(cleanId)
        if (existing != null && appDb.bookChapterDao.getChapterCount(existing.bookUrl) > 0) {
            val nativeToc = loadNativeBookChapters(existing)
            if (!nativeToc.isNullOrEmpty()) {
                return@withContext existing
            }
        }
        val crawler = newLocalCrawler()
        val existingCache = FanqieChapterCacheStore.loadCachedMap(cleanId)
        val outcome = crawler.downloadQuickStarter(cleanId, existingCache, quickCount = 8)
        FanqieChapterCacheStore.saveCachedMap(cleanId, outcome.updatedCache)
        saveOrUpdateLegadoBook(
            fanqieBookId = cleanId,
            title = outcome.metadata.title.ifBlank { titleHint },
            author = outcome.metadata.author.ifBlank { authorHint },
            coverUrl = outcome.metadata.coverUrl.ifBlank { coverHint },
            intro = outcome.metadata.intro.ifBlank { introHint },
            wordCountStr = outcome.metadata.formattedWordCount(),
            serialStatus = outcome.metadata.serialStatus,
            downloadedChapters = outcome.downloadedChapters,
            remoteTotalChapters = outcome.remoteTotalChapters,
            latestChapterTitle = outcome.latestChapterTitle,
            isPartial = outcome.isPartial,
            txtBytes = outcome.txtBytes,
            builtToc = outcome.builtToc
        )
    }

    /**
     * 检测单本番茄书籍的远端更新与最新章节名，并写回 Legado 的 Room 数据库。
     */
    suspend fun checkBookUpdate(book: Book): BookUpdateCheckResult = withContext(Dispatchers.IO) {
        val tagInfo = parseTag(book)
            ?: throw FanqieException("《${book.name}》不是番茄下载书籍，无法检测番茄更新")
        val crawler = newLocalCrawler()
        val meta = crawler.fetchMetadata(tagInfo.fanqieBookId, forceRefresh = true)
        val cacheMap = FanqieChapterCacheStore.loadCachedMap(tagInfo.fanqieBookId)
        val cachedByIndex = cacheMap.values
            .filter { it.formattedText.isNotBlank() || it.paragraphs.isNotEmpty() }
            .map { it.index }
            .toHashSet()

        val validRemoteChapters = meta.structuredChapters.filter { it.hasDownloadableId() }
        val localCount: Int
        val remoteCount: Int
        val newChapters: Int

        if (validRemoteChapters.isNotEmpty()) {
            // 精确比对远端有效章节集合（按 itemId 或章节序号）是否已在本地缓存中
            val missingRemote = validRemoteChapters.count { ch ->
                ch.cacheKey() !in cacheMap && ch.index !in cachedByIndex
            }
            remoteCount = validRemoteChapters.size
            localCount = (remoteCount - missingRemote).coerceAtLeast(cacheMap.size.coerceAtMost(remoteCount))
            newChapters = missingRemote
        } else {
            val rawCached = cacheMap.size
            localCount = maxOf(rawCached, tagInfo.downloadedChapters)
            remoteCount = maxOf(meta.downloadableChapterCount(), localCount)
            newChapters = (remoteCount - localCount).coerceAtLeast(0)
        }
        val latestTitle = meta.effectiveLatestChapter()
        val isPartial = tagInfo.isPartial && newChapters > 0

        if (meta.coverUrl.isNotBlank()) {
            val cachedCover = downloadAndCacheCover(localCoverFile(tagInfo.fanqieBookId), meta.coverUrl)
            if (cachedCover.isNotBlank()) {
                book.coverUrl = cachedCover
            }
        }
        book.customTag = encodeTag(tagInfo.fanqieBookId, localCount, remoteCount, isPartial)
        book.latestChapterTitle = if (newChapters > 0) {
            "[待续${newChapters}章] ${latestTitle.ifBlank { "共${remoteCount}章" }}"
        } else {
            latestTitle.ifBlank { book.latestChapterTitle }
        }
        book.lastCheckCount = newChapters
        book.lastCheckTime = System.currentTimeMillis()
        appDb.bookDao.update(book)
        postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)

        BookUpdateCheckResult(
            bookId = book.bookUrl,
            fanqieBookId = tagInfo.fanqieBookId,
            title = book.name,
            localChapters = localCount,
            remoteChapters = remoteCount,
            newChaptersCount = newChapters,
            latestChapterTitle = latestTitle,
            serialStatus = meta.serialStatus,
            isPartialResume = isPartial
        )
    }

    /**
     * 将下载或断点保存的 TXT 与精确字节偏移目录写入应用专属目录，并注册/更新到 Legado 的 Room 书架与章节表中。
     * 若用户当前正在阅读本书，保持阅读进度并热更新内存中的章节总数，实现无感边看边下。
     */
    @Synchronized
    fun saveOrUpdateLegadoBook(
        fanqieBookId: String,
        title: String,
        author: String,
        coverUrl: String,
        intro: String,
        wordCountStr: String,
        serialStatus: String,
        downloadedChapters: Int,
        remoteTotalChapters: Int,
        latestChapterTitle: String,
        isPartial: Boolean,
        txtBytes: ByteArray,
        builtToc: List<FanqieBuiltTocItem> = emptyList()
    ): Book {
        val cleanTitle = title.trim().ifEmpty { "番茄小说_$fanqieBookId" }
        val cleanAuthor = author.trim().ifEmpty { "未知作者" }
        val txtFile = File(booksDir, "fanqie_${fanqieBookId}.txt")
        txtFile.parentFile?.mkdirs()
        txtFile.writeBytes(txtBytes)
        if (builtToc.isNotEmpty()) {
            saveCompanionTocJson(txtFile, builtToc)
        }
        val fileUriStr = Uri.fromFile(txtFile).toString()

        val existing = findBookByFanqieId(fanqieBookId)
            ?: appDb.bookDao.getBook(fileUriStr)
            ?: appDb.bookDao.getBook(cleanTitle, cleanAuthor)

        val rawCoverCandidate = coverUrl.ifBlank {
            FanqieChapterCacheStore.loadMetadata(fanqieBookId)?.coverUrl.orEmpty()
        }.ifBlank {
            existing?.coverUrl.orEmpty()
        }
        val resolvedCoverPath = downloadAndCacheCover(localCoverFile(fanqieBookId), rawCoverCandidate)
            .ifBlank { existing?.coverUrl.orEmpty() }

        val effectiveRemote = maxOf(remoteTotalChapters, downloadedChapters)
        val pendingCount = (effectiveRemote - downloadedChapters).coerceAtLeast(0)
        val displayLatest = when {
            pendingCount > 0 && latestChapterTitle.isNotBlank() ->
                "[待续${pendingCount}章] $latestChapterTitle"
            pendingCount > 0 ->
                "已下 $downloadedChapters/$effectiveRemote 章（待续 $pendingCount 章）"
            latestChapterTitle.isNotBlank() ->
                latestChapterTitle
            else ->
                "共 $downloadedChapters 章"
        }

        // 设定 latestChapterTime 大于文件修改时间，彻底防止 Book.isLocalModified() 误判并重置目录规则
        val safeLatestTime = maxOf(System.currentTimeMillis(), txtFile.lastModified() + 5000L)
        val defaultTocRule = "^(?:===\\s*(.+?)\\s*===|###\\s*(.+?)\\s*###)$"

        // 若当前正在阅读器中打开了这本书，同步保留内存中的实时阅读章节与字偏移
        val activeReadingBook = ReadBook.book?.takeIf {
            it.bookUrl == fileUriStr || (existing != null && it.bookUrl == existing.bookUrl)
        }

        val book = if (existing != null) {
            existing.apply {
                if (activeReadingBook != null) {
                    this.durChapterIndex = ReadBook.durChapterIndex
                    this.durChapterPos = ReadBook.durChapterPos
                    this.durChapterTime = System.currentTimeMillis()
                    ReadBook.curTextChapter?.title?.takeIf { it.isNotBlank() }?.let {
                        this.durChapterTitle = it
                    }
                }
                this.bookUrl = fileUriStr
                this.tocUrl = defaultTocRule
                this.origin = BookType.localTag
                this.originName = "${cleanTitle}.txt"
                this.name = cleanTitle
                this.author = cleanAuthor
                if (resolvedCoverPath.isNotBlank()) this.coverUrl = resolvedCoverPath
                if (intro.isNotBlank()) this.intro = intro
                this.charset = "UTF-8"
                this.type = BookType.text or BookType.local
                if (wordCountStr.isNotBlank()) this.wordCount = wordCountStr
                this.kind = listOf("番茄小说", serialStatus).filter { it.isNotBlank() }.joinToString(",")
                this.customTag = encodeTag(fanqieBookId, downloadedChapters, effectiveRemote, isPartial)
                this.latestChapterTitle = displayLatest
                this.latestChapterTime = safeLatestTime
                this.lastCheckTime = System.currentTimeMillis()
                this.lastCheckCount = pendingCount
                this.totalChapterNum = maxOf(builtToc.size, downloadedChapters, 1)
                this.canUpdate = false
            }
        } else {
            val minOrder = try { appDb.bookDao.minOrder } catch (_: Throwable) { 0 }
            val firstNonVolIdx = builtToc.indexOfFirst { !it.isVolume }.coerceAtLeast(0)
            val firstNonVolTitle = builtToc.getOrNull(firstNonVolIdx)?.title
                ?: builtToc.firstOrNull()?.title
                ?: "第 1 章"
            Book(
                bookUrl = fileUriStr,
                tocUrl = defaultTocRule,
                origin = BookType.localTag,
                originName = "${cleanTitle}.txt",
                name = cleanTitle,
                author = cleanAuthor,
                kind = listOf("番茄小说", serialStatus).filter { it.isNotBlank() }.joinToString(","),
                customTag = encodeTag(fanqieBookId, downloadedChapters, effectiveRemote, isPartial),
                coverUrl = resolvedCoverPath.ifBlank { null },
                intro = intro.ifBlank { null },
                charset = "UTF-8",
                type = BookType.text or BookType.local,
                latestChapterTitle = displayLatest,
                latestChapterTime = safeLatestTime,
                lastCheckTime = System.currentTimeMillis(),
                lastCheckCount = pendingCount,
                totalChapterNum = maxOf(builtToc.size, downloadedChapters, 1),
                durChapterTitle = firstNonVolTitle,
                durChapterIndex = firstNonVolIdx,
                durChapterPos = 0,
                durChapterTime = System.currentTimeMillis(),
                wordCount = wordCountStr.ifBlank { null },
                canUpdate = false,
                order = minOrder - 1
            )
        }

        try {
            TextFile.clear()
            val chapters = if (builtToc.isNotEmpty()) {
                toBookChapters(book, builtToc)
            } else {
                loadNativeBookChapters(book) ?: LocalBook.getChapterList(book)
            }
            book.latestChapterTitle = displayLatest
            book.latestChapterTime = maxOf(System.currentTimeMillis(), txtFile.lastModified() + 5000L)
            book.lastCheckCount = pendingCount
            book.totalChapterNum = chapters.size.coerceAtLeast(1)
            if (book.durChapterIndex in chapters.indices) {
                book.durChapterTitle = chapters[book.durChapterIndex].title
            }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())

            // 若用户正在阅读该书，无感热更新阅读器内的总章节数与目录
            if (activeReadingBook != null) {
                ReadBook.chapterSize = chapters.size
                ReadBook.book?.totalChapterNum = chapters.size
                ReadBook.book?.customTag = book.customTag
                ReadBook.book?.latestChapterTitle = book.latestChapterTitle
                ReadBook.book?.latestChapterTime = book.latestChapterTime
            }
        } catch (_: Throwable) {
        }

        // 将本书已离线缓存的正文插图同步链接到阅读器 BookHelp 图片缓存目录，确保离线阅读秒开显示
        try {
            val cacheMap = FanqieChapterCacheStore.loadCachedMap(fanqieBookId)
            for (entry in cacheMap.values) {
                val urls = FanqieCrawler.extractImageUrls(entry.resolvedParagraphs())
                for (u in urls) {
                    val cachedImg = localInlineImageFile(fanqieBookId, u)
                    if (cachedImg.isFile && cachedImg.length() > 128L) {
                        if (!io.legado.app.help.book.BookHelp.isImageExist(book, u)) {
                            io.legado.app.help.book.BookHelp.writeImage(book, u, cachedImg.readBytes())
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        appDb.bookDao.insert(book)
        postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
        return book
    }

    /**
     * 同步执行增量/断点续下，每完成一个批次（30章）实时更新断点缓存与 Legado 书架。
     */
    fun downloadOrResumeSync(
        fanqieBookId: String,
        titleHint: String = "",
        authorHint: String = "",
        coverHint: String = "",
        shouldStop: (() -> Boolean)? = null,
        onProgress: (FanqieProgress) -> Unit
    ): Book {
        val crawler = newLocalCrawler()
        val existingCache = FanqieChapterCacheStore.loadCachedMap(fanqieBookId)

        val outcome = crawler.downloadBookIncremental(
            bookId = fanqieBookId,
            existingCache = existingCache,
            shouldStop = shouldStop,
            onCheckpoint = { downloadedCount, totalCount, latestTitle, checkpointBytes, checkpointToc, updatedCache ->
                FanqieChapterCacheStore.saveCachedMap(fanqieBookId, updatedCache)
                val savedMeta = FanqieChapterCacheStore.loadMetadata(fanqieBookId)
                saveOrUpdateLegadoBook(
                    fanqieBookId = fanqieBookId,
                    title = savedMeta?.title?.ifBlank { titleHint } ?: titleHint,
                    author = savedMeta?.author?.ifBlank { authorHint } ?: authorHint,
                    coverUrl = savedMeta?.coverUrl?.ifBlank { coverHint } ?: coverHint,
                    intro = savedMeta?.intro.orEmpty(),
                    wordCountStr = "",
                    serialStatus = "",
                    downloadedChapters = downloadedCount,
                    remoteTotalChapters = totalCount,
                    latestChapterTitle = latestTitle,
                    isPartial = downloadedCount < totalCount,
                    txtBytes = checkpointBytes,
                    builtToc = checkpointToc
                )
            },
            onProgress = onProgress
        )

        FanqieChapterCacheStore.saveCachedMap(fanqieBookId, outcome.updatedCache)
        return saveOrUpdateLegadoBook(
            fanqieBookId = outcome.metadata.bookId,
            title = outcome.metadata.title.ifBlank { titleHint },
            author = outcome.metadata.author.ifBlank { authorHint },
            coverUrl = outcome.metadata.coverUrl.ifBlank { coverHint },
            intro = outcome.metadata.intro,
            wordCountStr = outcome.metadata.formattedWordCount(),
            serialStatus = outcome.metadata.serialStatus,
            downloadedChapters = outcome.downloadedChapters,
            remoteTotalChapters = outcome.remoteTotalChapters,
            latestChapterTitle = outcome.latestChapterTitle,
            isPartial = outcome.isPartial,
            txtBytes = outcome.txtBytes,
            builtToc = outcome.builtToc
        )
    }
}
