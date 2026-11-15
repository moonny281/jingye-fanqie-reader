package io.legado.app.fanqie

/**
 * 原生分卷信息（对应番茄目录接口中的 volumeNameList / chapterListWithVolume）
 */
data class FanqieVolume(
    val index: Int,
    val name: String,
    val chapterCount: Int = 0
)

data class FanqieSearchItem(
    val bookId: String,
    val title: String,
    val author: String,
    val category: String,
    val score: String,
    val wordCount: Int,
    val serialStatus: String,
    val intro: String,
    val coverUrl: String = ""
) {
    fun formattedWordCount(): String = when {
        wordCount >= 100_000_000 -> String.format("%.2f亿字", wordCount / 100_000_000.0)
        wordCount >= 10_000 -> String.format("%.1f万字", wordCount / 10_000.0)
        wordCount > 0 -> "${wordCount}字"
        else -> ""
    }

    fun metaLine(): String {
        val parts = ArrayList<String>()
        if (author.isNotBlank()) parts.add(author)
        if (category.isNotBlank()) parts.add(category)
        val wc = formattedWordCount()
        if (wc.isNotBlank()) parts.add(wc)
        if (serialStatus.isNotBlank()) parts.add(serialStatus)
        if (score.isNotBlank() && score != "0" && score != "暂无") parts.add("${score}分")
        return parts.joinToString(" · ")
    }
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
    val chapterTitles: List<String> = emptyList(),
    val latestChapterTitle: String = "",
    val volumes: List<FanqieVolume> = emptyList(),
    val structuredChapters: List<FanqieChapterItem> = emptyList()
) {
    fun formattedWordCount(): String = when {
        wordCount >= 100_000_000 -> String.format("%.2f 亿字", wordCount / 100_000_000.0)
        wordCount >= 10_000 -> String.format("%.1f 万字", wordCount / 10_000.0)
        wordCount > 0 -> "$wordCount 字"
        else -> "未知字数"
    }

    fun effectiveLatestChapter(): String =
        latestChapterTitle.ifBlank {
            structuredChapters.lastOrNull()?.title?.ifBlank { null }
                ?: chapterTitles.lastOrNull().orEmpty()
        }
}

data class FanqieChapterItem(
    val index: Int,
    val itemId: String?,
    val title: String,
    val locked: Boolean = false,
    val volumeIndex: Int = 0,
    val volumeName: String = "",
    val wordCount: Int = 0,
    val firstPassTime: Long = 0L
) {
    fun cacheKey(): String = if (!itemId.isNullOrBlank()) itemId else "idx_$index"
}

/**
 * 合成 TXT 时的精确 UTF-8 字节偏移目录项（用于直通构建 Legado BookChapter 及 .toc.json）
 */
data class FanqieBuiltTocItem(
    val index: Int,
    val title: String,
    val isVolume: Boolean,
    val startByte: Long,
    val endByte: Long,
    val wordCount: Int = 0,
    val volumeName: String = "",
    val itemId: String? = null
)

data class FanqieProgress(
    val bookId: String,
    val status: String, // "start" | "downloading" | "paused" | "done" | "error"
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
    fun hasPendingDownload(): Boolean = newChaptersCount > 0 || isPartialResume
}

open class FanqieException(message: String, cause: Throwable? = null) : Exception(message, cause)
