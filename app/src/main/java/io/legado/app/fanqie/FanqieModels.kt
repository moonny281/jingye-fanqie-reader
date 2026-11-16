package io.legado.app.fanqie

import java.text.DecimalFormat

class FanqieException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 番茄小说一级目录（分卷信息）
 * @param index 0-based 分卷序号 (0, 1, 2...)
 * @param name 规范化或原始分卷名（如 "第一卷 灾厄降临"）
 * @param chapterCount 该一级分卷下包含的二级章节数量
 */
data class FanqieVolume(
    val index: Int,
    val name: String,
    val chapterCount: Int
) {
    val level: Int get() = 1
    fun displayOrder(): Int = index + 1
}

/**
 * 番茄小说二级目录（章节条目）
 * @param index 全书连续章节序号（1-based: 1..N）
 * @param itemId 番茄官方章节唯一 ID（用于正文抓取与缓存对账）
 * @param title 章节标题
 * @param locked 是否加密/VIP锁定
 * @param volumeIndex 所属一级分卷的 0-based 序号（0, 1, 2...）
 * @param volumeName 所属一级分卷名称
 * @param indexInVolume 在当前分卷内的相对章节序号（1-based: 1..M）
 * @param wordCount 章节字数
 * @param firstPassTime 章节首次发布时间戳（秒）
 */
data class FanqieChapterItem(
    val index: Int,
    val itemId: String?,
    val title: String,
    val locked: Boolean = false,
    val volumeIndex: Int = 0,
    val volumeName: String = "",
    val indexInVolume: Int = index,
    val wordCount: Int = 0,
    val firstPassTime: Long = 0L
) {
    val level: Int get() = 2
    fun cacheKey(): String = itemId?.takeIf { it.isNotBlank() } ?: "idx_$index"
    fun hasDownloadableId(): Boolean = !itemId.isNullOrBlank()
}

/**
 * 构建 TXT 时同步生成的精确字节偏移目录项（严格区分 level=1 一级分卷目录 与 level=2 二级章节目录），
 * 供 Legado Room `BookChapter` 与伴生 `.toc.json` 直接注入，避免全书正则扫描错位或漏章。
 */
data class FanqieBuiltTocItem(
    val index: Int,
    val title: String,
    val isVolume: Boolean,
    val startByte: Long,
    val endByte: Long,
    val wordCount: Int = 0,
    val volumeIndex: Int = 0,
    val volumeName: String = "",
    val chapterIndex: Int = 0,
    val indexInVolume: Int = 0,
    val itemId: String? = null
) {
    val level: Int get() = if (isVolume) 1 else 2
}

data class FanqieMetadata(
    val bookId: String,
    val title: String,
    val author: String,
    val score: String,
    val coverUrl: String,
    val intro: String,
    val wordCount: Int,
    val chapterCount: Int,
    val serialStatus: String,
    val availableChapters: Int,
    val pcAvailable: Boolean,
    val chapterTitles: List<String>,
    val latestChapterTitle: String = "",
    val volumes: List<FanqieVolume> = emptyList(),
    val structuredChapters: List<FanqieChapterItem> = emptyList()
) {
    fun formattedWordCount(): String {
        if (wordCount <= 0) return "未知"
        return if (wordCount >= 10000) {
            val df = DecimalFormat("0.0")
            "${df.format(wordCount / 10000.0)} 万字"
        } else {
            "$wordCount 字"
        }
    }

    fun effectiveLatestChapter(): String {
        if (latestChapterTitle.isNotBlank()) return latestChapterTitle
        if (structuredChapters.isNotEmpty()) return structuredChapters.last().title
        return chapterTitles.lastOrNull().orEmpty()
    }

    /**
     * 返回真实可下载的二级章节总数（剔除无 itemId 的幽灵章节）
     */
    fun downloadableChapterCount(): Int {
        if (structuredChapters.isNotEmpty()) {
            val valid = structuredChapters.count { it.hasDownloadableId() }
            if (valid > 0) return valid
            return structuredChapters.size
        }
        return chapterCount.coerceAtLeast(chapterTitles.size)
    }

    /**
     * 按一级分卷（Level 1）对二级章节（Level 2）进行分组，供前端目录树预览与 EPUB/TXT 导出使用。
     */
    fun groupedByVolume(): List<Pair<FanqieVolume, List<FanqieChapterItem>>> {
        if (structuredChapters.isEmpty()) {
            if (chapterTitles.isEmpty()) return emptyList()
            val fallbackChs = chapterTitles.mapIndexed { idx, t ->
                FanqieChapterItem(
                    index = idx + 1,
                    itemId = null,
                    title = t,
                    locked = false,
                    volumeIndex = 0,
                    volumeName = "正文卷",
                    indexInVolume = idx + 1
                )
            }
            return listOf(FanqieVolume(0, FanqieCrawler.normalizeVolumeTitle("正文卷", 0), fallbackChs.size) to fallbackChs)
        }
        val grouped = LinkedHashMap<Int, MutableList<FanqieChapterItem>>()
        val volNames = LinkedHashMap<Int, String>()
        for (v in volumes) {
            volNames[v.index] = v.name
        }
        for (ch in structuredChapters) {
            val vIdx = ch.volumeIndex.coerceAtLeast(0)
            grouped.getOrPut(vIdx) { ArrayList() }.add(ch)
            if (ch.volumeName.isNotBlank() && vIdx !in volNames) {
                volNames[vIdx] = ch.volumeName
            }
        }
        return grouped.entries.mapIndexed { seqIdx, (vIdx, list) ->
            val rawName = volNames[vIdx]?.takeIf { it.isNotBlank() } ?: "正文卷"
            val normName = FanqieCrawler.normalizeVolumeTitle(rawName, seqIdx)
            FanqieVolume(index = seqIdx, name = normName, chapterCount = list.size) to list
        }
    }
}

data class FanqieSearchItem(
    val bookId: String,
    val title: String,
    val author: String,
    val category: String = "",
    val score: String = "",
    val wordCount: Int = 0,
    val serialStatus: String = "",
    val intro: String = "",
    val coverUrl: String = ""
) {
    val abstractText: String get() = intro

    fun formattedWordCount(): String {
        if (wordCount <= 0) return ""
        return if (wordCount >= 10000) {
            val df = DecimalFormat("0.0")
            "${df.format(wordCount / 10000.0)}万字"
        } else {
            "${wordCount}字"
        }
    }

    fun metaLine(): String {
        val parts = mutableListOf<String>()
        if (author.isNotBlank()) parts.add(author)
        if (category.isNotBlank()) parts.add(category)
        if (serialStatus.isNotBlank()) parts.add(serialStatus)
        val wc = formattedWordCount()
        if (wc.isNotBlank()) parts.add(wc)
        if (score.isNotBlank() && score != "暂无" && score != "0" && score != "0.0") {
            parts.add("${score}分")
        }
        return parts.joinToString(" · ")
    }
}

data class FanqieProgress(
    val bookId: String,
    val status: String,
    val current: Int,
    val total: Int,
    val title: String,
    val chapterTitle: String,
    val percent: Int,
    val filename: String?,
    val failed: Int,
    val message: String,
    val latestChapterTitle: String = "",
    val resumedFromCount: Int = 0
)

data class BookUpdateCheckResult(
    val bookId: String,
    val fanqieBookId: String,
    val title: String,
    val localChapters: Int,
    val remoteChapters: Int,
    val newChaptersCount: Int,
    val latestChapterTitle: String,
    val serialStatus: String,
    val isPartialResume: Boolean
) {
    fun hasPendingDownload(): Boolean = newChaptersCount > 0
}
