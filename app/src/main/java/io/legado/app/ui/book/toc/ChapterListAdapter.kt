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
 * 微信读书墨水屏风格纯黑白线性目录适配器 (v0.4.3)：
 * - 分卷标题行（isVolume = true）采用纯黑底白字粗体横幅（#000000 底 + #FFFFFF 字），层级一目了然；
 * - 普通章节行采用纯白底 + 1dp 纯黑底分割线，当前阅读章节自动加粗并标注「【当前】」前缀；
 * - 彻底移除灰阶背景、水波纹与多余小图标。
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
            launch {
                for (i in startIndex until items.size) {
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
                for (i in startIndex downTo 0) {
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
                // 一级目录（分卷标题）：纯黑底色 + 纯白粗体文字（1-bit 反转线性横幅）
                tvChapterItem.setBackgroundColor(Color.BLACK)
                tvChapterName.setTextColor(Color.WHITE)
                tvChapterName.typeface = Typeface.DEFAULT_BOLD
                tvChapterName.text = "◆ [一级分卷] $rawTitle"
                if (!item.tag.isNullOrEmpty()) {
                    tvTag.setTextColor(Color.WHITE)
                    tvTag.text = item.tag
                    tvTag.visible()
                } else {
                    tvTag.gone()
                }
                if (!item.wordCount.isNullOrEmpty()) {
                    tvWordCount.setTextColor(Color.WHITE)
                    tvWordCount.text = item.wordCount
                    tvWordCount.visible()
                } else {
                    tvWordCount.gone()
                }
            } else {
                // 二级目录（具体章节）：纯白底色 + 底部 1dp 线性黑线 + 树状二级缩进前缀
                tvChapterItem.setBackgroundResource(R.drawable.bg_eink_border_bottom)
                tvChapterName.setTextColor(Color.BLACK)
                tvChapterName.typeface = if (isDur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tvChapterName.text = if (isDur) "  ├─ 【当前】$rawTitle" else "  ├─ $rawTitle"

                if (!item.tag.isNullOrEmpty()) {
                    tvTag.setTextColor(Color.BLACK)
                    tvTag.text = item.tag
                    tvTag.visible()
                } else {
                    tvTag.gone()
                }

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
            getItem(holder.layoutPosition)?.let {
                callback.openChapter(it)
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
        fun durChapterIndex(): Int
        fun onListChanged()
    }
}