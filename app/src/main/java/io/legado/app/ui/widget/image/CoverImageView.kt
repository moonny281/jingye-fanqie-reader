package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook

/**
 * 微信读书墨水屏风格纯黑白线性书封组件 (v0.4.3)：
 * 彻底移除 Glide 网络/本地位图加载与图片解码，不渲染任何灰阶图片。
 * 在网格模式下直接用纯黑白 1-bit 几何线条绘制装帧线框、书名与作者；在列表模式下自动隐藏。
 */
@Suppress("unused")
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var bitmapPath: String? = null
        private set
    private var name: String? = null
    private var author: String? = null

    private val bgPaint = Paint().apply {
        isAntiAlias = false
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val outerBorderPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val innerLinePaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val titlePaint = TextPaint().apply {
        isAntiAlias = true
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private val authorPaint = TextPaint().apply {
        isAntiAlias = true
        color = Color.BLACK
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.LEFT
    }

    init {
        setImageDrawable(null)
        setBackgroundColor(Color.WHITE)
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        if (params != null) {
            val width = params.width
            if (width >= 0) {
                params.height = width * 4 / 3
            } else {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        super.setLayoutParams(params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = if (measuredWidth > 0) measuredWidth * 4 / 3 else 0
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    @Suppress("DEPRECATION")
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        // 1. 纯白底色 + 外层粗黑线性边框
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, outerBorderPaint)

        // 2. 微信读书墨水屏线性装帧左书脊竖线与顶底装饰线
        val spineX = (w * 0.11f).coerceAtLeast(8f)
        canvas.drawLine(spineX, 1.5f, spineX, h - 1.5f, innerLinePaint)
        val footerY = h * 0.78f
        canvas.drawLine(spineX, footerY, w - 1.5f, footerY, innerLinePaint)

        // 3. 绘制纯黑书名
        val bookTitle = (name ?: "书籍").trim().ifEmpty { "书籍" }
        val contentLeft = spineX + (w * 0.08f).coerceAtLeast(6f)
        val contentWidth = (w - contentLeft - (w * 0.08f).coerceAtLeast(6f)).toInt().coerceAtLeast(16)

        titlePaint.textSize = (w * 0.135f).coerceIn(18f, 34f)
        val displayTitle = if (bookTitle.length > 14) bookTitle.take(13) + "…" else bookTitle
        val titleLayout = StaticLayout(
            displayTitle,
            titlePaint,
            contentWidth,
            Layout.Alignment.ALIGN_NORMAL,
            1.15f,
            0f,
            false
        )
        canvas.save()
        canvas.translate(contentLeft, h * 0.10f)
        titleLayout.draw(canvas)
        canvas.restore()

        // 4. 绘制底部纯黑作者名
        val bookAuthor = (author ?: "").trim()
        if (bookAuthor.isNotEmpty()) {
            authorPaint.textSize = (w * 0.095f).coerceIn(14f, 24f)
            val shortAuthor = if (bookAuthor.length > 7) bookAuthor.take(6) + "…" else bookAuthor
            val authorY = footerY + (h - footerY) * 0.62f
            canvas.drawText(shortAuthor, contentLeft, authorY, authorPaint)
        }
    }

    fun setHeight(height: Int) {
        val width = height * 3 / 4
        minimumWidth = width
    }

    fun load(
        searchBook: SearchBook,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null
    ) {
        load(searchBook.coverUrl, searchBook.name, searchBook.author, loadOnlyWifi, searchBook.origin, fragment, lifecycle)
    }

    fun load(
        book: Book,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null
    ) {
        load(book.getDisplayCover(), book.name, book.author, loadOnlyWifi, book.origin, fragment, lifecycle, onLoadFinish)
    }

    fun load(
        path: String? = null,
        name: String? = null,
        author: String? = null,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null
    ) {
        this.bitmapPath = null
        this.name = name?.replace(AppPattern.bdRegex, "")?.trim()
        this.author = author?.replace(AppPattern.bdRegex, "")?.trim()
        setImageDrawable(null)
        onLoadFinish?.invoke()
        invalidate()
    }
}
