package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfList2Binding
import io.legado.app.fanqie.FanqieUiHelper
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible
import splitties.views.onLongClick

class BooksAdapterList2(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : BaseBooksAdapter<ItemBookshelfList2Binding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfList2Binding {
        return ItemBookshelfList2Binding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfList2Binding,
        item: Book,
        payloads: MutableList<Any>
    ) = binding.run {
        ivCover.gone()
        ivAuthor.gone()
        ivLast.gone()
        vwForeground.gone()

        if (payloads.isEmpty()) {
            tvName.text = item.name
            tvAuthor.text = item.author.ifBlank { "未知" }
            tvRead.text = "读至：${item.durChapterTitle?.takeIf { it.isNotBlank() } ?: "未读"}"
            tvLast.text = "最新：${item.latestChapterTitle?.takeIf { it.isNotBlank() } ?: "无"}"
            upRefresh(binding, item)
            upLastUpdateTime(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> tvName.text = item.name
                        "author" -> tvAuthor.text = item.author.ifBlank { "未知" }
                        "dur" -> tvRead.text = "读至：${item.durChapterTitle?.takeIf { s -> s.isNotBlank() } ?: "未读"}"
                        "last" -> tvLast.text = "最新：${item.latestChapterTitle?.takeIf { s -> s.isNotBlank() } ?: "无"}"
                        "refresh" -> upRefresh(binding, item)
                        "lastUpdateTime" -> upLastUpdateTime(binding, item)
                    }
                }
            }
        }
    }

    private fun upRefresh(binding: ItemBookshelfList2Binding, item: Book) {
        FanqieUiHelper.bindShelfItem(binding.root, binding.tvFanqieAction, binding.bvUnread, item)
        if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
            binding.bvUnread.invisible()
            binding.rlLoading.visible()
        } else {
            binding.rlLoading.gone()
            if (AppConfig.showUnread) {
                binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
            } else {
                binding.bvUnread.invisible()
            }
        }
    }

    private fun upLastUpdateTime(binding: ItemBookshelfList2Binding, item: Book) {
        if (AppConfig.showLastUpdateTime && !item.isLocal) {
            val time = item.latestChapterTime.toTimeAgo()
            if (binding.tvLastUpdateTime.text != time) {
                binding.tvLastUpdateTime.text = time
            }
        } else {
            binding.tvLastUpdateTime.text = ""
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfList2Binding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }
    }
}
