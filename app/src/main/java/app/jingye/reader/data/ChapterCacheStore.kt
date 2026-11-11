package app.jingye.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CachedChapterEntry(
    val key: String,
    val index: Int,
    val title: String,
    val formattedText: String
)

class ChapterCacheStore(ctx: Context) {
    private val cacheDir = File(ctx.filesDir, "chapter_cache").apply { mkdirs() }

    private fun fileFor(fanqieBookId: String): File {
        val safeId = fanqieBookId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "unknown" }
        return File(cacheDir, "${safeId}.json")
    }

    @Synchronized
    fun loadCachedMap(fanqieBookId: String): LinkedHashMap<String, CachedChapterEntry> {
        val out = LinkedHashMap<String, CachedChapterEntry>()
        if (fanqieBookId.isBlank()) return out
        val file = fileFor(fanqieBookId)
        if (!file.isFile) return out
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key")
                val idx = o.optInt("index", i + 1)
                val title = o.optString("title")
                val text = o.optString("text")
                if (key.isNotBlank() && text.isNotBlank()) {
                    out[key] = CachedChapterEntry(key, idx, title, text)
                }
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    @Synchronized
    fun saveCachedMap(fanqieBookId: String, map: Map<String, CachedChapterEntry>) {
        if (fanqieBookId.isBlank()) return
        try {
            val arr = JSONArray()
            for (entry in map.values.sortedBy { it.index }) {
                arr.put(
                    JSONObject()
                        .put("key", entry.key)
                        .put("index", entry.index)
                        .put("title", entry.title)
                        .put("text", entry.formattedText)
                )
            }
            val f = fileFor(fanqieBookId)
            f.parentFile?.mkdirs()
            f.writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun countCached(fanqieBookId: String): Int = loadCachedMap(fanqieBookId).size

    @Synchronized
    fun removeCache(fanqieBookId: String) {
        if (fanqieBookId.isBlank()) return
        try {
            fileFor(fanqieBookId).delete()
        } catch (_: Exception) {
        }
    }
}
