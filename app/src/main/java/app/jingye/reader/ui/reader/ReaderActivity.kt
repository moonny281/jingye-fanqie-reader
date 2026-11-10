package app.jingye.reader.ui.reader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.data.Book
import app.jingye.reader.eink.GhostPolicy
import app.jingye.reader.eink.RefreshController
import app.jingye.reader.eink.RefreshControllers
import app.jingye.reader.reader.PageView
import app.jingye.reader.reader.ReaderEngine
import app.jingye.reader.reader.ReaderStyle
import app.jingye.reader.reader.TurnThrottle
import app.jingye.reader.ui.settings.SettingsActivity
import app.jingye.reader.ui.toc.TocActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReaderActivity : AppCompatActivity() {

    private lateinit var pageView: PageView
    private lateinit var headerChapterTitle: TextView
    private lateinit var headerBookTitle: TextView
    private lateinit var footer: TextView
    private lateinit var footerClock: TextView

    private lateinit var menuTopBar: View
    private lateinit var menuTopDivider: View
    private lateinit var menu: View
    private lateinit var menuBookTitle: TextView
    private lateinit var menuChapterIndicator: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var btnMenuBold: Button
    private lateinit var btnSpacing: Button
    private lateinit var btnMarginCycle: Button

    private lateinit var app: JingyeApp
    private lateinit var book: Book
    private lateinit var engine: ReaderEngine
    private lateinit var refresh: RefreshController
    private lateinit var ghost: GhostPolicy
    private lateinit var throttle: TurnThrottle

    private var renderJob: Job? = null
    private var openedOnce = false
    private var lastFrame: ReaderEngine.Frame? = null
    private var lastStyleSnapshot: ReaderStyle? = null

    private val tocLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            val c = r.data?.getIntExtra("chapter", -1) ?: -1
            if (c >= 0) {
                setMenu(false)
                executeAction(turn = false) { engine.jump(c) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        hideSystemBars()

        app = application as JingyeApp
        val bookId = intent.getStringExtra("bookId")
        val loaded = bookId?.let { app.library.get(it) }
        if (loaded == null) {
            finish()
            return
        }
        book = loaded
        engine = ReaderEngine(app.source, book.id)
        refresh = RefreshControllers.create(this)
        ghost = GhostPolicy { app.prefs.cleanEveryNPages }
        throttle = TurnThrottle { app.prefs.minTurnIntervalMs }

        pageView = findViewById(R.id.pageView)
        headerChapterTitle = findViewById(R.id.headerChapterTitle)
        headerBookTitle = findViewById(R.id.headerBookTitle)
        footer = findViewById(R.id.footer)
        footerClock = findViewById(R.id.footerClock)

        menuTopBar = findViewById(R.id.menuTopBar)
        menuTopDivider = findViewById(R.id.menuTopDivider)
        menu = findViewById(R.id.menu)
        menuBookTitle = findViewById(R.id.menuBookTitle)
        menuChapterIndicator = findViewById(R.id.menuChapterIndicator)
        sizeLabel = findViewById(R.id.sizeLabel)
        btnMenuBold = findViewById(R.id.btnMenuBold)
        btnSpacing = findViewById(R.id.btnSpacing)
        btnMarginCycle = findViewById(R.id.btnMarginCycle)

        headerBookTitle.text = "《${book.title}》"
        menuBookTitle.text = "《${book.title}》"
        pageView.swapZones = app.prefs.swapTapZones

        pageView.onTapZone = { zone ->
            if (menu.visibility == View.VISIBLE) {
                setMenu(false)
            } else {
                when (zone) {
                    PageView.Zone.LEFT -> turn(-1)
                    PageView.Zone.RIGHT -> turn(+1)
                    PageView.Zone.CENTER -> setMenu(true)
                }
            }
        }

        findViewById<View>(R.id.btnToc).setOnClickListener {
            tocLauncher.launch(
                Intent(this, TocActivity::class.java)
                    .putExtra("bookId", book.id)
                    .putExtra("chapter", lastFrame?.chapter ?: 0)
            )
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        findViewById<View>(R.id.btnPrevChapter).setOnClickListener {
            val cur = lastFrame?.chapter ?: 0
            if (cur > 0) {
                executeAction(turn = false) { engine.jump(cur - 1) }
            } else {
                footer.text = "已是第一章"
            }
        }
        findViewById<View>(R.id.btnNextChapter).setOnClickListener {
            val f = lastFrame ?: return@setOnClickListener
            if (f.chapter + 1 < f.chapterCount) {
                executeAction(turn = false) { engine.jump(f.chapter + 1) }
            } else {
                footer.text = "已是最后一章"
            }
        }
        findViewById<View>(R.id.btnSmaller).setOnClickListener { changeSize(-1) }
        findViewById<View>(R.id.btnBigger).setOnClickListener { changeSize(+1) }
        btnMenuBold.setOnClickListener { toggleBold() }
        btnSpacing.setOnClickListener { cycleSpacing() }
        btnMarginCycle.setOnClickListener { cycleMargin() }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            setMenu(false)
            startActivity(Intent(this, SettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        findViewById<View>(R.id.btnCleanNow).setOnClickListener {
            setMenu(false)
            lastFrame?.let { refresh.cleanRegion(pageView, it.bitmap) }
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        headerChapterTitle.text = book.title
        footer.text = "正在排版章节…"
        syncMenuButtons()

        // 确保在 PageView 完成真实测量（width > 100 && height > 100）后再启动引擎，杜绝 width=0 导致 1px 死循环
        pageView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (!openedOnce && w > 100 && h > 100) {
                openedOnce = true
                val style = app.prefs.style()
                lastStyleSnapshot = style
                executeAction(turn = false) {
                    engine.open(w, h, style, book.chapter, book.charOffset)
                }
            }
        }
        pageView.post {
            if (!openedOnce && pageView.width > 100 && pageView.height > 100) {
                openedOnce = true
                val style = app.prefs.style()
                lastStyleSnapshot = style
                executeAction(turn = false) {
                    engine.open(pageView.width, pageView.height, style, book.chapter, book.charOffset)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refresh.onReaderEnter(this)
        pageView.swapZones = app.prefs.swapTapZones
        syncMenuButtons()
        val currentStyle = app.prefs.style()
        if (openedOnce && lastFrame != null && lastStyleSnapshot != null && currentStyle != lastStyleSnapshot) {
            lastStyleSnapshot = currentStyle
            executeAction(turn = false) { engine.relayout(currentStyle) }
        }
    }

    override fun onPause() {
        super.onPause()
        refresh.onReaderExit(this)
        lastFrame?.let {
            app.library.saveProgress(book.id, it.chapter, it.charOffset, it.chapterCount)
        }
    }

    private fun turn(delta: Int) {
        if (!throttle.tryAcquire()) return
        executeAction(turn = true, boundaryMsg = if (delta > 0) "— 全书完 —" else "— 第一页 —") {
            if (delta > 0) engine.next() else engine.prev()
        }
    }

    private fun executeAction(
        turn: Boolean,
        boundaryMsg: String? = null,
        action: suspend () -> ReaderEngine.Frame?
    ) {
        if (turn && renderJob?.isActive == true) return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            try {
                val f = action()
                if (f == null) {
                    footer.text = boundaryMsg ?: ""
                } else {
                    show(f, clean = turn && ghost.onPageTurned())
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                footer.text = "加载异常：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun show(f: ReaderEngine.Frame, clean: Boolean) {
        lastFrame = f
        if (clean) refresh.cleanRegion(pageView, f.bitmap) else refresh.showPage(pageView, f.bitmap)
        updateHeaderAndFooter(f)
        syncMenuButtons()
        lifecycleScope.launch { engine.prefetch() }
    }

    private fun updateHeaderAndFooter(f: ReaderEngine.Frame) {
        headerChapterTitle.text = f.title
        menuChapterIndicator.text = "第 ${f.chapter + 1}/${f.chapterCount} 章 · ${f.title}"
        val totalPct = if (f.chapterCount > 0) {
            val chWeight = (f.chapter.toDouble() + (f.pageIndex + 1).toDouble() / f.pageCount.coerceAtLeast(1)) / f.chapterCount
            String.format(Locale.US, "%.1f%%", (chWeight * 100.0).coerceIn(0.1, 100.0))
        } else {
            "0.0%"
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        footer.text = "全书 $totalPct · 本章 ${f.pageIndex + 1}/${f.pageCount}"
        footerClock.text = time
    }

    private fun syncMenuButtons() {
        sizeLabel.text = "${app.prefs.textSizeSp}"
        btnMenuBold.text = "加粗:${if (app.prefs.bold) "开" else "关"}"
        btnSpacing.text = String.format(Locale.US, "行距:%.1f", app.prefs.lineSpacingX10 / 10f)
        btnMarginCycle.text = "边距:${app.prefs.marginDp}"
    }

    private fun setMenu(show: Boolean) {
        val vis = if (show) View.VISIBLE else View.GONE
        menuTopBar.visibility = vis
        menuTopDivider.visibility = vis
        menu.visibility = vis
    }

    private fun changeSize(delta: Int) {
        app.prefs.textSizeSp += delta
        val style = app.prefs.style()
        lastStyleSnapshot = style
        syncMenuButtons()
        executeAction(turn = false) { engine.relayout(style) }
    }

    private fun toggleBold() {
        app.prefs.bold = !app.prefs.bold
        val style = app.prefs.style()
        lastStyleSnapshot = style
        syncMenuButtons()
        executeAction(turn = false) { engine.relayout(style) }
    }

    private fun cycleSpacing() {
        val steps = listOf(12, 15, 18, 20)
        val cur = app.prefs.lineSpacingX10
        app.prefs.lineSpacingX10 = steps[(steps.indexOfFirst { it >= cur }.coerceAtLeast(0) + 1) % steps.size]
        val style = app.prefs.style()
        lastStyleSnapshot = style
        syncMenuButtons()
        executeAction(turn = false) { engine.relayout(style) }
    }

    private fun cycleMargin() {
        val steps = listOf(16, 24, 32, 40)
        val cur = app.prefs.marginDp
        app.prefs.marginDp = steps[(steps.indexOfFirst { it >= cur }.coerceAtLeast(0) + 1) % steps.size]
        val style = app.prefs.style()
        lastStyleSnapshot = style
        syncMenuButtons()
        executeAction(turn = false) { engine.relayout(style) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> +1
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0) turn(dir)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu.visibility == View.VISIBLE) setMenu(false) else super.onBackPressed()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
