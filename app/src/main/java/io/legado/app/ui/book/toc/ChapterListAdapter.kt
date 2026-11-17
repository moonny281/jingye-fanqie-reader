package io.legado.app.ui.book.toc

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ItemChapterListBinding
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 仿番茄小说 App 墨水屏 1-bit 纯黑白线性可折叠目录适配器 (v0.4.5)：
 * 1. 严格还原番茄小说原生分卷名与章节名（不强加人工前缀）；
 * 2. 一级分卷行（isVolume = true）支持点击原地折叠/展开该卷下的全部二级子章节，右侧直观显示「▼ 共N章」或「▶ 已折叠(N章)」；
 * 3. 二级子章节行（isVolume = false）采用纯白底 + 1dp 黑线分割，当前阅读章节加粗突出显示。
 */
class ChapterListAdapter(context: Context, val callback: Callback) :
    DiffRecyclerAdapter<BookChapter, ItemChapterListBinding>(context) {

    val cacheFileNames = hashSetOf<String>()
    private val displayTitleMap = ConcurrentHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())

    override val diffItemCallback: DiffUtil.ItemCallback<BookChapter>
        get() = object : DiffUtil.ItemCallback<BookChapter>() {

            override fun areItemsTheSame(
                oldItem: BookChapter,
                newItem: BookChapter
            ): Boolean {
                return oldItem.index == newItem.index
            }

            override fun areContentsTheSame(
                oldItem: BookChapter,
                newItem: BookChapter
            ): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
                    && oldItem.url == newItem.url
                    && oldItem.isVip == newItem.isVip
                    && oldItem.isPay == newItem.isPay
                    && oldItem.title == newItem.title
                    && oldItem.tag == newItem.tag
                    && oldItem.wordCount == newItem.wordCount
                    && oldItem.isVolume == newItem.isVolume
            }
        }

    private var upDisplayTileJob: Coroutine<*>? = null

    override fun onCurrentListChanged() {
        super.onCurrentListChanged()
        callback.onListChanged()
    }

    fun clearDisplayTitle() {
        upDisplayTileJob?.cancel()
        displayTitleMap.clear()
    }

    fun upDisplayTitles(startIndex: Int) {
        upDisplayTileJob?.cancel()
        upDisplayTileJob = Coroutine.async(callback.scope) {
            val book = callback.book ?: return@async
            val replaceRules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
            val replaceBook = book.toReplaceBook()
            val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
            val items = getItems()
            if (items.isEmpty()) return@async
            val safeStart = startIndex.coerceIn(0, items.lastIndex)
            launch {
                for (i in safeStart until items.size) {
                    val item = items[i]
                    if (displayTitleMap[item.title] == null) {
                        ensureActive()
                        val displayTitle = item.getDisplayTitle(replaceRules, useReplace, replaceBook = replaceBook)
                        ensureActive()
                        displayTitleMap[item.title] = displayTitle
                        handler.post {
                            notifyItemChanged(i, true)
                        }
                    }
                }
            }
            launch {
                for (i in safeStart downTo 0) {
                    val item = items[i]
                    if (displayTitleMap[item.title] == null) {
                        ensureActive()
                        val displayTitle = item.getDisplayTitle(replaceRules, useReplace, replaceBook = replaceBook)
                        ensureActive()
                        displayTitleMap[item.title] = displayTitle
                        handler.post {
                            notifyItemChanged(i, true)
                        }
                    }
                }
            }
        }
    }

    private fun getDisplayTitle(chapter: BookChapter): String {
        return displayTitleMap[chapter.title] ?: chapter.title
    }

    override fun getViewBinding(parent: ViewGroup): ItemChapterListBinding {
        return ItemChapterListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChapterListBinding,
        item: BookChapter,
        payloads: MutableList<Any>
    ) {
        binding.run {
            val isDur = callback.durChapterIndex() == item.index
            val rawTitle = getDisplayTitle(item)

            ivLocked.gone()
            ivChecked.gone()

            if (item.isVolume) {
                val collapsed = callback.isVolumeCollapsed(item.index)
                val subCount = callback.getVolumeSubChapterCount(item.index)
                val arrow = if (collapsed) "▶" else "▼"
                // 番茄 App 风格分卷横幅：1-bit 纯黑底白字粗体，支持点击折叠/展开子目录
                tvChapterItem.setBackgroundColor(Color.BLACK)
                tvChapterName.setTextColor(Color.WHITE)
                tvChapterName.typeface = Typeface.DEFAULT_BOLD
                tvChapterName.text = "$arrow  $rawTitle"

                tvTag.setTextColor(Color.WHITE)
                tvTag.text = when {
                    subCount > 0 && collapsed -> "已折叠 ($subCount 章)"
                    subCount > 0 -> "共 $subCount 章"
                    !item.tag.isNullOrEmpty() -> item.tag
                    else -> "点击折叠/展开"
                }
                tvTag.visible()

                if (!item.wordCount.isNullOrEmpty()) {
                    tvWordCount.setTextColor(Color.WHITE)
                    tvWordCount.text = item.wordCount
                    tvWordCount.visible()
                } else {
                    tvWordCount.gone()
                }
            } else {
                // 二级章节行：纯白底色 + 底部 1dp 线性黑线，保留番茄原生章节名
                tvChapterItem.setBackgroundResource(R.drawable.bg_eink_border_bottom)
                tvChapterName.setTextColor(Color.BLACK)
                tvChapterName.typeface = if (isDur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tvChapterName.text = if (isDur) "●  $rawTitle" else "    $rawTitle"

                tvTag.gone()

                if (AppConfig.tocCountWords && !item.wordCount.isNullOrEmpty()) {
                    tvWordCount.setTextColor(Color.BLACK)
                    tvWordCount.text = item.wordCount
                    tvWordCount.visible()
                } else {
                    tvWordCount.gone()
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChapterListBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                if (item.isVolume) {
                    callback.onVolumeHeaderClick(item)
                } else {
                    callback.openChapter(item)
                }
            }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                context.longToastOnUi(getDisplayTitle(item))
            }
            true
        }
    }

    interface Callback {
        val scope: CoroutineScope
        val book: Book?
        val isLocalBook: Boolean
        fun openChapter(bookChapter: BookChapter)
        fun onVolumeHeaderClick(volumeChapter: BookChapter)
        fun isVolumeCollapsed(volumeIndex: Int): Boolean
        fun getVolumeSubChapterCount(volumeIndex: Int): Int
        fun durChapterIndex(): Int
        fun onListChanged()
    }
}
