package app.jingye.reader.backend

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
        wordCount >= 10000 -> String.format("%.1f万字", wordCount / 10000.0)
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
    val latestChapterTitle: String = ""
) {
    fun formattedWordCount(): String = when {
        wordCount >= 10000 -> String.format("%.1f 万字", wordCount / 10000.0)
        wordCount > 0 -> "$wordCount 字"
        else -> "未知字数"
    }

    fun effectiveLatestChapter(): String =
        latestChapterTitle.ifBlank { chapterTitles.lastOrNull().orEmpty() }
}

data class FanqieChapterItem(
    val index: Int,
    val itemId: String?,
    val title: String,
    val locked: Boolean
) {
    fun cacheKey(): String = if (!itemId.isNullOrBlank()) itemId else "idx_$index"
}

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

class FanqieException(message: String, cause: Throwable? = null) : Exception(message, cause)
