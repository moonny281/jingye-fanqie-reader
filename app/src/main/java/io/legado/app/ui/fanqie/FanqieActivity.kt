package io.legado.app.ui.fanqie

import android.Manifest
import android.app.AlertDialog
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
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.fanqie.DownloadTaskState
import io.legado.app.fanqie.FanqieBookExporter
import io.legado.app.fanqie.FanqieDownloadManager
import io.legado.app.fanqie.FanqieLegadoBridge
import io.legado.app.fanqie.FanqieMetadata
import io.legado.app.fanqie.FanqieParser
import io.legado.app.fanqie.FanqiePrefs
import io.legado.app.fanqie.FanqieSearchItem
import io.legado.app.fanqie.FanqieUiHelper
import io.legado.app.ui.book.read.ReadBookActivity
import kotlinx.coroutines.launch

class FanqieActivity : AppCompatActivity() {

    private lateinit var rootView: View
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
    private lateinit var btnPreviewToc: Button
    private lateinit var btnExportBook: Button
    private lateinit var btnRedownloadBook: Button
    private lateinit var btnPauseDownload: Button
    private lateinit var btnOpenDownloadedBook: Button

    private lateinit var searchHeaderRow: View
    private lateinit var tvSearchResultCount: TextView
    private lateinit var searchRecyclerView: RecyclerView

    private var currentMetadata: FanqieMetadata? = null
    private var lastSearchResults: List<FanqieSearchItem> = emptyList()
    private var isQueryBusy = false
    private var pendingExportBytes: ByteArray? = null

    private val downloadListener: (DownloadTaskState?) -> Unit = { state ->
        onDownloadStateChanged(state)
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val safCreateTxtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            try {
                FanqieBookExporter.saveToDocumentUri(this, uri, bytes)
                tvStatus.text = "已成功导出精排含目录 TXT 到您选择的位置"
                Toast.makeText(this, "导出 TXT 成功！", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val safCreateEpubLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            try {
                FanqieBookExporter.saveToDocumentUri(this, uri, bytes)
                tvStatus.text = "已成功导出原生多级目录 EPUB 到您选择的位置"
                Toast.makeText(this, "导出 EPUB 成功！", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContentView(R.layout.activity_fanqie)

        rootView = findViewById(R.id.fanqieRoot)
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
        btnPreviewToc = findViewById(R.id.btnPreviewToc)
        btnExportBook = findViewById(R.id.btnExportBook)
        btnRedownloadBook = findViewById(R.id.btnRedownloadBook)
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

        findViewById<Button>(R.id.btnEinkClean)?.setOnClickListener {
            EinkRefreshHelper.showEinkSettingsDialog(this, rootView)
        }

        findViewById<Button>(R.id.btnToggleConfig).setOnClickListener {
            configContainer.visibility =
                if (configContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            EinkRefreshHelper.scrubAfterOverlayDismiss(rootView)
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
            EinkRefreshHelper.scrubAfterOverlayDismiss(rootView)
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
            EinkRefreshHelper.scrubAfterOverlayDismiss(rootView)
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
            EinkRefreshHelper.scrubAfterOverlayDismiss(rootView)
        }

        btnDownloadBook.setOnClickListener {
            val meta = currentMetadata ?: return@setOnClickListener
            FanqieDownloadManager.startBackgroundDownload(
                ctx = this,
                fanqieBookId = meta.bookId,
                title = meta.title,
                author = meta.author,
                coverUrl = meta.coverUrl
            )
            Toast.makeText(this, "已加入书架并转入后台全本离线（自动抓取封面、原生分卷目录与排版）", Toast.LENGTH_SHORT).show()
        }

        btnPreviewToc.setOnClickListener {
            val meta = currentMetadata ?: return@setOnClickListener
            FanqieUiHelper.showBookTwoLevelTocFromId(this, meta.bookId, meta.title, preloadedMeta = meta)
        }

        btnExportBook.setOnClickListener {
            val meta = currentMetadata ?: return@setOnClickListener
            showExportOptionsDialog(meta)
        }

        btnRedownloadBook.setOnClickListener {
            val meta = currentMetadata ?: return@setOnClickListener
            confirmAndRedownloadBook(meta)
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
                searchByKeyword("十日终焉")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        FanqieDownloadManager.addListener(downloadListener)
    }

    override fun onResume() {
        super.onResume()
        // 若用户从书架删除了书籍后返回番茄书城，自动刷新当前详情卡片状态（清除残留已下载标记）
        currentMetadata?.let { meta ->
            if (bookInfoCard.visibility == View.VISIBLE) {
                showBookDetailCard(meta, fromSearch = lastSearchResults.isNotEmpty())
            }
        }
        rootView.post {
            EinkRefreshHelper.scrubAfterOverlayDismiss(rootView)
        }
    }

    private fun confirmAndRedownloadBook(meta: FanqieMetadata) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("重新全本下载《${meta.title}》")
            .setMessage("将彻底清空本书旧的章节缓存与历史文件，并从第 1 章开始重新全本下载（包含正文插图与原生目录）。是否继续？")
            .setPositiveButton("清空并重下") { _, _ ->
                FanqieLegadoBridge.purgeFanqieBookData(meta.bookId, deleteShelfRecord = false)
                downloadProgressBar.progress = 0
                tvBookCheckpointInfo.text = "已清空旧缓存，正在重新启动全本后台离线…"
                btnDownloadBook.text = "后台离线中 (0%)"
                btnDownloadBook.isEnabled = false
                FanqieDownloadManager.startBackgroundDownload(
                    ctx = this,
                    fanqieBookId = meta.bookId,
                    title = meta.title,
                    author = meta.author,
                    coverUrl = meta.coverUrl
                )
                Toast.makeText(this, "已清空旧缓存并重新启动《${meta.title}》全本离线下载", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        EinkRefreshHelper.applyEinkDialogStyle(dlg, rootView)
        dlg.show()
    }

    override fun onStop() {
        super.onStop()
        FanqieDownloadManager.removeListener(downloadListener)
    }

    private fun showExportOptionsDialog(meta: FanqieMetadata) {
        val cachedCount = FanqieLegadoBridge.getCachedChapterCount(meta.bookId)
        if (cachedCount <= 0) {
            Toast.makeText(
                this,
                "《${meta.title}》尚未缓存章节，请先点击「立即阅读」或「全本后台离线」下载后再导出",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val options = arrayOf(
            "1. 导出标准 EPUB（含原生树状目录 + 封面 + 精排）至 Download",
            "2. 导出精排 TXT（含原生分卷目录索引 + 精排正文）至 Download",
            "3. 导出标准 EPUB 并自选保存位置",
            "4. 导出精排 TXT 并自选保存位置",
            "5. 重新抓取并修复书架内本书的原生目录与排版"
        )

        val dlg = AlertDialog.Builder(this)
            .setTitle("导出《${meta.title}》（已缓存 $cachedCount 章）")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> executeExport(meta, isEpub = true, useSaf = false)
                    1 -> executeExport(meta, isEpub = false, useSaf = false)
                    2 -> executeExport(meta, isEpub = true, useSaf = true)
                    3 -> executeExport(meta, isEpub = false, useSaf = true)
                    4 -> rebuildShelfTocOnly(meta)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        EinkRefreshHelper.applyEinkDialogStyle(dlg, rootView)
        dlg.show()
    }

    private fun executeExport(meta: FanqieMetadata, isEpub: Boolean, useSaf: Boolean) {
        if (isQueryBusy) return
        isQueryBusy = true
        val formatName = if (isEpub) "EPUB（含原生树状目录与排版）" else "精排 TXT（含原生分卷目录索引）"
        tvStatus.text = "正在抓取《${meta.title}》原生目录并打包生成 $formatName…"
        btnExportBook.isEnabled = false

        lifecycleScope.launch {
            try {
                val (fileName, bytes) = if (isEpub) {
                    FanqieBookExporter.buildExportEpubBytes(meta.bookId, meta)
                } else {
                    FanqieBookExporter.buildExportTxtBytes(meta.bookId, meta)
                }
                isQueryBusy = false
                btnExportBook.isEnabled = true

                if (useSaf) {
                    pendingExportBytes = bytes
                    if (isEpub) {
                        safCreateEpubLauncher.launch(fileName)
                    } else {
                        safCreateTxtLauncher.launch(fileName)
                    }
                } else {
                    val mime = if (isEpub) "application/epub+zip" else "text/plain"
                    val savedPath = FanqieBookExporter.saveToPublicDownloads(
                        context = this@FanqieActivity,
                        fileName = fileName,
                        mimeType = mime,
                        bytes = bytes
                    )
                    tvStatus.text = "导出成功！已保存至：$savedPath"
                    Toast.makeText(
                        this@FanqieActivity,
                        "已导出至：$savedPath",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                isQueryBusy = false
                btnExportBook.isEnabled = true
                tvStatus.text = "导出失败：${e.message}"
                Toast.makeText(this@FanqieActivity, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun rebuildShelfTocOnly(meta: FanqieMetadata) {
        if (isQueryBusy) return
        isQueryBusy = true
        tvStatus.text = "正在重新抓取《${meta.title}》原生分卷目录并同步至阅读器书架…"
        btnExportBook.isEnabled = false

        lifecycleScope.launch {
            try {
                val (resolvedMeta, chapters, _) = FanqieBookExporter.prepareBookData(meta.bookId, meta)
                isQueryBusy = false
                btnExportBook.isEnabled = true
                val volCount = resolvedMeta.volumes.size
                val volInfo = if (volCount > 0) "$volCount 个原生分卷、" else ""
                tvStatus.text = "原生目录修复完成：《${resolvedMeta.title}》共同步 ${volInfo}${chapters.size} 章目录！"
                Toast.makeText(
                    this@FanqieActivity,
                    "已同步原生目录（${volInfo}共 ${chapters.size} 章），直接打开阅读器即可查看完整目录",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                isQueryBusy = false
                btnExportBook.isEnabled = true
                tvStatus.text = "修复目录失败：${e.message}"
                Toast.makeText(this@FanqieActivity, "修复目录失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun quickReadBook(
        bookId: String,
        title: String,
        author: String,
        coverUrl: String,
        intro: String
    ) {
        if (isQueryBusy) return
        isQueryBusy = true
        tvStatus.text = "正在准备《$title》原生目录与首章内容并打开墨水屏阅读器…"
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

                val tag = FanqieLegadoBridge.parseTag(book)
                if (tag != null && tag.pendingCount > 0) {
                    FanqieDownloadManager.startBackgroundDownload(
                        ctx = this@FanqieActivity,
                        fanqieBookId = bookId,
                        title = book.name,
                        author = book.author,
                        coverUrl = book.coverUrl ?: coverUrl
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
                val total = if (state.status == "done" && cachedCount > 0) cachedCount else maxOf(state.total, curMeta.downloadableChapterCount(), cachedCount)
                if (cachedCount in 1 until total) {
                    btnDownloadBook.text = "断点续下 (${cachedCount}/$total)"
                    tvBookCheckpointInfo.text = "断点已保存：已下载 $cachedCount / $total 章（待续 ${total - cachedCount} 章 · 可直接导出或阅读）"
                } else if (cachedCount >= total && total > 0) {
                    btnDownloadBook.text = "检测新章 / 增量更新"
                    tvBookCheckpointInfo.text = "全本已下载完成：共 $cachedCount 章（含原生分卷目录与排版，支持导出 TXT/EPUB）"
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
                tvStatus.text = "搜索「$keyword」找到 ${items.size} 本小说 · 点击「立即阅读」秒开阅读器，或点击卡片下载/导出："
                searchHeaderRow.visibility = View.VISIBLE
                tvSearchResultCount.text = "搜索结果（共 ${items.size} 本 · 支持秒开边看边下与原生目录导出）"
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
                EinkRefreshHelper.scrubAfterOverlayDismiss(rootView)
            } catch (e: Exception) {
                isQueryBusy = false
                tvStatus.text = "搜索失败：${e.message}"
            }
        }
    }

    private fun onSearchItemSelected(item: FanqieSearchItem) {
        if (isQueryBusy) return
        isQueryBusy = true
        tvStatus.text = "正在获取《${item.title}》原生分卷目录与本地断点状态..."

        lifecycleScope.launch {
            try {
                val rawMeta = FanqieLegadoBridge.parseInput(item.bookId)
                val meta = if (rawMeta.coverUrl.isBlank() && item.coverUrl.isNotBlank()) {
                    rawMeta.copy(coverUrl = item.coverUrl).also {
                        io.legado.app.fanqie.FanqieChapterCacheStore.saveMetadata(it)
                    }
                } else {
                    rawMeta
                }
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
        tvStatus.text = "正在解析书籍详情与原生分卷章节目录..."
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
        ivDetailCover.visibility = View.VISIBLE

        val localCover = FanqieLegadoBridge.localCoverFile(meta.bookId)
        if (localCover.isFile && localCover.length() > 256L) {
            Glide.with(this)
                .load(localCover)
                .dontAnimate()
                .placeholder(R.drawable.bg_eink_default_cover_linear)
                .error(R.drawable.bg_eink_default_cover_linear)
                .into(ivDetailCover)
        } else if (meta.coverUrl.isNotBlank()) {
            val normCover = io.legado.app.fanqie.FanqieCrawler.normalizeCoverUrl(meta.coverUrl)
            Glide.with(this)
                .load(normCover)
                .dontAnimate()
                .placeholder(R.drawable.bg_eink_default_cover_linear)
                .error(R.drawable.bg_eink_default_cover_linear)
                .into(ivDetailCover)
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val cachedPath = FanqieLegadoBridge.downloadAndCacheCover(localCover, normCover)
                if (cachedPath.startsWith("/")) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (!isFinishing && currentMetadata?.bookId == meta.bookId) {
                            Glide.with(this@FanqieActivity)
                                .load(java.io.File(cachedPath))
                                .dontAnimate()
                                .into(ivDetailCover)
                        }
                    }
                }
            }
        } else {
            ivDetailCover.setImageResource(R.drawable.bg_eink_default_cover_linear)
        }

        tvBookTitle.text = meta.title
        val volSummary = if (meta.volumes.isNotEmpty()) "一级分卷 ${meta.volumes.size} 卷" else ""
        val realTotal = meta.downloadableChapterCount()
        val chCountText = if (realTotal > 0) "二级章节 $realTotal 章" else "支持全本拉取"
        val wcText = meta.formattedWordCount()
        val extra = listOf(meta.author, meta.serialStatus, volSummary, chCountText, wcText).filter { it.isNotBlank() }
        tvBookAuthor.text = extra.joinToString(" · ")

        val latest = meta.effectiveLatestChapter()
        tvBookLatestChapter.text = if (latest.isNotBlank()) {
            "最新章节：$latest"
        } else {
            "最新章节：共 ${meta.chapterCount} 章（实时同步番茄官方原生目录）"
        }

        val cachedCount = FanqieLegadoBridge.getCachedChapterCount(meta.bookId)
        val shelfBook = FanqieLegadoBridge.findShelfBookVerified(meta.bookId)
        val total = maxOf(meta.downloadableChapterCount(), cachedCount)

        btnOpenDownloadedBook.visibility = View.VISIBLE
        btnOpenDownloadedBook.text = if (shelfBook != null) "继续阅读" else "立即阅读（秒开）"

        if (cachedCount > 0 && cachedCount < total) {
            tvBookCheckpointInfo.text = "断点状态：已缓存 $cachedCount / $total 章（含原生目录，可续下或直接导出 TXT/EPUB）"
            btnDownloadBook.text = "断点续下 (${cachedCount}/$total)"
        } else if (cachedCount > 0 && cachedCount >= total) {
            tvBookCheckpointInfo.text = "断点状态：全本已下载 $cachedCount 章（含原生目录，支持导出 TXT/EPUB）"
            btnDownloadBook.text = "检测新章 / 更新"
        } else {
            tvBookCheckpointInfo.text = "支持「立即阅读」秒开边看边下、「全本后台离线」及「导出含目录 TXT/EPUB」"
            btnDownloadBook.text = "全本后台离线"
        }

        tvBookIntro.text = meta.intro.ifBlank { "暂无简介" }
        tvStatus.text = "已就绪：《${meta.title}》，支持秒开阅读、后台全本离线或导出含原生目录的 TXT/EPUB。"

        FanqieDownloadManager.getState(meta.bookId)?.let { onDownloadStateChanged(it) }
        EinkRefreshHelper.scrubAfterOverlayDismiss(rootView)
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
            holder.cover.visibility = View.VISIBLE
            val localCover = FanqieLegadoBridge.localCoverFile(item.bookId)
            if (localCover.isFile && localCover.length() > 256L) {
                Glide.with(holder.cover.context)
                    .load(localCover)
                    .dontAnimate()
                    .into(holder.cover)
            } else if (item.coverUrl.isNotBlank()) {
                val normCover = io.legado.app.fanqie.FanqieCrawler.normalizeCoverUrl(item.coverUrl)
                Glide.with(holder.cover.context)
                    .load(normCover)
                    .dontAnimate()
                    .into(holder.cover)
            } else {
                holder.cover.setImageDrawable(null)
            }
            holder.title.text = item.title
            holder.meta.text = item.metaLine()
            holder.intro.text = item.intro.ifBlank { "点击「立即阅读」秒开阅读器，或点击「详情/离线」后台下载与导出" }
            holder.itemView.setOnClickListener { onCardClick(item) }
            holder.btnDetail.setOnClickListener { onCardClick(item) }
            holder.btnQuickRead.setOnClickListener { onQuickReadClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
