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
import kotlinx.coroutines.launch

class TocActivity : AppCompatActivity() {

    private var reversed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toc)

        val app = application as JingyeApp
        val bookId = intent.getStringExtra("bookId") ?: run {
            finish()
            return
        }
        val currentChapter = intent.getIntExtra("chapter", 0)

        val rv = findViewById<RecyclerView>(R.id.tocRecyclerView)
        val header = findViewById<TextView>(R.id.tocHeaderTitle)
        val btnReverse = findViewById<Button>(R.id.btnReverseToc)

        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        rv.overScrollMode = View.OVER_SCROLL_NEVER

        findViewById<View>(R.id.btnCloseToc).setOnClickListener { finish() }

        lifecycleScope.launch {
            val count = app.source.chapterCount(bookId)
            val entries = (0 until count).map { i -> i to app.source.chapterTitle(bookId, i) }
            header.text = "目录 ($count 章)"

            val adapter = TocAdapter(entries, currentChapter) { selectedIndex ->
                setResult(RESULT_OK, Intent().putExtra("chapter", selectedIndex))
                finish()
            }
            rv.adapter = adapter

            if (currentChapter in 0 until count) {
                (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentChapter, 160)
            }

            btnReverse.setOnClickListener {
                reversed = !reversed
                btnReverse.text = if (reversed) "正序" else "倒序"
                val newList = if (reversed) entries.asReversed() else entries
                adapter.submit(newList)
                rv.scrollToPosition(0)
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private class TocAdapter(
        private var items: List<Pair<Int, String>>,
        private val currentIndex: Int,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<TocAdapter.VH>() {

        fun submit(newItems: List<Pair<Int, String>>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_toc, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (origIndex, rawTitle) = items[position]
            val isCurrent = (origIndex == currentIndex)
            holder.title.text = rawTitle
            holder.title.paint.isFakeBoldText = isCurrent
            holder.badge.visibility = if (isCurrent) View.VISIBLE else View.GONE
            holder.row.setOnClickListener { onSelect(origIndex) }
        }

        override fun getItemCount(): Int = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val row: View = v.findViewById(R.id.tocRowRoot)
            val title: TextView = v.findViewById(R.id.tocItemTitle)
            val badge: View = v.findViewById(R.id.tocCurrentBadge)
        }
    }
}
