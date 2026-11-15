package io.legado.app.ui.fanqie

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.fanqie.DownloadTaskState
import io.legado.app.fanqie.FanqieDownloadManager
import io.legado.app.fanqie.FanqieLegadoBridge
import io.legado.app.fanqie.FanqieMetadata
import io.legado.app.fanqie.FanqieParser
import io.legado.app.fanqie.FanqiePrefs
import io.legado.app.fanqie.FanqieSearchItem
import io.legado.app.ui.book.read.ReadBookActivity
import kotlinx.coroutines.launch

class FanqieActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var configContainer: LinearLayout
    private lateinit var etBookInput: EditText
    private lateinit var tvStatus: TextView

    private lateinit var bookInfoCard: LinearLayout
    private lateinit var ivDetailCover: ImageView
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
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContentView(R.layout.activity_fanqie)

        val rootView = findViewById<View>(R.id.fanqieRoot)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
            }
        }

        etServerUrl = findViewById(R.id.etServerUrl)
        configContainer = findViewById(R.id.configContainer)
        etBookInput = findViewById(R.id.etBookInput)
        tvStatus = findViewById(R.id.tvStatus)

        bookInfoCard = findViewById(R.id.bookInfoCard)
        ivDetailCover = findViewById(R.id.ivDetailCover)
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

        etServerUrl.setText(if (FanqiePrefs.useRemoteBackend) FanqiePrefs.backendUrl else "")

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
                FanqiePrefs.useRemoteBackend = false
                Toast.makeText(this, "已切换为手机本地内置引擎", Toast.LENGTH_SHORT).show()
            } else {
                FanqiePrefs.backendUrl = url
                FanqiePrefs.useRemoteBackend = true
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
            tvStatus.text = "支持输入书名/作者关键字搜书，或点击分类胶囊探索热门小说"
        }

        findViewById<Button>(R.id.btnSearchBook).setOnClickListener {
            val raw = etBookInput.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(this, "请输入书名、作者或粘贴番茄链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (FanqieParser.isUrlOrBookId(raw)) {
                parseBook(raw)
            } else {
                searchByKeyword(raw)
            }
        }

        // 绑定热门分类胶囊一键搜书
        val chipIds = listOf(
            R.id.chipCat1, R.id.chipCat2, R.id.chipCat3,
            R.id.chipCat4, R.id.chipCat5, R.id.chipCat6
        )
        for (cid in chipIds) {
            findViewById<TextView>(cid)?.setOnClickListener { v ->
                val kw = (v as TextView).text.toString()
                etBookInput.setText(kw)
                searchByKeyword(kw)
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
            Toast.makeText(this, "已加入书架并转入后台全本离线（状态栏可看实时进度）", Toast.LENGTH_SHORT).show()
        }

        btnPauseDownload.setOnClickListener {
            val meta = currentMetadata
            FanqieDownloadManager.requestPause(this, meta?.bookId)
        }

        btnOpenDownloadedBook.setOnClickListener {
            val meta = currentMetadata ?: return@setOnClickListener
            quickReadBook(
                bookId = meta.bookId,
                title = meta.title,
                author = meta.author,
                coverUrl = meta.coverUrl,
                intro = meta.intro
            )
        }

        // 支持从书架顶栏直接带入关键字或 bookId
        val initBookId = intent.getStringExtra("fanqie_book_id")?.trim().orEmpty()
        val initKeyword = intent.getStringExtra("search_keyword")?.trim().orEmpty()
        when {
            initBookId.isNotEmpty() -> {
                etBookInput.setText(initBookId)
                parseBook(initBookId)
            }
            initKeyword.isNotEmpty() -> {
                etBookInput.setText(initKeyword)
                if (FanqieParser.isUrlOrBookId(initKeyword)) parseBook(initKeyword) else searchByKeyword(initKeyword)
            }
            else -> {
                // 默认加载热门榜单推荐，避免进入页面一片空白
                searchByKeyword("十日终焉")
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

    /**
     * 秒开流式阅读：快速拉取前 8 章加入书架并直接打开阅读器，同时启动后台全本续下。
     */
    private fun quickReadBook(
        bookId: String,
        title: String,
        author: String,
        coverUrl: String,
        intro: String
    ) {
        if (isQueryBusy) return
        isQueryBusy = true
        tvStatus.text = "正在准备《$title》首章内容并打开墨水屏阅读器…"
        btnOpenDownloadedBook.isEnabled = false
        btnOpenDownloadedBook.text = "正在打开阅读器…"

        lifecycleScope.launch {
            try {
                val book = FanqieLegadoBridge.quickStartRead(
                    fanqieBookId = bookId,
                    titleHint = title,
                    authorHint = author,
                    coverHint = coverUrl,
                    introHint = intro
                )
                isQueryBusy = false
                btnOpenDownloadedBook.isEnabled = true
                btnOpenDownloadedBook.text = "立即阅读（秒开）"

                // 若尚未下完，同时在后台静默启动全本断点续下
                val tag = FanqieLegadoBridge.parseTag(book)
                if (tag != null && tag.pendingCount > 0) {
                    FanqieDownloadManager.startBackgroundDownload(
                        ctx = this@FanqieActivity,
                        fanqieBookId = bookId,
                        title = book.name,
                        author = book.author
                    )
                }

                val intent = Intent(this@FanqieActivity, ReadBookActivity::class.java).apply {
                    putExtra("bookUrl", book.bookUrl)
                }
                startActivity(intent)
                overridePendingTransition(0, 0)
            } catch (e: Exception) {
                isQueryBusy = false
                btnOpenDownloadedBook.isEnabled = true
                btnOpenDownloadedBook.text = "立即阅读（秒开）"
                tvStatus.text = "快速打开失败：${e.message}"
                Toast.makeText(this@FanqieActivity, "打开失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onDownloadStateChanged(state: DownloadTaskState?) {
        if (state == null) return
        tvStatus.text = state.message

        val curMeta = currentMetadata
        if (curMeta != null && curMeta.bookId == state.fanqieBookId) {
            downloadProgressBar.visibility = View.VISIBLE
            downloadProgressBar.progress = state.percent.coerceIn(0, 100)

            if (state.isRunning) {
                btnDownloadBook.isEnabled = false
                btnDownloadBook.text = "后台离线中 (${state.percent}%)"
                btnPauseDownload.visibility = View.VISIBLE
                tvBookCheckpointInfo.text = "已缓存 ${state.current}/${state.total} 章 · 当前：${state.chapterTitle}"
            } else {
                btnDownloadBook.isEnabled = true
                btnPauseDownload.visibility = View.GONE
                val cachedCount = FanqieLegadoBridge.getCachedChapterCount(curMeta.bookId)
                val total = maxOf(state.total, curMeta.chapterCount, cachedCount)
                if (cachedCount in 1 until total) {
                    btnDownloadBook.text = "断点续下 (${cachedCount}/$total)"
                    tvBookCheckpointInfo.text = "断点已保存：已下载 $cachedCount / $total 章（待续 ${total - cachedCount} 章）"
                } else if (cachedCount >= total && total > 0) {
                    btnDownloadBook.text = "检测新章 / 增量更新"
                    tvBookCheckpointInfo.text = "全本已下载完成并加入书架：共 $cachedCount 章"
                } else {
                    btnDownloadBook.text = "全本后台离线"
                }
            }
        }
    }

    private fun searchByKeyword(keyword: String) {
        if (isQueryBusy) return
        isQueryBusy = true
        tvStatus.text = "正在搜索「$keyword」..."
        bookInfoCard.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val items = FanqieLegadoBridge.searchBooks(keyword)
                isQueryBusy = false
                lastSearchResults = items
                tvStatus.text = "搜索「$keyword」找到 ${items.size} 本小说 · 点击「立即阅读」秒开阅读器，或点击卡片查看详情："
                searchHeaderRow.visibility = View.VISIBLE
                tvSearchResultCount.text = "搜索结果（共 ${items.size} 本 · 支持秒开边看边下）"
                searchRecyclerView.visibility = View.VISIBLE
                searchRecyclerView.adapter = SearchAdapter(
                    items = items,
                    onCardClick = { selected -> onSearchItemSelected(selected) },
                    onQuickReadClick = { selected ->
                        quickReadBook(
                            bookId = selected.bookId,
                            title = selected.title,
                            author = selected.author,
                            coverUrl = selected.coverUrl,
                            intro = selected.intro
                        )
                    }
                )
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

        lifecycleScope.launch {
            try {
                val meta = FanqieLegadoBridge.parseInput(item.bookId)
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

        lifecycleScope.launch {
            try {
                val meta = FanqieLegadoBridge.parseInput(rawInput)
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

        if (meta.coverUrl.isNotBlank()) {
            Glide.with(this)
                .load(meta.coverUrl)
                .placeholder(R.drawable.image_cover_default)
                .error(R.drawable.image_cover_default)
                .into(ivDetailCover)
        } else {
            ivDetailCover.setImageResource(R.drawable.image_cover_default)
        }

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

        val cachedCount = FanqieLegadoBridge.getCachedChapterCount(meta.bookId)
        val shelfBook = FanqieLegadoBridge.findBookByFanqieId(meta.bookId)
        val total = maxOf(meta.chapterCount, cachedCount)

        btnOpenDownloadedBook.visibility = View.VISIBLE
        btnOpenDownloadedBook.text = if (shelfBook != null) "继续阅读（已在书架）" else "立即阅读（秒开边看边下）"

        if (cachedCount > 0 && cachedCount < total) {
            tvBookCheckpointInfo.text = "断点状态：本地已缓存 $cachedCount / $total 章，可从第 ${cachedCount + 1} 章继续续下"
            btnDownloadBook.text = "断点续下 (${cachedCount}/$total)"
        } else if (cachedCount > 0 && cachedCount >= total) {
            tvBookCheckpointInfo.text = "断点状态：本地已完整下载 $cachedCount 章，已在阅读书架中"
            btnDownloadBook.text = "检测新章 / 增量更新"
        } else {
            tvBookCheckpointInfo.text = "支持「立即阅读」秒开首章边看边下，或「全本后台离线」每 30 章自动存断点"
            btnDownloadBook.text = "全本后台离线"
        }

        tvBookIntro.text = meta.intro.ifBlank { "暂无简介" }
        tvStatus.text = "已就绪：《${meta.title}》，点击「立即阅读」即可秒开进入阅读器。"

        FanqieDownloadManager.getState(meta.bookId)?.let { onDownloadStateChanged(it) }
    }

    private inner class SearchAdapter(
        private val items: List<FanqieSearchItem>,
        private val onCardClick: (FanqieSearchItem) -> Unit,
        private val onQuickReadClick: (FanqieSearchItem) -> Unit
    ) : RecyclerView.Adapter<SearchAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: TextView = view.findViewById(R.id.tvSearchThumb)
            val cover: ImageView = view.findViewById(R.id.ivSearchCover)
            val title: TextView = view.findViewById(R.id.tvSearchTitle)
            val meta: TextView = view.findViewById(R.id.tvSearchMeta)
            val intro: TextView = view.findViewById(R.id.tvSearchIntro)
            val btnQuickRead: TextView = view.findViewById(R.id.btnQuickReadItem)
            val btnDetail: TextView = view.findViewById(R.id.btnDetailItem)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_book, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.thumb.text = item.title.take(2)
            if (item.coverUrl.isNotBlank()) {
                Glide.with(holder.cover.context)
                    .load(item.coverUrl)
                    .into(holder.cover)
            } else {
                holder.cover.setImageDrawable(null)
            }
            holder.title.text = item.title
            holder.meta.text = item.metaLine()
            holder.intro.text = item.intro.ifBlank { "点击「立即阅读」秒开阅读器，或点击「全本离线」后台下载" }
            holder.itemView.setOnClickListener { onCardClick(item) }
            holder.btnDetail.setOnClickListener { onCardClick(item) }
            holder.btnQuickRead.setOnClickListener { onQuickReadClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
