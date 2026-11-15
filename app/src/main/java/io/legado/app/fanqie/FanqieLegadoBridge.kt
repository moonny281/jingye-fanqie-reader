package io.legado.app.fanqie

import android.net.Uri
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val booksDir: File
        get() = File(appCtx.filesDir, "fanqie_books").apply { mkdirs() }

    private val remoteClient = FanqieRemoteClient()

    private fun newLocalCrawler() = FanqieCrawler(
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

    fun getCachedChapterCount(fanqieBookId: String): Int {
        if (fanqieBookId.isBlank()) return 0
        val cached = FanqieChapterCacheStore.countCached(fanqieBookId)
        if (cached > 0) return cached
        val b = findBookByFanqieId(fanqieBookId) ?: return 0
        return parseTag(b)?.downloadedChapters ?: b.totalChapterNum
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
     * 检测单本番茄书籍的远端更新与最新章节名，并写回 Legado 的 Room 数据库。
     */
    suspend fun checkBookUpdate(book: Book): BookUpdateCheckResult = withContext(Dispatchers.IO) {
        val tagInfo = parseTag(book)
            ?: throw FanqieException("《${book.name}》不是番茄下载书籍，无法检测番茄更新")
        val crawler = newLocalCrawler()
        val meta = crawler.fetchMetadata(tagInfo.fanqieBookId, forceRefresh = true)
        val cachedCount = FanqieChapterCacheStore.countCached(tagInfo.fanqieBookId)
        val localCount = maxOf(cachedCount, tagInfo.downloadedChapters, book.totalChapterNum)
        val remoteCount = maxOf(meta.chapterCount, localCount)
        val newChapters = (remoteCount - localCount).coerceAtLeast(0)
        val latestTitle = meta.effectiveLatestChapter()
        val isPartial = tagInfo.isPartial && newChapters > 0

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
     * 将下载或断点保存的 TXT 写入应用专属目录，并注册/更新到 Legado 的 Room 书架数据库与章节表中。
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
        txtBytes: ByteArray
    ): Book {
        val cleanTitle = title.trim().ifEmpty { "番茄小说_$fanqieBookId" }
        val cleanAuthor = author.trim().ifEmpty { "未知作者" }
        val txtFile = File(booksDir, "fanqie_${fanqieBookId}.txt")
        txtFile.parentFile?.mkdirs()
        txtFile.writeBytes(txtBytes)
        val fileUriStr = Uri.fromFile(txtFile).toString()

        val existing = findBookByFanqieId(fanqieBookId)
            ?: appDb.bookDao.getBook(fileUriStr)
            ?: appDb.bookDao.getBook(cleanTitle, cleanAuthor)

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

        val book = if (existing != null) {
            existing.apply {
                this.bookUrl = fileUriStr
                this.tocUrl = "^【[^\\r\\n]{1,75}】$"
                this.origin = BookType.localTag
                this.originName = "${cleanTitle}.txt"
                this.name = cleanTitle
                this.author = cleanAuthor
                if (coverUrl.isNotBlank()) this.coverUrl = coverUrl
                if (intro.isNotBlank()) this.intro = intro
                this.charset = "UTF-8"
                this.type = BookType.text or BookType.local
                if (wordCountStr.isNotBlank()) this.wordCount = wordCountStr
                this.kind = listOf("番茄小说", serialStatus).filter { it.isNotBlank() }.joinToString(",")
                this.customTag = encodeTag(fanqieBookId, downloadedChapters, effectiveRemote, isPartial)
                this.latestChapterTitle = displayLatest
                this.latestChapterTime = txtFile.lastModified()
                this.lastCheckTime = System.currentTimeMillis()
                this.lastCheckCount = pendingCount
                this.totalChapterNum = maxOf(downloadedChapters, 1)
                this.canUpdate = true
            }
        } else {
            val minOrder = try { appDb.bookDao.minOrder } catch (_: Throwable) { 0 }
            Book(
                bookUrl = fileUriStr,
                tocUrl = "^【[^\\r\\n]{1,75}】$",
                origin = BookType.localTag,
                originName = "${cleanTitle}.txt",
                name = cleanTitle,
                author = cleanAuthor,
                kind = listOf("番茄小说", serialStatus).filter { it.isNotBlank() }.joinToString(","),
                customTag = encodeTag(fanqieBookId, downloadedChapters, effectiveRemote, isPartial),
                coverUrl = coverUrl.ifBlank { null },
                intro = intro.ifBlank { null },
                charset = "UTF-8",
                type = BookType.text or BookType.local,
                latestChapterTitle = displayLatest,
                latestChapterTime = txtFile.lastModified(),
                lastCheckTime = System.currentTimeMillis(),
                lastCheckCount = pendingCount,
                totalChapterNum = maxOf(downloadedChapters, 1),
                durChapterTitle = "第 1 章",
                durChapterIndex = 0,
                durChapterPos = 0,
                durChapterTime = System.currentTimeMillis(),
                wordCount = wordCountStr.ifBlank { null },
                canUpdate = true,
                order = minOrder - 1
            )
        }

        // 解析 TXT 章节偏移并同步写入 Legado 的 chapters 表，确保点击书籍秒开不卡顿
        try {
            TextFile.clear()
            val chapters = LocalBook.getChapterList(book)
            // 保留我们的最新章节状态文案
            book.latestChapterTitle = displayLatest
            book.lastCheckCount = pendingCount
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
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
        shouldStop: (() -> Boolean)? = null,
        onProgress: (FanqieProgress) -> Unit
    ): Book {
        val crawler = newLocalCrawler()
        val existingCache = FanqieChapterCacheStore.loadCachedMap(fanqieBookId)

        var lastSavedBook: Book? = null
        val outcome = crawler.downloadBookIncremental(
            bookId = fanqieBookId,
            existingCache = existingCache,
            shouldStop = shouldStop,
            onCheckpoint = { downloadedCount, totalCount, latestTitle, checkpointBytes, updatedCache ->
                FanqieChapterCacheStore.saveCachedMap(fanqieBookId, updatedCache)
                lastSavedBook = saveOrUpdateLegadoBook(
                    fanqieBookId = fanqieBookId,
                    title = titleHint,
                    author = authorHint,
                    coverUrl = "",
                    intro = "",
                    wordCountStr = "",
                    serialStatus = "",
                    downloadedChapters = downloadedCount,
                    remoteTotalChapters = totalCount,
                    latestChapterTitle = latestTitle,
                    isPartial = downloadedCount < totalCount,
                    txtBytes = checkpointBytes
                )
            },
            onProgress = onProgress
        )

        FanqieChapterCacheStore.saveCachedMap(fanqieBookId, outcome.updatedCache)
        val finalBook = saveOrUpdateLegadoBook(
            fanqieBookId = outcome.metadata.bookId,
            title = outcome.metadata.title.ifBlank { titleHint },
            author = outcome.metadata.author.ifBlank { authorHint },
            coverUrl = outcome.metadata.coverUrl,
            intro = outcome.metadata.intro,
            wordCountStr = outcome.metadata.formattedWordCount(),
            serialStatus = outcome.metadata.serialStatus,
            downloadedChapters = outcome.downloadedChapters,
            remoteTotalChapters = outcome.remoteTotalChapters,
            latestChapterTitle = outcome.latestChapterTitle,
            isPartial = outcome.isPartial,
            txtBytes = outcome.txtBytes
        )
        return finalBook
    }
}
