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
        wordCount >= 100_000_000 -> String.format("%.2f亿字", wordCount / 100_000_000.0)
        wordCount >= 10_000 -> String.format("%.1f万字", wordCount / 10_000.0)
        wordCount > 0 -> "${wordCount}字"
        else -> ""
    }

    fun metaLine(): String {
        val parts = ArrayList<String>()
        if (author.isNotBlank()) parts.add(author)
        if (category.isNotBlank()) parts.add(category)
        if (serialStatus.isNotBlank()) parts.add(serialStatus)
        val wc = formattedWordCount()
        if (wc.isNotBlank()) parts.add(wc)
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
    val chapterTitles: List<String> = emptyList()
) {
    fun formattedWordCount(): String = when {
        wordCount >= 100_000_000 -> String.format("%.2f 亿字", wordCount / 100_000_000.0)
        wordCount >= 10_000 -> String.format("%.1f 万字", wordCount / 10_000.0)
        else -> "$wordCount 字"
    }
}

data class FanqieChapterItem(
    val index: Int,
    val itemId: String?,
    val title: String,
    val locked: Boolean
)

data class FanqieProgress(
    val bookId: String,
    val status: String, // "start" | "downloading" | "done" | "error"
    val current: Int,
    val total: Int,
    val title: String,
    val chapterTitle: String,
    val percent: Int,
    val filename: String?,
    val failed: Int,
    val message: String
)

class FanqieException(message: String, cause: Throwable? = null) : Exception(message, cause)
