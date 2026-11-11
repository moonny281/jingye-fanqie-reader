package app.jingye.reader.ui.fanqie

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.backend.DownloadTaskState
import app.jingye.reader.backend.FanqieDownloadManager
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
    private lateinit var tvBookLatestChapter: TextView
    private lateinit var tvBookCheckpointInfo: TextView
    private lateinit var tvBookIntro: TextView
    private lateinit var btnBackToSearch: Button
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var btnDownloadBook: Button
    private lateinit var btnPauseDownload: Button
    private lateinit var btnOpenDownloadedBook: Button

    private lateinit var searchHeaderRow: View
    private lateinit var tvSearchResultCount: TextView
    private lateinit var searchRecyclerView: RecyclerView

    private var currentMetadata: FanqieMetadata? = null
    private var lastSearchResults: List<FanqieSearchItem> = emptyList()
    private var isQueryBusy = false

    private val downloadListener: (DownloadTaskState?) -> Unit = { state ->
        onDownloadStateChanged(state)
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fanqie)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
            }
        }

        val app = application as JingyeApp

        etServerUrl = findViewById(R.id.etServerUrl)
        configContainer = findViewById(R.id.configContainer)
        etBookInput = findViewById(R.id.etBookInput)
        tvStatus = findViewById(R.id.tvStatus)

        bookInfoCard = findViewById(R.id.bookInfoCard)
        tvBookTitle = findViewById(R.id.tvBookTitle)
        tvBookAuthor = findViewById(R.id.tvBookAuthor)
        tvBookLatestChapter = findViewById(R.id.tvBookLatestChapter)
        tvBookCheckpointInfo = findViewById(R.id.tvBookCheckpointInfo)
        tvBookIntro = findViewById(R.id.tvBookIntro)
        btnBackToSearch = findViewById(R.id.btnBackToSearch)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        btnDownloadBook = findViewById(R.id.btnDownloadBook)
        btnPauseDownload = findViewById(R.id.btnPauseDownload)
        btnOpenDownloadedBook = findViewById(R.id.btnOpenDownloadedBook)

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
                Toast.makeText(this, "已切换为手机本地内置引擎", Toast.LENGTH_SHORT).show()
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
            FanqieDownloadManager.startBackgroundDownload(
                ctx = this,
                fanqieBookId = meta.bookId,
                title = meta.title,
                author = meta.author
            )
            Toast.makeText(this, "已转入后台下载，状态栏实时显示进度，可随时返回书架", Toast.LENGTH_SHORT).show()
        }

        btnPauseDownload.setOnClickListener {
            val meta = currentMetadata
            FanqieDownloadManager.requestPause(this, meta?.bookId)
        }

        btnOpenDownloadedBook.setOnClickListener {
            val meta = currentMetadata ?: return@setOnClickListener
            val existing = app.library.findByFanqieId(meta.bookId)
            if (existing != null) {
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, existing.id)
                }
                startActivity(intent)
                overridePendingTransition(0, 0)
            } else {
                Toast.makeText(this, "正在写入首个章节批次，请稍候…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        FanqieDownloadManager.addListener(downloadListener)
    }

    override fun onStop() {
        super.onStop()
        FanqieDownloadManager.removeListener(downloadListener)
    }

    private fun onDownloadStateChanged(state: DownloadTaskState?) {
        if (state == null) return
        tvStatus.text = state.message

        val curMeta = currentMetadata
        if (curMeta != null && curMeta.bookId == state.fanqieBookId) {
            downloadProgressBar.visibility = View.VISIBLE
            downloadProgressBar.progress = state.percent.coerceIn(0, 100)

            val app = application as JingyeApp
            val shelfBook = app.library.findByFanqieId(curMeta.bookId)
            btnOpenDownloadedBook.visibility = if (shelfBook != null) View.VISIBLE else View.GONE

            if (state.isRunning) {
                btnDownloadBook.isEnabled = false
                btnDownloadBook.text = "后台下载中 (${state.percent}%)"
                btnPauseDownload.visibility = View.VISIBLE
                tvBookCheckpointInfo.text = "断点实时存档中：已下载 ${state.current}/${state.total} 章 · 当前：${state.chapterTitle}"
            } else {
                btnDownloadBook.isEnabled = true
                btnPauseDownload.visibility = View.GONE
                val cachedCount = app.fanqie.getCachedChapterCount(curMeta.bookId)
                val total = maxOf(state.total, curMeta.chapterCount, cachedCount)
                if (cachedCount in 1 until total) {
                    btnDownloadBook.text = "从第 ${cachedCount + 1} 章继续断点续下"
                    tvBookCheckpointInfo.text = "断点已保存：已下载 $cachedCount / $total 章（剩余 ${total - cachedCount} 章待续下）"
                } else if (cachedCount >= total && total > 0) {
                    btnDownloadBook.text = "检测新章 / 增量更新"
                    tvBookCheckpointInfo.text = "全本已下载完成：共 $cachedCount 章"
                } else {
                    btnDownloadBook.text = "后台全本下载（状态栏提示）"
                }
            }
        }
    }

    private fun searchByKeyword(keyword: String) {
        if (isQueryBusy) return
        isQueryBusy = true
        tvStatus.text = "正在搜索「$keyword」..."
        bookInfoCard.visibility = View.GONE

        val app = application as JingyeApp
        lifecycleScope.launch {
            try {
                val items = app.fanqie.searchBooks(keyword)
                isQueryBusy = false
                lastSearchResults = items
                tvStatus.text = "搜索「$keyword」共找到 ${items.size} 本相关小说，点击任意书籍即可查看最新章节并后台下载："
                searchHeaderRow.visibility = View.VISIBLE
                tvSearchResultCount.text = "搜索结果（共 ${items.size} 本 · 点击选书）"
                searchRecyclerView.visibility = View.VISIBLE
                searchRecyclerView.adapter = SearchAdapter(items) { selected ->
                    onSearchItemSelected(selected)
                }
            } catch (e: Exception) {
                isQueryBusy = false
                tvStatus.text = "搜索失败：${e.message}"
            }
        }
    }

    private fun onSearchItemSelected(item: FanqieSearchItem) {
        if (isQueryBusy) return
        isQueryBusy = true
        tvStatus.text = "正在获取《${item.title}》最新章节与本地断点状态..."

        val app = application as JingyeApp
        lifecycleScope.launch {
            try {
                val meta = app.fanqie.parseInput(item.bookId)
                isQueryBusy = false
                showBookDetailCard(meta, fromSearch = true)
            } catch (_: Exception) {
                isQueryBusy = false
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
                    chapterTitles = emptyList(),
                    latestChapterTitle = ""
                )
                showBookDetailCard(fallbackMeta, fromSearch = true)
            }
        }
    }

    private fun parseBook(rawInput: String) {
        if (isQueryBusy) return
        isQueryBusy = true
        tvStatus.text = "正在解析书籍详情与最新章节目录..."
        bookInfoCard.visibility = View.GONE

        val app = application as JingyeApp
        lifecycleScope.launch {
            try {
                val meta = app.fanqie.parseInput(rawInput)
                isQueryBusy = false
                showBookDetailCard(meta, fromSearch = lastSearchResults.isNotEmpty())
            } catch (e: Exception) {
                isQueryBusy = false
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
        val extra = listOf(meta.author, meta.serialStatus, chCountText, wcText).filter { it.isNotBlank() }
        tvBookAuthor.text = extra.joinToString(" · ")

        val latest = meta.effectiveLatestChapter()
        tvBookLatestChapter.text = if (latest.isNotBlank()) {
            "最新章节：$latest"
        } else {
            "最新章节：共 ${meta.chapterCount} 章（实时同步番茄官方源）"
        }

        val app = application as JingyeApp
        val cachedCount = app.fanqie.getCachedChapterCount(meta.bookId)
        val shelfBook = app.library.findByFanqieId(meta.bookId)
        val total = maxOf(meta.chapterCount, cachedCount)

        btnOpenDownloadedBook.visibility = if (shelfBook != null) View.VISIBLE else View.GONE

        if (cachedCount > 0 && cachedCount < total) {
            tvBookCheckpointInfo.text = "断点状态：本地已缓存 $cachedCount / $total 章，支持从第 ${cachedCount + 1} 章接着续下"
            btnDownloadBook.text = "从第 ${cachedCount + 1} 章继续后台续下"
        } else if (cachedCount > 0 && cachedCount >= total) {
            tvBookCheckpointInfo.text = "断点状态：本地已完整下载 $cachedCount 章，已在书架中"
            btnDownloadBook.text = "重新校验 / 增量更新"
        } else {
            tvBookCheckpointInfo.text = "断点状态：尚未下载（支持后台下载，每 30 章自动保存断点）"
            btnDownloadBook.text = "后台全本下载（状态栏提示）"
        }

        tvBookIntro.text = meta.intro.ifBlank { "暂无简介" }
        tvStatus.text = "已就绪：《${meta.title}》，点击下方按钮即可启动后台下载。"

        // Sync if already running in background
        FanqieDownloadManager.getState(meta.bookId)?.let { onDownloadStateChanged(it) }
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
            holder.intro.text = item.intro.ifBlank { "点击查看最新章节并后台下载全本" }
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
