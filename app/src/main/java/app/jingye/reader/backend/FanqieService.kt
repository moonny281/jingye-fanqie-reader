package app.jingye.reader.backend

import android.content.Context
import app.jingye.reader.data.Book
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

    suspend fun parseInput(rawInput: String): FanqieMetadata = withContext(Dispatchers.IO) {
        if (prefs.useRemoteBackend) {
            remoteClient.parse(prefs.backendUrl, rawInput)
        } else {
            val bookId = FanqieParser.resolveBookId(rawInput)
            newLocalCrawler().fetchMetadata(bookId)
        }
    }

    suspend fun downloadAndImport(
        metadata: FanqieMetadata,
        onProgress: (FanqieProgress) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        val txtBytes = if (prefs.useRemoteBackend) {
            remoteClient.downloadViaSse(prefs.backendUrl, metadata.bookId, onProgress)
        } else {
            val (_, bytes) = newLocalCrawler().downloadBook(metadata.bookId, onProgress)
            bytes
        }
        val book = library.importFanqieTxt(
            fanqieBookId = metadata.bookId,
            title = metadata.title,
            author = metadata.author,
            totalChapters = metadata.chapterCount,
            txtContent = txtBytes
        )
        localSource.invalidate(book.id)
        book
    }
}
