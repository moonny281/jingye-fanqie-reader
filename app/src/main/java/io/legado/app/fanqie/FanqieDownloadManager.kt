package io.legado.app.fanqie

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class DownloadTaskState(
    val fanqieBookId: String,
    val title: String,
    val author: String,
    val status: String, // "running" | "paused" | "done" | "error"
    val current: Int,
    val total: Int,
    val percent: Int,
    val chapterTitle: String,
    val latestChapterTitle: String,
    val message: String,
    val localBookUrl: String? = null
) {
    val isRunning: Boolean get() = status == "running" || status == "start" || status == "downloading"
}

object FanqieDownloadManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<String, DownloadTaskState>()
    private val stopFlags = ConcurrentHashMap<String, Boolean>()
    private val listeners = CopyOnWriteArraySet<(DownloadTaskState?) -> Unit>()

    @Volatile
    private var latestActiveBookId: String? = null

    fun startBackgroundDownload(
        ctx: Context,
        fanqieBookId: String,
        title: String,
        author: String = "",
        coverUrl: String = ""
    ) {
        if (fanqieBookId.isBlank()) return
        stopFlags[fanqieBookId] = false
        latestActiveBookId = fanqieBookId

        val initialState = DownloadTaskState(
            fanqieBookId = fanqieBookId,
            title = title.ifBlank { "番茄小说" },
            author = author,
            status = "running",
            current = 0,
            total = 100,
            percent = 0,
            chapterTitle = "正在连接后台下载服务…",
            latestChapterTitle = "",
            message = "已启动后台下载《${title.ifBlank { fanqieBookId }}》，可在系统状态栏查看进度"
        )
        updateState(initialState)

        val intent = Intent(ctx, FanqieDownloadService::class.java).apply {
            action = FanqieDownloadService.ACTION_START
            putExtra(FanqieDownloadService.EXTRA_FANQIE_BOOK_ID, fanqieBookId)
            putExtra(FanqieDownloadService.EXTRA_BOOK_TITLE, title)
            putExtra(FanqieDownloadService.EXTRA_BOOK_AUTHOR, author)
            putExtra(FanqieDownloadService.EXTRA_BOOK_COVER, coverUrl)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (_: Exception) {
            ctx.startService(intent)
        }
    }

    fun requestPause(ctx: Context, fanqieBookId: String? = latestActiveBookId) {
        val targetId = fanqieBookId ?: return
        stopFlags[targetId] = true
        states[targetId]?.let { cur ->
            if (cur.isRunning) {
                updateState(
                    cur.copy(
                        message = "正在保存当前批次断点，请稍候…"
                    )
                )
            }
        }
    }

    fun shouldStop(fanqieBookId: String): Boolean = stopFlags[fanqieBookId] == true

    fun getState(fanqieBookId: String): DownloadTaskState? = states[fanqieBookId]

    /**
     * 彻底清除指定书籍的内存下载状态与停止标志（书籍被删除或重置重下时调用）
     */
    fun clearState(fanqieBookId: String) {
        if (fanqieBookId.isBlank()) return
        stopFlags[fanqieBookId] = true
        states.remove(fanqieBookId)
        if (latestActiveBookId == fanqieBookId) {
            latestActiveBookId = null
        }
        notifyListeners(null)
    }

    fun getActiveOrLatestState(): DownloadTaskState? {
        val running = states.values.firstOrNull { it.isRunning }
        if (running != null) return running
        val id = latestActiveBookId ?: return null
        return states[id]
    }

    internal fun updateState(state: DownloadTaskState) {
        states[state.fanqieBookId] = state
        latestActiveBookId = state.fanqieBookId
        notifyListeners(state)
    }

    private fun notifyListeners(state: DownloadTaskState?) {
        mainHandler.post {
            for (listener in listeners) {
                listener(state)
            }
        }
    }

    fun addListener(listener: (DownloadTaskState?) -> Unit) {
        listeners.add(listener)
        listener(getActiveOrLatestState())
    }

    fun removeListener(listener: (DownloadTaskState?) -> Unit) {
        listeners.remove(listener)
    }
}
