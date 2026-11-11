package app.jingye.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Book(
    val id: String,
    val title: String,
    val path: String,
    val chapter: Int = 0,
    val charOffset: Int = 0,
    val lastReadAt: Long = 0L,
    val author: String = "",
    val totalChapters: Int = 0,
    val fanqieBookId: String = "",
    val remoteTotalChapters: Int = 0,
    val latestChapterTitle: String = "",
    val serialStatus: String = "",
    val hasUpdate: Boolean = false,
    val isPartialDownload: Boolean = false,
    val lastCheckedAt: Long = 0L
) {
    /** 待续下或新增的章节数 */
    fun pendingChaptersCount(): Int = (remoteTotalChapters - totalChapters).coerceAtLeast(0)
}

class LibraryStore(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "books").apply { mkdirs() }

    @Synchronized
    fun all(): List<Book> = read().sortedByDescending { it.lastReadAt }

    @Synchronized
    fun get(id: String): Book? = read().firstOrNull { it.id == id }

    @Synchronized
    fun findByFanqieId(fanqieBookId: String): Book? =
        if (fanqieBookId.isBlank()) null else read().firstOrNull { it.fanqieBookId == fanqieBookId }

    fun importTxt(uri: Uri): Book {
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "未命名.txt"
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.txt")
        val inputStream = ctx.contentResolver.openInputStream(uri)
            ?: error("无法打开所选文件")
        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val cleanTitle = name.removeSuffix(".txt").removeSuffix(".TXT").trim().ifEmpty { "未命名" }
        val book = Book(
            id = id,
            title = cleanTitle,
            path = file.absolutePath,
            lastReadAt = System.currentTimeMillis()
        )
        synchronized(this) {
            write(read() + book)
        }
        return book
    }

    fun importFanqieTxt(
        fanqieBookId: String,
        title: String,
        author: String,
        totalChapters: Int,
        txtContent: ByteArray,
        remoteTotalChapters: Int = totalChapters,
        latestChapterTitle: String = "",
        serialStatus: String = "",
        isPartialDownload: Boolean = false
    ): Book {
        synchronized(this) {
            val currentList = read().toMutableList()
            val existingIdx = currentList.indexOfFirst {
                (fanqieBookId.isNotBlank() && it.fanqieBookId == fanqieBookId) || it.title == title
            }
            val effectiveRemoteTotal = maxOf(remoteTotalChapters, totalChapters)
            val hasMore = effectiveRemoteTotal > totalChapters
            if (existingIdx >= 0) {
                val existing = currentList[existingIdx]
                val file = File(existing.path)
                file.parentFile?.mkdirs()
                file.writeBytes(txtContent)
                val updated = existing.copy(
                    title = title.ifBlank { existing.title },
                    author = author.ifBlank { existing.author },
                    totalChapters = maxOf(totalChapters, 1),
                    fanqieBookId = fanqieBookId.ifBlank { existing.fanqieBookId },
                    remoteTotalChapters = maxOf(effectiveRemoteTotal, existing.remoteTotalChapters),
                    latestChapterTitle = latestChapterTitle.ifBlank { existing.latestChapterTitle },
                    serialStatus = serialStatus.ifBlank { existing.serialStatus },
                    hasUpdate = hasMore && !isPartialDownload,
                    isPartialDownload = isPartialDownload,
                    lastCheckedAt = System.currentTimeMillis(),
                    lastReadAt = if (existing.lastReadAt > 0L) existing.lastReadAt else System.currentTimeMillis()
                )
                currentList[existingIdx] = updated
                write(currentList)
                return updated
            } else {
                val id = UUID.randomUUID().toString()
                val file = File(dir, "$id.txt")
                file.writeBytes(txtContent)
                val book = Book(
                    id = id,
                    title = title.ifBlank { "未命名" },
                    path = file.absolutePath,
                    chapter = 0,
                    charOffset = 0,
                    lastReadAt = System.currentTimeMillis(),
                    author = author,
                    totalChapters = maxOf(totalChapters, 1),
                    fanqieBookId = fanqieBookId,
                    remoteTotalChapters = effectiveRemoteTotal,
                    latestChapterTitle = latestChapterTitle,
                    serialStatus = serialStatus,
                    hasUpdate = hasMore && !isPartialDownload,
                    isPartialDownload = isPartialDownload,
                    lastCheckedAt = System.currentTimeMillis()
                )
                currentList.add(book)
                write(currentList)
                return book
            }
        }
    }

    @Synchronized
    fun updateRemoteStatus(
        id: String,
        remoteTotalChapters: Int,
        latestChapterTitle: String,
        serialStatus: String
    ): Book? {
        var updatedBook: Book? = null
        val newList = read().map {
            if (it.id == id) {
                val hasNew = remoteTotalChapters > it.totalChapters
                val b = it.copy(
                    remoteTotalChapters = maxOf(remoteTotalChapters, it.totalChapters),
                    latestChapterTitle = latestChapterTitle.ifBlank { it.latestChapterTitle },
                    serialStatus = serialStatus.ifBlank { it.serialStatus },
                    hasUpdate = hasNew && !it.isPartialDownload,
                    lastCheckedAt = System.currentTimeMillis()
                )
                updatedBook = b
                b
            } else {
                it
            }
        }
        write(newList)
        return updatedBook
    }

    @Synchronized
    fun saveProgress(id: String, chapter: Int, charOffset: Int, totalChapters: Int = 0) {
        write(read().map {
            if (it.id == id) {
                val newTotal = if (totalChapters > 0) totalChapters else it.totalChapters
                it.copy(
                    chapter = chapter,
                    charOffset = charOffset,
                    totalChapters = newTotal,
                    remoteTotalChapters = maxOf(it.remoteTotalChapters, newTotal),
                    lastReadAt = System.currentTimeMillis()
                )
            } else {
                it
            }
        })
    }

    @Synchronized
    fun updateTotalChapters(id: String, totalChapters: Int) {
        write(read().map {
            if (it.id == id) {
                it.copy(
                    totalChapters = totalChapters,
                    remoteTotalChapters = maxOf(it.remoteTotalChapters, totalChapters)
                )
            } else {
                it
            }
        })
    }

    @Synchronized
    fun remove(id: String) {
        get(id)?.let { File(it.path).delete() }
        write(read().filter { it.id != id })
    }

    private fun read(): List<Book> {
        val raw = sp.getString("books", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val totalCh = o.optInt("totalChapters", 0)
            Book(
                id = o.getString("id"),
                title = o.getString("title"),
                path = o.getString("path"),
                chapter = o.optInt("chapter", 0),
                charOffset = o.optInt("charOffset", 0),
                lastReadAt = o.optLong("lastReadAt", 0L),
                author = o.optString("author", ""),
                totalChapters = totalCh,
                fanqieBookId = o.optString("fanqieBookId", ""),
                remoteTotalChapters = o.optInt("remoteTotalChapters", totalCh),
                latestChapterTitle = o.optString("latestChapterTitle", ""),
                serialStatus = o.optString("serialStatus", ""),
                hasUpdate = o.optBoolean("hasUpdate", false),
                isPartialDownload = o.optBoolean("isPartialDownload", false),
                lastCheckedAt = o.optLong("lastCheckedAt", 0L)
            )
        }
    }

    private fun write(list: List<Book>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("path", it.path)
                    .put("chapter", it.chapter)
                    .put("charOffset", it.charOffset)
                    .put("lastReadAt", it.lastReadAt)
                    .put("author", it.author)
                    .put("totalChapters", it.totalChapters)
                    .put("fanqieBookId", it.fanqieBookId)
                    .put("remoteTotalChapters", it.remoteTotalChapters)
                    .put("latestChapterTitle", it.latestChapterTitle)
                    .put("serialStatus", it.serialStatus)
                    .put("hasUpdate", it.hasUpdate)
                    .put("isPartialDownload", it.isPartialDownload)
                    .put("lastCheckedAt", it.lastCheckedAt)
            )
        }
        sp.edit().putString("books", arr.toString()).apply()
    }
}
