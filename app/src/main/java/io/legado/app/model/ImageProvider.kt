package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.collection.LruCache
import io.legado.app.R
import io.legado.app.constant.AppLog.putDebug
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.isPdf
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.model.localBook.PdfFile
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object ImageProvider {

    private val errorBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.image_loading_error)
    }

    /**
     * 缓存bitmap LruCache实现
     * filePath bitmap
     */
    private const val M = 1024 * 1024
    val cacheSize: Int
        get() {
            if (AppConfig.bitmapCacheSize !in 1..1024) {
                AppConfig.bitmapCacheSize = 50
            }
            return AppConfig.bitmapCacheSize * M
        }

    val bitmapLruCache = BitmapLruCache()

    class BitmapLruCache : LruCache<String, Bitmap>(cacheSize) {

        private var removeCount = 0

        val count get() = putCount() + createCount() - evictionCount() - removeCount

        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (!evicted) {
                synchronized(this) {
                    removeCount++
                }
            }
            //错误图片不能释放,占位用,防止一直重复获取图片
            if (oldValue != errorBitmap) {
                oldValue.recycle()
                //putDebug("ImageProvider: trigger bitmap recycle. URI: $filePath")
                //putDebug("ImageProvider : cacheUsage ${size()}bytes / ${maxSize()}bytes")
            }
        }

    }

    fun put(key: String, bitmap: Bitmap) {
        ensureLruCacheSize(bitmap)
        bitmapLruCache.put(key, bitmap)
    }

    fun get(key: String): Bitmap? {
        return bitmapLruCache[key]
    }

    fun remove(key: String): Bitmap? {
        return bitmapLruCache.remove(key)
    }

    private fun getNotRecycled(key: String): Bitmap? {
        val bitmap = bitmapLruCache[key] ?: return null
        if (bitmap.isRecycled) {
            bitmapLruCache.remove(key)
            return null
        }
        return bitmap
    }

    private fun ensureLruCacheSize(bitmap: Bitmap) {
        val lruMaxSize = bitmapLruCache.maxSize()
        val lruSize = bitmapLruCache.size()
        val byteCount = bitmap.byteCount
        val size = if (byteCount > lruMaxSize) {
            min(256 * M, (byteCount * 1.3).toInt())
        } else if (lruSize + byteCount > lruMaxSize && bitmapLruCache.count < 5) {
            min(256 * M, (lruSize + byteCount * 1.3).toInt())
        } else {
            lruMaxSize
        }
        if (size > lruMaxSize) {
            bitmapLruCache.resize(size)
        }
    }

    /**
     *缓存网络图片和epub图片
     */
    suspend fun cacheImage(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): File {
        return withContext(IO) {
            val vFile = BookHelp.getImage(book, src)
            if (!BookHelp.isImageExist(book, src)) {
                // 1. 优先检查番茄小说本地离线插图缓存
                val fanqieCached = io.legado.app.fanqie.FanqieLegadoBridge.findCachedInlineImage(book, src)
                if (fanqieCached != null && fanqieCached.isFile) {
                    val newFile = FileUtils.createFileIfNotExist(vFile.absolutePath)
                    fanqieCached.inputStream().use { input ->
                        FileOutputStream(newFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    return@withContext vFile
                }
                // 2. 只要是网络图片或番茄图片标识，优先通过多候选 JPEG 转码下载器拉取并写入双端缓存
                val fqId = io.legado.app.fanqie.FanqieLegadoBridge.extractFanqieBookId(book).ifBlank { "common" }
                if (src.startsWith("http", ignoreCase = true) || src.startsWith("//") || src.contains("novel-pic") || src.contains("byteimg")) {
                    val downloaded = io.legado.app.fanqie.FanqieLegadoBridge.downloadAndCacheInlineImage(fqId, src)
                    if (downloaded != null && downloaded.isFile) {
                        val newFile = FileUtils.createFileIfNotExist(vFile.absolutePath)
                        downloaded.inputStream().use { input ->
                            FileOutputStream(newFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        return@withContext vFile
                    }
                }
                // 3. 原生 EPUB / PDF / MOBI / 常规书源下载通道
                val inputStream = when {
                    book.isEpub -> EpubFile.getImage(book, src)
                    book.isPdf -> PdfFile.getImage(book, src)
                    book.isMobi -> MobiFile.getImage(book, src)
                    else -> {
                        BookHelp.saveImage(bookSource, book, src)
                        null
                    }
                }
                inputStream?.use { input ->
                    val newFile = FileUtils.createFileIfNotExist(vFile.absolutePath)
                    FileOutputStream(newFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            return@withContext vFile
        }
    }

    /**
     *获取图片宽度高度信息
     */
    suspend fun getImageSize(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): Size {
        val file = cacheImage(book, src, bookSource)
        return decodeFileSize(file, src)
    }

    private fun decodeFileSize(file: File, src: String): Size {
        val op = BitmapFactory.Options()
        // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
        op.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            //svg size
            val size = SvgUtils.getSize(file.absolutePath)
            if (size != null) return size
            putDebug("ImageProvider: $src Unsupported image type")
            //file.delete() 重复下载
            return Size(errorBitmap.width, errorBitmap.height)
        }
        return Size(op.outWidth, op.outHeight)
    }

    // ---------------------------------------------------------------------
    // 0.4.7：番茄书正文插图排版期“非阻塞”取尺寸
    // 旧逻辑在排版协程里同步下载(多候选 URL × 10s/12s 超时)，慢网时排版被卡死、翻页取消后插图位置空白，
    // 下载失败还会退化为 <80px 的行内小占位。现改为：本地已有 → 直接读尺寸；没有 → 先用占位尺寸排版，
    // 后台异步抓取，抓完若真实比例与占位不同则防抖重排当前书。
    // ---------------------------------------------------------------------

    /** 占位排版尺寸（4:3 横图，宽高均 ≥ 80，保证走独立图框而不是行内小占位） */
    private val placeholderSize = Size(800, 600)
    private const val RETRY_COOLDOWN_MS = 60_000L
    private val placeholderSrcs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile
    private var relayoutJob: kotlinx.coroutines.Job? = null

    private fun isFanqieRemoteImage(book: Book, src: String): Boolean {
        if (io.legado.app.fanqie.FanqieLegadoBridge.extractFanqieBookId(book).isBlank()) return false
        return src.startsWith("http", ignoreCase = true) || src.startsWith("//") ||
            src.contains("novel-pic") || src.contains("byteimg")
    }

    private fun findLocalImage(book: Book, src: String): File? {
        val v = BookHelp.getImage(book, src)
        if (v.isFile && v.length() > 128L) return v
        val fq = io.legado.app.fanqie.FanqieLegadoBridge.findCachedInlineImage(book, src)
        if (fq != null && fq.isFile && fq.length() > 128L) return fq
        return null
    }

    /**
     * 排版期取图片尺寸：番茄书远程插图不再阻塞网络，其余书籍行为不变。
     */
    suspend fun getImageSizeFast(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): Size {
        if (!isFanqieRemoteImage(book, src)) return getImageSize(book, src, bookSource)
        val local = withContext(IO) { findLocalImage(book, src) }
        if (local != null) {
            placeholderSrcs.remove("${book.bookUrl}_$src")
            return withContext(IO) { decodeFileSize(local, src) }
        }
        val key = "${book.bookUrl}_$src"
        placeholderSrcs.add(key)
        val last = failedAt[key]
        if (last == null || System.currentTimeMillis() - last > RETRY_COOLDOWN_MS) {
            fetchInBackground(book, src, key)
        }
        return placeholderSize
    }

    private fun fetchInBackground(book: Book, src: String, key: String) {
        if (!asyncLoadingSet.add(key)) return
        asyncScope.launch {
            try {
                val file = cacheImage(book, src, ReadBook.bookSource)
                if (file.isFile && file.length() > 128L) {
                    failedAt.remove(key)
                    remove(file.absolutePath)
                    onImageFetched(book, src, key, file)
                } else {
                    failedAt[key] = System.currentTimeMillis()
                }
            } catch (_: Throwable) {
                failedAt[key] = System.currentTimeMillis()
            } finally {
                asyncLoadingSet.remove(key)
            }
        }
    }

    /**
     * 图片抓取完成：若排版时用的是占位尺寸且真实宽高比与占位差异明显，则防抖后重排当前阅读内容。
     */
    private fun onImageFetched(book: Book, src: String, key: String, file: File) {
        if (!placeholderSrcs.remove(key)) return
        val real = decodeFileSize(file, src)
        if (real.width < 1 || real.height < 1) return
        val ratioReal = real.height.toFloat() / real.width
        val ratioHolder = placeholderSize.height.toFloat() / placeholderSize.width
        val needRelayout = kotlin.math.abs(ratioReal - ratioHolder) / ratioHolder > 0.03f ||
            (real.width < 80 && real.height < 80)
        if (!needRelayout) {
            ReadBook.curTextChapter?.pages?.forEach { it.invalidateAll() }
            return
        }
        relayoutJob?.cancel()
        relayoutJob = asyncScope.launch {
            kotlinx.coroutines.delay(800) // 防抖：同一章多张图陆续到达只重排一次
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.loadContent(resetPageOffset = false)
            }
        }
    }

    private val asyncLoadingSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val asyncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + IO)

    fun isErrorOrMissing(bitmap: Bitmap?): Boolean {
        return bitmap == null || bitmap === errorBitmap || bitmap.isRecycled
    }

    /**
     * 当阅读页渲染时发现某张插图尚未缓存到本地，后台异步下载并自动刷新页面
     */
    fun triggerAsyncReloadIfNeeded(book: Book, src: String, view: android.view.View) {
        if (src.isBlank()) return
        val key = "${book.bookUrl}_$src"
        val last = failedAt[key]
        if (last != null && System.currentTimeMillis() - last < RETRY_COOLDOWN_MS) return
        if (!asyncLoadingSet.add(key)) return
        asyncScope.launch {
            try {
                val file = cacheImage(book, src, ReadBook.bookSource)
                if (file.isFile && file.length() > 128L) {
                    failedAt.remove(key)
                    remove(file.absolutePath)
                    onImageFetched(book, src, key, file)
                    view.post {
                        io.legado.app.model.ReadBook.curTextChapter?.pages?.forEach { page ->
                            page.invalidateAll()
                        }
                        view.invalidate()
                    }
                } else {
                    failedAt[key] = System.currentTimeMillis()
                    // 失败后刷新一次，让图框显示失败原因
                    view.post { view.invalidate() }
                }
            } catch (_: Throwable) {
                failedAt[key] = System.currentTimeMillis()
            } finally {
                asyncLoadingSet.remove(key)
            }
        }
    }

    /**
     *获取bitmap 使用LruCache缓存
     */
    fun getImage(
        book: Book,
        src: String,
        width: Int,
        height: Int? = null
    ): Bitmap {
        if (book.getUseReplaceRule() && src.isBlank()) {
            book.setUseReplaceRule(false)
            appCtx.toastOnUi(R.string.error_image_url_empty)
        }
        var vFile = BookHelp.getImage(book, src)
        if (!vFile.isFile || vFile.length() <= 128L) {
            val fqImg = io.legado.app.fanqie.FanqieLegadoBridge.findCachedInlineImage(book, src)
            if (fqImg != null && fqImg.isFile && fqImg.length() > 128L) {
                vFile = fqImg
            } else {
                return errorBitmap
            }
        }
        val cacheBitmap = getNotRecycled(vFile.absolutePath)
        if (cacheBitmap != null && cacheBitmap !== errorBitmap) return cacheBitmap
        return kotlin.runCatching {
            val bitmap = BitmapUtils.decodeBitmap(vFile.absolutePath, width, height)
                ?: BitmapFactory.decodeFile(vFile.absolutePath)
                ?: SvgUtils.createBitmap(vFile.absolutePath, width, height)
                ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
            put(vFile.absolutePath, bitmap)
            bitmap
        }.getOrDefault(errorBitmap)
    }

    fun clear() {
        bitmapLruCache.evictAll()
    }

}
