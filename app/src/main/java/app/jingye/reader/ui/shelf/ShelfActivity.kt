package app.jingye.reader.ui.shelf

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import app.jingye.reader.data.Book
import app.jingye.reader.ui.fanqie.FanqieActivity
import app.jingye.reader.ui.reader.ReaderActivity
import app.jingye.reader.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

class ShelfActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyContainer: View
    private lateinit var tvBookCount: TextView
    private lateinit var btnCheckAllUpdates: Button

    private lateinit var shelfDownloadBanner: LinearLayout
    private lateinit var tvShelfDownloadTitle: TextView
    private lateinit var pbShelfDownload: ProgressBar
    private lateinit var tvShelfDownloadDetail: TextView
    private lateinit var btnShelfDownloadAction: Button
    private lateinit var btnShelfDownloadClose: Button

    private lateinit var heroRecentCard: LinearLayout
    private lateinit var tvHeroCoverLabel: TextView
    private lateinit var tvHeroStatusBadge: TextView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroMeta: TextView
    private lateinit var tvHeroLatestChapter: TextView
    private lateinit var btnHeroRead: Button
    private lateinit var btnHeroUpdate: Button

    private var isCheckingUpdates = false

    private val downloadListener: (DownloadTaskState?) -> Unit = { state ->
        bindDownloadBanner(state)
        if (state != null && (state.status == "done" || state.status == "paused" || state.current % 30 == 0)) {
            refreshShelf()
        }
    }

    private val pickTxtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importTxtFromUri(uri)
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shelf)

        requestNotificationPermissionIfNeeded()

        recyclerView = findViewById(R.id.shelfRecyclerView)
        emptyContainer = findViewById(R.id.emptyContainer)
        tvBookCount = findViewById(R.id.tvBookCount)
        btnCheckAllUpdates = findViewById(R.id.btnCheckAllUpdates)

        shelfDownloadBanner = findViewById(R.id.shelfDownloadBanner)
        tvShelfDownloadTitle = findViewById(R.id.tvShelfDownloadTitle)
        pbShelfDownload = findViewById(R.id.pbShelfDownload)
        tvShelfDownloadDetail = findViewById(R.id.tvShelfDownloadDetail)
        btnShelfDownloadAction = findViewById(R.id.btnShelfDownloadAction)
        btnShelfDownloadClose = findViewById(R.id.btnShelfDownloadClose)

        heroRecentCard = findViewById(R.id.heroRecentCard)
        tvHeroCoverLabel = findViewById(R.id.tvHeroCoverLabel)
        tvHeroStatusBadge = findViewById(R.id.tvHeroStatusBadge)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroMeta = findViewById(R.id.tvHeroMeta)
        tvHeroLatestChapter = findViewById(R.id.tvHeroLatestChapter)
        btnHeroRead = findViewById(R.id.btnHeroRead)
        btnHeroUpdate = findViewById(R.id.btnHeroUpdate)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.itemAnimator = null

        btnCheckAllUpdates.setOnClickListener {
            checkAllShelfBooksUpdates()
        }

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

        btnShelfDownloadClose.setOnClickListener {
            val st = FanqieDownloadManager.getActiveOrLatestState()
            if (st != null && st.isRunning) {
                FanqieDownloadManager.requestPause(this, st.fanqieBookId)
            } else {
                FanqieDownloadManager.dismissCompletedBanner()
                shelfDownloadBanner.visibility = View.GONE
            }
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Exception) {
                }
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

    override fun onResume() {
        super.onResume()
        refreshShelf()
    }

    private fun bindDownloadBanner(state: DownloadTaskState?) {
        if (state == null) {
            shelfDownloadBanner.visibility = View.GONE
            return
        }
        shelfDownloadBanner.visibility = View.VISIBLE
        pbShelfDownload.progress = state.percent.coerceIn(0, 100)
        tvShelfDownloadDetail.text = state.message

        when {
            state.isRunning -> {
                tvShelfDownloadTitle.text = "后台下载《${state.title}》 (${state.percent}%)"
                btnShelfDownloadAction.text = "暂停存断点"
                btnShelfDownloadAction.setOnClickListener {
                    FanqieDownloadManager.requestPause(this, state.fanqieBookId)
                }
            }
            state.status == "paused" || state.status == "error" -> {
                tvShelfDownloadTitle.text = "《${state.title}》已存断点 (${state.current}/${state.total}章)"
                btnShelfDownloadAction.text = "继续续下"
                btnShelfDownloadAction.setOnClickListener {
                    FanqieDownloadManager.startBackgroundDownload(
                        ctx = this,
                        fanqieBookId = state.fanqieBookId,
                        title = state.title,
                        author = state.author
                    )
                }
            }
            else -> {
                tvShelfDownloadTitle.text = "《${state.title}》下载完成 (${state.current}章)"
                btnShelfDownloadAction.text = "立即阅读"
                btnShelfDownloadAction.setOnClickListener {
                    val app = application as JingyeApp
                    val book = state.localBookId?.let { app.library.get(it) }
                        ?: app.library.findByFanqieId(state.fanqieBookId)
                    if (book != null) openBook(book)
                }
            }
        }
    }

    private fun refreshShelf() {
        val app = application as JingyeApp
        val books = app.library.all()
        val fanqieCount = books.count { it.fanqieBookId.isNotBlank() && it.fanqieBookId != "demo_v031" }
        tvBookCount.text = "${books.size} 本藏书 · ${fanqieCount} 本支持在线更新"

        if (books.isEmpty()) {
            heroRecentCard.visibility = View.GONE
            emptyContainer.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            val recent = books.first()
            bindHeroCard(recent)

            recyclerView.adapter = BookAdapter(
                books = books,
                onClick = { book -> openBook(book) },
                onCheckOrResume = { book -> handleBookCheckOrResume(book) },
                onLongClick = { book -> showBookActions(book) }
            )
        }
    }

    private fun bindHeroCard(recent: Book) {
        heroRecentCard.visibility = View.VISIBLE
        tvHeroCoverLabel.text = recent.title.take(4)
        tvHeroTitle.text = recent.title

        val totalCh = recent.totalChapters.coerceAtLeast(1)
        val remoteCh = maxOf(recent.remoteTotalChapters, totalCh)
        val curCh = (recent.chapter + 1).coerceAtMost(totalCh)
        val pct = ((curCh * 100) / totalCh).coerceIn(1, 100)
        val authorStr = recent.author.ifBlank { "本地藏书" }

        tvHeroMeta.text = if (recent.isPartialDownload || remoteCh > totalCh) {
            "$authorStr · 已读 $curCh/$totalCh 章 · 已下 $totalCh/$remoteCh 章"
        } else {
            "$authorStr · 已读至第 $curCh/$totalCh 章 ($pct%)"
        }

        tvHeroLatestChapter.text = if (recent.latestChapterTitle.isNotBlank()) {
            "最新：${recent.latestChapterTitle}"
        } else if (recent.fanqieBookId.isNotBlank() && recent.fanqieBookId != "demo_v031") {
            "最新：点击右侧「检测更新」获取最新章节"
        } else {
            "本地精排书籍 · 共 $totalCh 章"
        }

        val pendingCount = recent.pendingChaptersCount()
        when {
            recent.isPartialDownload && pendingCount > 0 -> {
                tvHeroStatusBadge.visibility = View.VISIBLE
                tvHeroStatusBadge.text = "待续下 +$pendingCount"
                btnHeroUpdate.visibility = View.VISIBLE
                btnHeroUpdate.text = "断点续下"
            }
            recent.hasUpdate && pendingCount > 0 -> {
                tvHeroStatusBadge.visibility = View.VISIBLE
                tvHeroStatusBadge.text = "有新章 +$pendingCount"
                btnHeroUpdate.visibility = View.VISIBLE
                btnHeroUpdate.text = "更新(+$pendingCount)"
            }
            recent.fanqieBookId.isNotBlank() && recent.fanqieBookId != "demo_v031" -> {
                tvHeroStatusBadge.visibility = if (recent.lastCheckedAt > 0L) View.VISIBLE else View.GONE
                tvHeroStatusBadge.text = "已最新"
                btnHeroUpdate.visibility = View.VISIBLE
                btnHeroUpdate.text = "检测更新"
            }
            else -> {
                tvHeroStatusBadge.visibility = View.GONE
                btnHeroUpdate.visibility = View.GONE
            }
        }

        heroRecentCard.setOnClickListener { openBook(recent) }
        btnHeroRead.setOnClickListener { openBook(recent) }
        btnHeroUpdate.setOnClickListener { handleBookCheckOrResume(recent) }
    }

    private fun handleBookCheckOrResume(book: Book) {
        if (book.fanqieBookId.isBlank() || book.fanqieBookId == "demo_v031") {
            Toast.makeText(this, "《${book.title}》为本地书籍，无需在线更新", Toast.LENGTH_SHORT).show()
            return
        }
        val pendingCount = book.pendingChaptersCount()
        if ((book.isPartialDownload || book.hasUpdate) && pendingCount > 0) {
            // 直接从断点处启动后台下载！
            Toast.makeText(
                this,
                "已启动《${book.title}》后台断点续下（待下 $pendingCount 章），状态栏可看进度",
                Toast.LENGTH_SHORT
            ).show()
            FanqieDownloadManager.startBackgroundDownload(
                ctx = this,
                fanqieBookId = book.fanqieBookId,
                title = book.title,
                author = book.author
            )
            return
        }

        // 否则先在线检测更新状态与最新章节
        checkSingleBookUpdate(book)
    }

    private fun checkSingleBookUpdate(book: Book) {
        if (isCheckingUpdates) return
        isCheckingUpdates = true
        Toast.makeText(this, "正在检测《${book.title}》最新章节…", Toast.LENGTH_SHORT).show()

        val app = application as JingyeApp
        lifecycleScope.launch {
            try {
                val res = app.fanqie.checkBookUpdate(book)
                isCheckingUpdates = false
                refreshShelf()

                if (res.hasPendingDownload()) {
                    val actionLabel = if (res.isPartialResume) "断点续下" else "立即后台更新"
                    AlertDialog.Builder(this@ShelfActivity)
                        .setTitle("《${res.title}》发现可用章节")
                        .setMessage(
                            "当前已下载：${res.localChapters} 章\n" +
                                "远端总章节：${res.remoteChapters} 章（待下 ${res.newChaptersCount} 章）\n" +
                                "最新章节：${res.latestChapterTitle.ifBlank { "未知" }}\n\n" +
                                "是否立即从第 ${res.localChapters + 1} 章开始后台断点续下？"
                        )
                        .setPositiveButton(actionLabel) { _, _ ->
                            FanqieDownloadManager.startBackgroundDownload(
                                ctx = this@ShelfActivity,
                                fanqieBookId = book.fanqieBookId,
                                title = book.title,
                                author = book.author
                            )
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                } else {
                    Toast.makeText(
                        this@ShelfActivity,
                        "《${res.title}》已是最新（共 ${res.remoteChapters} 章 · 最新：${res.latestChapterTitle.ifBlank { "无" }}）",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                isCheckingUpdates = false
                Toast.makeText(this@ShelfActivity, "检测失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkAllShelfBooksUpdates() {
        val app = application as JingyeApp
        val fanqieBooks = app.library.all().filter {
            it.fanqieBookId.isNotBlank() && it.fanqieBookId != "demo_v031"
        }
        if (fanqieBooks.isEmpty()) {
            Toast.makeText(this, "书架中暂无番茄在线书籍可检测更新", Toast.LENGTH_SHORT).show()
            return
        }
        if (isCheckingUpdates) return
        isCheckingUpdates = true
        btnCheckAllUpdates.isEnabled = false
        btnCheckAllUpdates.text = "检测中…"

        lifecycleScope.launch {
            var updatedBooksCount = 0
            var totalNewChapters = 0
            for (b in fanqieBooks) {
                try {
                    val res = app.fanqie.checkBookUpdate(b)
                    if (res.newChaptersCount > 0) {
                        updatedBooksCount++
                        totalNewChapters += res.newChaptersCount
                    }
                } catch (_: Exception) {
                }
            }
            isCheckingUpdates = false
            btnCheckAllUpdates.isEnabled = true
            btnCheckAllUpdates.text = "检测更新"
            refreshShelf()

            val msg = if (updatedBooksCount > 0) {
                "检测完成：发现 $updatedBooksCount 本书共有 $totalNewChapters 章待更新/续下！"
            } else {
                "检测完成：书架全部 ${fanqieBooks.size} 本番茄小说均已是最新章节！"
            }
            Toast.makeText(this@ShelfActivity, msg, Toast.LENGTH_LONG).show()
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
        val isFanqie = book.fanqieBookId.isNotBlank() && book.fanqieBookId != "demo_v031"
        val options = if (isFanqie) {
            arrayOf(
                "立即阅读",
                "检测最新章节状态",
                "后台断点续下 / 增量更新",
                "清空断点缓存并重新全本下载",
                "从书架删除"
            )
        } else {
            arrayOf("立即阅读", "从书架删除")
        }

        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(options) { _, which ->
                val app = application as JingyeApp
                if (isFanqie) {
                    when (which) {
                        0 -> openBook(book)
                        1 -> checkSingleBookUpdate(book)
                        2 -> FanqieDownloadManager.startBackgroundDownload(
                            ctx = this,
                            fanqieBookId = book.fanqieBookId,
                            title = book.title,
                            author = book.author
                        )
                        3 -> {
                            app.chapterCache.removeCache(book.fanqieBookId)
                            FanqieDownloadManager.startBackgroundDownload(
                                ctx = this,
                                fanqieBookId = book.fanqieBookId,
                                title = book.title,
                                author = book.author
                            )
                        }
                        4 -> {
                            app.chapterCache.removeCache(book.fanqieBookId)
                            app.library.remove(book.id)
                            app.localSource.invalidate(book.id)
                            refreshShelf()
                        }
                    }
                } else {
                    when (which) {
                        0 -> openBook(book)
                        1 -> {
                            app.library.remove(book.id)
                            app.localSource.invalidate(book.id)
                            refreshShelf()
                        }
                    }
                }
            }
            .show()
    }

    private fun createSampleBook() {
        val app = application as JingyeApp
        val sampleContent = buildString {
            appendLine("【第一章 净页 v0.3.1 墨水屏升级导览】")
            appendLine("　　欢迎使用「净页」海信 A7 墨水屏阅读器 v0.3.1。")
            appendLine("　　本版本重点升级了四大核心能力：")
            appendLine("　　一、后台下载与状态栏实时通知：开始下载番茄小说后可随时退回书架或阅读其他书籍，系统状态栏会静默展示下载进度百分比与当前章节，并支持在通知栏一键暂停保存断点。")
            appendLine("　　二、断点续下与书架检测更新：书架每本书均配备「检测更新 / 断点续下」按钮，实时显示番茄最新章节名称；未下完或连载更新的小说会直接从上次已下载的章节位置接着往后下，无需重复下载。")
            appendLine("　　三、阅读界面支持「左右滑动翻页」：在阅读页面既可以往左滑动翻下一页、往右滑动翻上一页，也支持点按屏幕左右侧与音量键翻页。")
            appendLine("　　四、内置 6 款墨水屏专用字体与 5 套微信读书风格预设排版，点击屏幕中央即可一键切换。")
            appendLine()
            appendLine("【第二章 手势滑动与防残影说明】")
            appendLine("　　您可以尝试在屏幕上向左滑动翻到下一页，或向右滑动返回上一页。")
            appendLine("　　每翻页达到设定步长后会自动触发黑白全刷清除墨水屏残影。")
        }
        val book = app.library.importFanqieTxt(
            fanqieBookId = "demo_v031",
            title = "净页墨水屏精排指南 v0.3.1",
            author = "净页开发组",
            totalChapters = 2,
            txtContent = sampleContent.toByteArray(Charsets.UTF_8),
            remoteTotalChapters = 2,
            latestChapterTitle = "第二章 手势滑动与防残影说明",
            serialStatus = "已完结",
            isPartialDownload = false
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
        private val onCheckOrResume: (Book) -> Unit,
        private val onLongClick: (Book) -> Unit
    ) : RecyclerView.Adapter<BookAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val coverThumb: TextView = view.findViewById(R.id.tvCoverThumb)
            val coverSerialTag: TextView = view.findViewById(R.id.tvCoverSerialTag)
            val title: TextView = view.findViewById(R.id.bookTitle)
            val updateBadge: TextView = view.findViewById(R.id.tvUpdateBadge)
            val author: TextView = view.findViewById(R.id.bookAuthor)
            val progress: TextView = view.findViewById(R.id.bookProgress)
            val latestChapter: TextView = view.findViewById(R.id.tvLatestChapter)
            val btnRead: Button = view.findViewById(R.id.btnItemRead)
            val btnCheckUpdate: Button = view.findViewById(R.id.btnItemCheckUpdate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = books[position]
            val isFanqie = item.fanqieBookId.isNotBlank() && item.fanqieBookId != "demo_v031"
            val totalCh = item.totalChapters.coerceAtLeast(1)
            val remoteCh = maxOf(item.remoteTotalChapters, totalCh)
            val curCh = (item.chapter + 1).coerceAtMost(totalCh)
            val pct = ((curCh * 100) / totalCh).coerceIn(1, 100)
            val authorStr = item.author.ifBlank { if (isFanqie) "番茄小说" else "本地TXT" }
            val pending = item.pendingChaptersCount()

            holder.coverThumb.text = item.title.take(4)
            holder.coverSerialTag.text = when {
                item.serialStatus.contains("完结") -> "完结"
                item.serialStatus.contains("连载") -> "连载"
                isFanqie -> "番茄"
                else -> "本地"
            }

            holder.title.text = item.title

            when {
                item.isPartialDownload && pending > 0 -> {
                    holder.updateBadge.visibility = View.VISIBLE
                    holder.updateBadge.setBackgroundResource(R.drawable.bg_eink_tag_inverted)
                    holder.updateBadge.setTextColor(0xFFFFFFFF.toInt())
                    holder.updateBadge.text = "待续下 +$pending"
                }
                item.hasUpdate && pending > 0 -> {
                    holder.updateBadge.visibility = View.VISIBLE
                    holder.updateBadge.setBackgroundResource(R.drawable.bg_eink_tag_inverted)
                    holder.updateBadge.setTextColor(0xFFFFFFFF.toInt())
                    holder.updateBadge.text = "有更新 +$pending"
                }
                isFanqie && item.lastCheckedAt > 0L -> {
                    holder.updateBadge.visibility = View.VISIBLE
                    holder.updateBadge.setBackgroundResource(R.drawable.bg_eink_tag)
                    holder.updateBadge.setTextColor(0xFF000000.toInt())
                    holder.updateBadge.text = "已最新"
                }
                else -> {
                    holder.updateBadge.visibility = View.GONE
                }
            }

            holder.author.text = if (remoteCh > totalCh) {
                "$authorStr · 已下 $totalCh/$remoteCh 章（断点可续）"
            } else if (item.totalChapters > 0) {
                "$authorStr · 共 $totalCh 章"
            } else {
                authorStr
            }

            holder.progress.text = if (item.lastReadAt > 0L && item.totalChapters > 0) {
                "阅读进度：第 $curCh/$totalCh 章 ($pct%)"
            } else {
                "尚未阅读 · 点击开始精排阅读"
            }

            if (item.latestChapterTitle.isNotBlank()) {
                holder.latestChapter.visibility = View.VISIBLE
                holder.latestChapter.text = "最新：${item.latestChapterTitle}"
            } else if (isFanqie) {
                holder.latestChapter.visibility = View.VISIBLE
                holder.latestChapter.text = "最新：点击右侧「检测更新」查询"
            } else {
                holder.latestChapter.visibility = View.GONE
            }

            holder.btnRead.setOnClickListener { onClick(item) }

            if (isFanqie) {
                holder.btnCheckUpdate.visibility = View.VISIBLE
                holder.btnCheckUpdate.text = when {
                    item.isPartialDownload && pending > 0 -> "断点续下"
                    item.hasUpdate && pending > 0 -> "更新(+$pending)"
                    else -> "检测更新"
                }
                holder.btnCheckUpdate.setOnClickListener { onCheckOrResume(item) }
            } else {
                holder.btnCheckUpdate.visibility = View.GONE
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
