package io.legado.app.fanqie

import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

data class CachedChapterEntry(
    val key: String,
    val index: Int,
    val title: String,
    val formattedText: String,
    val volumeIndex: Int = 0,
    val volumeName: String = "",
    val paragraphs: List<String> = emptyList(),
    val wordCount: Int = 0,
    /**
     * 缓存 schema 版本：0 = 0.4.6 及以前（解析时已丢弃 `<img>`，无插图 URL）；
     * 1 = 0.4.7+（保留 `<img>` 行）。从 JSON 读取旧缓存时缺省为 0，新构造的条目默认为当前版本。
     */
    val imgSchema: Int = IMG_SCHEMA
) {
    companion object {
        const val IMG_SCHEMA = 1
    }

    /** 旧版缓存（不含插图 URL），需重新抓取才能显示正文插图 */
    fun isLegacyNoImage(): Boolean = imgSchema < IMG_SCHEMA

    /**
     * 若旧版缓存仅保存了 formattedText，自动从中拆出干净的正文段落列表，保证导出 EPUB/TXT 时兼容旧断点。
     */
    fun resolvedParagraphs(): List<String> {
        if (paragraphs.isNotEmpty()) return paragraphs
        val lines = formattedText.lines()
        val result = ArrayList<String>()
        for ((idx, rawLine) in lines.withIndex()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            if (idx == 0 && ((trimmed.startsWith("【") && trimmed.endsWith("】")) || (trimmed.startsWith("###") && trimmed.endsWith("###")) || (trimmed.startsWith("===") && trimmed.endsWith("===")))) continue
            result.add(trimmed.removePrefix("　　").trim())
        }
        return result
    }

    fun resolvedWordCount(): Int {
        if (wordCount > 0) return wordCount
        return resolvedParagraphs().sumOf { it.length }
    }
}

object FanqieChapterCacheStore {
    private val cacheDir: File
        get() = File(appCtx.filesDir, "fanqie_chapter_cache").apply { mkdirs() }

    private fun safeId(fanqieBookId: String): String =
        fanqieBookId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "unknown" }

    private fun fileFor(fanqieBookId: String): File =
        File(cacheDir, "${safeId(fanqieBookId)}.json")

    private fun metaFileFor(fanqieBookId: String): File =
        File(cacheDir, "${safeId(fanqieBookId)}.meta.json")

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
                val volIdx = o.optInt("volume_index", 0)
                val volName = o.optString("volume_name", "")
                val wc = o.optInt("word_count", 0)
                val imgV = o.optInt("img_v", 0)
                val pArr = o.optJSONArray("paragraphs")
                val paras = ArrayList<String>()
                if (pArr != null) {
                    for (p in 0 until pArr.length()) {
                        val line = pArr.optString(p)
                        if (line.isNotBlank()) paras.add(line)
                    }
                }
                if (key.isNotBlank() && (text.isNotBlank() || paras.isNotEmpty())) {
                    val effectiveText = if (text.isNotBlank()) text else FanqieCrawler.formatChapter(title, paras)
                    out[key] = CachedChapterEntry(
                        key = key,
                        index = idx,
                        title = title,
                        formattedText = effectiveText,
                        volumeIndex = volIdx,
                        volumeName = volName,
                        paragraphs = paras,
                        wordCount = wc,
                        imgSchema = imgV
                    )
                }
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    /** 该书缓存中是否存在 0.4.6 及以前生成的、不含插图 URL 的旧章节 */
    fun countLegacyNoImageChapters(fanqieBookId: String): Int =
        loadCachedMap(fanqieBookId).values.count { it.isLegacyNoImage() }

    @Synchronized
    fun saveCachedMap(fanqieBookId: String, map: Map<String, CachedChapterEntry>) {
        if (fanqieBookId.isBlank()) return
        try {
            val arr = JSONArray()
            for (entry in map.values.sortedBy { it.index }) {
                val pArr = JSONArray()
                entry.resolvedParagraphs().forEach { pArr.put(it) }
                arr.put(
                    JSONObject()
                        .put("key", entry.key)
                        .put("index", entry.index)
                        .put("title", entry.title)
                        .put("text", entry.formattedText)
                        .put("volume_index", entry.volumeIndex)
                        .put("volume_name", entry.volumeName)
                        .put("word_count", entry.resolvedWordCount())
                        .put("img_v", entry.imgSchema)
                        .put("paragraphs", pArr)
                )
            }
            val f = fileFor(fanqieBookId)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun saveMetadata(meta: FanqieMetadata) {
        if (meta.bookId.isBlank()) return
        try {
            val titlesArr = JSONArray()
            meta.chapterTitles.forEach { titlesArr.put(it) }

            val volumesArr = JSONArray()
            meta.volumes.forEach { v ->
                volumesArr.put(
                    JSONObject()
                        .put("index", v.index)
                        .put("name", v.name)
                        .put("chapter_count", v.chapterCount)
                )
            }

            val chaptersArr = JSONArray()
            meta.structuredChapters.forEach { ch ->
                chaptersArr.put(
                    JSONObject()
                        .put("index", ch.index)
                        .put("item_id", ch.itemId ?: "")
                        .put("title", ch.title)
                        .put("locked", ch.locked)
                        .put("volume_index", ch.volumeIndex)
                        .put("volume_name", ch.volumeName)
                        .put("index_in_volume", ch.indexInVolume)
                        .put("word_count", ch.wordCount)
                        .put("first_pass_time", ch.firstPassTime)
                )
            }

            val obj = JSONObject()
                .put("book_id", meta.bookId)
                .put("title", meta.title)
                .put("author", meta.author)
                .put("score", meta.score)
                .put("cover_url", meta.coverUrl)
                .put("intro", meta.intro)
                .put("word_count", meta.wordCount)
                .put("chapter_count", meta.chapterCount)
                .put("serial_status", meta.serialStatus)
                .put("available_chapters", meta.availableChapters)
                .put("pc_available", meta.pcAvailable)
                .put("latest_chapter_title", meta.latestChapterTitle)
                .put("chapter_titles", titlesArr)
                .put("volumes", volumesArr)
                .put("structured_chapters", chaptersArr)
                .put("updated_at", System.currentTimeMillis())

            val f = metaFileFor(meta.bookId)
            f.parentFile?.mkdirs()
            f.writeText(obj.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun loadMetadata(fanqieBookId: String): FanqieMetadata? {
        if (fanqieBookId.isBlank()) return null
        val f = metaFileFor(fanqieBookId)
        if (!f.isFile) return null
        return try {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val titlesArr = obj.optJSONArray("chapter_titles")
            val titles = ArrayList<String>()
            if (titlesArr != null) {
                for (i in 0 until titlesArr.length()) titles.add(titlesArr.optString(i))
            }
            val volumesArr = obj.optJSONArray("volumes")
            val volumes = ArrayList<FanqieVolume>()
            if (volumesArr != null) {
                for (i in 0 until volumesArr.length()) {
                    val v = volumesArr.optJSONObject(i) ?: continue
                    val name = v.optString("name").trim()
                    if (name.isNotEmpty()) {
                        volumes.add(FanqieVolume(v.optInt("index", i), name, v.optInt("chapter_count", 0)))
                    }
                }
            }
            val chArr = obj.optJSONArray("structured_chapters")
            val structuredChapters = ArrayList<FanqieChapterItem>()
            if (chArr != null) {
                for (i in 0 until chArr.length()) {
                    val c = chArr.optJSONObject(i) ?: continue
                    structuredChapters.add(
                        FanqieChapterItem(
                            index = c.optInt("index", i + 1),
                            itemId = c.optString("item_id").ifBlank { null },
                            title = c.optString("title", "第${i + 1}章"),
                            locked = c.optBoolean("locked", false),
                            volumeIndex = c.optInt("volume_index", 0),
                            volumeName = c.optString("volume_name", ""),
                            indexInVolume = c.optInt("index_in_volume", i + 1),
                            wordCount = c.optInt("word_count", 0),
                            firstPassTime = c.optLong("first_pass_time", 0L)
                        )
                    )
                }
            }
            FanqieMetadata(
                bookId = obj.optString("book_id", fanqieBookId),
                title = obj.optString("title", "未知书名"),
                author = obj.optString("author", "未知作者"),
                score = obj.optString("score", "暂无"),
                coverUrl = obj.optString("cover_url", ""),
                intro = obj.optString("intro", ""),
                wordCount = obj.optInt("word_count", 0),
                chapterCount = obj.optInt("chapter_count", titles.size),
                serialStatus = obj.optString("serial_status", "未知"),
                availableChapters = obj.optInt("available_chapters", titles.size),
                pcAvailable = obj.optBoolean("pc_available", true),
                chapterTitles = titles,
                latestChapterTitle = obj.optString("latest_chapter_title", ""),
                volumes = volumes,
                structuredChapters = structuredChapters
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun countCached(fanqieBookId: String): Int = loadCachedMap(fanqieBookId).size

    /**
     * 彻底删除指定番茄书籍的本地章节缓存 (.json) 与目录元数据缓存 (.meta.json)
     */
    @Synchronized
    fun deleteBookCache(fanqieBookId: String) {
        if (fanqieBookId.isBlank()) return
        try {
            val f = fileFor(fanqieBookId)
            if (f.exists()) f.delete()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            if (tmp.exists()) tmp.delete()
            val mf = metaFileFor(fanqieBookId)
            if (mf.exists()) mf.delete()
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun removeCache(fanqieBookId: String) {
        if (fanqieBookId.isBlank()) return
        try {
            fileFor(fanqieBookId).delete()
            metaFileFor(fanqieBookId).delete()
        } catch (_: Exception) {
        }
    }
}
