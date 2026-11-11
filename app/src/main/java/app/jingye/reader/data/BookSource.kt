package app.jingye.reader.data

interface BookSource {
    suspend fun chapterCount(bookId: String): Int
    suspend fun chapterTitle(bookId: String, index: Int): String
    suspend fun chapterText(bookId: String, index: Int): CharSequence
}
