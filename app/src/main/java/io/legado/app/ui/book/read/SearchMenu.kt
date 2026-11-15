package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.databinding.ViewSearchMenuBinding
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

/**
 * 搜索界面菜单（原生墨水屏模式：已禁用弹出/收起动画）
 */
class SearchMenu @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewSearchMenuBinding.inflate(LayoutInflater.from(context), this, true)

    private val bgColor: Int = context.bottomBackground
    private val textColor: Int = context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    private val bottomBackgroundList: ColorStateList =
        Selector.colorBuild().setDefaultColor(bgColor)
            .setPressedColor(ColorUtils.darkenColor(bgColor)).create()

    private val searchResultList: MutableList<SearchResult> = mutableListOf()
    private var currentSearchResultIndex: Int = -1
    private var lastSearchResultIndex: Int = -1
    private val hasSearchResult: Boolean
        get() = searchResultList.isNotEmpty()
    val selectedSearchResult: SearchResult?
        get() = searchResultList.getOrNull(currentSearchResultIndex)
    val previousSearchResult: SearchResult?
        get() = searchResultList.getOrNull(lastSearchResultIndex)
    val bottomMenuVisible get() = isVisible && binding.llBottomMenu.isVisible

    init {
        initView()
        bindEvent()
        updateSearchInfo()
    }

    fun upSearchResultList(resultList: List<SearchResult>) {
        searchResultList.clear()
        searchResultList.addAll(resultList)
        updateSearchInfo()
    }

    private fun initView() = binding.run {
        llSearchBaseInfo.setBackgroundResource(R.drawable.bg_eink_border_top)
        tvCurrentSearchInfo.setTextColor(bottomBackgroundList)
        llBottomBg.setBackgroundResource(R.drawable.bg_eink_border_top)
        fabLeft.backgroundTintList = bottomBackgroundList
        fabLeft.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        fabRight.backgroundTintList = bottomBackgroundList
        fabRight.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvMainMenu.setTextColor(textColor)
        tvSearchResults.setTextColor(textColor)
        tvSearchExit.setTextColor(textColor)
        ivMainMenu.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchResults.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchExit.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchContentUp.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchContentDown.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvCurrentSearchInfo.setTextColor(textColor)
        applyNavigationBarPadding()
    }

    fun runMenuIn() {
        this.visible()
        binding.llBottomMenu.visible()
        binding.vwMenuBg.visible()
        binding.fabLeft.visible(hasSearchResult)
        binding.fabRight.visible(hasSearchResult)
        binding.vwMenuBg.setOnClickListener { runMenuOut() }
        callBack.upSystemUiVisibility()
    }

    fun runMenuOut(onMenuOutEnd: (() -> Unit)? = null) {
        if (this.isVisible) {
            binding.llBottomMenu.invisible()
            binding.vwMenuBg.invisible()
            this.invisible()
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSearchInfo() {
        ReadBook.curTextChapter?.let {
            binding.tvCurrentSearchInfo.text =
                """${context.getString(R.string.search_content_size)}: ${searchResultList.size} / 当前章节: ${it.title}"""
        }
    }

    fun updateSearchResultIndex(updateIndex: Int) {
        lastSearchResultIndex = currentSearchResultIndex
        currentSearchResultIndex = when {
            updateIndex < 0 -> 0
            updateIndex >= searchResultList.size -> searchResultList.size - 1
            else -> updateIndex
        }
    }

    private fun bindEvent() = binding.run {
        llSearchResults.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(selectedSearchResult?.query)
            }
        }

        llMainMenu.setOnClickListener {
            runMenuOut {
                callBack.cancelSelect()
                callBack.showMenuBar()
                this@SearchMenu.invisible()
            }
        }

        llSearchExit.setOnClickListener {
            runMenuOut {
                callBack.exitSearchMenu()
            }
        }

        fabLeft.setOnClickListener {
            updateSearchResultIndex(currentSearchResultIndex - 1)
            callBack.navigateToSearch(
                searchResultList[currentSearchResultIndex],
                currentSearchResultIndex
            )
        }

        ivSearchContentUp.setOnClickListener {
            updateSearchResultIndex(currentSearchResultIndex - 1)
            callBack.navigateToSearch(
                searchResultList[currentSearchResultIndex],
                currentSearchResultIndex
            )
        }

        ivSearchContentDown.setOnClickListener {
            updateSearchResultIndex(currentSearchResultIndex + 1)
            callBack.navigateToSearch(
                searchResultList[currentSearchResultIndex],
                currentSearchResultIndex
            )
        }

        fabRight.setOnClickListener {
            updateSearchResultIndex(currentSearchResultIndex + 1)
            callBack.navigateToSearch(
                searchResultList[currentSearchResultIndex],
                currentSearchResultIndex
            )
        }
    }

    interface CallBack {
        var isShowingSearchResult: Boolean
        fun openSearchActivity(searchWord: String?)
        fun showSearchSetting()
        fun upSystemUiVisibility()
        fun exitSearchMenu()
        fun showMenuBar()
        fun navigateToSearch(searchResult: SearchResult, index: Int)
        fun onMenuShow()
        fun onMenuHide()
        fun cancelSelect()
    }

}
