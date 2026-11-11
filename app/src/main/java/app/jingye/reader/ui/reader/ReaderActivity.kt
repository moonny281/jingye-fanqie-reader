package app.jingye.reader.ui.reader

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.data.Book
import app.jingye.reader.eink.GhostPolicy
import app.jingye.reader.eink.RefreshController
import app.jingye.reader.eink.RefreshControllers
import app.jingye.reader.reader.BuiltinFont
import app.jingye.reader.reader.FontManager
import app.jingye.reader.reader.LayoutPreset
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
    private lateinit var headerChapterTitle: TextView
    private lateinit var headerFontBadge: TextView
    private lateinit var footerProgressText: TextView
    private lateinit var footerClockBattery: TextView
    private lateinit var footerPageText: TextView
    private lateinit var readingProgressBar: ProgressBar

    private lateinit var readerTopMenu: View
    private lateinit var readerBottomMenu: View
    private lateinit var tvMenuBookTitle: TextView
    private lateinit var tvMenuChapterInfo: TextView
    private lateinit var tvQuickFontSize: TextView

    private lateinit var btnPresetWeread: Button
    private lateinit var btnPresetCompact: Button
    private lateinit var btnPresetComfort: Button
    private lateinit var btnPresetClassic: Button
    private lateinit var btnPresetCrisp: Button

    private lateinit var btnFontSerif: Button
    private lateinit var btnFontWenkai: Button
    private lateinit var btnFontSans: Button
    private lateinit var btnFontEinkBold: Button
    private lateinit var btnFontFangsong: Button
    private lateinit var btnFontRounded: Button
    private lateinit var btnFontCustom: Button

    private lateinit var engine: ReaderEngine
    private lateinit var refresh: RefreshController
    private lateinit var ghost: GhostPolicy
    private lateinit var throttle: TurnThrottle

    private lateinit var currentBook: Book
    private var currentFrame: ReaderEngine.Frame? = null
    private var isEngineReady = false
    private var isBusy = false
    private var lastStyleSignature = ""

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val tocLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val ch = res.data?.getIntExtra(TocActivity.EXTRA_SELECTED_CHAPTER, -1) ?: -1
            if (ch >= 0) {
                jumpToChapter(ch)
            }
        }
    }

    private val pickFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val app = application as JingyeApp
                val (path, cleanName) = FontManager.importCustomFont(this, uri)
                app.prefs.customFontPath = path
                app.prefs.customFontName = cleanName
                app.prefs.fontId = BuiltinFont.CUSTOM.id
                Toast.makeText(this, "已应用自定义字体：$cleanName", Toast.LENGTH_SHORT).show()
                relayoutCurrentStyle()
            } catch (e: Exception) {
                Toast.makeText(this, "导入字体失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        val app = application as JingyeApp
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val found = (if (bookId != null) app.library.get(bookId) else null) ?: app.library.all().firstOrNull()
        if (found == null) {
            Toast.makeText(this, "书架暂无书籍", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentBook = found

        pageView = findViewById(R.id.pageView)
        headerChapterTitle = findViewById(R.id.headerChapterTitle)
        headerFontBadge = findViewById(R.id.headerFontBadge)
        footerProgressText = findViewById(R.id.footerProgressText)
        footerClockBattery = findViewById(R.id.footerClockBattery)
        footerPageText = findViewById(R.id.footerPageText)
        readingProgressBar = findViewById(R.id.readingProgressBar)

        readerTopMenu = findViewById(R.id.readerTopMenu)
        readerBottomMenu = findViewById(R.id.readerBottomMenu)
        tvMenuBookTitle = findViewById(R.id.tvMenuBookTitle)
        tvMenuChapterInfo = findViewById(R.id.tvMenuChapterInfo)
        tvQuickFontSize = findViewById(R.id.tvQuickFontSize)

        btnPresetWeread = findViewById(R.id.btnPresetWeread)
        btnPresetCompact = findViewById(R.id.btnPresetCompact)
        btnPresetComfort = findViewById(R.id.btnPresetComfort)
        btnPresetClassic = findViewById(R.id.btnPresetClassic)
        btnPresetCrisp = findViewById(R.id.btnPresetCrisp)

        btnFontSerif = findViewById(R.id.btnFontSerif)
        btnFontWenkai = findViewById(R.id.btnFontWenkai)
        btnFontSans = findViewById(R.id.btnFontSans)
        btnFontEinkBold = findViewById(R.id.btnFontEinkBold)
        btnFontFangsong = findViewById(R.id.btnFontFangsong)
        btnFontRounded = findViewById(R.id.btnFontRounded)
        btnFontCustom = findViewById(R.id.btnFontCustom)

        tvMenuBookTitle.text = currentBook.title

        engine = ReaderEngine(app.source, currentBook.id, this)
        refresh = RefreshControllers.create(this)
        ghost = GhostPolicy { app.prefs.cleanEveryNPages }
        throttle = TurnThrottle { app.prefs.minTurnIntervalMs }

        pageView.swapZones = app.prefs.swapTapZones
        pageView.onTapZone = { zone ->
            if (isMenuVisible()) {
                hideMenu()
            } else {
                when (zone) {
                    PageView.Zone.LEFT -> turnPrev()
                    PageView.Zone.RIGHT -> turnNext()
                    PageView.Zone.CENTER -> showMenu()
                }
            }
        }

        setupMenuActions()

        pageView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 100 && h > 100 && !isEngineReady && !isBusy) {
                openEngineInitial(w, h)
            }
        }
    }

    private fun setupMenuActions() {
        val app = application as JingyeApp

        findViewById<Button>(R.id.btnBackShelf).setOnClickListener {
            finish()
            overridePendingTransition(0, 0)
        }

        findViewById<Button>(R.id.btnFullRefresh).setOnClickListener {
            hideMenu()
            currentFrame?.let { refresh.cleanRegion(pageView, it.bitmap) }
        }

        findViewById<Button>(R.id.btnPrevChapter).setOnClickListener {
            val frame = currentFrame ?: return@setOnClickListener
            if (frame.chapter <= 0) {
                Toast.makeText(this, "已是第一章", Toast.LENGTH_SHORT).show()
            } else {
                jumpToChapter(frame.chapter - 1)
            }
        }

        findViewById<Button>(R.id.btnNextChapter).setOnClickListener {
            val frame = currentFrame ?: return@setOnClickListener
            if (frame.chapter + 1 >= frame.chapterCount) {
                Toast.makeText(this, "已是最后一章", Toast.LENGTH_SHORT).show()
            } else {
                jumpToChapter(frame.chapter + 1)
            }
        }

        // Preset buttons
        btnPresetWeread.setOnClickListener { applyPreset(LayoutPreset.WEREAD_STD) }
        btnPresetCompact.setOnClickListener { applyPreset(LayoutPreset.COMPACT_FAST) }
        btnPresetComfort.setOnClickListener { applyPreset(LayoutPreset.COMFORT_LARGE) }
        btnPresetClassic.setOnClickListener { applyPreset(LayoutPreset.CLASSIC_BOOK) }
        btnPresetCrisp.setOnClickListener { applyPreset(LayoutPreset.EINK_CRISP) }

        // Font buttons
        btnFontSerif.setOnClickListener { applyFont(BuiltinFont.SERIF) }
        btnFontWenkai.setOnClickListener { applyFont(BuiltinFont.WENKAI) }
        btnFontSans.setOnClickListener { applyFont(BuiltinFont.SANS) }
        btnFontEinkBold.setOnClickListener { applyFont(BuiltinFont.EINK_BOLD) }
        btnFontFangsong.setOnClickListener { applyFont(BuiltinFont.FANGSONG) }
        btnFontRounded.setOnClickListener { applyFont(BuiltinFont.ROUNDED) }
        btnFontCustom.setOnClickListener {
            if (app.prefs.customFontPath.isNotEmpty() && app.prefs.fontId != BuiltinFont.CUSTOM.id) {
                applyFont(BuiltinFont.CUSTOM)
            } else {
                launchFontPicker()
            }
        }
        btnFontCustom.setOnLongClickListener {
            launchFontPicker()
            true
        }

        // Fine-grained typography buttons
        findViewById<Button>(R.id.btnFontMinus).setOnClickListener {
            app.prefs.textSizeSp = (app.prefs.textSizeSp - 1).coerceIn(15, 36)
            app.prefs.presetId = "custom"
            relayoutCurrentStyle()
        }

        findViewById<Button>(R.id.btnFontPlus).setOnClickListener {
            app.prefs.textSizeSp = (app.prefs.textSizeSp + 1).coerceIn(15, 36)
            app.prefs.presetId = "custom"
            relayoutCurrentStyle()
        }

        findViewById<Button>(R.id.btnQuickBold).setOnClickListener {
            app.prefs.bold = !app.prefs.bold
            app.prefs.presetId = "custom"
            relayoutCurrentStyle()
        }

        findViewById<Button>(R.id.btnQuickLineSpacing).setOnClickListener {
            val cur = app.prefs.lineSpacingX10
            app.prefs.lineSpacingX10 = when {
                cur < 13 -> 15
                cur < 16 -> 17
                cur < 18 -> 19
                else -> 12
            }
            app.prefs.presetId = "custom"
            relayoutCurrentStyle()
        }

        findViewById<Button>(R.id.btnQuickParaSpacing).setOnClickListener {
            val cur = app.prefs.paraSpacingDp
            app.prefs.paraSpacingDp = when {
                cur < 6 -> 10
                cur < 12 -> 16
                cur < 20 -> 22
                else -> 4
            }
            app.prefs.presetId = "custom"
            relayoutCurrentStyle()
        }

        findViewById<Button>(R.id.btnQuickMargin).setOnClickListener {
            val cur = app.prefs.marginDp
            app.prefs.marginDp = when {
                cur < 18 -> 22
                cur < 26 -> 30
                else -> 14
            }
            app.prefs.presetId = "custom"
            relayoutCurrentStyle()
        }

        findViewById<TextView>(R.id.btnMenuToc).setOnClickListener {
            hideMenu()
            val frame = currentFrame ?: return@setOnClickListener
            val intent = Intent(this, TocActivity::class.java).apply {
                putExtra(TocActivity.EXTRA_BOOK_ID, currentBook.id)
                putExtra(TocActivity.EXTRA_CURRENT_CHAPTER, frame.chapter)
            }
            tocLauncher.launch(intent)
            overridePendingTransition(0, 0)
        }

        findViewById<TextView>(R.id.btnMenuSettings).setOnClickListener {
            hideMenu()
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(0, 0)
        }

        findViewById<TextView>(R.id.btnMenuClose).setOnClickListener {
            hideMenu()
        }
    }

    private fun launchFontPicker() {
        try {
            pickFontLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开字体选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPreset(preset: LayoutPreset) {
        val app = application as JingyeApp
        app.prefs.applyPreset(preset, switchFontToo = true)
        relayoutCurrentStyle()
    }

    private fun applyFont(font: BuiltinFont) {
        val app = application as JingyeApp
        app.prefs.fontId = font.id
        relayoutCurrentStyle()
    }

    private fun currentStyleSig(): String {
        val p = (application as JingyeApp).prefs
        return "${p.presetId}_${p.fontId}_${p.customFontPath}_${p.textSizeSp}_${p.lineSpacingX10}_${p.paraSpacingDp}_${p.letterSpacingX100}_${p.marginDp}_${p.marginVdp}_${p.bold}"
    }

    private fun openEngineInitial(w: Int, h: Int) {
        isBusy = true
        val app = application as JingyeApp
        val latestBook = app.library.get(currentBook.id) ?: currentBook
        currentBook = latestBook
        val style = app.prefs.style()
        lastStyleSignature = currentStyleSig()

        headerChapterTitle.text = "正在精排章节..."
        footerProgressText.text = currentBook.title

        lifecycleScope.launch {
            try {
                val frame = engine.open(
                    w = w,
                    h = h,
                    style = style,
                    chapter = currentBook.chapter,
                    charOffset = currentBook.charOffset
                )
                isEngineReady = true
                isBusy = false
                presentFrame(frame, fullClean = true)
                engine.prefetch()
            } catch (e: Exception) {
                isBusy = false
                headerChapterTitle.text = "加载失败"
                Toast.makeText(this@ReaderActivity, "打开失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun relayoutCurrentStyle() {
        if (!isEngineReady || isBusy) return
        isBusy = true
        val app = application as JingyeApp
        val style = app.prefs.style()
        lastStyleSignature = currentStyleSig()

        lifecycleScope.launch {
            try {
                val frame = engine.relayout(style)
                isBusy = false
                presentFrame(frame, fullClean = true)
                updateMenuLabels()
                engine.prefetch()
            } catch (_: Exception) {
                isBusy = false
            }
        }
    }

    private fun jumpToChapter(targetChapter: Int) {
        if (!isEngineReady || isBusy) return
        isBusy = true
        lifecycleScope.launch {
            try {
                val frame = engine.jump(targetChapter)
                isBusy = false
                presentFrame(frame, fullClean = true)
                updateMenuLabels()
                engine.prefetch()
            } catch (_: Exception) {
                isBusy = false
            }
        }
    }

    private fun turnNext() {
        if (!isEngineReady || isBusy || !throttle.tryAcquire()) return
        isBusy = true
        lifecycleScope.launch {
            try {
                val prevCh = currentFrame?.chapter ?: -1
                val frame = engine.next()
                isBusy = false
                if (frame == null) {
                    Toast.makeText(this@ReaderActivity, "已读完最后一页", Toast.LENGTH_SHORT).show()
                } else {
                    val fullClean = ghost.onPageTurned() || (frame.chapter != prevCh)
                    presentFrame(frame, fullClean = fullClean)
                    engine.prefetch()
                }
            } catch (_: Exception) {
                isBusy = false
            }
        }
    }

    private fun turnPrev() {
        if (!isEngineReady || isBusy || !throttle.tryAcquire()) return
        isBusy = true
        lifecycleScope.launch {
            try {
                val prevCh = currentFrame?.chapter ?: -1
                val frame = engine.prev()
                isBusy = false
                if (frame == null) {
                    Toast.makeText(this@ReaderActivity, "已是第一页", Toast.LENGTH_SHORT).show()
                } else {
                    val fullClean = ghost.onPageTurned() || (frame.chapter != prevCh)
                    presentFrame(frame, fullClean = fullClean)
                    engine.prefetch()
                }
            } catch (_: Exception) {
                isBusy = false
            }
        }
    }

    private fun presentFrame(frame: ReaderEngine.Frame, fullClean: Boolean) {
        currentFrame = frame
        val app = application as JingyeApp
        app.library.saveProgress(
            id = currentBook.id,
            chapter = frame.chapter,
            charOffset = frame.charOffset,
            totalChapters = frame.chapterCount
        )

        if (fullClean) {
            refresh.cleanRegion(pageView, frame.bitmap)
        } else {
            refresh.showPage(pageView, frame.bitmap)
        }

        val fontObj = BuiltinFont.fromId(app.prefs.fontId)
        val tf = FontManager.resolveBaseTypeface(this, fontObj, app.prefs.customFontPath)
        headerChapterTitle.typeface = tf
        headerChapterTitle.text = frame.title

        val presetTitle = LayoutPreset.fromId(app.prefs.presetId)?.title ?: "自定义"
        val fontLabel = if (fontObj == BuiltinFont.CUSTOM && app.prefs.customFontName.isNotEmpty()) {
            app.prefs.customFontName
        } else {
            fontObj.displayName
        }
        headerFontBadge.text = "$presetTitle · $fontLabel"

        val totalCh = frame.chapterCount.coerceAtLeast(1)
        val curCh = frame.chapter + 1
        val pct = (((frame.chapter + (frame.pageIndex + 1).toFloat() / frame.pageCount.coerceAtLeast(1)) / totalCh) * 100f)
            .coerceIn(0.1f, 100f)
        readingProgressBar.progress = (pct * 10f).toInt().coerceIn(1, 1000)

        footerProgressText.text = "第 ${curCh}/${totalCh} 章 · ${String.format(Locale.US, "%.1f%%", pct)}"
        footerClockBattery.text = "${clockFormat.format(Date())} · ${readBatteryPercentage()}%"
        footerPageText.text = "${frame.pageIndex + 1} / ${frame.pageCount}"
    }

    private fun readBatteryPercentage(): Int {
        return try {
            val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else 100
        } catch (_: Exception) {
            100
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as JingyeApp
        pageView.swapZones = app.prefs.swapTapZones
        refresh.onReaderEnter(this)
        if (isEngineReady && currentStyleSig() != lastStyleSignature) {
            relayoutCurrentStyle()
        }
    }

    override fun onPause() {
        super.onPause()
        refresh.onReaderExit(this)
    }

    private fun isMenuVisible(): Boolean = readerBottomMenu.visibility == View.VISIBLE

    private fun showMenu() {
        updateMenuLabels()
        readerTopMenu.visibility = View.VISIBLE
        readerBottomMenu.visibility = View.VISIBLE
    }

    private fun hideMenu() {
        readerTopMenu.visibility = View.GONE
        readerBottomMenu.visibility = View.GONE
    }

    private fun stylePillButton(btn: Button, selected: Boolean) {
        if (selected) {
            btn.setBackgroundResource(R.drawable.bg_eink_button_solid)
            btn.setTextColor(Color.WHITE)
            btn.typeface = Typeface.DEFAULT_BOLD
        } else {
            btn.setBackgroundResource(R.drawable.bg_eink_button_outline)
            btn.setTextColor(Color.BLACK)
            btn.typeface = Typeface.DEFAULT
        }
    }

    private fun updateMenuLabels() {
        val app = application as JingyeApp
        val p = app.prefs
        val frame = currentFrame
        if (frame != null) {
            tvMenuChapterInfo.text = "${frame.title} (${frame.chapter + 1}/${frame.chapterCount})"
        }
        tvQuickFontSize.text = "${p.textSizeSp}sp"

        findViewById<Button>(R.id.btnQuickBold).text = if (p.bold) "加粗:开" else "加粗:关"
        findViewById<Button>(R.id.btnQuickLineSpacing).text = String.format(Locale.US, "行距:%.1f", p.lineSpacingX10 / 10f)
        findViewById<Button>(R.id.btnQuickParaSpacing).text = "段距:${p.paraSpacingDp}"
        findViewById<Button>(R.id.btnQuickMargin).text = "边距:${p.marginDp}"

        // Active preset
        stylePillButton(btnPresetWeread, p.presetId == LayoutPreset.WEREAD_STD.id)
        stylePillButton(btnPresetCompact, p.presetId == LayoutPreset.COMPACT_FAST.id)
        stylePillButton(btnPresetComfort, p.presetId == LayoutPreset.COMFORT_LARGE.id)
        stylePillButton(btnPresetClassic, p.presetId == LayoutPreset.CLASSIC_BOOK.id)
        stylePillButton(btnPresetCrisp, p.presetId == LayoutPreset.EINK_CRISP.id)

        // Active font
        stylePillButton(btnFontSerif, p.fontId == BuiltinFont.SERIF.id)
        stylePillButton(btnFontWenkai, p.fontId == BuiltinFont.WENKAI.id)
        stylePillButton(btnFontSans, p.fontId == BuiltinFont.SANS.id)
        stylePillButton(btnFontEinkBold, p.fontId == BuiltinFont.EINK_BOLD.id)
        stylePillButton(btnFontFangsong, p.fontId == BuiltinFont.FANGSONG.id)
        stylePillButton(btnFontRounded, p.fontId == BuiltinFont.ROUNDED.id)

        btnFontCustom.text = if (p.customFontName.isNotEmpty()) "自选:${p.customFontName}" else "+导入字体"
        stylePillButton(btnFontCustom, p.fontId == BuiltinFont.CUSTOM.id)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (isMenuVisible()) hideMenu()
                turnNext()
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (isMenuVisible()) hideMenu()
                turnPrev()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isMenuVisible()) {
                    hideMenu()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
