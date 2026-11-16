package io.legado.app.fanqie

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ReadBookActivity
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
            // 先精确核验是否真的还有未下载章节，若实际已全部下载完则自动治愈状态，避免无效下载
            scope.launch {
                val res = try {
                    FanqieLegadoBridge.checkBookUpdate(item)
                } catch (_: Exception) {
                    null
                }
                if (res != null && !res.hasPendingDownload()) {
                    Toast.makeText(
                        context,
                        "《${item.name}》经核验已全部下载完毕（共 ${res.localChapters} 章），已自动同步完成状态！",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val realPending = res?.newChaptersCount ?: pending
                Toast.makeText(
                    context,
                    "已启动《${item.name}》后台断点续下（待下 $realPending 章）",
                    Toast.LENGTH_SHORT
                ).show()
                FanqieDownloadManager.startBackgroundDownload(
                    ctx = context,
                    fanqieBookId = tagInfo.fanqieBookId,
                    title = item.name,
                    author = item.author
                )
            }
            return
        }

        val actions = arrayOf(
            "1. 查看一级/二级目录结构（支持折叠与选章跳读）",
            "2. 检测远端新章节更新（精确对账）",
            "3. 导出标准 EPUB（含一级分卷+二级章节树状目录 + 封面）",
            "4. 导出精排 TXT（含一级/二级目录索引 + 精排正文）",
            "5. 重新抓取并修复本书原生一级/二级目录"
        )
        AlertDialog.Builder(context)
            .setTitle("《${item.name}》目录管理与导出")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showBookTwoLevelTocFromId(context, tagInfo.fanqieBookId, item.name)
                    1 -> checkSingleBookUpdate(context, item)
                    2 -> exportShelfBook(context, tagInfo.fanqieBookId, item.name, isEpub = true)
                    3 -> exportShelfBook(context, tagInfo.fanqieBookId, item.name, isEpub = false)
                    4 -> rebuildShelfBookToc(context, tagInfo.fanqieBookId, item.name)
                }
            }
            .setNegativeButton("取消", null)
            .create().also {
                EinkRefreshHelper.applyEinkDialogStyle(it)
                it.show()
            }
    }

    /**
     * 根据书籍 ID 加载元数据与本地缓存，并弹出 1-bit 纯黑白线性【一级分卷 / 二级章节】树状目录预览框。
     */
    fun showBookTwoLevelTocFromId(
        context: Context,
        fanqieBookId: String,
        titleHint: String = "",
        preloadedMeta: FanqieMetadata? = null
    ) {
        scope.launch {
            try {
                val (meta, cacheKeys) = withContext(Dispatchers.IO) {
                    val cleanId = FanqieParser.resolveBookId(fanqieBookId)
                    val loadedMeta = preloadedMeta?.takeIf { it.structuredChapters.isNotEmpty() }
                        ?: FanqieChapterCacheStore.loadMetadata(cleanId)?.takeIf { it.structuredChapters.isNotEmpty() }
                        ?: FanqieLegadoBridge.newLocalCrawler().fetchMetadata(cleanId, forceRefresh = true)
                    val cmap = FanqieChapterCacheStore.loadCachedMap(cleanId)
                    val keys = HashSet<String>(cmap.size * 2)
                    keys.addAll(cmap.keys)
                    cmap.values.forEach { keys.add("idx_${it.index}") }
                    loadedMeta to keys
                }
                showTwoLevelTocDialog(context, meta, cacheKeys)
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "加载《${titleHint.ifBlank { fanqieBookId }}》目录失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 微信读书墨水屏风格 1-bit 纯黑白线性【一级目录（分卷） + 二级目录（章节）】可折叠树状预览弹窗。
     */
    fun showTwoLevelTocDialog(
        context: Context,
        meta: FanqieMetadata,
        cachedKeys: Set<String>
    ) {
        val groups = meta.groupedByVolume()
        if (groups.isEmpty()) {
            Toast.makeText(context, "暂无目录数据", Toast.LENGTH_SHORT).show()
            return
        }

        val dp = context.resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp + 0.5f).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(12.dp(), 10.dp(), 12.dp(), 6.dp())
        }

        val totalChapters = groups.sumOf { it.second.size }
        val cachedChapters = groups.sumOf { (_, list) ->
            list.count { it.cacheKey() in cachedKeys || "idx_${it.index}" in cachedKeys }
        }

        val summaryTv = TextView(context).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            text = "一级分卷：${groups.size} 卷  |  二级章节：共 $totalChapters 章（已缓存 $cachedChapters 章）
点击黑底【一级目录】可展开/收起该卷下的【二级章节】，点击章节可直接跳转阅读："
            setPadding(4.dp(), 2.dp(), 4.dp(), 8.dp())
        }
        rootLayout.addView(summaryTv)

        val scrollView = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        scrollView.addView(listContainer)
        rootLayout.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
            )
        )

        var dialogRef: AlertDialog? = null

        for ((volIdx, pair) in groups.withIndex()) {
            val (vol, chaptersInVol) = pair
            val cachedInVol = chaptersInVol.count {
                it.cacheKey() in cachedKeys || "idx_${it.index}" in cachedKeys
            }

            val volHeader = TextView(context).apply {
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(10.dp(), 9.dp(), 10.dp(), 9.dp())
            }

            val chapterGroupLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                visibility = if (volIdx == 0 || totalChapters <= 80) View.VISIBLE else View.GONE
            }

            fun updateVolHeaderText() {
                val arrow = if (chapterGroupLayout.visibility == View.VISIBLE) "▼" else "▶"
                volHeader.text = "$arrow [一级目录] ${vol.name} （共 ${chaptersInVol.size} 章 · 已下 $cachedInVol 章）"
            }
            updateVolHeaderText()

            volHeader.setOnClickListener {
                chapterGroupLayout.visibility =
                    if (chapterGroupLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                updateVolHeaderText()
                EinkRefreshHelper.scrubAfterOverlayDismiss(rootLayout)
            }

            val volLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (volIdx == 0) 0 else 6.dp()
            }
            listContainer.addView(volHeader, volLp)

            fun populateChapters(showAll: Boolean) {
                chapterGroupLayout.removeAllViews()
                val displayList = if (showAll || chaptersInVol.size <= 120) {
                    chaptersInVol
                } else {
                    chaptersInVol.take(80)
                }

                for (ch in displayList) {
                    val isCached = ch.cacheKey() in cachedKeys || "idx_${ch.index}" in cachedKeys
                    val chRow = TextView(context).apply {
                        setBackgroundResource(R.drawable.bg_eink_border_bottom)
                        setTextColor(Color.BLACK)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = if (isCached) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setPadding(14.dp(), 8.dp(), 10.dp(), 8.dp())
                        val statusBadge = if (isCached) "[已缓存]" else "[待下载]"
                        val wcInfo = if (ch.wordCount > 0) " · ${ch.wordCount}字" else ""
                        text = "├─ [二级] ${ch.title}   $statusBadge$wcInfo"
                        setOnClickListener {
                            dialogRef?.dismiss()
                            jumpToChapterFromPreview(context, meta, ch)
                        }
                    }
                    chapterGroupLayout.addView(
                        chRow,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }

                if (!showAll && chaptersInVol.size > 120) {
                    val moreBtn = TextView(context).apply {
                        setBackgroundResource(R.drawable.bg_eink_button_outline)
                        setTextColor(Color.BLACK)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
                        text = "展开本卷剩余 ${chaptersInVol.size - 80} 章二级目录（共 ${chaptersInVol.size} 章）"
                        setOnClickListener {
                            populateChapters(showAll = true)
                            EinkRefreshHelper.scrubAfterOverlayDismiss(rootLayout)
                        }
                    }
                    val moreLp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(14.dp(), 6.dp(), 14.dp(), 6.dp())
                    }
                    chapterGroupLayout.addView(moreBtn, moreLp)
                }
            }

            populateChapters(showAll = false)
            listContainer.addView(
                chapterGroupLayout,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dlg = AlertDialog.Builder(context)
            .setTitle("《${meta.title}》一级/二级目录结构")
            .setView(rootLayout)
            .setPositiveButton("刷新目录") { _, _ ->
                showBookTwoLevelTocFromId(context, meta.bookId, meta.title, preloadedMeta = null)
            }
            .setNegativeButton("关闭", null)
            .create()
        dialogRef = dlg
        EinkRefreshHelper.applyEinkDialogStyle(dlg, rootLayout)
        dlg.show()
    }

    private fun jumpToChapterFromPreview(
        context: Context,
        meta: FanqieMetadata,
        targetChapter: FanqieChapterItem
    ) {
        Toast.makeText(context, "正在定位至：${targetChapter.title}…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val book = withContext(Dispatchers.IO) {
                    val b = FanqieLegadoBridge.quickStartRead(
                        fanqieBookId = meta.bookId,
                        titleHint = meta.title,
                        authorHint = meta.author,
                        coverHint = meta.coverUrl,
                        introHint = meta.intro
                    )
                    val chapters = appDb.bookChapterDao.getChapterList(b.bookUrl)
                    val targetTocIdx = chapters.indexOfFirst {
                        !it.isVolume && (it.url == targetChapter.itemId || it.title.contains(targetChapter.title))
                    }
                    if (targetTocIdx >= 0) {
                        b.durChapterIndex = targetTocIdx
                        b.durChapterPos = 0
                        b.durChapterTitle = chapters[targetTocIdx].title
                        appDb.bookDao.update(b)
                    }
                    b
                }
                val intent = Intent(context, ReadBookActivity::class.java).apply {
                    putExtra("bookUrl", book.bookUrl)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "打开章节失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkSingleBookUpdate(context: Context, item: Book) {
        Toast.makeText(context, "正在精确对账《${item.name}》远端目录与本地缓存…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val res = FanqieLegadoBridge.checkBookUpdate(item)
                if (res.hasPendingDownload()) {
                    val actionText = if (res.isPartialResume) "断点续下" else "后台更新"
                    AlertDialog.Builder(context)
                        .setTitle("《${res.title}》发现待下载章节")
                        .setMessage(
                            "本地已缓存：${res.localChapters} 章
" +
                                "远端可下总章：${res.remoteChapters} 章（待下 ${res.newChaptersCount} 章）
" +
                                "最新章节：${res.latestChapterTitle.ifBlank { "未知" }}

" +
                                "是否立即启动后台增量续下？"
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
                        .create().also {
                            EinkRefreshHelper.applyEinkDialogStyle(it)
                            it.show()
                        }
                } else {
                    Toast.makeText(
                        context,
                        "《${res.title}》已全部下载完成，无新增章节（共 ${res.remoteChapters} 章 · 最新：${res.latestChapterTitle.ifBlank { "无" }}）",
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
        Toast.makeText(context, "正在抓取原生一级/二级目录并打包导出《$bookTitle》($fmt)…", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(context, "正在重新抓取《$bookTitle》原生一级分卷与二级章节目录…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val (meta, chapters, _) = FanqieBookExporter.prepareBookData(fanqieBookId)
                val volInfo = if (meta.volumes.isNotEmpty()) "${meta.volumes.size} 个一级分卷、" else ""
                Toast.makeText(
                    context,
                    "《${meta.title}》多级目录修复完成（${volInfo}共 ${chapters.size} 章二级章节）！",
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
            Toast.makeText(context, "正在后台精确对账 ${fanqieBooks.size} 本番茄小说目录与缓存状态…", Toast.LENGTH_SHORT).show()
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
                    .setMessage("共发现 $hasUpdateBooks 本书有未下完或新更新章节（合计 $totalPending 章）。
是否立即在后台依次断点续下？")
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
                    .create().also {
                        EinkRefreshHelper.applyEinkDialogStyle(it)
                        it.show()
                    }
            } else {
                Toast.makeText(
                    context,
                    "检测完成：全部 ${fanqieBooks.size} 本番茄小说均已完整下载，无新增章节！",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
