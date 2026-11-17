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
        if (!asyncLoadingSet.add(key)) return
        asyncScope.launch {
            try {
                val file = cacheImage(book, src, ReadBook.bookSource)
                if (file.isFile && file.length() > 128L) {
                    remove(file.absolutePath)
                    view.post {
                        io.legado.app.model.ReadBook.curTextChapter?.pages?.forEach { page ->
                            page.invalidateAll()
                        }
                        view.invalidate()
                    }
                }
            } catch (_: Throwable) {
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
