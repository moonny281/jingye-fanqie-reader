package app.jingye.reader.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.ui.debug.EpdDebugActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var app: JingyeApp
    private lateinit var valFontSize: TextView
    private lateinit var valLineSpacing: TextView
    private lateinit var valMargin: TextView
    private lateinit var btnToggleBold: Button
    private lateinit var btnToggleAA: Button
    private lateinit var valCleanPages: TextView
    private lateinit var valThrottleMs: TextView
    private lateinit var btnToggleSwapZones: Button
    private lateinit var valAppInterval: TextView

    private val cleanSteps = listOf(0, 10, 20, 30, 50, 80)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        app = application as JingyeApp
        valFontSize = findViewById(R.id.valFontSize)
        valLineSpacing = findViewById(R.id.valLineSpacing)
        valMargin = findViewById(R.id.valMargin)
        btnToggleBold = findViewById(R.id.btnToggleBold)
        btnToggleAA = findViewById(R.id.btnToggleAA)
        valCleanPages = findViewById(R.id.valCleanPages)
        valThrottleMs = findViewById(R.id.valThrottleMs)
        btnToggleSwapZones = findViewById(R.id.btnToggleSwapZones)
        valAppInterval = findViewById(R.id.valAppInterval)

        findViewById<View>(R.id.btnCloseSettings).setOnClickListener { finish() }

        // 1. 字号 14-40
        findViewById<View>(R.id.btnFontMinus).setOnClickListener {
            app.prefs.textSizeSp -= 1
            renderValues()
        }
        findViewById<View>(R.id.btnFontPlus).setOnClickListener {
            app.prefs.textSizeSp += 1
            renderValues()
        }

        // 2. 行距 1.2-2.0
        findViewById<View>(R.id.btnSpacingMinus).setOnClickListener {
            app.prefs.lineSpacingX10 -= 1
            renderValues()
        }
        findViewById<View>(R.id.btnSpacingPlus).setOnClickListener {
            app.prefs.lineSpacingX10 += 1
            renderValues()
        }

        // 3. 页边距 8-48, 步长 4
        findViewById<View>(R.id.btnMarginMinus).setOnClickListener {
            app.prefs.marginDp -= 4
            renderValues()
        }
        findViewById<View>(R.id.btnMarginPlus).setOnClickListener {
            app.prefs.marginDp += 4
            renderValues()
        }

        // 4. 字重
        btnToggleBold.setOnClickListener {
            app.prefs.bold = !app.prefs.bold
            renderValues()
        }

        // 5. 抗锯齿
        btnToggleAA.setOnClickListener {
            app.prefs.antiAlias = !app.prefs.antiAlias
            renderValues()
        }

        // 6. 局部清影间隔 (从不 / 10 / 20 / 30 / 50 / 80)
        findViewById<View>(R.id.btnCleanMinus).setOnClickListener {
            val cur = app.prefs.cleanEveryNPages
            val idx = cleanSteps.indexOf(cur).let { if (it < 0) 3 else it }
            app.prefs.cleanEveryNPages = cleanSteps[(idx - 1).coerceAtLeast(0)]
            renderValues()
        }
        findViewById<View>(R.id.btnCleanPlus).setOnClickListener {
            val cur = app.prefs.cleanEveryNPages
            val idx = cleanSteps.indexOf(cur).let { if (it < 0) 3 else it }
            app.prefs.cleanEveryNPages = cleanSteps[(idx + 1).coerceAtMost(cleanSteps.lastIndex)]
            renderValues()
        }

        // 7. 翻页最小间隔 150-600ms, 步长 50
        findViewById<View>(R.id.btnThrottleMinus).setOnClickListener {
            app.prefs.minTurnIntervalMs -= 50
            renderValues()
        }
        findViewById<View>(R.id.btnThrottlePlus).setOnClickListener {
            app.prefs.minTurnIntervalMs += 50
            renderValues()
        }

        // 8. 左右点击互换
        btnToggleSwapZones.setOnClickListener {
            app.prefs.swapTapZones = !app.prefs.swapTapZones
            renderValues()
        }

        // 9. 番茄 APP 批次间隔 2-15s
        findViewById<View>(R.id.btnIntervalMinus).setOnClickListener {
            app.prefs.appApiIntervalSec -= 1
            renderValues()
        }
        findViewById<View>(R.id.btnIntervalPlus).setOnClickListener {
            app.prefs.appApiIntervalSec += 1
            renderValues()
        }

        // 10. 墨水屏调试页
        findViewById<View>(R.id.btnOpenEpdDebug).setOnClickListener {
            startActivity(Intent(this, EpdDebugActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        renderValues()
    }

    private fun renderValues() {
        valFontSize.text = "${app.prefs.textSizeSp}"
        valLineSpacing.text = String.format("%.1f", app.prefs.lineSpacingX10 / 10f)
        valMargin.text = "${app.prefs.marginDp}"
        btnToggleBold.text = if (app.prefs.bold) "开" else "关"
        btnToggleAA.text = if (app.prefs.antiAlias) "开" else "关"
        val clean = app.prefs.cleanEveryNPages
        valCleanPages.text = if (clean <= 0) "从不" else "${clean}页"
        valThrottleMs.text = "${app.prefs.minTurnIntervalMs}ms"
        btnToggleSwapZones.text = if (app.prefs.swapTapZones) "开" else "关"
        valAppInterval.text = "${app.prefs.appApiIntervalSec}s"
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
