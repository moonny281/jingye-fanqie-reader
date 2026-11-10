package app.jingye.reader.ui.shelf

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.data.Book
import app.jingye.reader.ui.fanqie.FanqieActivity
import app.jingye.reader.ui.reader.ReaderActivity
import app.jingye.reader.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShelfActivity : AppCompatActivity() {

    private lateinit var app: JingyeApp
    private lateinit var shelfCountBadge: TextView
    private lateinit var recentCard: View
    private lateinit var recentCoverText: TextView
    private lateinit var recentBookTitle: TextView
    private lateinit var recentBookMeta: TextView
    private lateinit var shelfSectionHeader: View
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var errorLine: TextView
    private lateinit var deleteOverlay: View
    private lateinit var deleteTitle: TextView
    private lateinit var adapter: BookAdapter

    private var pendingDeleteBook: Book? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        errorLine.visibility = View.VISIBLE
        errorLine.text = "正在导入本地 TXT…"
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { app.library.importTxt(uri) }
            }.onSuccess {
                errorLine.visibility = View.GONE
                reload()
            }.onFailure {
                errorLine.visibility = View.VISIBLE
                errorLine.text = "导入失败：${it.message}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shelf)

        app = application as JingyeApp
        shelfCountBadge = findViewById(R.id.shelfCountBadge)
        recentCard = findViewById(R.id.recentCard)
        recentCoverText = findViewById(R.id.recentCoverText)
        recentBookTitle = findViewById(R.id.recentBookTitle)
        recentBookMeta = findViewById(R.id.recentBookMeta)
        shelfSectionHeader = findViewById(R.id.shelfSectionHeader)
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        errorLine = findViewById(R.id.errorLine)
        deleteOverlay = findViewById(R.id.deleteDialogOverlay)
        deleteTitle = findViewById(R.id.deleteDialogTitle)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.itemAnimator = null
        recyclerView.overScrollMode = View.OVER_SCROLL_NEVER

        adapter = BookAdapter(
            onClick = { book -> openBook(book) },
            onLongClick = { book ->
                pendingDeleteBook = book
                deleteTitle.text = "将《${book.title}》移出书架？"
                deleteOverlay.visibility = View.VISIBLE
            }
        )
        recyclerView.adapter = adapter

        val openFanqie = View.OnClickListener {
            errorLine.visibility = View.GONE
            startActivity(Intent(this, FanqieActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        val launchImport = View.OnClickListener {
            errorLine.visibility = View.GONE
            picker.launch(arrayOf("text/plain", "text/*"))
        }

        findViewById<View>(R.id.btnFanqie).setOnClickListener(openFanqie)
        findViewById<View>(R.id.navFanqie).setOnClickListener(openFanqie)
        findViewById<View>(R.id.btnEmptyFanqie).setOnClickListener(openFanqie)

        findViewById<View>(R.id.btnImport).setOnClickListener(launchImport)
        findViewById<View>(R.id.btnEmptyImport).setOnClickListener(launchImport)

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            errorLine.visibility = View.GONE
            startActivity(Intent(this, SettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        findViewById<View>(R.id.btnCancelDelete).setOnClickListener {
            pendingDeleteBook = null
            deleteOverlay.visibility = View.GONE
        }

        findViewById<View>(R.id.btnConfirmDelete).setOnClickListener {
            pendingDeleteBook?.let { b ->
                app.library.remove(b.id)
                app.localSource.invalidate(b.id)
            }
            pendingDeleteBook = null
            deleteOverlay.visibility = View.GONE
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun openBook(book: Book) {
        errorLine.visibility = View.GONE
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", book.id))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun reload() {
        val books = app.library.all()
        shelfCountBadge.text = "${books.size} 本"
        adapter.submit(books)

        if (books.isEmpty()) {
            recentCard.visibility = View.GONE
            shelfSectionHeader.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            shelfSectionHeader.visibility = View.VISIBLE
            recyclerView.visibility = View.VISIBLE

            val topBook = books.first()
            recentCard.visibility = View.VISIBLE
            recentCoverText.text = formatCoverLabel(topBook.title)
            recentBookTitle.text = topBook.title
            recentBookMeta.text = formatProgressText(topBook)
            recentCard.setOnClickListener { openBook(topBook) }
            findViewById<View>(R.id.btnContinueRead).setOnClickListener { openBook(topBook) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (deleteOverlay.visibility == View.VISIBLE) {
            pendingDeleteBook = null
            deleteOverlay.visibility = View.GONE
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        fun formatCoverLabel(title: String): String {
            val clean = title.replace(Regex("[《》【】\\s]"), "").trim()
            return if (clean.length <= 6) clean else clean.take(6)
        }

        fun formatProgressText(b: Book): String {
            val authorPart = if (b.author.isNotBlank()) "${b.author} · " else ""
            val progPart = when {
                b.chapter == 0 && b.charOffset == 0 -> "尚未开始阅读"
                b.totalChapters > 0 -> {
                    val pct = (((b.chapter + 1) * 100.0) / b.totalChapters).toInt().coerceIn(1, 100)
                    "读至第 ${b.chapter + 1}/${b.totalChapters} 章 ($pct%)"
                }
                else -> "读至第 ${b.chapter + 1} 章"
            }
            return "$authorPart$progPart"
        }

        fun formatBadgeText(b: Book): String = when {
            b.chapter == 0 && b.charOffset == 0 -> "新书"
            b.totalChapters > 0 -> {
                val pct = (((b.chapter + 1) * 100.0) / b.totalChapters).toInt().coerceIn(1, 100)
                "已读 $pct%"
            }
            else -> "第${b.chapter + 1}章"
        }
    }

    private class BookAdapter(
        private val onClick: (Book) -> Unit,
        private val onLongClick: (Book) -> Unit
    ) : RecyclerView.Adapter<BookAdapter.VH>() {

        private var items: List<Book> = emptyList()

        fun submit(list: List<Book>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val b = items[position]
            holder.cover.text = formatCoverLabel(b.title)
            holder.title.text = b.title
            holder.sub.text = formatProgressText(b)
            holder.badge.text = formatBadgeText(b)
            holder.row.setOnClickListener { onClick(b) }
            holder.row.setOnLongClickListener {
                onLongClick(b)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val row: View = v.findViewById(R.id.rowRoot)
            val cover: TextView = v.findViewById(R.id.bookCoverThumb)
            val title: TextView = v.findViewById(R.id.bookTitle)
            val sub: TextView = v.findViewById(R.id.bookSub)
            val badge: TextView = v.findViewById(R.id.bookBadge)
        }
    }
}
