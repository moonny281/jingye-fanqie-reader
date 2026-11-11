package app.jingye.reader.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import app.jingye.reader.ui.reader.ReaderActivity
import app.jingye.reader.ui.shelf.ShelfActivity
import java.util.concurrent.Executors

class FanqieDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "jingye_download_channel"
        const val NOTIF_ID_PROGRESS = 4101
        const val NOTIF_ID_COMPLETE = 4102

        const val ACTION_START = "app.jingye.reader.action.START_DOWNLOAD"
        const val ACTION_PAUSE = "app.jingye.reader.action.PAUSE_DOWNLOAD"

        const val EXTRA_FANQIE_BOOK_ID = "fanqie_book_id"
        const val EXTRA_BOOK_TITLE = "book_title"
        const val EXTRA_BOOK_AUTHOR = "book_author"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var nm: NotificationManager

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_PAUSE) {
            val bookId = intent.getStringExtra(EXTRA_FANQIE_BOOK_ID)
            FanqieDownloadManager.requestPause(this, bookId)
            return START_NOT_STICKY
        }

        val fanqieBookId = intent?.getStringExtra(EXTRA_FANQIE_BOOK_ID).orEmpty()
        val titleHint = intent?.getStringExtra(EXTRA_BOOK_TITLE).orEmpty().ifBlank { "番茄小说" }
        val authorHint = intent?.getStringExtra(EXTRA_BOOK_AUTHOR).orEmpty()

        if (fanqieBookId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotif = buildProgressNotification(
            fanqieBookId = fanqieBookId,
            title = "正在准备下载《$titleHint》",
            content = "连接番茄小说章节源…",
            percent = 0,
            indeterminate = true
        )
        try {
            startForeground(NOTIF_ID_PROGRESS, initialNotif)
        } catch (_: Exception) {
            nm.notify(NOTIF_ID_PROGRESS, initialNotif)
        }

        executor.execute {
            runDownloadJob(fanqieBookId, titleHint, authorHint)
        }

        return START_NOT_STICKY
    }

    private fun runDownloadJob(fanqieBookId: String, titleHint: String, authorHint: String) {
        val app = application as JingyeApp
        var lastNotifTime = 0L

        try {
            val savedBook = app.fanqie.downloadOrResumeSync(
                fanqieBookId = fanqieBookId,
                titleHint = titleHint,
                authorHint = authorHint,
                shouldStop = { FanqieDownloadManager.shouldStop(fanqieBookId) },
                onProgress = { prog ->
                    val bookTitle = prog.title.ifBlank { titleHint }
                    val stateStatus = when (prog.status) {
                        "done" -> "done"
                        "paused" -> "paused"
                        "error" -> "error"
                        else -> "running"
                    }
                    FanqieDownloadManager.updateState(
                        DownloadTaskState(
                            fanqieBookId = fanqieBookId,
                            title = bookTitle,
                            author = authorHint,
                            status = stateStatus,
                            current = prog.current,
                            total = prog.total,
                            percent = prog.percent,
                            chapterTitle = prog.chapterTitle,
                            latestChapterTitle = prog.latestChapterTitle,
                            message = prog.message
                        )
                    )

                    val now = System.currentTimeMillis()
                    if (now - lastNotifTime >= 350L || prog.status == "done" || prog.status == "paused") {
                        lastNotifTime = now
                        val notifTitle = if (prog.resumedFromCount > 0) {
                            "断点续下《$bookTitle》 (${prog.percent}%)"
                        } else {
                            "正在下载《$bookTitle》 (${prog.percent}%)"
                        }
                        val notifBody = "${prog.current}/${prog.total} 章 · ${prog.chapterTitle.ifBlank { prog.message }}"
                        val n = buildProgressNotification(
                            fanqieBookId = fanqieBookId,
                            title = notifTitle,
                            content = notifBody,
                            percent = prog.percent,
                            indeterminate = false
                        )
                        nm.notify(NOTIF_ID_PROGRESS, n)
                    }
                }
            )

            val wasPaused = FanqieDownloadManager.shouldStop(fanqieBookId) || savedBook.isPartialDownload
            val finalState = FanqieDownloadManager.getState(fanqieBookId)
            val finalMsg = if (wasPaused) {
                "已保存断点（${savedBook.totalChapters}/${savedBook.remoteTotalChapters} 章）· 最新：${savedBook.latestChapterTitle.ifBlank { "可随时续下" }}"
            } else {
                "下载完成（共 ${savedBook.totalChapters} 章）· 最新：${savedBook.latestChapterTitle.ifBlank { "已是最新" }}"
            }

            FanqieDownloadManager.updateState(
                DownloadTaskState(
                    fanqieBookId = fanqieBookId,
                    title = savedBook.title,
                    author = savedBook.author,
                    status = if (wasPaused) "paused" else "done",
                    current = savedBook.totalChapters,
                    total = maxOf(savedBook.remoteTotalChapters, savedBook.totalChapters),
                    percent = if (savedBook.remoteTotalChapters > 0) {
                        (savedBook.totalChapters * 100) / savedBook.remoteTotalChapters
                    } else 100,
                    chapterTitle = finalState?.chapterTitle.orEmpty(),
                    latestChapterTitle = savedBook.latestChapterTitle,
                    message = finalMsg,
                    localBookId = savedBook.id
                )
            )

            val finishNotif = buildCompletionNotification(
                title = if (wasPaused) "《${savedBook.title}》断点已保存" else "《${savedBook.title}》下载完成",
                content = finalMsg,
                localBookId = savedBook.id
            )
            nm.notify(NOTIF_ID_COMPLETE, finishNotif)
        } catch (e: Exception) {
            val errMsg = e.message ?: "下载异常中断"
            FanqieDownloadManager.updateState(
                DownloadTaskState(
                    fanqieBookId = fanqieBookId,
                    title = titleHint,
                    author = authorHint,
                    status = "error",
                    current = 0,
                    total = 100,
                    percent = 0,
                    chapterTitle = "",
                    latestChapterTitle = "",
                    message = "下载中断：$errMsg（已下章节已自动保存断点）"
                )
            )
            val errNotif = buildCompletionNotification(
                title = "《$titleHint》下载中断",
                content = "$errMsg（可在书架点击断点续下）",
                localBookId = null
            )
            nm.notify(NOTIF_ID_COMPLETE, errNotif)
        } finally {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "小说后台下载与更新",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在状态栏显示番茄小说后台下载、断点续下与章节更新进度"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildProgressNotification(
        fanqieBookId: String,
        title: String,
        content: String,
        percent: Int,
        indeterminate: Boolean
    ): Notification {
        val openShelfIntent = Intent(this, ShelfActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(this, 1, openShelfIntent, piFlags)

        val pauseIntent = Intent(this, FanqieDownloadService::class.java).apply {
            action = ACTION_PAUSE
            putExtra(EXTRA_FANQIE_BOOK_ID, fanqieBookId)
        }
        val pausePi = PendingIntent.getService(this, 2, pauseIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setContentIntent(contentPi)
            .addAction(0, "暂停并存断点", pausePi)
            .build()
    }

    private fun buildCompletionNotification(
        title: String,
        content: String,
        localBookId: String?
    ): Notification {
        val targetIntent = if (!localBookId.isNullOrBlank()) {
            Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_ID, localBookId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(this, ShelfActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(this, 3, targetIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .build()
    }
}
