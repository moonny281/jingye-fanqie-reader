package io.legado.app.eink

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import splitties.init.appCtx

object EinkRefreshHelper {
    private const val PREF_NAME = "eink_refresh_prefs"
    private const val KEY_CLEAN_EVERY_N = "clean_every_n_pages"
    private const val KEY_EPD_MODE = "epd_display_mode"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var turnCounter = 0

    var cleanEveryNPages: Int
        get() = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CLEAN_EVERY_N, 12)
        set(value) {
            appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CLEAN_EVERY_N, value.coerceIn(0, 100))
                .apply()
        }

    var epdModeIndex: Int
        get() = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_EPD_MODE, 0)
        set(value) {
            appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_EPD_MODE, value.coerceIn(0, EpdDisplayMode.entries.lastIndex))
                .apply()
            EpdSettings.currentMode = EpdDisplayMode.entries[value.coerceIn(0, EpdDisplayMode.entries.lastIndex)]
        }

    fun onPageTurned(targetView: View) {
        val threshold = cleanEveryNPages
        if (threshold <= 0) return
        turnCounter++
        if (turnCounter >= threshold) {
            turnCounter = 0
            triggerFullRefresh(targetView)
        }
    }

    /**
     * 执行海信 A7 墨水屏全刷清残影：优先调用系统 EPD 反射接口并配合极短黑白反转闪烁彻底消除墨水粒子残影。
     */
    fun triggerFullRefresh(targetView: View) {
        EpdSettings.applyRefresh(targetView, forceFull = true)
        val root = targetView.rootView as? ViewGroup ?: return
        val overlay = View(targetView.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
        }
        try {
            root.addView(overlay)
            mainHandler.postDelayed({
                overlay.setBackgroundColor(Color.WHITE)
                overlay.invalidate()
            }, 95L)
            mainHandler.postDelayed({
                try {
                    root.removeView(overlay)
                    targetView.invalidate()
                } catch (_: Throwable) {
                }
            }, 190L)
        } catch (_: Throwable) {
            targetView.invalidate()
        }
    }

    /**
     * 原生内嵌墨水屏全刷与残影消除设置弹窗（无棋盘格干扰，阅读页内直接调节）。
     */
    fun showEinkSettingsDialog(context: Context, targetView: View?) {
        val options = arrayOf(
            "立即全刷一次（清除当前屏幕残影）",
            "每 5 页自动全刷（高画质零残影）",
            "每 10 页自动全刷",
            "每 12 页自动全刷（海信 A7 推荐）",
            "每 15 页自动全刷",
            "每 20 页自动全刷（省电均衡）",
            "每 30 页自动全刷",
            "关闭翻页自动全刷（仅手动触发）"
        )
        val values = intArrayOf(-1, 5, 10, 12, 15, 20, 30, 0)
        val curN = cleanEveryNPages
        val currentDesc = if (curN <= 0) "已关闭自动全刷" else "当前：每 $curN 页自动全刷"

        AlertDialog.Builder(context)
            .setTitle("墨水屏全刷与残影控制 ($currentDesc)")
            .setItems(options) { _, which ->
                val v = values[which]
                if (v == -1) {
                    targetView?.let { triggerFullRefresh(it) }
                    Toast.makeText(context, "已执行墨水屏全局清残影刷新", Toast.LENGTH_SHORT).show()
                } else {
                    cleanEveryNPages = v
                    turnCounter = 0
                    val msg = if (v == 0) "已关闭翻页自动全刷" else "已设为每 $v 页自动全刷清残影"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
