package app.jingye.reader.backend

import android.content.Context
import app.jingye.reader.data.Book
import app.jingye.reader.data.ChapterCacheStore
import app.jingye.reader.data.LibraryStore
import app.jingye.reader.data.LocalTxtSource
import app.jingye.reader.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FanqieService(
    ctx: Context,
    private val prefs: ReaderPrefs,
    private val library: LibraryStore,
    private val chapterCache: ChapterCacheStore,
    private val localSource: LocalTxtSource
) {
    private val deviceCacheFile = File(ctx.filesDir, ".fanqie_device.json")
    private val remoteClient = FanqieRemoteClient()

    private fun newLocalCrawler() = FanqieCrawler(
        cacheFile = deviceCacheFile,
        appApiIntervalSec = prefs.appApiIntervalSec
    )

    suspend fun checkRemoteHealth(url: String): String = withContext(Dispatchers.IO) {
        remoteClient.checkHealth(url)
    }

    /** 按书名或作者名搜索书籍列表。 */
    suspend fun searchBooks(keyword: String): List<FanqieSearchItem> = withContext(Dispatchers.IO) {
        newLocalCrawler().searchBooks(keyword)
    }

    /** 解析分享链接或数字 book_id 为完整书籍详情。 */
    suspend fun parseInput(rawInput: String): FanqieMetadata = withContext(Dispatchers.IO) {
        if (prefs.useRemoteBackend) {
            remoteClient.parse(prefs.backendUrl, rawInput)
        } else {
            val bookId = FanqieParser.resolveBookId(rawInput)
            newLocalCrawler().fetchMetadata(bookId, forceRefresh = true)
        }
    }

    /** 查询本地已缓存的章节数（用于提示断点续下）。 */
    fun getCachedChapterCount(fanqieBookId: String): Int {
        if (fanqieBookId.isBlank()) return 0
        val cached = chapterCache.countCached(fanqieBookId)
        if (cached > 0) return cached
        return library.findByFanqieId(fanqieBookId)?.totalChapters ?: 0
    }

    /**
     * 检测单本书籍的远端更新状态与最新章节名，并同步更新 LibraryStore。
     */
    suspend fun checkBookUpdate(book: Book): BookUpdateCheckResult = withContext(Dispatchers.IO) {
        if (book.fanqieBookId.isBlank()) {
            throw FanqieException("《${book.title}》为本地导入 TXT，不支持番茄在线检测更新")
        }
        val crawler = newLocalCrawler()
        val meta = crawler.fetchMetadata(book.fanqieBookId, forceRefresh = true)
        val cachedCount = chapterCache.countCached(book.fanqieBookId)
        val localCount = if (cachedCount > 0) maxOf(cachedCount, book.totalChapters) else book.totalChapters
        val remoteCount = maxOf(meta.chapterCount, localCount)
        val latestTitle = meta.effectiveLatestChapter()
        val newChapters = (remoteCount - localCount).coerceAtLeast(0)

        library.updateRemoteStatus(
            id = book.id,
            remoteTotalChapters = remoteCount,
            latestChapterTitle = latestTitle,
            serialStatus = meta.serialStatus
        )

        BookUpdateCheckResult(
            bookId = book.id,
            fanqieBookId = book.fanqieBookId,
            title = book.title,
            localChapters = localCount,
            remoteChapters = remoteCount,
            newChaptersCount = newChapters,
            latestChapterTitle = latestTitle,
            serialStatus = meta.serialStatus,
            isPartialResume = book.isPartialDownload && remoteCount > localCount
        )
    }

    /**
     * 支持断点续下、实时批次存档与暂停控制的下载导入方法。
     */
    fun downloadOrResumeSync(
        fanqieBookId: String,
        titleHint: String = "",
        authorHint: String = "",
        shouldStop: (() -> Boolean)? = null,
        onProgress: (FanqieProgress) -> Unit
    ): Book {
        val crawler = newLocalCrawler()
        val existingCache = chapterCache.loadCachedMap(fanqieBookId)

        val outcome = crawler.downloadBookIncremental(
            bookId = fanqieBookId,
            existingCache = existingCache,
            shouldStop = shouldStop,
            onCheckpoint = { downloadedCount, totalCount, latestTitle, checkpointBytes, updatedCache ->
                chapterCache.saveCachedMap(fanqieBookId, updatedCache)
                val savedBook = library.importFanqieTxt(
                    fanqieBookId = fanqieBookId,
                    title = titleHint,
                    author = authorHint,
                    totalChapters = downloadedCount,
                    txtContent = checkpointBytes,
                    remoteTotalChapters = totalCount,
                    latestChapterTitle = latestTitle,
                    isPartialDownload = downloadedCount < totalCount
                )
                localSource.invalidate(savedBook.id)
            },
            onProgress = onProgress
        )

        chapterCache.saveCachedMap(fanqieBookId, outcome.updatedCache)
        val book = library.importFanqieTxt(
            fanqieBookId = outcome.metadata.bookId,
            title = outcome.metadata.title.ifBlank { titleHint },
            author = outcome.metadata.author.ifBlank { authorHint },
            totalChapters = outcome.downloadedChapters,
            txtContent = outcome.txtBytes,
            remoteTotalChapters = outcome.remoteTotalChapters,
            latestChapterTitle = outcome.latestChapterTitle,
            serialStatus = outcome.metadata.serialStatus,
            isPartialDownload = outcome.isPartial
        )
        localSource.invalidate(book.id)
        return book
    }

    suspend fun downloadAndImport(
        metadata: FanqieMetadata,
        shouldStop: (() -> Boolean)? = null,
        onProgress: (FanqieProgress) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        if (prefs.useRemoteBackend) {
            val txtBytes = remoteClient.downloadViaSse(prefs.backendUrl, metadata.bookId, onProgress)
            val book = library.importFanqieTxt(
                fanqieBookId = metadata.bookId,
                title = metadata.title,
                author = metadata.author,
                totalChapters = metadata.chapterCount,
                txtContent = txtBytes,
                remoteTotalChapters = metadata.chapterCount,
                latestChapterTitle = metadata.effectiveLatestChapter(),
                serialStatus = metadata.serialStatus,
                isPartialDownload = false
            )
            localSource.invalidate(book.id)
            book
        } else {
            downloadOrResumeSync(
                fanqieBookId = metadata.bookId,
                titleHint = metadata.title,
                authorHint = metadata.author,
                shouldStop = shouldStop,
                onProgress = onProgress
            )
        }
    }
}
