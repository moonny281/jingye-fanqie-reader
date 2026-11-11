package app.jingye.reader.ui.toc

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TocActivity : AppCompatActivity() {

    data class TocItem(val index: Int, val title: String)

    private lateinit var recyclerView: RecyclerView
    private var chapters: List<TocItem> = emptyList()
    private var currentChapterIndex: Int = 0
    private var reversed: Boolean = false

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CURRENT_CHAPTER = "current_chapter"
        const val EXTRA_SELECTED_CHAPTER = "selected_chapter"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toc)

        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run {
            finish()
            return
        }
        currentChapterIndex = intent.getIntExtra(EXTRA_CURRENT_CHAPTER, 0)

        val tvTocTitle = findViewById<TextView>(R.id.tvTocTitle)
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(0, 0)
        }

        val btnReverse = findViewById<Button>(R.id.btnReverseToc)
        btnReverse.setOnClickListener {
            reversed = !reversed
            btnReverse.text = if (reversed) "正序" else "倒序"
            bindList()
        }

        recyclerView = findViewById(R.id.tocRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.itemAnimator = null

        val app = application as JingyeApp
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val count = app.source.chapterCount(bookId)
                (0 until count).map { idx ->
                    TocItem(idx, app.source.chapterTitle(bookId, idx))
                }
            }
            chapters = loaded
            tvTocTitle.text = "目录（共 ${chapters.size} 章）"
            bindList()
            val scrollTarget = if (reversed) (chapters.size - 1 - currentChapterIndex) else currentChapterIndex
            if (scrollTarget in chapters.indices) {
                recyclerView.scrollToPosition(scrollTarget)
            }
        }
    }

    private fun bindList() {
        val displayList = if (reversed) chapters.reversed() else chapters
        recyclerView.adapter = TocAdapter(displayList)
    }

    private inner class TocAdapter(
        private val list: List<TocItem>
    ) : RecyclerView.Adapter<TocAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tocChapterTitle)
            val badge: TextView = view.findViewById(R.id.tocCurrentBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_toc, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.title.text = item.title
            holder.badge.visibility = if (item.index == currentChapterIndex) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                val res = Intent().apply {
                    putExtra(EXTRA_SELECTED_CHAPTER, item.index)
                }
                setResult(RESULT_OK, res)
                finish()
                overridePendingTransition(0, 0)
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
