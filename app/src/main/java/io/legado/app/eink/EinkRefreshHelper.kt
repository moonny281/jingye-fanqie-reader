package io.legado.app.eink

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import splitties.init.appCtx

object EinkRefreshHelper {
    private const val PREF_EINK_EVERY_N = "eink_clean_every_n_pages"
    private val handler = Handler(Looper.getMainLooper())
    private var pageCounter = 0

    var cleanEveryNPages: Int
        get() = appCtx.getSharedPreferences("eink_prefs", Context.MODE_PRIVATE)
            .getInt(PREF_EINK_EVERY_N, 12)
        set(value) {
            appCtx.getSharedPreferences("eink_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_EINK_EVERY_N, value.coerceIn(0, 60))
                .apply()
        }

    fun onPageTurned(targetView: View) {
        val everyN = cleanEveryNPages
        if (everyN <= 0) return
        pageCounter++
        if (pageCounter >= everyN) {
            pageCounter = 0
            triggerFullRefresh(targetView)
        }
    }

    /**
     * 触发海信墨水屏黑白闪烁全刷以彻底消除残影
     */
    fun triggerFullRefresh(targetView: View) {
        val parent = targetView as? ViewGroup ?: (targetView.parent as? ViewGroup)
        if (parent == null) {
            targetView.invalidate()
            return
        }
        val overlay = View(targetView.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        try {
            parent.addView(overlay)
            handler.postDelayed({
                overlay.setBackgroundColor(Color.WHITE)
                overlay.invalidate()
            }, 110L)
            handler.postDelayed({
                try {
                    parent.removeView(overlay)
                } catch (_: Throwable) {
                }
                targetView.invalidate()
            }, 220L)
        } catch (_: Throwable) {
            targetView.invalidate()
        }
    }
}
