package app.jingye.reader.ui.fanqie

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.backend.FanqieMetadata
import app.jingye.reader.backend.FanqieParser
import app.jingye.reader.backend.FanqieSearchItem
import app.jingye.reader.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

class FanqieActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var configContainer: LinearLayout
    private lateinit var etBookInput: EditText
    private lateinit var tvStatus: TextView

    private lateinit var bookInfoCard: LinearLayout
    private lateinit var tvBookTitle: TextView
    private lateinit var tvBookAuthor: TextView
    private lateinit var tvBookIntro: TextView
    private lateinit var btnBackToSearch: Button
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var btnDownloadBook: Button

    private lateinit var searchHeaderRow: View
    private lateinit var tvSearchResultCount: TextView
    private lateinit var searchRecyclerView: RecyclerView

    private var currentMetadata: FanqieMetadata? = null
    private var lastSearchResults: List<FanqieSearchItem> = emptyList()
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fanqie)

        val app = application as JingyeApp

        etServerUrl = findViewById(R.id.etServerUrl)
        configContainer = findViewById(R.id.configContainer)
        etBookInput = findViewById(R.id.etBookInput)
        tvStatus = findViewById(R.id.tvStatus)

        bookInfoCard = findViewById(R.id.bookInfoCard)
        tvBookTitle = findViewById(R.id.tvBookTitle)
        tvBookAuthor = findViewById(R.id.tvBookAuthor)
        tvBookIntro = findViewById(R.id.tvBookIntro)
        btnBackToSearch = findViewById(R.id.btnBackToSearch)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        btnDownloadBook = findViewById(R.id.btnDownloadBook)

        searchHeaderRow = findViewById(R.id.searchHeaderRow)
        tvSearchResultCount = findViewById(R.id.tvSearchResultCount)
        searchRecyclerView = findViewById(R.id.searchRecyclerView)
        searchRecyclerView.layoutManager = LinearLayoutManager(this)
        searchRecyclerView.itemAnimator = null

        etServerUrl.setText(if (app.prefs.useRemoteBackend) app.prefs.backendUrl else "")

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(0, 0)
        }

        findViewById<Button>(R.id.btnToggleConfig).setOnClickListener {
            configContainer.visibility =
                if (configContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btnSaveServer).setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            if (url.isEmpty()) {
                app.prefs.useRemoteBackend = false
                Toast.makeText(this, "已切换为手机本地直连引擎", Toast.LENGTH_SHORT).show()
            } else {
                app.prefs.backendUrl = url
                app.prefs.useRemoteBackend = true
                Toast.makeText(this, "已保存远端服务地址：$url", Toast.LENGTH_SHORT).show()
            }
            configContainer.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnPasteClipboard).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (clipText.isNotBlank()) {
                etBookInput.setText(clipText)
                if (FanqieParser.isUrlOrBookId(clipText)) {
                    parseBook(clipText)
                } else {
                    searchByKeyword(clipText)
                }
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnClearInput).setOnClickListener {
            etBookInput.setText("")
            tvStatus.text = "支持输入书名/作者关键字搜书，或粘贴番茄分享链接/Book ID"
        }

        findViewById<Button>(R.id.btnSearchBook).setOnClickListener {
            val raw = etBookInput.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(this, "请输入书名或作者名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (FanqieParser.isUrlOrBookId(raw)) {
                parseBook(raw)
            } else {
                searchByKeyword(raw)
            }
        }

        findViewById<Button>(R.id.btnParseBook).setOnClickListener {
            val raw = etBookInput.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(this, "请输入番茄链接或 19 位 Book ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (FanqieParser.isUrlOrBookId(raw)) {
                parseBook(raw)
            } else {
                searchByKeyword(raw)
            }
        }

        etBookInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val raw = etBookInput.text.toString().trim()
                if (raw.isNotEmpty()) {
                    if (FanqieParser.isUrlOrBookId(raw)) parseBook(raw) else searchByKeyword(raw)
                }
                true
            } else {
                false
            }
        }

        btnBackToSearch.setOnClickListener {
            bookInfoCard.visibility = View.GONE
            if (lastSearchResults.isNotEmpty()) {
                searchHeaderRow.visibility = View.VISIBLE
                searchRecyclerView.visibility = View.VISIBLE
            }
        }

        btnDownloadBook.setOnClickListener {
            val meta = currentMetadata ?: return@setOnClickListener
            downloadBook(meta)
        }
    }

    private fun searchByKeyword(keyword: String) {
        if (isBusy) return
        isBusy = true
        tvStatus.text = "正在搜索「$keyword」..."
        bookInfoCard.visibility = View.GONE

        val app = application as JingyeApp
        lifecycleScope.launch {
            try {
                val items = app.fanqie.searchBooks(keyword)
                isBusy = false
                lastSearchResults = items
                tvStatus.text = "搜索「$keyword」共找到 ${items.size} 本相关小说，点击任意书籍即可查看详情并全本下载："
                searchHeaderRow.visibility = View.VISIBLE
                tvSearchResultCount.text = "搜索结果（共 ${items.size} 本 · 点击选书）"
                searchRecyclerView.visibility = View.VISIBLE
                searchRecyclerView.adapter = SearchAdapter(items) { selected ->
                    onSearchItemSelected(selected)
                }
            } catch (e: Exception) {
                isBusy = false
                tvStatus.text = "搜索失败：${e.message}"
            }
        }
    }

    private fun onSearchItemSelected(item: FanqieSearchItem) {
        if (isBusy) return
        isBusy = true
        tvStatus.text = "正在获取《${item.title}》详情与目录..."

        val app = application as JingyeApp
        lifecycleScope.launch {
            try {
                val meta = app.fanqie.parseInput(item.bookId)
                isBusy = false
                showBookDetailCard(meta, fromSearch = true)
            } catch (_: Exception) {
                isBusy = false
                val fallbackMeta = FanqieMetadata(
                    bookId = item.bookId,
                    title = item.title,
                    author = item.author,
                    score = item.score,
                    coverUrl = item.coverUrl,
                    intro = item.intro,
                    wordCount = item.wordCount,
                    chapterCount = 0,
                    serialStatus = item.serialStatus,
                    availableChapters = 0,
                    pcAvailable = true,
                    chapterTitles = emptyList()
                )
                showBookDetailCard(fallbackMeta, fromSearch = true)
            }
        }
    }

    private fun parseBook(rawInput: String) {
        if (isBusy) return
        isBusy = true
        tvStatus.text = "正在解析书籍详情与章节目录..."
        bookInfoCard.visibility = View.GONE

        val app = application as JingyeApp
        lifecycleScope.launch {
            try {
                val meta = app.fanqie.parseInput(rawInput)
                isBusy = false
                showBookDetailCard(meta, fromSearch = lastSearchResults.isNotEmpty())
            } catch (e: Exception) {
                isBusy = false
                tvStatus.text = "解析失败：${e.message}"
            }
        }
    }

    private fun showBookDetailCard(meta: FanqieMetadata, fromSearch: Boolean) {
        currentMetadata = meta
        bookInfoCard.visibility = View.VISIBLE
        btnBackToSearch.visibility = if (fromSearch) View.VISIBLE else View.GONE

        tvBookTitle.text = meta.title
        val chCountText = if (meta.chapterCount > 0) "共 ${meta.chapterCount} 章" else "支持全本拉取"
        val wcText = meta.formattedWordCount()
        val extra = listOf(meta.author, chCountText, wcText, "ID:${meta.bookId}").filter { it.isNotBlank() }
        tvBookAuthor.text = extra.joinToString(" · ")
        tvBookIntro.text = meta.intro.ifBlank { "暂无简介" }
        tvStatus.text = "已就绪：《${meta.title}》，点击下方「全本下载并立即阅读」即可加入书架。"
    }

    private fun downloadBook(meta: FanqieMetadata) {
        if (isBusy) return
        isBusy = true
        btnDownloadBook.isEnabled = false
        downloadProgressBar.visibility = View.VISIBLE
        downloadProgressBar.progress = 0

        val app = application as JingyeApp
        lifecycleScope.launch {
            try {
                val book = app.fanqie.downloadAndImport(meta) { prog ->
                    runOnUiThread {
                        downloadProgressBar.progress = prog.percent.coerceIn(0, 100)
                        tvStatus.text = prog.message.ifBlank {
                            "正在下载 (${prog.current}/${prog.total})：${prog.chapterTitle}"
                        }
                    }
                }
                isBusy = false
                btnDownloadBook.isEnabled = true
                tvStatus.text = "下载完成：《${book.title}》已加入书架！"
                Toast.makeText(this@FanqieActivity, "《${book.title}》已加入书架，正在打开阅读", Toast.LENGTH_SHORT).show()
                val intent = Intent(this@FanqieActivity, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                }
                startActivity(intent)
                overridePendingTransition(0, 0)
                finish()
            } catch (e: Exception) {
                isBusy = false
                btnDownloadBook.isEnabled = true
                tvStatus.text = "下载失败：${e.message}"
                Toast.makeText(this@FanqieActivity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class SearchAdapter(
        private val items: List<FanqieSearchItem>,
        private val onClick: (FanqieSearchItem) -> Unit
    ) : RecyclerView.Adapter<SearchAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: TextView = view.findViewById(R.id.tvSearchThumb)
            val title: TextView = view.findViewById(R.id.tvSearchTitle)
            val meta: TextView = view.findViewById(R.id.tvSearchMeta)
            val intro: TextView = view.findViewById(R.id.tvSearchIntro)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_book, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.thumb.text = item.title.take(4)
            holder.title.text = item.title
            holder.meta.text = item.metaLine()
            holder.intro.text = item.intro.ifBlank { "点击查看详情并下载全本" }
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
