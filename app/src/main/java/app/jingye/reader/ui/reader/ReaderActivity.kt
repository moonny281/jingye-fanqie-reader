package app.jingye.reader.ui.reader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
import app.jingye.reader.reader.TurnThrottle
import app.jingye.reader.ui.settings.SettingsActivity
import app.jingye.reader.ui.toc.TocActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReaderActivity : AppCompatActivity() {

    private lateinit var pageView: PageView
    private lateinit var footer: TextView
    private lateinit var footerClock: TextView
    private lateinit var menu: View
    private lateinit var sizeLabel: TextView

    private lateinit var app: JingyeApp
    private lateinit var book: Book
    private lateinit var engine: ReaderEngine
    private lateinit var refresh: RefreshController
    private lateinit var ghost: GhostPolicy
    private lateinit var throttle: TurnThrottle

    private var busy = false
    private var lastFrame: ReaderEngine.Frame? = null
    private var lastStyleSnapshot: app.jingye.reader.reader.ReaderStyle? = null

    private val tocLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            val c = r.data?.getIntExtra("chapter", -1) ?: -1
            if (c >= 0) {
                setMenu(false)
                run(turn = false) { engine.jump(c) }
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
        footer = findViewById(R.id.footer)
        footerClock = findViewById(R.id.footerClock)
        menu = findViewById(R.id.menu)
        sizeLabel = findViewById(R.id.sizeLabel)
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
        findViewById<View>(R.id.btnSmaller).setOnClickListener { changeSize(-1) }
        findViewById<View>(R.id.btnBigger).setOnClickListener { changeSize(+1) }
        findViewById<View>(R.id.btnSpacing).setOnClickListener { cycleSpacing() }
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

        footer.text = "加载中…"
        pageView.post {
            val style = app.prefs.style()
            lastStyleSnapshot = style
            run(turn = false) {
                engine.open(pageView.width, pageView.height, style, book.chapter, book.charOffset)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refresh.onReaderEnter(this)
        pageView.swapZones = app.prefs.swapTapZones
        val currentStyle = app.prefs.style()
        if (lastFrame != null && lastStyleSnapshot != null && currentStyle != lastStyleSnapshot) {
            lastStyleSnapshot = currentStyle
            run(turn = false) { engine.relayout(currentStyle) }
        }
    }

    override fun onPause() {
        super.onPause()
        refresh.onReaderExit(this)
        // 海信 A7 后台清理策略严格，必须在 onPause 立即保存章节与字符偏移
        lastFrame?.let {
            app.library.saveProgress(book.id, it.chapter, it.charOffset, it.chapterCount)
        }
    }

    private fun turn(delta: Int) {
        if (!throttle.tryAcquire()) return
        run(turn = true, boundaryMsg = if (delta > 0) "— 全书完 —" else "— 第一页 —") {
            if (delta > 0) engine.next() else engine.prev()
        }
    }

    private fun run(
        turn: Boolean,
        boundaryMsg: String? = null,
        action: suspend () -> ReaderEngine.Frame?
    ) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val f = action()
                if (f == null) {
                    footer.text = boundaryMsg ?: ""
                } else {
                    show(f, clean = turn && ghost.onPageTurned())
                }
            } finally {
                busy = false
            }
        }
    }

    private fun show(f: ReaderEngine.Frame, clean: Boolean) {
        lastFrame = f
        if (clean) refresh.cleanRegion(pageView, f.bitmap) else refresh.showPage(pageView, f.bitmap)
        updateFooter(f)
        sizeLabel.text = app.prefs.textSizeSp.toString()
        lifecycleScope.launch { engine.prefetch() }
    }

    /** 页脚只在翻页时更新，不开定时器，避免无谓触发墨水屏刷新。 */
    private fun updateFooter(f: ReaderEngine.Frame) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        footer.text = "${f.title} · ${f.pageIndex + 1}/${f.pageCount}"
        footerClock.text = time
    }

    private fun setMenu(show: Boolean) {
        menu.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun changeSize(delta: Int) {
        app.prefs.textSizeSp += delta
        val style = app.prefs.style()
        lastStyleSnapshot = style
        run(turn = false) { engine.relayout(style) }
    }

    private fun cycleSpacing() {
        val steps = listOf(12, 15, 18, 20)
        val cur = app.prefs.lineSpacingX10
        app.prefs.lineSpacingX10 = steps[(steps.indexOfFirst { it >= cur }.coerceAtLeast(0) + 1) % steps.size]
        val style = app.prefs.style()
        lastStyleSnapshot = style
        run(turn = false) { engine.relayout(style) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> +1
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0) turn(dir) // 丢弃长按重复事件，避免墨水屏连续刷新
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
