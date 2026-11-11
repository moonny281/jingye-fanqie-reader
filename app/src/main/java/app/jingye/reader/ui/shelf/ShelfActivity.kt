package app.jingye.reader.ui.shelf

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.data.Book
import app.jingye.reader.ui.fanqie.FanqieActivity
import app.jingye.reader.ui.reader.ReaderActivity
import app.jingye.reader.ui.settings.SettingsActivity

class ShelfActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyContainer: View
    private lateinit var tvBookCount: TextView
    private lateinit var heroRecentCard: LinearLayout
    private lateinit var tvHeroCoverLabel: TextView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroMeta: TextView

    private val pickTxtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importTxtFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shelf)

        recyclerView = findViewById(R.id.shelfRecyclerView)
        emptyContainer = findViewById(R.id.emptyContainer)
        tvBookCount = findViewById(R.id.tvBookCount)
        heroRecentCard = findViewById(R.id.heroRecentCard)
        tvHeroCoverLabel = findViewById(R.id.tvHeroCoverLabel)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroMeta = findViewById(R.id.tvHeroMeta)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.itemAnimator = null

        findViewById<Button>(R.id.btnFanqie).setOnClickListener {
            startActivity(Intent(this, FanqieActivity::class.java))
            overridePendingTransition(0, 0)
        }

        findViewById<Button>(R.id.btnImportTxt).setOnClickListener {
            try {
                pickTxtLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnLoadDemo).setOnClickListener {
            createSampleBook()
        }

        findViewById<TextView>(R.id.navFanqie).setOnClickListener {
            startActivity(Intent(this, FanqieActivity::class.java))
            overridePendingTransition(0, 0)
        }

        findViewById<TextView>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShelf()
    }

    private fun refreshShelf() {
        val app = application as JingyeApp
        val books = app.library.all()
        tvBookCount.text = "${books.size} 本藏书"

        if (books.isEmpty()) {
            heroRecentCard.visibility = View.GONE
            emptyContainer.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            val recent = books.first()
            heroRecentCard.visibility = View.VISIBLE
            tvHeroCoverLabel.text = recent.title.take(4)
            tvHeroTitle.text = recent.title
            val totalCh = recent.totalChapters.coerceAtLeast(1)
            val curCh = (recent.chapter + 1).coerceAtMost(totalCh)
            val pct = ((curCh * 100) / totalCh).coerceIn(1, 100)
            val authorStr = recent.author.ifBlank { "本地藏书" }
            tvHeroMeta.text = if (recent.totalChapters > 0) {
                "$authorStr · 已读至第 ${curCh}/${totalCh} 章 ($pct%)"
            } else {
                "$authorStr · 点击继续阅读"
            }
            heroRecentCard.setOnClickListener {
                openBook(recent)
            }

            recyclerView.adapter = BookAdapter(
                books = books,
                onClick = { book -> openBook(book) },
                onLongClick = { book -> showBookActions(book) }
            )
        }
    }

    private fun openBook(book: Book) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    private fun showBookActions(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(arrayOf("立即阅读", "从书架删除")) { _, which ->
                when (which) {
                    0 -> openBook(book)
                    1 -> {
                        val app = application as JingyeApp
                        app.library.remove(book.id)
                        app.localSource.invalidate(book.id)
                        refreshShelf()
                    }
                }
            }
            .show()
    }

    private fun createSampleBook() {
        val app = application as JingyeApp
        val sampleContent = buildString {
            appendLine("【第一章 墨水屏微信读书风格精排导览】")
            appendLine("　　欢迎使用「净页」海信 A7 墨水屏阅读器 v0.3.0。")
            appendLine("　　本版本重点升级了阅读界面与番茄搜书功能：")
            appendLine("　　一、支持按书名或作者名直接搜索番茄小说，点选搜索结果即可一键全本下载并加入书架。")
            appendLine("　　二、内置多种墨水屏专属字体切换（思源宋体、霞鹜文楷、思源黑体、墨水屏凝黑、古籍刻本、护眼柔黑），并支持导入本地 TTF/OTF 字体文件。")
            appendLine("　　三、内置五套精调排版预设（微信精排、紧凑速读、舒朗大字、古籍雅致、高对比黑），在阅读页点击屏幕中央呼出菜单即可一键切换。")
            appendLine()
            appendLine("【第二章 墨水屏刷新与防残影说明】")
            appendLine("　　在阅读界面中，点击屏幕右侧或按音量下键翻下一页，点击屏幕左侧或按音量上键翻上一页，点击屏幕中央呼出精排控制面板。")
            appendLine("　　每翻页一定步数后会自动触发全刷清除墨水屏残影，您也可以随时点击顶部菜单的「全刷清残影」按钮立即刷新。")
        }
        val book = app.library.importFanqieTxt(
            fanqieBookId = "demo_v030",
            title = "净页墨水屏精排指南",
            author = "净页开发组",
            totalChapters = 2,
            txtContent = sampleContent.toByteArray(Charsets.UTF_8)
        )
        app.localSource.invalidate(book.id)
        refreshShelf()
        openBook(book)
    }

    private fun importTxtFromUri(uri: Uri) {
        try {
            val app = application as JingyeApp
            val book = app.library.importTxt(uri)
            app.localSource.invalidate(book.id)
            refreshShelf()
            openBook(book)
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private inner class BookAdapter(
        private val books: List<Book>,
        private val onClick: (Book) -> Unit,
        private val onLongClick: (Book) -> Unit
    ) : RecyclerView.Adapter<BookAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val coverThumb: TextView = view.findViewById(R.id.tvCoverThumb)
            val title: TextView = view.findViewById(R.id.bookTitle)
            val author: TextView = view.findViewById(R.id.bookAuthor)
            val progress: TextView = view.findViewById(R.id.bookProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = books[position]
            val totalCh = item.totalChapters.coerceAtLeast(1)
            val curCh = (item.chapter + 1).coerceAtMost(totalCh)
            val pct = ((curCh * 100) / totalCh).coerceIn(1, 100)
            val authorStr = item.author.ifBlank { "本地TXT" }

            holder.coverThumb.text = item.title.take(4)
            holder.title.text = item.title
            holder.author.text = if (item.totalChapters > 0) {
                "$authorStr · 共 ${item.totalChapters} 章"
            } else {
                authorStr
            }
            holder.progress.text = if (item.lastReadAt > 0L && item.totalChapters > 0) {
                "上次读至：第 $curCh 章 ($pct%)"
            } else {
                "点击开始阅读"
            }

            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = books.size
    }
}
