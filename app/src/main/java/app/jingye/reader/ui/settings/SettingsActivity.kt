package app.jingye.reader.ui.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.reader.BuiltinFont
import app.jingye.reader.reader.FontManager
import app.jingye.reader.reader.LayoutPreset
import app.jingye.reader.ui.debug.EpdDebugActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvCurrentPresetDesc: TextView
    private lateinit var tvCurrentFontDesc: TextView
    private lateinit var tvFontSize: TextView
    private lateinit var tvLineSpacing: TextView
    private lateinit var tvParaSpacing: TextView
    private lateinit var tvLetterSpacing: TextView
    private lateinit var tvMargin: TextView
    private lateinit var tvBoldState: TextView
    private lateinit var tvFullRefreshEvery: TextView

    private lateinit var btnSetPresetWeread: Button
    private lateinit var btnSetPresetCompact: Button
    private lateinit var btnSetPresetComfort: Button
    private lateinit var btnSetPresetClassic: Button
    private lateinit var btnSetPresetCrisp: Button

    private lateinit var btnSetFontSerif: Button
    private lateinit var btnSetFontWenkai: Button
    private lateinit var btnSetFontSans: Button
    private lateinit var btnSetFontEinkBold: Button
    private lateinit var btnSetFontFangsong: Button
    private lateinit var btnSetFontRounded: Button
    private lateinit var btnImportCustomFont: Button

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
                Toast.makeText(this, "已导入并应用字体：$cleanName", Toast.LENGTH_SHORT).show()
                updateLabels()
            } catch (e: Exception) {
                Toast.makeText(this, "字体导入失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val app = application as JingyeApp
        val p = app.prefs

        tvCurrentPresetDesc = findViewById(R.id.tvCurrentPresetDesc)
        tvCurrentFontDesc = findViewById(R.id.tvCurrentFontDesc)
        tvFontSize = findViewById(R.id.tvFontSize)
        tvLineSpacing = findViewById(R.id.tvLineSpacing)
        tvParaSpacing = findViewById(R.id.tvParaSpacing)
        tvLetterSpacing = findViewById(R.id.tvLetterSpacing)
        tvMargin = findViewById(R.id.tvMargin)
        tvBoldState = findViewById(R.id.tvBoldState)
        tvFullRefreshEvery = findViewById(R.id.tvFullRefreshEvery)

        btnSetPresetWeread = findViewById(R.id.btnSetPresetWeread)
        btnSetPresetCompact = findViewById(R.id.btnSetPresetCompact)
        btnSetPresetComfort = findViewById(R.id.btnSetPresetComfort)
        btnSetPresetClassic = findViewById(R.id.btnSetPresetClassic)
        btnSetPresetCrisp = findViewById(R.id.btnSetPresetCrisp)

        btnSetFontSerif = findViewById(R.id.btnSetFontSerif)
        btnSetFontWenkai = findViewById(R.id.btnSetFontWenkai)
        btnSetFontSans = findViewById(R.id.btnSetFontSans)
        btnSetFontEinkBold = findViewById(R.id.btnSetFontEinkBold)
        btnSetFontFangsong = findViewById(R.id.btnSetFontFangsong)
        btnSetFontRounded = findViewById(R.id.btnSetFontRounded)
        btnImportCustomFont = findViewById(R.id.btnImportCustomFont)

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(0, 0)
        }

        btnSetPresetWeread.setOnClickListener { selectPreset(LayoutPreset.WEREAD_STD) }
        btnSetPresetCompact.setOnClickListener { selectPreset(LayoutPreset.COMPACT_FAST) }
        btnSetPresetComfort.setOnClickListener { selectPreset(LayoutPreset.COMFORT_LARGE) }
        btnSetPresetClassic.setOnClickListener { selectPreset(LayoutPreset.CLASSIC_BOOK) }
        btnSetPresetCrisp.setOnClickListener { selectPreset(LayoutPreset.EINK_CRISP) }

        btnSetFontSerif.setOnClickListener { selectFont(BuiltinFont.SERIF) }
        btnSetFontWenkai.setOnClickListener { selectFont(BuiltinFont.WENKAI) }
        btnSetFontSans.setOnClickListener { selectFont(BuiltinFont.SANS) }
        btnSetFontEinkBold.setOnClickListener { selectFont(BuiltinFont.EINK_BOLD) }
        btnSetFontFangsong.setOnClickListener { selectFont(BuiltinFont.FANGSONG) }
        btnSetFontRounded.setOnClickListener { selectFont(BuiltinFont.ROUNDED) }
        btnImportCustomFont.setOnClickListener {
            try {
                pickFontLauncher.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnFontMinus).setOnClickListener {
            p.textSizeSp = (p.textSizeSp - 1).coerceIn(15, 36)
            p.presetId = "custom"
            updateLabels()
        }
        findViewById<Button>(R.id.btnFontPlus).setOnClickListener {
            p.textSizeSp = (p.textSizeSp + 1).coerceIn(15, 36)
            p.presetId = "custom"
            updateLabels()
        }

        findViewById<Button>(R.id.btnLineMinus).setOnClickListener {
            p.lineSpacingX10 = (p.lineSpacingX10 - 1).coerceIn(11, 22)
            p.presetId = "custom"
            updateLabels()
        }
        findViewById<Button>(R.id.btnLinePlus).setOnClickListener {
            p.lineSpacingX10 = (p.lineSpacingX10 + 1).coerceIn(11, 22)
            p.presetId = "custom"
            updateLabels()
        }

        findViewById<Button>(R.id.btnParaMinus).setOnClickListener {
            p.paraSpacingDp = (p.paraSpacingDp - 2).coerceIn(0, 30)
            p.presetId = "custom"
            updateLabels()
        }
        findViewById<Button>(R.id.btnParaPlus).setOnClickListener {
            p.paraSpacingDp = (p.paraSpacingDp + 2).coerceIn(0, 30)
            p.presetId = "custom"
            updateLabels()
        }

        findViewById<Button>(R.id.btnLetterMinus).setOnClickListener {
            p.letterSpacingX100 = (p.letterSpacingX100 - 1).coerceIn(0, 15)
            p.presetId = "custom"
            updateLabels()
        }
        findViewById<Button>(R.id.btnLetterPlus).setOnClickListener {
            p.letterSpacingX100 = (p.letterSpacingX100 + 1).coerceIn(0, 15)
            p.presetId = "custom"
            updateLabels()
        }

        findViewById<Button>(R.id.btnMarginMinus).setOnClickListener {
            p.marginDp = (p.marginDp - 2).coerceIn(10, 44)
            p.presetId = "custom"
            updateLabels()
        }
        findViewById<Button>(R.id.btnMarginPlus).setOnClickListener {
            p.marginDp = (p.marginDp + 2).coerceIn(10, 44)
            p.presetId = "custom"
            updateLabels()
        }

        findViewById<Button>(R.id.btnToggleBold).setOnClickListener {
            p.bold = !p.bold
            p.presetId = "custom"
            updateLabels()
        }

        findViewById<Button>(R.id.btnRefreshMinus).setOnClickListener {
            p.cleanEveryNPages = (p.cleanEveryNPages - 2).coerceIn(4, 40)
            updateLabels()
        }
        findViewById<Button>(R.id.btnRefreshPlus).setOnClickListener {
            p.cleanEveryNPages = (p.cleanEveryNPages + 2).coerceIn(4, 40)
            updateLabels()
        }

        findViewById<Button>(R.id.btnOpenEpdDebug).setOnClickListener {
            startActivity(Intent(this, EpdDebugActivity::class.java))
            overridePendingTransition(0, 0)
        }

        updateLabels()
    }

    private fun selectPreset(preset: LayoutPreset) {
        val app = application as JingyeApp
        app.prefs.applyPreset(preset, switchFontToo = true)
        updateLabels()
    }

    private fun selectFont(font: BuiltinFont) {
        val app = application as JingyeApp
        app.prefs.fontId = font.id
        updateLabels()
    }

    private fun styleButton(btn: Button, selected: Boolean) {
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

    private fun updateLabels() {
        val p = (application as JingyeApp).prefs
        val preset = LayoutPreset.fromId(p.presetId)
        if (preset == null) {
            tvCurrentPresetDesc.text = "当前：自定义微调排版"
        } else {
            tvCurrentPresetDesc.text = "当前：${preset.title}（${preset.subtitle}）"
        }

        styleButton(btnSetPresetWeread, p.presetId == LayoutPreset.WEREAD_STD.id)
        styleButton(btnSetPresetCompact, p.presetId == LayoutPreset.COMPACT_FAST.id)
        styleButton(btnSetPresetComfort, p.presetId == LayoutPreset.COMFORT_LARGE.id)
        styleButton(btnSetPresetClassic, p.presetId == LayoutPreset.CLASSIC_BOOK.id)
        styleButton(btnSetPresetCrisp, p.presetId == LayoutPreset.EINK_CRISP.id)

        val fontObj = BuiltinFont.fromId(p.fontId)
        val fontName = if (fontObj == BuiltinFont.CUSTOM && p.customFontName.isNotEmpty()) {
            p.customFontName
        } else {
            fontObj.displayName
        }
        tvCurrentFontDesc.text = "当前字体：$fontName（${fontObj.description}）"

        styleButton(btnSetFontSerif, p.fontId == BuiltinFont.SERIF.id)
        styleButton(btnSetFontWenkai, p.fontId == BuiltinFont.WENKAI.id)
        styleButton(btnSetFontSans, p.fontId == BuiltinFont.SANS.id)
        styleButton(btnSetFontEinkBold, p.fontId == BuiltinFont.EINK_BOLD.id)
        styleButton(btnSetFontFangsong, p.fontId == BuiltinFont.FANGSONG.id)
        styleButton(btnSetFontRounded, p.fontId == BuiltinFont.ROUNDED.id)

        btnImportCustomFont.text = if (p.customFontName.isNotEmpty()) {
            "自选字体：${p.customFontName}（点击更换 .ttf/.otf）"
        } else {
            "导入本地字体文件 (.ttf / .otf)"
        }
        styleButton(btnImportCustomFont, p.fontId == BuiltinFont.CUSTOM.id)

        tvFontSize.text = "正文字号：${p.textSizeSp} sp"
        tvLineSpacing.text = String.format(Locale.US, "行距倍率：%.1fx", p.lineSpacingX10 / 10f)
        tvParaSpacing.text = "段间距离：${p.paraSpacingDp} dp"
        tvLetterSpacing.text = String.format(Locale.US, "字间距离：%.2f em", p.letterSpacingX100 / 100f)
        tvMargin.text = "左右边距：${p.marginDp} dp"
        tvBoldState.text = "墨水屏笔画加深：${if (p.bold) "开启" else "关闭"}"
        tvFullRefreshEvery.text = "全刷去残影步长：${p.cleanEveryNPages} 页"
    }
}
