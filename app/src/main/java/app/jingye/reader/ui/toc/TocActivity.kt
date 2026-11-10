package app.jingye.reader.ui.toc

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.jingye.reader.JingyeApp
import app.jingye.reader.R
import kotlinx.coroutines.launch

class TocActivity : AppCompatActivity() {

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
        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        rv.overScrollMode = View.OVER_SCROLL_NEVER

        findViewById<View>(R.id.btnCloseToc).setOnClickListener { finish() }

        lifecycleScope.launch {
            val count = app.source.chapterCount(bookId)
            val titles = (0 until count).map { i -> app.source.chapterTitle(bookId, i) }
            header.text = "目录 (共 $count 章)"
            rv.adapter = TocAdapter(titles, currentChapter) { selectedIndex ->
                setResult(RESULT_OK, Intent().putExtra("chapter", selectedIndex))
                finish()
            }
            if (currentChapter in 0 until count) {
                (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentChapter, 120)
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private class TocAdapter(
        private val titles: List<String>,
        private val currentIndex: Int,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<TocAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_toc, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val raw = titles[position]
            holder.title.text = if (position == currentIndex) "▶ $raw" else raw
            holder.title.paint.isFakeBoldText = (position == currentIndex)
            holder.title.setOnClickListener { onSelect(position) }
        }

        override fun getItemCount(): Int = titles.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tocItemTitle)
        }
    }
}
