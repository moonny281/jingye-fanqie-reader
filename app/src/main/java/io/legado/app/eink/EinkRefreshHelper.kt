package io.legado.app.eink

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.activity
import io.legado.app.utils.postEvent
import splitties.init.appCtx
import kotlin.math.roundToInt

/**
 * 墨水屏深度去残影核心控制器 (v0.4.3)：
 * 1. 画布级内联双相消残影脉冲（直接在 ContentTextView 与 DecorView 顶层覆层执行黑/白波形饱和驱动，彻底解决普通 View 叠加被跳帧导致残影残留的问题）
 * 2. 翻页每页自动执行「白场微擦除脉冲（White-Blanking Pulse）」，切断上一页黑字像素残留
 * 3. 周期性 + 弹窗/菜单关闭自动执行「黑->白双相波形复位全刷（Black-White Waveform Reset）」，彻底消除灰阶残影与弹窗边框残影
 * 4. 阅读进度/页眉页脚一键开关与分辨率自适应阅读面积分配
 */
object EinkRefreshHelper {
    private const val PREF_NAME = "eink_refresh_prefs"
    private const val KEY_CLEAN_EVERY_N = "clean_every_n_pages_v043"
    private const val KEY_PARTIAL_EVERY_N = "partial_clean_every_n_pages_v043"
    private const val KEY_EPD_MODE = "epd_display_mode"
    private const val KEY_SHOW_PROGRESS_BAR = "show_read_progress_bar_v042"
    private const val KEY_ADAPTIVE_RESOLUTION = "adaptive_screen_resolution_v042"
    private const val OVERLAY_TAG = "eink_top_flash_overlay_v043"

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

    /**
     * 专用于墨水屏波形复位的顶层覆层视图（强制 Z 轴置顶，直接绘制纯黑/纯白帧）
     */
    private class EinkWaveformOverlayView(context: Context) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        var phaseColor: Int = Color.TRANSPARENT
            set(value) {
                field = value
                invalidate()
            }

        init {
            isClickable = false
            isFocusable = false
            translationZ = 9999f
            elevation = 9999f
        }

        override fun onDraw(canvas: Canvas) {
            if (phaseColor != Color.TRANSPARENT) {
                paint.color = phaseColor
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    private val prefs
        get() = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 每 N 页自动执行一次全局黑白反转深层清残影（默认每 8 页全刷一次，设为 0 关闭自动全刷）
     */
    var cleanEveryNPages: Int
        get() = prefs.getInt(KEY_CLEAN_EVERY_N, 8)
        set(value) {
            prefs.edit().putInt(KEY_CLEAN_EVERY_N, value.coerceIn(0, 100)).apply()
        }

    /**
     * 每 N 页自动执行一次无黑闪局部白场微擦除（默认 1 = 每翻 1 页均执行白场微擦除，彻底切断残影累积）
     */
    var partialCleanEveryNPages: Int
        get() = prefs.getInt(KEY_PARTIAL_EVERY_N, 1)
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

    fun computeAdaptiveMargins(widthPx: Int, heightPx: Int, density: Float): AdaptiveMarginsDp {
        val safeDensity = if (density <= 0f) 2.75f else density
        val widthDp = widthPx / safeDensity
        val heightDp = heightPx / safeDensity
        val aspectRatio = if (widthPx > 0) heightPx.toFloat() / widthPx.toFloat() else 1.77f
        val showBar = showReadProgressBar

        val horizDp = when {
            widthDp <= 360f || aspectRatio >= 1.95f -> 10
            widthDp <= 440f || aspectRatio >= 1.70f -> 12
            widthDp <= 600f -> 15
            else -> (widthDp * 0.028f).roundToInt().coerceIn(16, 26)
        }

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

    fun toggleReadProgressBar(context: Context? = null): Boolean {
        val nextState = !showReadProgressBar
        showReadProgressBar = nextState
        applyProgressBarStateToConfig(nextState)
        ChapterProvider.upStyle()
        ChapterProvider.upLayout()
        postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2, 5, 6, 8, 10))
        context?.let {
            val msg = if (nextState) {
                "已开启底部线性阅读进度栏"
            } else {
                "已关闭阅读进度栏（释放 100% 全屏阅读面积）"
            }
            Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
        }
        return nextState
    }

    fun applyProgressBarStateToConfig(showProgress: Boolean) {
        ReadBookConfig.configList.forEach { cfg ->
            cfg.headerMode = 2
            cfg.showHeaderLine = false
            cfg.footerMode = if (showProgress) 0 else 1
            cfg.showFooterLine = showProgress
        }
        ReadBookConfig.durConfig.apply {
            headerMode = 2
            showHeaderLine = false
            footerMode = if (showProgress) 0 else 1
            showFooterLine = showProgress
        }
        ReadTipConfig.headerMode = 2
        ReadTipConfig.footerMode = if (showProgress) 0 else 1
        ReadBookConfig.save()
    }

    /**
     * 获取或挂载当前窗口 DecorView 顶层的墨水屏波形覆层
     */
    private fun getOrAttachOverlay(targetView: View): EinkWaveformOverlayView? {
        val decor = (targetView.activity?.window?.decorView as? ViewGroup)
            ?: (targetView.rootView as? ViewGroup)
            ?: return null
        val existing = decor.findViewWithTag<EinkWaveformOverlayView>(OVERLAY_TAG)
        if (existing != null) {
            existing.bringToFront()
            return existing
        }
        val overlay = EinkWaveformOverlayView(targetView.context).apply {
            tag = OVERLAY_TAG
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        return try {
            decor.addView(overlay)
            overlay.bringToFront()
            overlay
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 翻页时调用的双级墨水屏去残影调度器：
     * - 达到全刷阈值（默认每 8 页）时执行双相黑->白深度全刷
     * - 其余每一页执行画布内联白场擦除（White-Blanking Pulse），彻底防止旧字迹残留
     */
    fun onPageTurned(targetView: View, contentRect: Rect? = null) {
        val fullThreshold = cleanEveryNPages
        if (fullThreshold > 0) {
            turnCounter++
            if (turnCounter >= fullThreshold) {
                turnCounter = 0
                partialTurnCounter = 0
                triggerFullRefresh(targetView, showToast = false)
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

        EpdSettings.applyPartialRefresh(targetView, contentRect)
    }

    /**
     * 执行墨水屏【局部刷新清残影】（无黑闪白场脉冲）：
     * 若目标为 ContentTextView，直接在画布层执行 60ms 纯白帧擦除 + 真实文字重绘；
     * 同时驱动顶层白场覆层与硬件区域 EPD 刷新，确保 E-Ink 控制器完整扫描一帧纯白波形后再渲染新字。
     */
    fun triggerPartialRefresh(
        targetView: View,
        dirtyRect: Rect? = null,
        showToast: Boolean = false
    ) {
        val rect = dirtyRect ?: Rect(0, 0, targetView.width, targetView.height)
        val contentView = targetView as? ContentTextView

        if (contentView != null) {
            // 第一阶段：画布级 100% 纯白帧擦除（60ms，刚好跨越墨水屏 1 帧完整电泳扫描周期）
            contentView.einkFlashPhase = 2
            contentView.invalidate()
            EpdSettings.applyPartialRefresh(contentView, rect)

            mainHandler.postDelayed({
                // 第二阶段：恢复绘制 1-bit 纯黑正文
                contentView.einkFlashPhase = 0
                contentView.invalidate()
                EpdSettings.applyPartialRefresh(contentView, rect)
            }, 60L)
        } else {
            val overlay = getOrAttachOverlay(targetView)
            if (overlay != null) {
                overlay.visibility = View.VISIBLE
                overlay.bringToFront()
                overlay.phaseColor = Color.WHITE
                EpdSettings.applyPartialRefresh(overlay, rect)
                mainHandler.postDelayed({
                    overlay.phaseColor = Color.TRANSPARENT
                    overlay.visibility = View.GONE
                    EpdSettings.applyPartialRefresh(targetView, rect)
                    targetView.invalidate()
                }, 65L)
            } else {
                EpdSettings.applyPartialRefresh(targetView, rect)
                targetView.invalidate()
            }
        }

        if (showToast) {
            Toast.makeText(targetView.context, "已执行墨水屏局部白场清残影", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 执行墨水屏【全局全刷清残影】（双相黑->白深层波形复位）：
     * 第一相（0~105ms）：全屏 100% 纯黑 (#000000)，将全部黑色微胶囊推至顶层；
     * 第二相（105~210ms）：全屏 100% 纯白 (#FFFFFF)，将全部白色微胶囊推至顶层，彻底抹除一切灰阶残影；
     * 第三相（210ms）：隐藏覆层并触发全树重绘与硬件 GC16 全刷。
     */
    fun triggerFullRefresh(targetView: View, showToast: Boolean = false) {
        val contentView = targetView as? ContentTextView
        contentView?.let {
            it.einkFlashPhase = 1
            it.invalidate()
        }
        EpdSettings.applyRefresh(targetView, forceFull = true)

        val overlay = getOrAttachOverlay(targetView)
        if (overlay != null) {
            overlay.visibility = View.VISIBLE
            overlay.bringToFront()
            overlay.phaseColor = Color.BLACK
            EpdSettings.applyRefresh(overlay, forceFull = true)

            mainHandler.postDelayed({
                contentView?.let {
                    it.einkFlashPhase = 2
                    it.invalidate()
                }
                overlay.phaseColor = Color.WHITE
                EpdSettings.applyRefresh(overlay, forceFull = true)
            }, 105L)

            mainHandler.postDelayed({
                contentView?.let {
                    it.einkFlashPhase = 0
                    it.invalidate()
                }
                overlay.phaseColor = Color.TRANSPARENT
                overlay.visibility = View.GONE
                EpdSettings.applyRefresh(targetView, forceFull = true)
                invalidateHierarchy(targetView.rootView ?: targetView)
            }, 210L)
        } else {
            mainHandler.postDelayed({
                contentView?.let {
                    it.einkFlashPhase = 0
                    it.invalidate()
                }
                targetView.invalidate()
            }, 120L)
        }

        if (showToast) {
            Toast.makeText(targetView.context, "已执行墨水屏全局黑白双相清残影", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 当菜单、弹窗（Dialog / PopupMenu）或底部栏收起时自动调用：
     * 立即执行一次快速白场+重绘擦除，消除弹窗边框与菜单文字在阅读页/书架上留下的残影。
     */
    fun scrubAfterOverlayDismiss(targetView: View?) {
        val v = targetView ?: return
        mainHandler.post {
            triggerPartialRefresh(v, showToast = false)
        }
    }

    /**
     * 递归重绘整个视图树，确保所有线性边框与文字干净刷新
     */
    fun invalidateHierarchy(view: View) {
        view.invalidate()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                invalidateHierarchy(view.getChildAt(i))
            }
        }
    }

    /**
     * 将任意原生 AlertDialog 改造为微信读书墨水屏纯黑白线性弹窗（去灰阶遮罩、去动画、关闭时自动清残影）
     */
    fun applyEinkDialogStyle(dialog: AlertDialog, hostView: View? = null) {
        dialog.window?.let { win ->
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = win.attributes
            attr.dimAmount = 0f
            attr.windowAnimations = 0
            win.attributes = attr
            win.setBackgroundDrawableResource(R.drawable.bg_eink_border_dialog)
        }
        dialog.setOnDismissListener {
            val target = hostView ?: (dialog.context as? Activity)?.window?.decorView
            scrubAfterOverlayDismiss(target)
        }
    }

    /**
     * 原生内嵌墨水屏综合控制面板（纯黑白线性风格）
     */
    fun showEinkSettingsDialog(
        context: Context,
        targetView: View?,
        onStateChanged: (() -> Unit)? = null
    ) {
        val dm = context.resources.displayMetrics
        val progressState = if (showReadProgressBar) "开启（底部线性进度）" else "已关闭（100%全屏正文）"
        val adaptiveState = if (adaptiveScreenResolution) {
            "开启（已适配 ${dm.widthPixels}×${dm.heightPixels}）"
        } else {
            "手动边距模式"
        }
        val curPartial = partialCleanEveryNPages
        val partialDesc = when (curPartial) {
            0 -> "仅基础局刷"
            1 -> "每页白场清残影（推荐）"
            else -> "每 $curPartial 页白场清残影"
        }
        val curFull = cleanEveryNPages
        val fullDesc = if (curFull <= 0) "已关闭自动全刷" else "每 $curFull 页自动全刷"

        val options = arrayOf(
            "[ 局刷清残影 ] 立即白场擦除正文区残影（无黑闪）",
            "[ 全刷清残影 ] 立即全局黑白双相深层消残影",
            "[ 阅读进度栏 ] $progressState",
            "[ 自适应分辨率 ] $adaptiveState",
            "[ 翻页局刷频率 ] 切换（当前：$partialDesc）",
            "[ 自动全刷频率 ] 每 5 页自动全局全刷（强力无残影）",
            "[ 自动全刷频率 ] 每 8 页自动全局全刷（默认推荐）",
            "[ 自动全刷频率 ] 每 15 页自动全局全刷（省电模式）",
            "[ 自动全刷频率 ] 关闭翻页自动全局全刷"
        )

        val dlg = AlertDialog.Builder(context)
            .setTitle("墨水屏去残影与显示设置 ($fullDesc · $partialDesc)")
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
                            1 -> 2
                            2 -> 3
                            3 -> 0
                            else -> 1
                        }
                        partialTurnCounter = 0
                        val pMsg = when (partialCleanEveryNPages) {
                            0 -> "已设为仅基础重绘"
                            1 -> "已设为每翻 1 页自动白场擦除清残影"
                            else -> "已设为每翻 $partialCleanEveryNPages 页自动白场擦除清残影"
                        }
                        Toast.makeText(context, pMsg, Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        cleanEveryNPages = 5
                        turnCounter = 0
                        Toast.makeText(context, "已设为每 5 页自动全局全刷清残影", Toast.LENGTH_SHORT).show()
                    }
                    6 -> {
                        cleanEveryNPages = 8
                        turnCounter = 0
                        Toast.makeText(context, "已设为每 8 页自动全局全刷清残影", Toast.LENGTH_SHORT).show()
                    }
                    7 -> {
                        cleanEveryNPages = 15
                        turnCounter = 0
                        Toast.makeText(context, "已设为每 15 页自动全局全刷清残影", Toast.LENGTH_SHORT).show()
                    }
                    8 -> {
                        cleanEveryNPages = 0
                        turnCounter = 0
                        Toast.makeText(context, "已关闭自动全局全刷，仅保留每页白场清残影", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .create()
        applyEinkDialogStyle(dlg, targetView)
        dlg.show()
    }
}
