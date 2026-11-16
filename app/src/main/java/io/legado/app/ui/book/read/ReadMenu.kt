package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.databinding.ViewReadMenuBinding
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.openUrl
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import splitties.views.onClick
import splitties.views.onLongClick

/**
 * 阅读界面菜单（原生墨水屏深度重构版：零动画弹出/收起，内置局刷清残影、全刷清残影与阅读进度一键显隐）
 */
class ReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var canShowMenu: Boolean = false
    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewReadMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private var confirmSkipToChapter: Boolean = false

    private var bgColor: Int = Color.WHITE
    private var textColor: Int = Color.BLACK

    private var bottomBackgroundList: ColorStateList = Selector.colorBuild()
        .setDefaultColor(bgColor)
        .setPressedColor(ColorUtils.darkenColor(bgColor))
        .create()

    private val sourceMenu by lazy {
        PopupMenu(context, binding.tvSourceAction).apply {
            inflate(R.menu.book_read_source)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_login -> callBack.showLogin()
                    R.id.menu_chapter_pay -> callBack.payAction()
                    R.id.menu_edit_source -> callBack.openSourceEditActivity()
                    R.id.menu_disable_source -> callBack.disableSource()
                }
                true
            }
        }
    }

    init {
        initView()
        bindEvent()
    }

    private fun initView(reset: Boolean = false) = binding.run {
        tvCustomBtn.setColorFilter(context.accentColor)
        titleBar.setTextColor(textColor)
        titleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
        titleBar.setColorFilter(textColor)
        tvChapterName.setTextColor(textColor)
        tvChapterUrl.setTextColor(textColor)

        llBottomBg.setBackgroundResource(R.drawable.bg_eink_border_top)
        llFloatingButton.gone()
        llBrightness.gone()
        llEinkQuickBar.visible()
        upProgressToggleBtnText()

        tvPre.setTextColor(textColor)
        tvNext.setTextColor(textColor)
        ivCatalog.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvCatalog.setTextColor(textColor)
        ivReadAloud.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvReadAloud.setTextColor(textColor)
        ivFont.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvFont.setTextColor(textColor)
        ivSetting.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvSetting.setTextColor(textColor)

        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        applyNavigationBarPadding()
    }

    fun upProgressToggleBtnText() {
        val partialOn = EinkRefreshHelper.partialRefreshEnabled
        binding.btnEinkPartialSwitch.text = EinkRefreshHelper.getPartialRefreshButtonLabel()
        if (partialOn) {
            binding.btnEinkPartialSwitch.setBackgroundResource(R.drawable.bg_eink_button_solid)
            binding.btnEinkPartialSwitch.setTextColor(Color.WHITE)
        } else {
            binding.btnEinkPartialSwitch.setBackgroundResource(R.drawable.selector_eink_button)
            binding.btnEinkPartialSwitch.setTextColor(Color.BLACK)
        }

        val fullOn = EinkRefreshHelper.autoPageRefreshEnabled
        binding.btnEinkFullSwitch.text = EinkRefreshHelper.getFullRefreshButtonLabel()
        if (fullOn) {
            binding.btnEinkFullSwitch.setBackgroundResource(R.drawable.bg_eink_button_solid)
            binding.btnEinkFullSwitch.setTextColor(Color.WHITE)
        } else {
            binding.btnEinkFullSwitch.setBackgroundResource(R.drawable.selector_eink_button)
            binding.btnEinkFullSwitch.setTextColor(Color.BLACK)
        }

        val showProgress = EinkRefreshHelper.showReadProgressBar
        binding.btnEinkInvert.text = if (showProgress) "进度:开" else "进度:关"
        if (showProgress) {
            binding.btnEinkInvert.setBackgroundResource(R.drawable.bg_eink_button_solid)
            binding.btnEinkInvert.setTextColor(Color.WHITE)
        } else {
            binding.btnEinkInvert.setBackgroundResource(R.drawable.selector_eink_button)
            binding.btnEinkInvert.setTextColor(Color.BLACK)
        }

        binding.btnEinkConfig.text = EinkRefreshHelper.getIntervalButtonLabel()
    }

    fun reset() {
        initView(true)
    }

    fun refreshMenuColorFilter() {
        binding.titleBar.setColorFilter(textColor)
    }

    fun upBrightnessState() {
        // 墨水屏模式不显示软件亮度遮罩条
    }

    fun setScreenBrightness(value: Float) {
        // 墨水屏模式交由系统前光控制
    }

    fun runMenuIn(anim: Boolean = false) {
        callBack.onMenuShow()
        this.visible()
        binding.titleBar.visible()
        binding.bottomMenu.visible()
        binding.tvSourceAction.text =
            ReadBook.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
        binding.tvSourceAction.isGone = ReadBook.isLocalBook
        ReadBook.bookSource?.let {
            if (it.customButton) {
                binding.tvCustomBtn.visibility = VISIBLE
            }
        }
        binding.llBrightness.gone()
        binding.llFloatingButton.gone()
        upProgressToggleBtnText()
        binding.vwMenuBg.setOnClickListener { runMenuOut() }
        callBack.upSystemUiVisibility()
    }

    fun runMenuOut(anim: Boolean = false, onMenuOutEnd: (() -> Unit)? = null) {
        callBack.onMenuHide()
        if (this.isVisible) {
            binding.vwMenuBg.setOnClickListener(null)
            this@ReadMenu.invisible()
            binding.titleBar.invisible()
            binding.bottomMenu.invisible()
            canShowMenu = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
            EinkRefreshHelper.scrubAfterOverlayDismiss(activity?.window?.decorView ?: this@ReadMenu)
        } else {
            onMenuOutEnd?.invoke()
        }
    }

    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        titleBar.toolbar.setOnClickListener {
            callBack.openBookInfoActivity()
        }
        val chapterViewClickListener = OnClickListener {
            if (ReadBook.isLocalBook) {
                return@OnClickListener
            }
            if (AppConfig.readUrlInBrowser) {
                context.openUrl(tvChapterUrl.text.toString().substringBefore(",{"))
            } else {
                Coroutine.async {
                    context.startActivity<WebViewActivity> {
                        val url = tvChapterUrl.text.toString()
                        val bookSource = ReadBook.bookSource
                        putExtra("title", tvChapterName.text)
                        putExtra("url", url)
                        putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                        putExtra("sourceName", bookSource?.bookSourceName)
                        putExtra("sourceType", bookSource?.getSourceType())
                    }
                }
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            if (ReadBook.isLocalBook) {
                return@OnLongClickListener true
            }
            context.alert(R.string.open_fun) {
                setMessage(R.string.use_browser_open)
                okButton {
                    AppConfig.readUrlInBrowser = true
                }
                noButton {
                    AppConfig.readUrlInBrowser = false
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)
        tvCustomBtn.setOnClickListener {
            val book = ReadBook.book ?: return@setOnClickListener
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
        }
        tvCustomBtn.setOnLongClickListener {
            val book = ReadBook.book ?: return@setOnLongClickListener true
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.LONG_CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
            true
        }
        //书源操作
        tvSourceAction.onClick {
            sourceMenu.menu.findItem(R.id.menu_login).isVisible =
                !ReadBook.bookSource?.loginUrl.isNullOrEmpty()
            sourceMenu.menu.findItem(R.id.menu_chapter_pay).isVisible =
                !ReadBook.bookSource?.loginUrl.isNullOrEmpty()
                        && ReadBook.curTextChapter?.isVip == true
                        && ReadBook.curTextChapter?.isPay != true
            sourceMenu.show()
        }

        //阅读进度
        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
                when (AppConfig.progressBarBehavior) {
                    "page" -> ReadBook.skipToPage(seekBar.progress)
                    "chapter" -> {
                        if (confirmSkipToChapter) {
                            callBack.skipToChapter(seekBar.progress)
                        } else {
                            context.alert("章节跳转确认", "确定要跳转章节吗？") {
                                yesButton {
                                    confirmSkipToChapter = true
                                    callBack.skipToChapter(seekBar.progress)
                                }
                                noButton {
                                    upSeekBar()
                                }
                                onCancelled {
                                    upSeekBar()
                                }
                            }
                        }
                    }
                }
            }

        })

        // 原生墨水屏底栏快捷胶囊按钮：
        // 1. 独立开关按钮：控制是否开启局部刷新清残影
        btnEinkPartialSwitch.setOnClickListener {
            EinkRefreshHelper.togglePartialRefresh(context)
            upProgressToggleBtnText()
        }
        // 2. 独立开关按钮：控制是否开启全局刷新清残影（隔页全刷）
        btnEinkFullSwitch.setOnClickListener {
            EinkRefreshHelper.toggleFullRefresh(context)
            upProgressToggleBtnText()
        }
        // 3. 独立开关按钮：控制底部阅读进度栏显示/隐藏
        btnEinkInvert.setOnClickListener {
            EinkRefreshHelper.toggleReadProgressBar(context)
            upProgressToggleBtnText()
        }
        // 4. 立即执行一次局部刷新清残影
        btnEinkAutopage.setOnClickListener {
            runMenuOut {
                val rv = activity?.findViewById<ReadView>(R.id.read_view)
                if (rv != null) {
                    rv.triggerPartialRefresh(showToast = true)
                } else {
                    val target = activity?.window?.decorView ?: this@ReadMenu
                    EinkRefreshHelper.triggerPartialRefresh(target, showToast = true)
                }
            }
        }
        // 5. 立即执行一次全局黑白全刷清残影
        btnEinkRefresh.setOnClickListener {
            runMenuOut {
                val rv = activity?.findViewById<ReadView>(R.id.read_view)
                if (rv != null) {
                    rv.triggerFullRefresh(showToast = true)
                } else {
                    val target = activity?.window?.decorView ?: this@ReadMenu
                    EinkRefreshHelper.triggerFullRefresh(target, showToast = true)
                }
            }
        }
        // 6. 点击快速循环切换隔几页刷新间隔（5 / 10 / 15 / 20 / 30 页）
        btnEinkConfig.setOnClickListener {
            EinkRefreshHelper.cycleCleanEveryNPages(context)
            upProgressToggleBtnText()
        }
        // 7. 打开 1-bit 纯黑白线性墨水屏清残影与阅读综合设置面板
        btnEinkSearch.setOnClickListener {
            runMenuOut {
                val rv = activity?.findViewById<ReadView>(R.id.read_view)
                val target = rv?.curPage?.contentView ?: rv ?: activity?.window?.decorView
                EinkRefreshHelper.showEinkSettingsDialog(context, target) {
                    upProgressToggleBtnText()
                }
            }
        }

        //搜索
        fabSearch.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(null)
            }
        }

        //自动翻页
        fabAutoPage.setOnClickListener {
            runMenuOut {
                callBack.autoPage()
            }
        }

        //替换
        fabReplaceRule.setOnClickListener { callBack.openReplaceRule() }

        //上一章
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }

        //下一章
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }

        //目录
        llCatalog.setOnClickListener {
            runMenuOut {
                callBack.openChapterList()
            }
        }

        //朗读
        llReadAloud.setOnClickListener {
            runMenuOut {
                callBack.onClickReadAloud()
            }
        }
        llReadAloud.onLongClick {
            runMenuOut {
                callBack.showReadAloudDialog()
            }
        }
        //界面
        llFont.setOnClickListener {
            runMenuOut {
                callBack.showReadStyle()
            }
        }

        //设置
        llSetting.setOnClickListener {
            runMenuOut {
                callBack.showMoreSetting()
            }
        }
    }

    fun upBookView() {
        binding.titleBar.title = ReadBook.book?.name
        ReadBook.curTextChapter?.let {
            binding.tvChapterName.text = it.title
            binding.tvChapterName.visible()
            if (!ReadBook.isLocalBook) {
                binding.tvChapterUrl.text = it.chapter.getAbsoluteURL()
                binding.tvChapterUrl.visible()
            } else {
                binding.tvChapterUrl.gone()
            }
            upSeekBar()
            binding.tvPre.isEnabled = ReadBook.durChapterIndex != 0
            binding.tvNext.isEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
        } ?: let {
            binding.tvChapterName.gone()
            binding.tvChapterUrl.gone()
        }
    }

    fun upSeekBar() {
        binding.seekReadPage.apply {
            when (AppConfig.progressBarBehavior) {
                "page" -> {
                    ReadBook.curTextChapter?.let {
                        max = it.pageSize.minus(1)
                        progress = ReadBook.durPageIndex
                    }
                }

                "chapter" -> {
                    max = ReadBook.simulatedChapterSize - 1
                    progress = ReadBook.durChapterIndex
                }
            }
        }
    }

    fun setSeekPage(seek: Int) {
        binding.seekReadPage.progress = seek
    }

    fun setAutoPage(autoPage: Boolean) = binding.run {
        if (autoPage) {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page_stop)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page_stop)
        } else {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page)
        }
        fabAutoPage.setColorFilter(textColor)
    }

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openSourceEditActivity()
        fun openBookInfoActivity()
        fun showReadStyle()
        fun showMoreSetting()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showHelp()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()
    }

}
