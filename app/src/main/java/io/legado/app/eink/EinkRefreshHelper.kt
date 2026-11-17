package io.legado.app.eink

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
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
 * 墨水屏刷新、1-bit 线性菜单与排版核心控制器 (v0.4.3 深度修复版)：
 * 1. 独立提供【局部刷新清残影开关】(partialRefreshEnabled) 与【全局刷新清残影开关】(fullRefreshEnabled / autoPageRefreshEnabled)：
 *    - 用户可通过阅读器底栏按钮或墨水屏设置面板独立控制是否开启局部刷新清残影与全局刷新清残影；
 *    - 支持自定义隔几页自动刷新间隔（5 / 10 / 15 / 20 / 30 页）及手动立即局刷/立即全刷。
 * 2. 提供 1-bit 纯黑白线性零动画三点弹出菜单 (showLinearMenuPopup & attachLinearOverflowMenu)，彻底替代系统带动画与阴影的 OverflowPopup。
 * 3. 提供 1-bit 纯黑白线性墨水屏综合设置面板 (showEinkSettingsDialog)，所有开关均为 1-bit 黑白线性按钮。
 */
object EinkRefreshHelper {
    private const val PREF_NAME = "eink_refresh_prefs"
    private const val KEY_PARTIAL_REFRESH_ENABLED = "partial_refresh_enabled_v043"
    private const val KEY_AUTO_PAGE_REFRESH_ENABLED = "auto_page_refresh_enabled_v043"
    private const val KEY_CLEAN_EVERY_N = "clean_every_n_pages_v043"
    private const val KEY_AUTO_REFRESH_FULL_MODE = "auto_refresh_use_full_mode_v043"
    private const val KEY_EPD_MODE = "epd_display_mode"
    private const val KEY_SHOW_PROGRESS_BAR = "show_read_progress_bar_v042"
    private const val KEY_ADAPTIVE_RESOLUTION = "adaptive_screen_resolution_v042"
    private const val OVERLAY_TAG = "eink_top_flash_overlay_v043"

    val PAGE_INTERVAL_OPTIONS = intArrayOf(5, 10, 15, 20, 30)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var turnCounter = 0

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
     * 独立开关 1：是否开启【局部刷新清残影】（默认 false，由用户通过按钮自主控制开启/关闭）
     */
    var partialRefreshEnabled: Boolean
        get() = prefs.getBoolean(KEY_PARTIAL_REFRESH_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PARTIAL_REFRESH_ENABLED, value).apply()
        }

    /**
     * 独立开关 2：是否开启【全局刷新清残影 / 隔几页全刷】（默认 false，由用户通过按钮自主控制开启/关闭）
     */
    var autoPageRefreshEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAGE_REFRESH_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_PAGE_REFRESH_ENABLED, value).apply()
            turnCounter = 0
        }

    var fullRefreshEnabled: Boolean
        get() = autoPageRefreshEnabled
        set(value) {
            autoPageRefreshEnabled = value
        }

    /**
     * 开启自动刷新后，每隔 N 页执行一次清残影刷新（默认 10 页，支持 5 / 10 / 15 / 20 / 30 页）
     */
    var cleanEveryNPages: Int
        get() = prefs.getInt(KEY_CLEAN_EVERY_N, 10).coerceIn(1, 100)
        set(value) {
            prefs.edit().putInt(KEY_CLEAN_EVERY_N, value.coerceIn(1, 100)).apply()
            turnCounter = 0
        }

    var autoRefreshUseFullMode: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REFRESH_FULL_MODE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_REFRESH_FULL_MODE, value).apply()
        }

    var epdModeIndex: Int
        get() = prefs.getInt(KEY_EPD_MODE, 0)
        set(value) {
            val idx = value.coerceIn(0, EpdDisplayMode.entries.lastIndex)
            prefs.edit().putInt(KEY_EPD_MODE, idx).apply()
            EpdSettings.currentMode = EpdDisplayMode.entries[idx]
        }

    var showReadProgressBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PROGRESS_BAR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_PROGRESS_BAR, value).apply()
        }

    var adaptiveScreenResolution: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_RESOLUTION, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ADAPTIVE_RESOLUTION, value).apply()
        }

    /**
     * 一键切换【局部刷新清残影】开关
     */
    fun togglePartialRefresh(context: Context? = null): Boolean {
        val next = !partialRefreshEnabled
        partialRefreshEnabled = next
        context?.let {
            val msg = if (next) {
                "已开启【局部刷新清残影】（翻页及关闭弹窗时自动白场擦除残影）"
            } else {
                "已关闭【局部刷新清残影】"
            }
            Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
        }
        return next
    }

    /**
     * 一键切换【全局刷新清残影】开关
     */
    fun toggleFullRefresh(context: Context? = null): Boolean {
        val next = !autoPageRefreshEnabled
        autoPageRefreshEnabled = next
        context?.let {
            val msg = if (next) {
                "已开启【全局刷新清残影】：每翻 $cleanEveryNPages 页自动执行一次黑白全刷"
            } else {
                "已关闭【全局刷新清残影】（翻页不主动黑闪）"
            }
            Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
        }
        return next
    }

    fun toggleAutoPageRefresh(context: Context? = null): Boolean = toggleFullRefresh(context)

    /**
     * 循环切换隔几页刷新间隔（5 -> 10 -> 15 -> 20 -> 30 页）
     */
    fun cycleCleanEveryNPages(context: Context? = null): Int {
        val cur = cleanEveryNPages
        val idx = PAGE_INTERVAL_OPTIONS.indexOfFirst { it == cur }
        val nextVal = if (idx in 0 until PAGE_INTERVAL_OPTIONS.lastIndex) {
            PAGE_INTERVAL_OPTIONS[idx + 1]
        } else {
            PAGE_INTERVAL_OPTIONS[0]
        }
        cleanEveryNPages = nextVal
        context?.let {
            Toast.makeText(it, "清残影页数间隔已设为：每 $nextVal 页", Toast.LENGTH_SHORT).show()
        }
        return nextVal
    }

    fun getPartialRefreshButtonLabel(): String =
        if (partialRefreshEnabled) "局刷清残影:开" else "局刷清残影:关"

    fun getFullRefreshButtonLabel(): String =
        if (autoPageRefreshEnabled) "全刷清残影:开" else "全刷清残影:关"

    fun getIntervalButtonLabel(): String = "间隔:${cleanEveryNPages}页"

    fun getAutoRefreshButtonLabel(): String =
        if (autoPageRefreshEnabled) "隔${cleanEveryNPages}页刷:开" else "隔页刷:关"

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
     * 翻页时的墨水屏刷新调度：
     * - 根据用户控制的【全局刷新清残影开关】(autoPageRefreshEnabled) 与【局部刷新清残影开关】(partialRefreshEnabled) 执行；
     * - 两个开关均关闭时：翻页零闪烁，仅做普通视图重绘。
     */
    fun onPageTurned(targetView: View, contentRect: Rect? = null) {
        val fullOn = autoPageRefreshEnabled
        val partialOn = partialRefreshEnabled

        if (!fullOn && !partialOn) {
            turnCounter = 0
            targetView.invalidate()
            return
        }

        val interval = cleanEveryNPages.coerceAtLeast(1)
        turnCounter++
        if (turnCounter >= interval) {
            turnCounter = 0
            if (fullOn) {
                triggerFullRefresh(targetView, showToast = false)
            } else if (partialOn) {
                triggerPartialRefresh(targetView, contentRect, showToast = false)
            }
            return
        }

        if (partialOn) {
            EpdSettings.applyPartialRefresh(targetView, contentRect)
        } else {
            targetView.invalidate()
        }
    }

    /**
     * 执行墨水屏【局部刷新清残影】（白场脉冲擦除，无全屏黑闪）
     */
    fun triggerPartialRefresh(
        targetView: View,
        dirtyRect: Rect? = null,
        showToast: Boolean = false
    ) {
        val rect = dirtyRect ?: Rect(0, 0, targetView.width, targetView.height)
        val contentView = targetView as? ContentTextView

        if (contentView != null) {
            contentView.einkFlashPhase = 2
            contentView.invalidate()
            EpdSettings.applyPartialRefresh(contentView, rect)

            mainHandler.postDelayed({
                contentView.einkFlashPhase = 0
                contentView.invalidate()
                EpdSettings.applyPartialRefresh(contentView, rect)
            }, 55L)
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
                }, 55L)
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
     * 执行墨水屏【全局刷新清残影】（双相黑->白深层波形复位）
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
            }, 95L)

            mainHandler.postDelayed({
                contentView?.let {
                    it.einkFlashPhase = 0
                    it.invalidate()
                }
                overlay.phaseColor = Color.TRANSPARENT
                overlay.visibility = View.GONE
                EpdSettings.applyRefresh(targetView, forceFull = true)
                invalidateHierarchy(targetView.rootView ?: targetView)
            }, 190L)
        } else {
            mainHandler.postDelayed({
                contentView?.let {
                    it.einkFlashPhase = 0
                    it.invalidate()
                }
                targetView.invalidate()
            }, 100L)
        }

        if (showToast) {
            Toast.makeText(targetView.context, "已执行墨水屏全局黑白全刷清残影", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 弹窗/菜单关闭或界面切换后的重绘：
     * 若用户开启了【局部刷新清残影开关】(partialRefreshEnabled)，自动执行局部刷新擦除弹窗残影；
     * 若未开启，仅通知视图树普通重绘，绝不闪烁。
     */
    /**
     * 弹窗/菜单关闭或界面切换后的无黑闪白场擦除：
     * - 当【局部刷新清残影】开启（默认开启）时，执行 40ms 快速白场脉冲 + 硬件局刷 + 视图树重绘，彻底消除任何弹窗边框或菜单文字残影；
     * - 当用户手动关闭局部刷新开关时，仅通知视图树普通重绘。
     */
    fun scrubAfterOverlayDismiss(targetView: View?) {
        val v = targetView ?: return
        mainHandler.post {
            val root = v.rootView ?: v
            if (partialRefreshEnabled) {
                val overlay = getOrAttachOverlay(root)
                if (overlay != null) {
                    overlay.visibility = View.VISIBLE
                    overlay.bringToFront()
                    overlay.phaseColor = Color.WHITE
                    EpdSettings.applyPartialRefresh(overlay)
                    mainHandler.postDelayed({
                        overlay.phaseColor = Color.TRANSPARENT
                        overlay.visibility = View.GONE
                        EpdSettings.applyPartialRefresh(root)
                        invalidateHierarchy(root)
                    }, 42L)
                } else {
                    EpdSettings.applyPartialRefresh(root)
                    invalidateHierarchy(root)
                }
            } else {
                invalidateHierarchy(root)
            }
        }
    }

    /**
     * 弹窗刚弹出显示时的墨水屏去残影刷新：
     * 擦除弹窗白底下方原本页面文字留下的浅灰底影，使弹窗内部纯白底黑线框干净锐利。
     */
    fun scrubDialogOnShow(dialogView: View?) {
        val v = dialogView ?: return
        mainHandler.post {
            enforce1BitWireframeOnTree(v)
            if (partialRefreshEnabled) {
                EpdSettings.applyPartialRefresh(v)
            }
            invalidateHierarchy(v)
        }
    }

    /**
     * 递归将弹窗内部视图树统一规范为 1-bit 纯黑白线框无阴影样式：
     * - 移除所有子 View 的 elevation / translationZ / outline 阴影与水波纹动画；
     * - 关闭 ScrollView / RecyclerView 边缘晕影光效（overScrollMode = OVER_SCROLL_NEVER）。
     */
    fun enforce1BitWireframeOnTree(view: View?) {
        val v = view ?: return
        v.elevation = 0f
        v.translationZ = 0f
        v.stateListAnimator = null
        v.overScrollMode = View.OVER_SCROLL_NEVER
        if (v is androidx.recyclerview.widget.RecyclerView) {
            v.itemAnimator = null
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                enforce1BitWireframeOnTree(v.getChildAt(i))
            }
        }
    }

    fun invalidateHierarchy(view: View) {
        view.invalidate()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                invalidateHierarchy(view.getChildAt(i))
            }
        }
    }

    /**
     * 通用 1-bit 纯黑白线框无动画弹窗样式（支持 android.app.Dialog / AlertDialog / AppCompat AlertDialog）
     */
    fun applyEinkDialogStyle(dialog: android.app.Dialog, hostView: View? = null) {
        dialog.window?.let { win ->
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = win.attributes
            attr.dimAmount = 0f
            attr.windowAnimations = R.style.Animation_EInk_None
            win.attributes = attr
            win.setBackgroundDrawableResource(R.drawable.bg_eink_border_dialog)
            win.decorView.elevation = 0f
            win.decorView.translationZ = 0f
        }
        dialog.setOnShowListener {
            val dec = dialog.window?.decorView
            if (dialog is AlertDialog) {
                listOf(
                    AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEGATIVE,
                    AlertDialog.BUTTON_NEUTRAL
                ).forEach { which ->
                    dialog.getButton(which)?.apply {
                        setTextColor(Color.BLACK)
                        typeface = Typeface.DEFAULT_BOLD
                        stateListAnimator = null
                        setBackgroundResource(R.drawable.selector_eink_button)
                    }
                }
            } else if (dialog is androidx.appcompat.app.AlertDialog) {
                listOf(
                    androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE,
                    androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE,
                    androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL
                ).forEach { which ->
                    dialog.getButton(which)?.apply {
                        setTextColor(Color.BLACK)
                        typeface = Typeface.DEFAULT_BOLD
                        stateListAnimator = null
                        setBackgroundResource(R.drawable.selector_eink_button)
                    }
                }
            }
            scrubDialogOnShow(dec)
        }
        dialog.setOnDismissListener {
            val target = hostView ?: (dialog.context as? Activity)?.window?.decorView
            scrubAfterOverlayDismiss(target)
        }
    }

    /**
     * 通用 1-bit 纯黑白线框无动画 PopupWindow 样式
     */
    fun applyEinkPopupWindowStyle(popup: PopupWindow, anchorView: View? = null) {
        popup.animationStyle = R.style.Animation_EInk_None
        popup.elevation = 0f
        popup.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        popup.contentView?.let { cv ->
            cv.setBackgroundResource(R.drawable.bg_popup_menu)
            enforce1BitWireframeOnTree(cv)
        }
        popup.setOnDismissListener {
            val root = anchorView?.rootView ?: popup.contentView?.rootView
            scrubAfterOverlayDismiss(root)
        }
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun styleLinearToggleButton(btn: TextView, isOn: Boolean, onText: String = "已开启", offText: String = "已关闭") {
        btn.text = if (isOn) "[ $onText ]" else "[ $offText ]"
        if (isOn) {
            btn.setBackgroundResource(R.drawable.bg_eink_button_solid)
            btn.setTextColor(Color.WHITE)
        } else {
            btn.setBackgroundResource(R.drawable.bg_eink_button_outline)
            btn.setTextColor(Color.BLACK)
        }
    }

    /**
     * 1-Bit 纯黑白线性墨水屏刷新与阅读设置对话框（无任何彩色/灰阶/动画/原生滑动开关，全部采用 1-bit 黑白线性按钮）
     */
    fun showEinkSettingsDialog(
        context: Context,
        targetView: View?,
        onStateChanged: (() -> Unit)? = null
    ) {
        val dm = context.resources.displayMetrics
        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_eink_border_dialog)
            setPadding(dp(context, 16f), dp(context, 14f), dp(context, 16f), dp(context, 14f))
        }
        scroll.addView(root)

        // 标题栏
        val titleTv = TextView(context).apply {
            text = "墨水屏清残影开关与阅读设置"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(context, 10f))
        }
        root.addView(titleTv)
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1.5f))
            setBackgroundColor(Color.BLACK)
        })

        fun addDivider() {
            root.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = dp(context, 10f)
                    bottomMargin = dp(context, 10f)
                }
                setBackgroundColor(Color.BLACK)
            })
        }

        fun createSwitchRow(title: String, subtitle: String, initialOn: Boolean, onToggle: (TextView) -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(context, 10f)
                }
            }
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(context, 10f)
                }
            }
            val tvT = TextView(context).apply {
                text = title
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val tvS = TextView(context).apply {
                text = subtitle
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(context, 2f), 0, 0)
            }
            textCol.addView(tvT)
            textCol.addView(tvS)

            val btn = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 84f), dp(context, 34f))
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
            }
            styleLinearToggleButton(btn, initialOn)
            btn.setOnClickListener { onToggle(btn) }
            row.setOnClickListener { onToggle(btn) }

            row.addView(textCol)
            row.addView(btn)
            root.addView(row)
        }

        // 1. 局部刷新清残影开关
        createSwitchRow(
            title = "局部刷新清残影",
            subtitle = "开启后翻页与收起弹窗/菜单时执行白场局刷消除残影",
            initialOn = partialRefreshEnabled
        ) { btn ->
            val next = togglePartialRefresh(context)
            styleLinearToggleButton(btn, next)
            onStateChanged?.invoke()
        }

        addDivider()

        // 2. 全局刷新清残影开关
        createSwitchRow(
            title = "全局刷新清残影（隔页全刷）",
            subtitle = "开启后按下方设定页数定期执行黑白反相深层清残影",
            initialOn = autoPageRefreshEnabled
        ) { btn ->
            val next = toggleFullRefresh(context)
            styleLinearToggleButton(btn, next)
            onStateChanged?.invoke()
        }

        // 3. 隔几页刷新间隔选择行
        val intervalLabel = TextView(context).apply {
            text = "清残影触发间隔页数（点选即生效）："
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 10f), 0, dp(context, 6f))
        }
        root.addView(intervalLabel)

        val intervalRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val intervalButtons = ArrayList<Pair<Int, TextView>>()
        fun refreshIntervalButtons() {
            val cur = cleanEveryNPages
            for ((pages, b) in intervalButtons) {
                if (pages == cur) {
                    b.setBackgroundResource(R.drawable.bg_eink_button_solid)
                    b.setTextColor(Color.WHITE)
                } else {
                    b.setBackgroundResource(R.drawable.bg_eink_button_outline)
                    b.setTextColor(Color.BLACK)
                }
            }
        }
        for ((idx, pages) in PAGE_INTERVAL_OPTIONS.withIndex()) {
            val b = TextView(context).apply {
                text = "${pages}页"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 34f), 1f).apply {
                    if (idx > 0) marginStart = dp(context, 6f)
                }
                setOnClickListener {
                    cleanEveryNPages = pages
                    refreshIntervalButtons()
                    onStateChanged?.invoke()
                    Toast.makeText(context, "已设置每 $pages 页执行一次清残影刷新", Toast.LENGTH_SHORT).show()
                }
            }
            intervalButtons.add(pages to b)
            intervalRow.addView(b)
        }
        refreshIntervalButtons()
        root.addView(intervalRow)

        addDivider()

        // 4. 底部阅读进度栏开关
        createSwitchRow(
            title = "底部阅读进度栏",
            subtitle = "关闭后隐藏底部章节与页码，释放 100% 屏幕高度给正文",
            initialOn = showReadProgressBar
        ) { btn ->
            val next = toggleReadProgressBar(context)
            styleLinearToggleButton(btn, next)
            onStateChanged?.invoke()
        }

        addDivider()

        // 5. 自适应屏幕分辨率开关
        createSwitchRow(
            title = "自适应分辨率阅读面积",
            subtitle = "当前屏幕 ${dm.widthPixels}×${dm.heightPixels}，自动分配最佳正文边距",
            initialOn = adaptiveScreenResolution
        ) { btn ->
            adaptiveScreenResolution = !adaptiveScreenResolution
            ChapterProvider.upStyle()
            ChapterProvider.upLayout()
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5, 6, 8, 10))
            styleLinearToggleButton(btn, adaptiveScreenResolution)
            onStateChanged?.invoke()
        }

        addDivider()

        // 6. 立即执行局刷 / 全刷按钮行
        val manualRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 4f)
            }
        }
        var dialogRef: AlertDialog? = null

        val btnNowPartial = TextView(context).apply {
            text = "立即局部刷新清残影"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.selector_eink_button)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 38f), 1f).apply {
                marginEnd = dp(context, 6f)
            }
            setOnClickListener {
                dialogRef?.dismiss()
                val v = targetView ?: (context as? Activity)?.window?.decorView
                if (v != null) triggerPartialRefresh(v, showToast = true)
            }
        }
        val btnNowFull = TextView(context).apply {
            text = "立即全局全刷清残影"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_eink_button_solid)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 38f), 1f).apply {
                marginStart = dp(context, 6f)
            }
            setOnClickListener {
                dialogRef?.dismiss()
                val v = targetView ?: (context as? Activity)?.window?.decorView
                if (v != null) triggerFullRefresh(v, showToast = true)
            }
        }
        manualRow.addView(btnNowPartial)
        manualRow.addView(btnNowFull)
        root.addView(manualRow)

        val btnClose = TextView(context).apply {
            text = "完成并关闭"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.selector_eink_button)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 38f)).apply {
                topMargin = dp(context, 12f)
            }
            setOnClickListener { dialogRef?.dismiss() }
        }
        root.addView(btnClose)

        val dlg = AlertDialog.Builder(context)
            .setView(scroll)
            .create()
        dialogRef = dlg
        applyEinkDialogStyle(dlg, targetView)
        dlg.show()
    }

    /**
     * 1-Bit 纯黑白线性零动画三点弹出菜单（彻底替代系统带缩放渐变动画与阴影的 OverflowPopup / PopupMenu）
     */
    @SuppressLint("RestrictedApi")
    fun showLinearMenuPopup(
        anchorView: View,
        menu: Menu,
        onlyOverflow: Boolean = false,
        onItemClick: (MenuItem) -> Unit
    ) {
        val context = anchorView.context
        val visibleItems = ArrayList<MenuItem>()
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            if (onlyOverflow) {
                val impl = item as? MenuItemImpl
                if (impl != null && !impl.requiresOverflow()) continue
            }
            visibleItems.add(item)
        }
        if (visibleItems.isEmpty()) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.isVisible) visibleItems.add(item)
            }
        }
        if (visibleItems.isEmpty()) return

        val scrollView = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setBackgroundResource(R.drawable.bg_popup_menu)
        }
        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_popup_menu)
            setPadding(dp(context, 2f), dp(context, 2f), dp(context, 2f), dp(context, 2f))
        }
        scrollView.addView(listLayout)

        val popupWidth = dp(context, 216f)
        val popup = PopupWindow(
            scrollView,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            animationStyle = R.style.Animation_EInk_None
            elevation = 0f
            isOutsideTouchable = true
            isClippingEnabled = true
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            setOnDismissListener {
                scrubAfterOverlayDismiss(anchorView.rootView)
            }
        }

        val textColorList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            ),
            intArrayOf(Color.WHITE, Color.BLACK)
        )

        for ((index, item) in visibleItems.withIndex()) {
            val row = TextView(context).apply {
                text = "▪  ${item.title}"
                setTextColor(textColorList)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(context, 14f), dp(context, 11f), dp(context, 14f), dp(context, 11f))
                setBackgroundResource(R.drawable.selector_eink_button)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    if (item.hasSubMenu()) {
                        item.subMenu?.let { sub ->
                            showLinearMenuPopup(anchorView, sub, onlyOverflow = false, onItemClick = onItemClick)
                        }
                    } else {
                        onItemClick(item)
                    }
                }
            }
            listLayout.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            if (index < visibleItems.lastIndex) {
                val divider = View(context).apply {
                    setBackgroundColor(Color.BLACK)
                }
                listLayout.addView(
                    divider,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                )
            }
        }

        // 零动画立即在锚点下方弹出
        val loc = IntArray(2)
        anchorView.getLocationOnScreen(loc)
        val screenW = context.resources.displayMetrics.widthPixels
        val xOff = (anchorView.width - popupWidth).coerceAtMost(screenW - loc[0] - popupWidth - dp(context, 8f))
        popup.showAsDropDown(anchorView, xOff, dp(context, 2f))
    }

    /**
     * 拦截 Toolbar 右上角三点按钮（OverflowMenuButton），点击时直接弹出 1-bit 纯黑白线性无动画菜单
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachLinearOverflowMenu(toolbar: Toolbar, onItemClick: (MenuItem) -> Unit) {
        toolbar.popupTheme = R.style.AppTheme_PopupOverlay
        toolbar.stateListAnimator = null
        toolbar.elevation = 0f

        fun hookOverflowView(root: ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is ActionMenuView) {
                    child.popupTheme = R.style.AppTheme_PopupOverlay
                    for (j in 0 until child.childCount) {
                        val itemView = child.getChildAt(j)
                        itemView.stateListAnimator = null
                        val clsName = itemView.javaClass.name
                        if (clsName.contains("OverflowMenuButton", ignoreCase = true)) {
                            itemView.setBackgroundResource(R.drawable.selector_eink_list_item)
                            itemView.setOnTouchListener { v, ev ->
                                if (ev.action == MotionEvent.ACTION_UP) {
                                    showLinearMenuPopup(v, toolbar.menu, onlyOverflow = true, onItemClick = onItemClick)
                                }
                                true
                            }
                            itemView.setOnClickListener { v ->
                                showLinearMenuPopup(v, toolbar.menu, onlyOverflow = true, onItemClick = onItemClick)
                            }
                        }
                    }
                } else if (child is ViewGroup) {
                    hookOverflowView(child)
                }
            }
        }

        toolbar.post { hookOverflowView(toolbar) }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            hookOverflowView(toolbar)
        }
    }
}
