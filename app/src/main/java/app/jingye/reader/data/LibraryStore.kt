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
    val fanqieBookId: String = ""
)

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
        txtContent: ByteArray
    ): Book {
        synchronized(this) {
            val currentList = read().toMutableList()
            val existingIdx = currentList.indexOfFirst {
                (fanqieBookId.isNotBlank() && it.fanqieBookId == fanqieBookId) || it.title == title
            }
            if (existingIdx >= 0) {
                val existing = currentList[existingIdx]
                val file = File(existing.path)
                file.parentFile?.mkdirs()
                file.writeBytes(txtContent)
                val updated = existing.copy(
                    title = title,
                    author = author,
                    totalChapters = maxOf(totalChapters, existing.totalChapters),
                    fanqieBookId = fanqieBookId,
                    lastReadAt = System.currentTimeMillis()
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
                    totalChapters = totalChapters,
                    fanqieBookId = fanqieBookId
                )
                currentList.add(book)
                write(currentList)
                return book
            }
        }
    }

    @Synchronized
    fun saveProgress(id: String, chapter: Int, charOffset: Int, totalChapters: Int = 0) {
        write(read().map {
            if (it.id == id) {
                it.copy(
                    chapter = chapter,
                    charOffset = charOffset,
                    totalChapters = if (totalChapters > 0) totalChapters else it.totalChapters,
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
            if (it.id == id) it.copy(totalChapters = totalChapters) else it
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
            Book(
                id = o.getString("id"),
                title = o.getString("title"),
                path = o.getString("path"),
                chapter = o.optInt("chapter", 0),
                charOffset = o.optInt("charOffset", 0),
                lastReadAt = o.optLong("lastReadAt", 0L),
                author = o.optString("author", ""),
                totalChapters = o.optInt("totalChapters", 0),
                fanqieBookId = o.optString("fanqieBookId", "")
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
            )
        }
        sp.edit().putString("books", arr.toString()).apply()
    }
}
