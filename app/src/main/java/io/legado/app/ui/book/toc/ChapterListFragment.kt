package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterListFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_chapter_list),
    ChapterListAdapter.Callback,
    TocViewModel.ChapterListCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentChapterListBinding::bind)
    private val mLayoutManager by lazy { UpLinearLayoutManager(requireContext()) }
    private val adapter by lazy { ChapterListAdapter(requireContext(), this) }
    private var durChapterIndex = 0

    private var chapterList: List<BookChapter> = emptyList()
    private val collapsedVolumes = HashSet<Int>()
    private val volumeSubCounts = HashMap<Int, Int>()
    private var currentSearchKey: String? = null
    private var shouldScrollToDurOnNextChange = true

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        viewModel.chapterListCallBack = this@ChapterListFragment
        llChapterBaseInfo.setBackgroundResource(R.drawable.bg_eink_border_top)
        tvCurrentChapterInfo.setTextColor(Color.BLACK)
        ivChapterTop.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
        ivChapterBottom.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
        initRecyclerView()
        initView()
        viewModel.bookData.observe(this@ChapterListFragment) {
            initBook(it)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() = binding.run {
        ivChapterTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
            EinkRefreshHelper.scrubAfterOverlayDismiss(binding.root)
        }
        ivChapterBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
                EinkRefreshHelper.scrubAfterOverlayDismiss(binding.root)
            }
        }
        tvCurrentChapterInfo.setOnClickListener {
            // 若当前章节所在分卷已被折叠，先自动展开该分卷再定位
            val parentVolIdx = findParentVolumeIndex(durChapterIndex)
            if (parentVolIdx != null && collapsedVolumes.remove(parentVolIdx)) {
                shouldScrollToDurOnNextChange = true
                applyVolumeFilterAndSubmit()
            } else {
                scrollToDurChapter()
            }
        }
        tvToggleVolumes.setOnClickListener {
            toggleAllVolumes()
        }
        binding.llChapterBaseInfo.applyNavigationBarPadding()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        lifecycleScope.launch {
            durChapterIndex = book.durChapterIndex
            shouldScrollToDurOnNextChange = true
            upChapterList(null)
            binding.tvCurrentChapterInfo.text =
                "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
            initCacheFileNames(book)
        }
    }

    private fun initCacheFileNames(book: Book) {
        lifecycleScope.launch(IO) {
            adapter.cacheFileNames.addAll(BookHelp.getChapterFiles(book))
            withContext(Main) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    adapter.cacheFileNames.add(chapter.getFileName())
                    adapter.getItems().forEachIndexed { index, bookChapter ->
                        if (bookChapter.index == chapter.index) {
                            adapter.notifyItemChanged(index, true)
                        }
                    }
                }
            }
        }
    }

    private fun rebuildVolumeStats(all: List<BookChapter>) {
        volumeSubCounts.clear()
        var currentVolIdx: Int? = null
        for (ch in all) {
            if (ch.isVolume) {
                currentVolIdx = ch.index
                volumeSubCounts[currentVolIdx] = 0
            } else if (currentVolIdx != null) {
                volumeSubCounts[currentVolIdx] = (volumeSubCounts[currentVolIdx] ?: 0) + 1
            }
        }
    }

    private fun findParentVolumeIndex(targetChapterIndex: Int): Int? {
        var lastVolIdx: Int? = null
        for (ch in chapterList) {
            if (ch.isVolume) {
                lastVolIdx = ch.index
            }
            if (ch.index == targetChapterIndex) {
                return lastVolIdx
            }
        }
        return lastVolIdx
    }

    private fun updateToggleVolumesButton() {
        val totalVolumes = volumeSubCounts.size
        if (totalVolumes == 0 || !currentSearchKey.isNullOrBlank()) {
            binding.tvToggleVolumes.visibility = View.GONE
            return
        }
        binding.tvToggleVolumes.visibility = View.VISIBLE
        val allCollapsed = collapsedVolumes.size >= totalVolumes
        binding.tvToggleVolumes.text = if (allCollapsed) "展开分卷" else "折叠分卷"
    }

    private fun applyVolumeFilterAndSubmit() {
        val all = chapterList
        if (all.isEmpty()) {
            adapter.setItems(emptyList())
            updateToggleVolumesButton()
            return
        }
        if (!currentSearchKey.isNullOrBlank() || volumeSubCounts.isEmpty()) {
            adapter.setItems(all)
            updateToggleVolumesButton()
            return
        }
        val filtered = ArrayList<BookChapter>(all.size)
        var currentVolCollapsed = false
        for (ch in all) {
            if (ch.isVolume) {
                currentVolCollapsed = ch.index in collapsedVolumes
                filtered.add(ch)
            } else if (!currentVolCollapsed) {
                filtered.add(ch)
            }
        }
        adapter.setItems(filtered)
        updateToggleVolumesButton()
    }

    private fun toggleAllVolumes() {
        if (volumeSubCounts.isEmpty()) return
        shouldScrollToDurOnNextChange = false
        if (collapsedVolumes.size >= volumeSubCounts.size) {
            collapsedVolumes.clear()
        } else {
            collapsedVolumes.addAll(volumeSubCounts.keys)
        }
        applyVolumeFilterAndSubmit()
        EinkRefreshHelper.scrubAfterOverlayDismiss(binding.root)
    }

    override fun onVolumeHeaderClick(volumeChapter: BookChapter) {
        shouldScrollToDurOnNextChange = false
        if (!collapsedVolumes.remove(volumeChapter.index)) {
            collapsedVolumes.add(volumeChapter.index)
        }
        applyVolumeFilterAndSubmit()
        EinkRefreshHelper.scrubAfterOverlayDismiss(binding.root)
    }

    override fun isVolumeCollapsed(volumeIndex: Int): Boolean {
        return volumeIndex in collapsedVolumes
    }

    override fun getVolumeSubChapterCount(volumeIndex: Int): Int {
        return volumeSubCounts[volumeIndex] ?: 0
    }

    override fun upChapterList(searchKey: String?) {
        currentSearchKey = searchKey
        lifecycleScope.launch {
            val list = withContext(IO) {
                val end = (book?.simulatedTotalChapterNum() ?: Int.MAX_VALUE) - 1
                when {
                    searchKey.isNullOrBlank() ->
                        appDb.bookChapterDao.getChapterList(viewModel.bookUrl, 0, end)
                    else ->
                        appDb.bookChapterDao.search(viewModel.bookUrl, searchKey, 0, end)
                }
            }
            if (searchKey.isNullOrBlank()) {
                chapterList = list
                rebuildVolumeStats(list)
                // 默认展开当前阅读章节所在的分卷（若分卷总数较多，仍保持当前阅读卷展开）
                val curParentVol = findParentVolumeIndex(durChapterIndex)
                if (curParentVol != null) {
                    collapsedVolumes.remove(curParentVol)
                }
                applyVolumeFilterAndSubmit()
            } else {
                adapter.setItems(list)
                updateToggleVolumesButton()
            }
        }
    }

    private fun scrollToDurChapter() {
        lifecycleScope.launch {
            var scrollPos = 0
            withContext(Default) {
                adapter.getItems().forEachIndexed { index, bookChapter ->
                    if (bookChapter.index >= durChapterIndex) {
                        scrollPos = index
                        return@withContext
                    }
                    scrollPos = index
                }
            }
            mLayoutManager.scrollToPositionWithOffset(scrollPos, 0)
            adapter.upDisplayTitles(scrollPos)
            EinkRefreshHelper.scrubAfterOverlayDismiss(binding.root)
        }
    }

    override fun onListChanged() {
        if (shouldScrollToDurOnNextChange) {
            shouldScrollToDurOnNextChange = false
            scrollToDurChapter()
        } else {
            val firstPos = mLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
            adapter.upDisplayTitles(firstPos)
        }
    }

    override fun clearDisplayTitle() {
        adapter.clearDisplayTitle()
        adapter.upDisplayTitles(mLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0))
    }

    override fun upAdapter() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override val scope: CoroutineScope
        get() = lifecycleScope

    override val book: Book?
        get() = viewModel.bookData.value

    override val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

    override fun openChapter(bookChapter: BookChapter) {
        if (bookChapter.isVolume) {
            onVolumeHeaderClick(bookChapter)
            return
        }
        activity?.run {
            if (book?.isVideo == true) {
                val volumes = arrayListOf<BookChapter>()
                chapterList.forEach { chapter ->
                    if (chapter.isVolume) {
                        volumes.add(chapter)
                    }
                }
                var chapterInVolumeIndex = 0
                var durVolumeIndex = 0
                if (volumes.isNotEmpty()) {
                    for ((index, volume) in volumes.reversed().withIndex()) {
                        val first = bookChapter.index
                        if (volume.index < first) {
                            chapterInVolumeIndex = first - volume.index - 1
                            durVolumeIndex = volumes.size - index - 1
                            break
                        } else if (volume.index == first) {
                            chapterInVolumeIndex = 0
                            durVolumeIndex = volumes.size - index - 1
                            break
                        }
                    }
                } else {
                    chapterInVolumeIndex = bookChapter.index
                }
                setResult(
                    RESULT_OK, Intent()
                        .putExtra("index", bookChapter.index)
                        .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
                        .putExtra("durVolumeIndex", durVolumeIndex)
                        .putExtra("chapterInVolumeIndex", chapterInVolumeIndex)
                )
                finish()
                return@run
            }
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
            )
            finish()
        }
    }
}
