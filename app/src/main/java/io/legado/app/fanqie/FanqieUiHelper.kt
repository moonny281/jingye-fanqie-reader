package io.legado.app.fanqie

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.TextView
import android.widget.Toast
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.text.BadgeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object FanqieUiHelper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun bindShelfItem(
        rootView: View,
        tvFanqieAction: TextView,
        bvUnread: BadgeView,
        item: Book
    ) {
        if (AppConfig.isEInkMode) {
            rootView.setBackgroundResource(R.drawable.bg_eink_border_bottom)
        }
        val tagInfo = FanqieLegadoBridge.parseTag(item)
        if (tagInfo == null) {
            tvFanqieAction.visibility = View.GONE
            return
        }

        tvFanqieAction.visibility = View.VISIBLE
        val activeState = FanqieDownloadManager.getState(tagInfo.fanqieBookId)
        val pending = tagInfo.pendingCount

        when {
            activeState != null && activeState.isRunning -> {
                tvFanqieAction.setBackgroundResource(R.drawable.bg_eink_button_solid)
                tvFanqieAction.setTextColor(0xFFFFFFFF.toInt())
                tvFanqieAction.text = "离线中 ${activeState.percent}%"
                bvUnread.setHighlight(true)
                bvUnread.setBadgeCount(pending.coerceAtLeast(1))
            }
            pending > 0 -> {
                tvFanqieAction.setBackgroundResource(R.drawable.bg_eink_button_solid)
                tvFanqieAction.setTextColor(0xFFFFFFFF.toInt())
                tvFanqieAction.text = if (tagInfo.isPartial) "续下(+$pending)" else "更新(+$pending)"
                bvUnread.setHighlight(true)
                bvUnread.setBadgeCount(pending)
            }
            else -> {
                tvFanqieAction.setBackgroundResource(R.drawable.bg_eink_button_outline)
                tvFanqieAction.setTextColor(0xFF000000.toInt())
                tvFanqieAction.text = "目录/导出"
            }
        }

        tvFanqieAction.setOnClickListener {
            onShelfBookActionClick(rootView.context, item)
        }
    }

    fun onShelfBookActionClick(context: Context, item: Book) {
        val tagInfo = FanqieLegadoBridge.parseTag(item)
        if (tagInfo == null) {
            Toast.makeText(context, "该书籍不是番茄下载书籍", Toast.LENGTH_SHORT).show()
            return
        }

        val activeState = FanqieDownloadManager.getState(tagInfo.fanqieBookId)
        if (activeState != null && activeState.isRunning) {
            FanqieDownloadManager.requestPause(context, tagInfo.fanqieBookId)
            Toast.makeText(context, "正在暂停《${item.name}》并保存断点…", Toast.LENGTH_SHORT).show()
            return
        }

        val pending = tagInfo.pendingCount
        if (pending > 0) {
            Toast.makeText(
                context,
                "已启动《${item.name}》后台断点续下（从第 ${tagInfo.downloadedChapters + 1} 章继续，待下 $pending 章）",
                Toast.LENGTH_SHORT
            ).show()
            FanqieDownloadManager.startBackgroundDownload(
                ctx = context,
                fanqieBookId = tagInfo.fanqieBookId,
                title = item.name,
                author = item.author
            )
            return
        }

        val actions = arrayOf(
            "1. 检测远端新章节更新",
            "2. 导出标准 EPUB（含原生树状目录 + 封面 + 精排）",
            "3. 导出精排 TXT（含原生分卷目录索引 + 精排正文）",
            "4. 重新抓取并修复本书原生分卷目录"
        )
        AlertDialog.Builder(context)
            .setTitle("《${item.name}》番茄管理与导出")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> checkSingleBookUpdate(context, item)
                    1 -> exportShelfBook(context, tagInfo.fanqieBookId, item.name, isEpub = true)
                    2 -> exportShelfBook(context, tagInfo.fanqieBookId, item.name, isEpub = false)
                    3 -> rebuildShelfBookToc(context, tagInfo.fanqieBookId, item.name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkSingleBookUpdate(context: Context, item: Book) {
        Toast.makeText(context, "正在检测《${item.name}》最新章节…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val res = FanqieLegadoBridge.checkBookUpdate(item)
                if (res.hasPendingDownload()) {
                    val actionText = if (res.isPartialResume) "断点续下" else "后台更新"
                    AlertDialog.Builder(context)
                        .setTitle("《${res.title}》发现新章节")
                        .setMessage(
                            "本地已下：${res.localChapters} 章\n" +
                                "远端总章：${res.remoteChapters} 章（待下 ${res.newChaptersCount} 章）\n" +
                                "最新章节：${res.latestChapterTitle.ifBlank { "未知" }}\n\n" +
                                "是否立即从第 ${res.localChapters + 1} 章启动后台断点续下？"
                        )
                        .setPositiveButton(actionText) { _, _ ->
                            FanqieDownloadManager.startBackgroundDownload(
                                ctx = context,
                                fanqieBookId = res.fanqieBookId,
                                title = res.title,
                                author = item.author
                            )
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                } else {
                    Toast.makeText(
                        context,
                        "《${res.title}》已是最新（共 ${res.remoteChapters} 章 · 最新：${res.latestChapterTitle.ifBlank { "无" }}）",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "检测失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportShelfBook(context: Context, fanqieBookId: String, bookTitle: String, isEpub: Boolean) {
        val fmt = if (isEpub) "EPUB" else "精排 TXT"
        Toast.makeText(context, "正在抓取原生目录并打包导出《$bookTitle》($fmt)…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val (fileName, bytes) = if (isEpub) {
                    FanqieBookExporter.buildExportEpubBytes(fanqieBookId)
                } else {
                    FanqieBookExporter.buildExportTxtBytes(fanqieBookId)
                }
                val mime = if (isEpub) "application/epub+zip" else "text/plain"
                val savedPath = withContext(Dispatchers.IO) {
                    FanqieBookExporter.saveToPublicDownloads(context, fileName, mime, bytes)
                }
                Toast.makeText(context, "导出成功！已保存至：$savedPath", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun rebuildShelfBookToc(context: Context, fanqieBookId: String, bookTitle: String) {
        Toast.makeText(context, "正在重新抓取《$bookTitle》原生分卷目录并同步排版…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val (meta, chapters, _) = FanqieBookExporter.prepareBookData(fanqieBookId)
                val volInfo = if (meta.volumes.isNotEmpty()) "${meta.volumes.size} 个分卷、" else ""
                Toast.makeText(
                    context,
                    "《${meta.title}》原生目录修复完成（${volInfo}共 ${chapters.size} 章）！",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "修复目录失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun checkAllShelfFanqieBooks(context: Context) {
        scope.launch {
            val fanqieBooks = withContext(Dispatchers.IO) {
                appDb.bookDao.all.filter { FanqieLegadoBridge.isFanqieBook(it) }
            }
            if (fanqieBooks.isEmpty()) {
                Toast.makeText(context, "书架中暂无番茄小说，请点击顶部搜索栏添加", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(context, "正在后台检测 ${fanqieBooks.size} 本番茄小说更新状态…", Toast.LENGTH_SHORT).show()
            var hasUpdateBooks = 0
            var totalPending = 0
            val pendingBooks = ArrayList<Book>()
            for (b in fanqieBooks) {
                try {
                    val res = FanqieLegadoBridge.checkBookUpdate(b)
                    if (res.newChaptersCount > 0) {
                        hasUpdateBooks++
                        totalPending += res.newChaptersCount
                        pendingBooks.add(b)
                    }
                } catch (_: Exception) {
                }
            }
            if (hasUpdateBooks > 0) {
                AlertDialog.Builder(context)
                    .setTitle("番茄书籍更新检测完成")
                    .setMessage("共发现 $hasUpdateBooks 本书有未下完或新更新章节（合计 $totalPending 章）。\n是否立即在后台依次断点续下？")
                    .setPositiveButton("全部后台续下") { _, _ ->
                        for (pb in pendingBooks) {
                            val t = FanqieLegadoBridge.parseTag(pb) ?: continue
                            FanqieDownloadManager.startBackgroundDownload(
                                ctx = context,
                                fanqieBookId = t.fanqieBookId,
                                title = pb.name,
                                author = pb.author
                            )
                        }
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            } else {
                Toast.makeText(
                    context,
                    "检测完成：全部 ${fanqieBooks.size} 本番茄小说均已是最新章节！",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
