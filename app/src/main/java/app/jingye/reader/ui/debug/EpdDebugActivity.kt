package app.jingye.reader.ui.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.eink.EpdSettings
import app.jingye.reader.eink.RefreshController
import app.jingye.reader.eink.RefreshControllers
import app.jingye.reader.reader.PageView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EpdDebugActivity : AppCompatActivity() {

    private lateinit var app: JingyeApp
    private lateinit var debugPageView: PageView
    private lateinit var debugStatusText: TextView
    private lateinit var epdReadoutText: TextView
    private lateinit var btnToggleHwAccel: Button
    private lateinit var btnToggleDebugAA: Button
    private lateinit var expWritePanel: View
    private lateinit var refresh: RefreshController

    private var currentBitmap: Bitmap? = null
    private var hwAccel = true
    private var debugAntiAlias = true
    private var stressJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epd_debug)

        app = application as JingyeApp
        refresh = RefreshControllers.create(this)
        debugPageView = findViewById(R.id.debugPageView)
        debugStatusText = findViewById(R.id.debugStatusText)
        epdReadoutText = findViewById(R.id.epdReadoutText)
        btnToggleHwAccel = findViewById(R.id.btnToggleHwAccel)
        btnToggleDebugAA = findViewById(R.id.btnToggleDebugAA)
        expWritePanel = findViewById(R.id.expWritePanel)

        debugAntiAlias = app.prefs.antiAlias
        updateToggleLabels()

        findViewById<View>(R.id.btnCloseDebug).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRefreshSysVals).setOnClickListener { refreshReadout() }

        findViewById<View>(R.id.btnPatWhite).setOnClickListener {
            showPattern("全白") { TestPatterns.solid(debugPageView.width, debugPageView.height, Color.WHITE) }
        }
        findViewById<View>(R.id.btnPatBlack).setOnClickListener {
            showPattern("全黑") { TestPatterns.solid(debugPageView.width, debugPageView.height, Color.BLACK) }
        }
        findViewById<View>(R.id.btnPatChecker).setOnClickListener {
            showPattern("棋盘格 (64px)") { TestPatterns.checker(debugPageView.width, debugPageView.height, 64) }
        }
        findViewById<View>(R.id.btnPatGray).setOnClickListener {
            showPattern("16 级灰阶条") { TestPatterns.grayRamp(debugPageView.width, debugPageView.height, 16) }
        }
        findViewById<View>(R.id.btnPatText).setOnClickListener {
            showPattern("文字样张") {
                val st = app.prefs.style().copy(antiAlias = debugAntiAlias)
                TestPatterns.sampleText(debugPageView.width, debugPageView.height, st, 1)
            }
        }

        findViewById<View>(R.id.btnCleanRegion).setOnClickListener {
            val bmp = currentBitmap ?: return@setOnClickListener
            debugStatusText.text = "执行局部清影：黑(120ms) → 白(120ms) → 内容"
            refresh.cleanRegion(debugPageView, bmp)
        }

        findViewById<View>(R.id.btnStress250).setOnClickListener { runStressTest(250L) }
        findViewById<View>(R.id.btnStress400).setOnClickListener { runStressTest(400L) }

        btnToggleHwAccel.setOnClickListener {
            hwAccel = !hwAccel
            debugPageView.setLayerType(
                if (hwAccel) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE,
                null
            )
            updateToggleLabels()
            debugStatusText.text = "已切换 PageView 图层：${if (hwAccel) "HARDWARE" else "SOFTWARE"}"
        }

        btnToggleDebugAA.setOnClickListener {
            debugAntiAlias = !debugAntiAlias
            app.prefs.antiAlias = debugAntiAlias
            updateToggleLabels()
            showPattern("文字样张 (抗锯齿=${if (debugAntiAlias) "开" else "关"})") {
                val st = app.prefs.style().copy(antiAlias = debugAntiAlias)
                TestPatterns.sampleText(debugPageView.width, debugPageView.height, st, 1)
            }
        }

        findViewById<View>(R.id.btnToggleExpWrite).setOnClickListener {
            expWritePanel.visibility = if (expWritePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<View>(R.id.btnWriteMode1).setOnClickListener { tryWriteMode(1) }
        findViewById<View>(R.id.btnWriteMode2).setOnClickListener { tryWriteMode(2) }
        findViewById<View>(R.id.btnWriteMode3).setOnClickListener { tryWriteMode(3) }

        refreshReadout()
        debugPageView.post {
            showPattern("文字样张") {
                val st = app.prefs.style().copy(antiAlias = debugAntiAlias)
                TestPatterns.sampleText(debugPageView.width, debugPageView.height, st, 1)
            }
        }
    }

    private fun updateToggleLabels() {
        btnToggleHwAccel.text = "硬件加速:${if (hwAccel) "开" else "关"}"
        btnToggleDebugAA.text = "抗锯齿:${if (debugAntiAlias) "开" else "关"}"
    }

    private fun showPattern(label: String, builder: () -> Bitmap) {
        stressJob?.cancel()
        val bmp = builder()
        currentBitmap = bmp
        refresh.showPage(debugPageView, bmp)
        debugStatusText.text = "当前显示：$label"
    }

    private fun runStressTest(intervalMs: Long) {
        stressJob?.cancel()
        stressJob = lifecycleScope.launch {
            val st = app.prefs.style().copy(antiAlias = debugAntiAlias)
            for (i in 1..20) {
                val bmp = if (i % 2 == 0) {
                    TestPatterns.checker(debugPageView.width, debugPageView.height, 48)
                } else {
                    TestPatterns.sampleText(debugPageView.width, debugPageView.height, st, i)
                }
                currentBitmap = bmp
                refresh.showPage(debugPageView, bmp)
                debugStatusText.text = "翻页压力测试 ($intervalMs ms)：第 $i/20 帧"
                delay(intervalMs)
            }
            debugStatusText.text = "翻页压力测试完成 (共 20 帧，间隔 ${intervalMs}ms)"
        }
    }

    private fun refreshReadout() {
        val sb = StringBuilder()
        sb.appendLine("== Settings (system / global / secure) ==")
        for (item in EpdSettings.read(this)) {
            val ns = item.namespace ?: "未找到"
            val v = item.value ?: "null"
            sb.appendLine("${item.key} [$ns] = $v")
        }
        sb.appendLine()
        sb.appendLine("== SystemProperties (getprop) ==")
        for (propKey in EpdSettings.PROPS) {
            val v = EpdSettings.prop(propKey) ?: "(受限或未设置)"
            sb.appendLine("$propKey = $v")
        }
        epdReadoutText.text = sb.toString().trimEnd()
    }

    private fun tryWriteMode(mode: Int) {
        val res = EpdSettings.tryWriteSystemInt(this, "current_epd_mode", mode)
        res.onSuccess {
            debugStatusText.text = "实验写入成功：current_epd_mode = $mode"
            refreshReadout()
        }.onFailure {
            debugStatusText.text = "实验写入未生效：${it.message}"
        }
    }

    override fun finish() {
        stressJob?.cancel()
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
