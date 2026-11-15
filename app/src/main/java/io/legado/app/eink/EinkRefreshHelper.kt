package io.legado.app.eink

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.postEvent
import splitties.init.appCtx
import kotlin.math.roundToInt

/**
 * 墨水屏深度重构核心控制器：
 * 1. 局部刷新清残影（Partial Refresh Anti-Ghosting，无全屏黑闪，仅针对正文区域做微白场擦除与区域重绘）
 * 2. 全局全刷清残影（Full Refresh Anti-Ghosting，黑白反转深层复位墨水粒子）
 * 3. 阅读进度/页眉页脚一键开关（关闭后释放 100% 屏幕垂直高度给正文）
 * 4. 自适应屏幕分辨率排版计算器（根据屏幕物理像素宽高、DPI 与长宽比动态分配最佳正文显示面积）
 */
object EinkRefreshHelper {
    private const val PREF_NAME = "eink_refresh_prefs"
    private const val KEY_CLEAN_EVERY_N = "clean_every_n_pages"
    private const val KEY_PARTIAL_EVERY_N = "partial_clean_every_n_pages"
    private const val KEY_EPD_MODE = "epd_display_mode"
    private const val KEY_SHOW_PROGRESS_BAR = "show_read_progress_bar_v042"
    private const val KEY_ADAPTIVE_RESOLUTION = "adaptive_screen_resolution_v042"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var turnCounter = 0
    private var partialTurnCounter = 0

    data class AdaptiveMarginsDp(
        val paddingLeftDp: Int,
        val paddingRightDp: Int,
        val paddingTopDp: Int,
        val paddingBottomDp: Int,
        val headerPadHorizontalDp: Int,
        val headerPadTopDp: Int,
        val headerPadBottomDp: Int,
        val footerPadHorizontalDp: Int,
        val footerPadTopDp: Int,
        val footerPadBottomDp: Int
    )

    private val prefs
        get() = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 每 N 页自动执行一次全局全刷清残影（0 表示关闭自动全刷）
     */
    var cleanEveryNPages: Int
        get() = prefs.getInt(KEY_CLEAN_EVERY_N, 15)
        set(value) {
            prefs.edit().putInt(KEY_CLEAN_EVERY_N, value.coerceIn(0, 100)).apply()
        }

    /**
     * 每 N 页自动执行一次无黑闪局部清残影微擦除（0 表示仅基础重绘，1 表示每页局刷，3 表示每 3 页局刷微擦除）
     */
    var partialCleanEveryNPages: Int
        get() = prefs.getInt(KEY_PARTIAL_EVERY_N, 3)
        set(value) {
            prefs.edit().putInt(KEY_PARTIAL_EVERY_N, value.coerceIn(0, 50)).apply()
        }

    var epdModeIndex: Int
        get() = prefs.getInt(KEY_EPD_MODE, 0)
        set(value) {
            val idx = value.coerceIn(0, EpdDisplayMode.entries.lastIndex)
            prefs.edit().putInt(KEY_EPD_MODE, idx).apply()
            EpdSettings.currentMode = EpdDisplayMode.entries[idx]
        }

    /**
     * 是否显示阅读进度与状态信息栏（可随时关闭以获得 100% 纯正文全屏阅读面积）
     */
    var showReadProgressBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PROGRESS_BAR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_PROGRESS_BAR, value).apply()
        }

    /**
     * 是否开启自适应屏幕分辨率分配阅读显示面积（默认开启）
     */
    var adaptiveScreenResolution: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_RESOLUTION, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ADAPTIVE_RESOLUTION, value).apply()
        }

    /**
     * 根据设备实际屏幕分辨率（像素宽高、密度、纵横比）自适应分配最佳正文中边距与状态栏边距，最大化阅读显示面积
     */
    fun computeAdaptiveMargins(widthPx: Int, heightPx: Int, density: Float): AdaptiveMarginsDp {
        val safeDensity = if (density <= 0f) 2.75f else density
        val widthDp = widthPx / safeDensity
        val heightDp = heightPx / safeDensity
        val aspectRatio = if (widthPx > 0) heightPx.toFloat() / widthPx.toFloat() else 1.77f
        val showBar = showReadProgressBar

        // 根据屏幕横向宽度自适应分配左右留白：窄屏手机墨水屏采用紧凑窄边距，平板墨水屏采用适度呼吸边距
        val horizDp = when {
            widthDp <= 360f || aspectRatio >= 1.95f -> 10 // 20:9 海信阅读手机等紧凑窄屏，最大化单行字数
            widthDp <= 440f || aspectRatio >= 1.70f -> 12 // 常规 6~6.7 寸墨水屏
            widthDp <= 600f -> 15                         // 7~7.8 寸墨水屏阅读器
            else -> (widthDp * 0.028f).roundToInt().coerceIn(16, 26) // 10.3 寸以上大屏墨水屏
        }

        // 根据纵向高度与是否开启底部进度栏自适应分配上下边距：关闭进度栏时上下仅留 4dp 极窄安全边
        val topDp = when {
            !showBar -> if (heightDp <= 720f) 4 else 6
            heightDp <= 680f -> 3
            else -> 4
        }
        val bottomDp = when {
            !showBar -> if (heightDp <= 720f) 4 else 6
            heightDp <= 680f -> 2
            else -> 3
        }

        return AdaptiveMarginsDp(
            paddingLeftDp = horizDp,
            paddingRightDp = horizDp,
            paddingTopDp = topDp,
            paddingBottomDp = bottomDp,
            headerPadHorizontalDp = horizDp,
            headerPadTopDp = 2,
            headerPadBottomDp = 2,
            footerPadHorizontalDp = horizDp,
            footerPadTopDp = 2,
            footerPadBottomDp = 3
        )
    }

    /**
     * 一键切换阅读进度与页眉页脚显示状态，并立即触发全页重新排版以释放或恢复垂直阅读面积
     */
    fun toggleReadProgressBar(context: Context? = null): Boolean {
        val nextState = !showReadProgressBar
        showReadProgressBar = nextState
        applyProgressBarStateToConfig(nextState)
        ChapterProvider.upStyle()
        ChapterProvider.upLayout()
        postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2, 5, 6, 8, 10))
        context?.let {
            val msg = if (nextState) {
                "已开启底部精简阅读进度栏"
            } else {
                "已关闭阅读进度栏（已释放 100% 全屏阅读面积）"
            }
            Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
        }
        return nextState
    }

    fun applyProgressBarStateToConfig(showProgress: Boolean) {
        ReadBookConfig.configList.forEach { cfg ->
            // 默认隐藏顶部页眉，避免顶部与底部双重占用垂直阅读面积
            cfg.headerMode = 2
            cfg.showHeaderLine = false
            cfg.footerMode = if (showProgress) 0 else 1
            cfg.showFooterLine = false
        }
        ReadBookConfig.durConfig.apply {
            headerMode = 2
            showHeaderLine = false
            footerMode = if (showProgress) 0 else 1
            showFooterLine = false
        }
        ReadTipConfig.headerMode = 2
        ReadTipConfig.footerMode = if (showProgress) 0 else 1
        ReadBookConfig.save()
    }

    /**
     * 翻页时调用的双级墨水屏刷新调度器：
     * - 达到全刷阈值时执行全局黑白反转全刷
     * - 未达到全刷阈值时执行区域 EPD 局部刷新 + 周期性无黑闪局部白场清残影
     */
    fun onPageTurned(targetView: View, contentRect: Rect? = null) {
        val fullThreshold = cleanEveryNPages
        if (fullThreshold > 0) {
            turnCounter++
            if (turnCounter >= fullThreshold) {
                turnCounter = 0
                partialTurnCounter = 0
                triggerFullRefresh(targetView)
                return
            }
        }

        val partialThreshold = partialCleanEveryNPages
        if (partialThreshold > 0) {
            partialTurnCounter++
            if (partialTurnCounter >= partialThreshold) {
                partialTurnCounter = 0
                triggerPartialRefresh(targetView, contentRect, showToast = false)
                return
            }
        }

        // 基础每页区域 EPD 局刷
        EpdSettings.applyPartialRefresh(targetView, contentRect)
    }

    /**
     * 执行墨水屏【局部刷新清残影】（无全屏黑闪）：
     * 仅针对阅读正文区域执行一次 35ms 纯白微擦除 + 局部 EPD 区域重绘，瞬间消除文字边缘灰阶残影且不晃眼。
     */
    fun triggerPartialRefresh(
        targetView: View,
        dirtyRect: Rect? = null,
        showToast: Boolean = false
    ) {
        val rect = dirtyRect ?: Rect(
            ChapterProvider.paddingLeft.coerceAtLeast(0),
            ChapterProvider.paddingTop.coerceAtLeast(0),
            ChapterProvider.visibleRight.takeIf { it > 0 } ?: targetView.width,
            ChapterProvider.visibleBottom.takeIf { it > 0 } ?: targetView.height
        )
        EpdSettings.applyPartialRefresh(targetView, rect)

        val root = targetView.rootView as? ViewGroup
        if (root != null && rect.width() > 0 && rect.height() > 0) {
            val loc = IntArray(2)
            targetView.getLocationInWindow(loc)
            val scrubOverlay = View(targetView.context).apply {
                layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = loc[0] + rect.left
                    topMargin = loc[1] + rect.top
                }
                setBackgroundColor(Color.WHITE)
                isClickable = false
                isFocusable = false
            }
            try {
                root.addView(scrubOverlay)
                EpdSettings.applyPartialRefresh(scrubOverlay)
                mainHandler.postDelayed({
                    try {
                        root.removeView(scrubOverlay)
                        EpdSettings.applyPartialRefresh(targetView, rect)
                    } catch (_: Throwable) {
                        targetView.postInvalidate()
                    }
                }, 38L)
            } catch (_: Throwable) {
                targetView.postInvalidate()
            }
        } else {
            targetView.postInvalidate()
        }

        if (showToast) {
            Toast.makeText(targetView.context, "已执行正文区域局部刷新清残影", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 执行墨水屏【全局全刷清残影】：调用系统 EPD 全刷接口并配合极短黑白反转闪烁彻底消除全屏墨水粒子残影。
     */
    fun triggerFullRefresh(targetView: View, showToast: Boolean = false) {
        EpdSettings.applyRefresh(targetView, forceFull = true)
        val root = targetView.rootView as? ViewGroup ?: run {
            targetView.invalidate()
            return
        }
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
            }, 85L)
            mainHandler.postDelayed({
                try {
                    root.removeView(overlay)
                    EpdSettings.applyRefresh(targetView, forceFull = true)
                    targetView.invalidate()
                } catch (_: Throwable) {
                }
            }, 170L)
        } catch (_: Throwable) {
            targetView.invalidate()
        }
        if (showToast) {
            Toast.makeText(targetView.context, "已执行墨水屏全局全刷清残影", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 原生内嵌墨水屏综合控制面板（支持局部刷新清残影、全局全刷、阅读进度开关、自适应屏幕分辨率排版与全刷频率调节）
     */
    fun showEinkSettingsDialog(
        context: Context,
        targetView: View?,
        onStateChanged: (() -> Unit)? = null
    ) {
        val dm = context.resources.displayMetrics
        val progressState = if (showReadProgressBar) "开启（底部精简进度）" else "已关闭（100%全屏正文）"
        val adaptiveState = if (adaptiveScreenResolution) {
            "开启（已适配 ${dm.widthPixels}×${dm.heightPixels}）"
        } else {
            "手动边距模式"
        }
        val curPartial = partialCleanEveryNPages
        val partialDesc = when (curPartial) {
            0 -> "仅基础局刷"
            1 -> "每页局刷清残影"
            else -> "每 $curPartial 页局刷清残影"
        }
        val curFull = cleanEveryNPages
        val fullDesc = if (curFull <= 0) "已关闭自动全刷" else "每 $curFull 页自动全刷"

        val options = arrayOf(
            "⚡ 立即局部刷新清残影（无黑闪·仅刷正文区）",
            "⬛ 立即全局全刷清残影（黑白反转深层消残影）",
            "📖 阅读进度栏开关：$progressState",
            "📐 自适应屏幕分辨率分配阅读面积：$adaptiveState",
            "🔄 翻页局部清残影：切换（当前：$partialDesc）",
            "⏱ 每 10 页自动全局全刷",
            "⏱ 每 15 页自动全局全刷（推荐）",
            "⏱ 每 25 页自动全局全刷（省电）",
            "⏱ 关闭翻页自动全局全刷（仅保留局刷/手动）"
        )

        AlertDialog.Builder(context)
            .setTitle("墨水屏刷新与阅读面积 ($fullDesc · $partialDesc)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> targetView?.let { triggerPartialRefresh(it, showToast = true) }
                    1 -> targetView?.let { triggerFullRefresh(it, showToast = true) }
                    2 -> {
                        toggleReadProgressBar(context)
                        onStateChanged?.invoke()
                    }
                    3 -> {
                        adaptiveScreenResolution = !adaptiveScreenResolution
                        ChapterProvider.upStyle()
                        ChapterProvider.upLayout()
                        postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5, 6, 8, 10))
                        onStateChanged?.invoke()
                        val msg = if (adaptiveScreenResolution) {
                            "已开启自适应屏幕分辨率（${dm.widthPixels}×${dm.heightPixels}）最大化阅读面积"
                        } else {
                            "已切换为手动边距设置模式"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        partialCleanEveryNPages = when (partialCleanEveryNPages) {
                            3 -> 1
                            1 -> 5
                            5 -> 0
                            else -> 3
                        }
                        partialTurnCounter = 0
                        val pMsg = when (partialCleanEveryNPages) {
                            0 -> "已设为仅基础重绘（关闭周期局刷微擦除）"
                            1 -> "已设为每翻 1 页自动局部刷新清残影"
                            else -> "已设为每翻 $partialCleanEveryNPages 页自动局部刷新清残影"
                        }
                        Toast.makeText(context, pMsg, Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        cleanEveryNPages = 10
                        turnCounter = 0
                        Toast.makeText(context, "已设为每 10 页自动全局全刷清残影", Toast.LENGTH_SHORT).show()
                    }
                    6 -> {
                        cleanEveryNPages = 15
                        turnCounter = 0
                        Toast.makeText(context, "已设为每 15 页自动全局全刷清残影", Toast.LENGTH_SHORT).show()
                    }
                    7 -> {
                        cleanEveryNPages = 25
                        turnCounter = 0
                        Toast.makeText(context, "已设为每 25 页自动全局全刷清残影", Toast.LENGTH_SHORT).show()
                    }
                    8 -> {
                        cleanEveryNPages = 0
                        turnCounter = 0
                        Toast.makeText(context, "已关闭自动全局全刷，仅保留局部刷新", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
