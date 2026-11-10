package app.jingye.reader.ui.fanqie

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.backend.FanqieMetadata
import app.jingye.reader.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

/**
 * 番茄小说解析与下载前端页（遵循净页墨水屏纯黑白、零动画设计规范）。
 * 支持两种后端工作模式：
 * 1. 本机直连（默认）：内置完整移植自 Jees996/fanqie-novel-downloader 的匿名设备注册、x-argus/x-ladon 签名与 AES 解密内核；
 * 2. 远程后端：连接局域网或服务器运行的 FastAPI `app.py`（POST /api/parse + SSE /api/download/progress）。
 */
class FanqieActivity : AppCompatActivity() {

    private lateinit var app: JingyeApp
    private lateinit var btnToggleBackend: Button
    private lateinit var remoteConfigBox: View
    private lateinit var editBackendUrl: EditText
    private lateinit var editShareInput: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var metaCard: View
    private lateinit var metaTitle: TextView
    private lateinit var metaSummary: TextView
    private lateinit var metaIntro: TextView
    private lateinit var btnParse: Button
    private lateinit var btnDownloadToShelf: Button
    private lateinit var btnDownloadAndRead: Button

    private var currentMeta: FanqieMetadata? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fanqie)

        app = application as JingyeApp
        btnToggleBackend = findViewById(R.id.btnToggleBackend)
        remoteConfigBox = findViewById(R.id.remoteConfigBox)
        editBackendUrl = findViewById(R.id.editBackendUrl)
        editShareInput = findViewById(R.id.editShareInput)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        metaCard = findViewById(R.id.metaCard)
        metaTitle = findViewById(R.id.metaTitle)
        metaSummary = findViewById(R.id.metaSummary)
        metaIntro = findViewById(R.id.metaIntro)
        btnParse = findViewById(R.id.btnParse)
        btnDownloadToShelf = findViewById(R.id.btnDownloadToShelf)
        btnDownloadAndRead = findViewById(R.id.btnDownloadAndRead)

        editBackendUrl.setText(app.prefs.backendUrl)
        refreshBackendModeUi()

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        btnToggleBackend.setOnClickListener {
            if (busy) return@setOnClickListener
            app.prefs.useRemoteBackend = !app.prefs.useRemoteBackend
            refreshBackendModeUi()
        }

        findViewById<View>(R.id.btnSaveBackendUrl).setOnClickListener {
            if (busy) return@setOnClickListener
            val url = editBackendUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                app.prefs.backendUrl = url
            }
            checkRemoteHealth()
        }

        findViewById<View>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (clipText.isNotBlank()) {
                editShareInput.setText(clipText)
                editShareInput.setSelection(clipText.length)
            } else {
                statusTitle.text = "剪贴板为空"
                statusDetail.text = "请先在番茄小说 App 复制分享链接，或直接输入书籍 ID。"
            }
        }

        findViewById<View>(R.id.btnClear).setOnClickListener {
            if (busy) return@setOnClickListener
            editShareInput.setText("")
            metaCard.visibility = View.GONE
            currentMeta = null
            statusTitle.text = "已清空输入"
            statusDetail.text = "请粘贴新的番茄小说分享链接或书籍 ID。"
        }

        btnParse.setOnClickListener {
            if (busy) return@setOnClickListener
            parseBook()
        }

        btnDownloadToShelf.setOnClickListener {
            if (busy) return@setOnClickListener
            startDownload(openAfter = false)
        }

        btnDownloadAndRead.setOnClickListener {
            if (busy) return@setOnClickListener
            startDownload(openAfter = true)
        }
    }

    private fun refreshBackendModeUi() {
        if (app.prefs.useRemoteBackend) {
            btnToggleBackend.text = "通道:远程FastAPI"
            remoteConfigBox.visibility = View.VISIBLE
            statusTitle.text = "当前模式：远程 FastAPI 后端 (${app.prefs.backendUrl})"
            statusDetail.text = "通过 POST /api/parse 与 SSE /api/download/progress 调用外部服务端。"
        } else {
            btnToggleBackend.text = "通道:本机直连"
            remoteConfigBox.visibility = View.GONE
            statusTitle.text = "当前模式：本机内置网关直连（无需外部服务器）"
            statusDetail.text = "内置匿名设备注册 + x-argus/x-ladon 签名 + AES 解密，批次间隔 ${app.prefs.appApiIntervalSec} 秒。"
        }
    }

    private fun checkRemoteHealth() {
        busy = true
        statusTitle.text = "正在检测远程后端连接…"
        statusDetail.text = app.prefs.backendUrl
        lifecycleScope.launch {
            try {
                val msg = app.fanqie.checkRemoteHealth(app.prefs.backendUrl)
                statusTitle.text = msg
                statusDetail.text = "远程服务地址已保存：${app.prefs.backendUrl}"
            } catch (e: Exception) {
                statusTitle.text = "连接失败：${e.message}"
                statusDetail.text = "请确认服务端已执行 python app.py 并监听局域网 IP，或切换回「通道:本机直连」。"
            } finally {
                busy = false
            }
        }
    }

    private fun parseBook() {
        val raw = editShareInput.text.toString().trim()
        if (raw.isEmpty()) {
            statusTitle.text = "请输入分享链接或书籍 ID"
            return
        }
        busy = true
        metaCard.visibility = View.GONE
        statusTitle.text = "正在解析书籍信息…"
        statusDetail.text = "正在识别 book_id 并拉取元数据与目录信息"

        lifecycleScope.launch {
            try {
                val meta = app.fanqie.parseInput(raw)
                currentMeta = meta
                metaTitle.text = "《${meta.title}》"
                metaSummary.text = "作者：${meta.author} · 评分：${meta.score}\n" +
                    "字数：${meta.formattedWordCount()} · 状态：${meta.serialStatus} · 共 ${meta.chapterCount} 章 (可下 ${meta.availableChapters} 章)"
                metaIntro.text = meta.intro.ifBlank { "暂无简介" }
                metaCard.visibility = View.VISIBLE
                statusTitle.text = "解析成功：《${meta.title}》"
                statusDetail.text = "书籍 ID：${meta.bookId} · 点击下方按钮开始下载全书"
            } catch (e: Exception) {
                statusTitle.text = "解析失败"
                statusDetail.text = e.message ?: "请检查链接是否有效或网络是否通畅"
            } finally {
                busy = false
            }
        }
    }

    private fun startDownload(openAfter: Boolean) {
        val meta = currentMeta ?: return
        busy = true
        btnParse.isEnabled = false
        btnDownloadToShelf.isEnabled = false
        btnDownloadAndRead.isEnabled = false

        statusTitle.text = "正在启动下载《${meta.title}》…"
        statusDetail.text = "首次下载将自动注册匿名设备并换取正文解密密钥…"

        lifecycleScope.launch {
            try {
                val savedBook = app.fanqie.downloadAndImport(meta) { prog ->
                    runOnUiThread {
                        statusTitle.text = "下载进度：${prog.percent}% (${prog.current}/${prog.total} 章)"
                        statusDetail.text = prog.message.ifBlank { prog.chapterTitle }
                    }
                }
                statusTitle.text = "《${savedBook.title}》已加入书架！"
                statusDetail.text = "全书已按净页墨水屏排版格式保存至本地，随时可离线阅读。"
                if (openAfter) {
                    startActivity(Intent(this@FanqieActivity, ReaderActivity::class.java).putExtra("bookId", savedBook.id))
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                    finish()
                }
            } catch (e: Exception) {
                statusTitle.text = "下载失败"
                statusDetail.text = e.message ?: "下载过程中出现异常，请稍后重试"
            } finally {
                busy = false
                btnParse.isEnabled = true
                btnDownloadToShelf.isEnabled = true
                btnDownloadAndRead.isEnabled = true
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
