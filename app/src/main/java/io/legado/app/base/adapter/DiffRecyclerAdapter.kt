package io.legado.app.base.adapter

import android.content.Context
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import splitties.views.onLongClick

/**
 * DiffRecyclerAdapter（原生墨水屏模式：已禁用所有列表项加载动画与 ItemAnimator）
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
abstract class DiffRecyclerAdapter<ITEM, VB : ViewBinding>(protected val context: Context) :
    RecyclerView.Adapter<ItemViewHolder>() {

    val inflater: LayoutInflater = LayoutInflater.from(context)

    private val asyncListDiffer: AsyncListDiffer<ITEM> by lazy {
        AsyncListDiffer(this, diffItemCallback).apply {
            addListListener { _, _ ->
                onCurrentListChanged()
                if (keepScrollPosition) {
                    layoutManager?.onRestoreInstanceState(layoutState)
                    layoutState = null
                }
            }
        }
    }

    private var itemClickListener: ((holder: ItemViewHolder, item: ITEM) -> Unit)? = null
    private var itemLongClickListener: ((holder: ItemViewHolder, item: ITEM) -> Boolean)? = null

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var layoutState: Parcelable? = null

    var itemAnimation: ItemAnimation? = null

    abstract val diffItemCallback: DiffUtil.ItemCallback<ITEM>

    open val keepScrollPosition = false

    fun setOnItemClickListener(listener: (holder: ItemViewHolder, item: ITEM) -> Unit) {
        itemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (holder: ItemViewHolder, item: ITEM) -> Boolean) {
        itemLongClickListener = listener
    }

    fun bindToRecyclerView(recyclerView: RecyclerView) {
        recyclerView.itemAnimator = null
        recyclerView.adapter = this
    }

    fun setItems(items: List<ITEM>?) {
        kotlin.runCatching {
            if (keepScrollPosition) {
                layoutState = layoutManager?.onSaveInstanceState()
            }
            asyncListDiffer.submitList(items?.toMutableList())
        }
    }

    fun setItem(position: Int, item: ITEM) {
        kotlin.runCatching {
            asyncListDiffer.currentList[position] = item
            notifyItemChanged(position)
        }
    }

    fun updateItem(item: ITEM) {
        kotlin.runCatching {
            val index = asyncListDiffer.currentList.indexOf(item)
            if (index >= 0) {
                asyncListDiffer.currentList[index] = item
                notifyItemChanged(index)
            }
        }
    }

    fun updateItem(position: Int, payload: Any) {
        kotlin.runCatching {
            val size = itemCount
            if (position in 0 until size) {
                notifyItemChanged(position, payload)
            }
        }
    }

    fun updateItems(fromPosition: Int, toPosition: Int, payloads: Any) {
        kotlin.runCatching {
            val size = itemCount
            if (fromPosition in 0 until size && toPosition in 0 until size) {
                notifyItemRangeChanged(
                    fromPosition,
                    toPosition - fromPosition + 1,
                    payloads
                )
            }
        }
    }

    fun isEmpty() = asyncListDiffer.currentList.isEmpty()

    fun isNotEmpty() = asyncListDiffer.currentList.isNotEmpty()

    fun getItem(position: Int): ITEM? = asyncListDiffer.currentList.getOrNull(position)

    fun getItems(): List<ITEM> = asyncListDiffer.currentList

    protected open fun getSpanSize(viewType: Int, position: Int) = 1

    final override fun getItemCount() = getItems().size

    final override fun getItemViewType(position: Int): Int {
        return 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        return ItemViewHolder(getViewBinding(parent))
    }

    protected abstract fun getViewBinding(parent: ViewGroup): VB

    final override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {}

    open fun onCurrentListChanged() {
    }

    @Suppress("UNCHECKED_CAST")
    final override fun onBindViewHolder(
        holder: ItemViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        registerListener(holder, (holder.binding as VB))
        registerItemListener(holder)
        getItem(holder.layoutPosition)?.let {
            convert(holder, holder.binding, it, payloads)
        }
    }

    private fun registerItemListener(holder: ItemViewHolder) {
        if (itemClickListener != null) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    itemClickListener?.invoke(holder, it)
                }
            }
        }

        if (itemLongClickListener != null) {
            holder.itemView.onLongClick {
                getItem(holder.layoutPosition)?.let {
                    itemLongClickListener?.invoke(holder, it)
                }
            }
        }
    }

    override fun onViewAttachedToWindow(holder: ItemViewHolder) {
        super.onViewAttachedToWindow(holder)
        // 墨水屏模式禁用列表项进入动画
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.itemAnimator = null
        val manager = recyclerView.layoutManager
        layoutManager = manager
        if (manager is GridLayoutManager) {
            manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return getSpanSize(getItemViewType(position), position)
                }
            }
        }
    }

    protected open fun startAnimation(holder: ItemViewHolder, item: ItemAnimation) {
        // 墨水屏模式禁用列表项动画
    }

    abstract fun convert(
        holder: ItemViewHolder,
        binding: VB,
        item: ITEM,
        payloads: MutableList<Any>
    )

    abstract fun registerListener(holder: ItemViewHolder, binding: VB)

}