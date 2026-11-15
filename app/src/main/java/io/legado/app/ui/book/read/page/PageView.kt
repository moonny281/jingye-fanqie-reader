package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import io.legado.app.R
import io.legado.app.constant.AppConst.timeFormat
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ViewBookPageBinding
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.BatteryView
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setTextIfNotEqual
import splitties.views.backgroundColor
import java.util.Date

/**
 * 页面视图（墨水屏深度优化版：支持一键完全关闭进度与状态栏以释放 100% 屏幕阅读面积 + 分辨率自适应紧凑页眉页脚）
 */
class PageView(context: Context) : FrameLayout(context) {

    private val binding = ViewBookPageBinding.inflate(LayoutInflater.from(context), this, true)
    private val readBookActivity get() = activity as? ReadBookActivity
    private var battery = 100
    private var tvTitle: BatteryView? = null
    private var tvTime: BatteryView? = null
    private var tvBattery: BatteryView? = null
    private var tvBatteryP: BatteryView? = null
    private var tvPage: BatteryView? = null
    private var tvTotalProgress: BatteryView? = null
    private var tvTotalProgress1: BatteryView? = null
    private var tvPageAndTotal: BatteryView? = null
    private var tvBookName: BatteryView? = null
    private var tvTimeBattery: BatteryView? = null
    private var tvTimeBatteryP: BatteryView? = null
    private var isMainView = false
    var isScroll = false

    val contentView: ContentTextView
        get() = binding.contentTextView

    val headerHeight: Int
        get() {
            val h1 = if (binding.vwStatusBar.isGone) 0 else binding.vwStatusBar.height
            val h2 = if (binding.llHeader.isGone) 0 else binding.llHeader.height
            return h1 + h2 + binding.vwRoot.paddingTop
        }
    val imgBgPaddingStart: Int
        get() {
            return binding.vwRoot.paddingStart
        }

    init {
        if (!isInEditMode) {
            upStyle()
            binding.vwStatusBar.applyStatusBarPadding()
            binding.vwNavigationBar.applyNavigationBarPadding()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        upBg()
    }

    fun upStyle() = binding.run {
        upTipStyle()
        ReadBookConfig.let {
            val textColor = Color.BLACK
            val tipColor = with(ReadTipConfig) {
                if (tipColor == 0) textColor else tipColor
            }
            val tipDividerColor = with(ReadTipConfig) {
                when (tipDividerColor) {
                    -1 -> ContextCompat.getColor(context, R.color.divider)
                    0 -> textColor
                    else -> tipDividerColor
                }
            }
            tvHeaderLeft.setColor(tipColor)
            tvHeaderMiddle.setColor(tipColor)
            tvHeaderRight.setColor(tipColor)
            tvFooterLeft.setColor(tipColor)
            tvFooterMiddle.setColor(tipColor)
            tvFooterRight.setColor(tipColor)
            vwTopDivider.backgroundColor = tipDividerColor
            vwBottomDivider.backgroundColor = tipDividerColor
            upStatusBar()
            upNavigationBar()
            upPaddingDisplayCutouts()

            if (EinkRefreshHelper.adaptiveScreenResolution) {
                val dm = resources.displayMetrics
                val adaptive = EinkRefreshHelper.computeAdaptiveMargins(
                    dm.widthPixels,
                    dm.heightPixels,
                    dm.density
                )
                llHeader.setPadding(
                    adaptive.headerPadHorizontalDp.dpToPx(),
                    adaptive.headerPadTopDp.dpToPx(),
                    adaptive.headerPadHorizontalDp.dpToPx(),
                    adaptive.headerPadBottomDp.dpToPx()
                )
                llFooter.setPadding(
                    adaptive.footerPadHorizontalDp.dpToPx(),
                    adaptive.footerPadTopDp.dpToPx(),
                    adaptive.footerPadHorizontalDp.dpToPx(),
                    adaptive.footerPadBottomDp.dpToPx()
                )
            } else {
                llHeader.setPadding(
                    it.headerPaddingLeft.dpToPx(),
                    it.headerPaddingTop.dpToPx(),
                    it.headerPaddingRight.dpToPx(),
                    it.headerPaddingBottom.dpToPx()
                )
                llFooter.setPadding(
                    it.footerPaddingLeft.dpToPx(),
                    it.footerPaddingTop.dpToPx(),
                    it.footerPaddingRight.dpToPx(),
                    it.footerPaddingBottom.dpToPx()
                )
            }
            vwTopDivider.gone(llHeader.isGone || !it.showHeaderLine)
            vwBottomDivider.gone(llFooter.isGone || !it.showFooterLine)
        }
        upTime()
        upBattery(battery)
    }

    /**
     * 显示状态栏时隐藏header
     */
    fun upStatusBar() = with(binding.vwStatusBar) {
        isGone = ReadBookConfig.hideStatusBar || readBookActivity?.isInMultiWindow == true
    }

    fun upNavigationBar() {
        binding.vwNavigationBar.isGone = ReadBookConfig.hideNavigationBar
    }

    fun upPaddingDisplayCutouts() {
        if (ReadBookConfig.isNineBgImg) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.vwRoot, null)
            return
        }
        if (AppConfig.paddingDisplayCutouts) {
            binding.vwRoot.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                binding.vwRoot.setPadding(
                    insets.left,
                    if (binding.vwStatusBar.isGone) insets.top else 0,
                    insets.right,
                    insets.bottom
                )
                windowInsets
            }
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(binding.vwRoot, null)
            binding.vwRoot.setPadding(0, 0, 0, 0)
        }
    }

    /**
     * 更新阅读进度与页眉页脚显示状态（当进度总开关关闭或所有子项为空时彻底隐藏容器以释放 100% 屏幕高度）
     */
    private fun upTipStyle() = binding.run {
        tvHeaderLeft.tag = null
        tvHeaderMiddle.tag = null
        tvHeaderRight.tag = null
        tvFooterLeft.tag = null
        tvFooterMiddle.tag = null
        tvFooterRight.tag = null

        val progressMasterEnabled = EinkRefreshHelper.showReadProgressBar
        val hasAnyHeaderItem = with(ReadTipConfig) {
            tipHeaderLeft != none || tipHeaderMiddle != none || tipHeaderRight != none
        }
        val hasAnyFooterItem = with(ReadTipConfig) {
            tipFooterLeft != none || tipFooterMiddle != none || tipFooterRight != none
        }

        llHeader.isGone = if (!progressMasterEnabled || !hasAnyHeaderItem) {
            true
        } else {
            when (ReadTipConfig.headerMode) {
                1 -> false
                2 -> true
                else -> !ReadBookConfig.hideStatusBar
            }
        }

        llFooter.isGone = if (!progressMasterEnabled || !hasAnyFooterItem) {
            true
        } else {
            when (ReadTipConfig.footerMode) {
                1 -> true
                else -> false
            }
        }

        ReadTipConfig.apply {
            tvHeaderLeft.isGone = tipHeaderLeft == none
            tvHeaderRight.isGone = tipHeaderRight == none
            tvHeaderMiddle.isGone = tipHeaderMiddle == none
            tvFooterLeft.isGone = tipFooterLeft == none
            tvFooterRight.isGone = tipFooterRight == none
            tvFooterMiddle.isGone = tipFooterMiddle == none
        }
        tvTitle = getTipView(ReadTipConfig.chapterTitle)?.apply {
            tag = ReadTipConfig.chapterTitle
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvTime = getTipView(ReadTipConfig.time)?.apply {
            tag = ReadTipConfig.time
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvBattery = getTipView(ReadTipConfig.battery)?.apply {
            tag = ReadTipConfig.battery
            isBattery = true
            textSize = 10.5f
        }
        tvPage = getTipView(ReadTipConfig.page)?.apply {
            tag = ReadTipConfig.page
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvTotalProgress = getTipView(ReadTipConfig.totalProgress)?.apply {
            tag = ReadTipConfig.totalProgress
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvTotalProgress1 = getTipView(ReadTipConfig.totalProgress1)?.apply {
            tag = ReadTipConfig.totalProgress1
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvPageAndTotal = getTipView(ReadTipConfig.pageAndTotal)?.apply {
            tag = ReadTipConfig.pageAndTotal
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvBookName = getTipView(ReadTipConfig.bookName)?.apply {
            tag = ReadTipConfig.bookName
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvTimeBattery = getTipView(ReadTipConfig.timeBattery)?.apply {
            tag = ReadTipConfig.timeBattery
            isBattery = true
            typeface = ChapterProvider.typeface
            textSize = 10.5f
        }
        tvBatteryP = getTipView(ReadTipConfig.batteryPercentage)?.apply {
            tag = ReadTipConfig.batteryPercentage
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvTimeBatteryP = getTipView(ReadTipConfig.timeBatteryPercentage)?.apply {
            tag = ReadTipConfig.timeBatteryPercentage
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
    }

    /**
     * 获取信息视图
     */
    private fun getTipView(tip: Int): BatteryView? = binding.run {
        return when (tip) {
            ReadTipConfig.tipHeaderLeft -> tvHeaderLeft
            ReadTipConfig.tipHeaderMiddle -> tvHeaderMiddle
            ReadTipConfig.tipHeaderRight -> tvHeaderRight
            ReadTipConfig.tipFooterLeft -> tvFooterLeft
            ReadTipConfig.tipFooterMiddle -> tvFooterMiddle
            ReadTipConfig.tipFooterRight -> tvFooterRight
            else -> null
        }
    }

    /**
     * 更新背景（原生纯白墨水屏背景）
     */
    fun upBg() {
        binding.vwRoot.background = LayerDrawable(
            arrayOf(
                Color.WHITE.toDrawable(),
                ReadBookConfig.bg ?: Color.WHITE.toDrawable()
            )
        )
        upBgAlpha()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        ReadBookConfig.bg?.alpha = 255
        binding.vwRoot.invalidate()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        tvTime?.text = timeFormat.format(Date(System.currentTimeMillis()))
        upTimeBattery()
    }

    /**
     * 更新电池信息
     */
    @SuppressLint("SetTextI18n")
    fun upBattery(battery: Int) {
        this.battery = battery
        tvBattery?.setBattery(battery)
        tvBatteryP?.text = "$battery%"
        upTimeBattery()
    }

    @SuppressLint("SetTextI18n")
    private fun upTimeBattery() {
        val time = timeFormat.format(Date(System.currentTimeMillis()))
        tvTimeBattery?.setBattery(battery, time)
        tvTimeBatteryP?.text = "$time $battery%"
    }

    /**
     * 设置内容
     */
    fun setContent(textPage: TextPage, resetPageOffset: Boolean = true) {
        if (isMainView && !isScroll) {
            setProgress(textPage)
        } else {
            post {
                setProgress(textPage)
            }
        }
        if (resetPageOffset) {
            resetPageOffset()
        }
        binding.contentTextView.setContent(textPage)
    }

    fun invalidateContentView() {
        binding.contentTextView.invalidate()
    }

    fun setContentDescription(content: String) {
        binding.contentTextView.contentDescription = content
    }

    fun resetPageOffset() {
        binding.contentTextView.resetPageOffset()
    }

    /**
     * 设置进度
     */
    @SuppressLint("SetTextI18n")
    fun setProgress(textPage: TextPage) = textPage.apply {
        tvBookName?.setTextIfNotEqual(ReadBook.book?.name)
        tvTitle?.setTextIfNotEqual(textPage.title)
        val readProgress = readProgress
        tvTotalProgress?.setTextIfNotEqual(readProgress)
        tvTotalProgress1?.setTextIfNotEqual("${chapterIndex.plus(1)}/${chapterSize}")
        if (textChapter.isCompleted) {
            tvPageAndTotal?.setTextIfNotEqual("${index.plus(1)}/$pageSize  $readProgress")
            tvPage?.setTextIfNotEqual("${index.plus(1)}/$pageSize")
        } else {
            val pageSizeInt = pageSize
            val pageSize = if (pageSizeInt <= 0) "-" else "~$pageSizeInt"
            tvPageAndTotal?.setTextIfNotEqual("${index.plus(1)}/$pageSize  $readProgress")
            tvPage?.setTextIfNotEqual("${index.plus(1)}/$pageSize")
        }
    }

    fun setAutoPager(autoPager: AutoPager?) {
        binding.contentTextView.setAutoPager(autoPager)
    }

    fun submitRenderTask() {
        binding.contentTextView.submitRenderTask()
    }

    fun setIsScroll(value: Boolean) {
        isScroll = value
        binding.contentTextView.setIsScroll(value)
    }

    fun scroll(offset: Int) {
        binding.contentTextView.scroll(offset)
    }

    fun upSelectAble(selectAble: Boolean) {
        binding.contentTextView.selectAble = selectAble
    }

    fun onClick(x: Float, y: Float): Boolean {
        return binding.contentTextView.click(x - imgBgPaddingStart, y - headerHeight)
    }

    fun longPress(
        x: Float, y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        return binding.contentTextView.longPress(x - imgBgPaddingStart, y - headerHeight, select)
    }

    fun selectText(
        x: Float, y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        return binding.contentTextView.selectText(x - imgBgPaddingStart, y - headerHeight, select)
    }

    fun getCurVisiblePage(): TextPage {
        return binding.contentTextView.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return binding.contentTextView.getReadAloudPos()
    }

    fun markAsMainView() {
        isMainView = true
        binding.contentTextView.isMainView = true
    }

    fun selectStartMove(x: Float, y: Float) {
        binding.contentTextView.selectStartMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int
    ) {
        binding.contentTextView.selectStartMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectStartMoveIndex(textPos: TextPos) {
        binding.contentTextView.selectStartMoveIndex(textPos)
    }

    fun selectEndMove(x: Float, y: Float) {
        binding.contentTextView.selectEndMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectEndMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int
    ) {
        binding.contentTextView.selectEndMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectEndMoveIndex(textPos: TextPos) {
        binding.contentTextView.selectEndMoveIndex(textPos)
    }

    fun getReverseStartCursor(): Boolean {
        return binding.contentTextView.reverseStartCursor
    }

    fun getReverseEndCursor(): Boolean {
        return binding.contentTextView.reverseEndCursor
    }

    fun isLongScreenShot(): Boolean {
        return binding.contentTextView.longScreenshot
    }

    fun resetReverseCursor() {
        binding.contentTextView.resetReverseCursor()
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        binding.contentTextView.cancelSelect(clearSearchResult)
    }

    fun createBookmark(): Bookmark? {
        return binding.contentTextView.createBookmark()
    }

    fun relativePage(relativePagePos: Int): TextPage {
        return binding.contentTextView.relativePage(relativePagePos)
    }

    val textPage get() = binding.contentTextView.textPage

    val selectedText: String get() = binding.contentTextView.getSelectedText()

    val selectStartPos get() = binding.contentTextView.selectStart
}