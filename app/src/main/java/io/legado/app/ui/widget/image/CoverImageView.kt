package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.fanqie.FanqieCrawler
import io.legado.app.fanqie.FanqieLegadoBridge
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import java.io.File

/**
 * 书架与详情页图书封面组件 (v0.4.3 深度修复版)：
 * 1. 完整支持书架列表/网格、书籍详情页的真实图书封面加载（禁用动画 dontAnimate，外加 1.5dp 纯黑 1-bit 线性外框）；
 * 2. 对旧版入架或缺失 coverUrl 的番茄/本地书籍，自动同步检测本地封面缓存并在后台自动补抓封面；
 * 3. 仅当书籍确无封面图片或网络加载失败时，绘制微信读书墨水屏风格的 1-bit 纯黑白线性书封作为回退。
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
    private var loadFailed: Boolean = false

    private val bgPaint = Paint().apply {
        isAntiAlias = false
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val outerBorderPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val innerLinePaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
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
        scaleType = ScaleType.CENTER_CROP
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        if (params != null) {
            val width = params.width
            if (width > 0 && params.height <= 0) {
                params.height = width * 4 / 3
            }
        }
        super.setLayoutParams(params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        if (heightMode == MeasureSpec.EXACTLY && heightSize > 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            val measuredHeight = if (widthSize > 0) widthSize * 4 / 3 else heightSize
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val curDrawable = drawable
        val hasRealImage = !AppConfig.useDefaultCover &&
            !loadFailed &&
            !bitmapPath.isNullOrBlank() &&
            curDrawable != null &&
            curDrawable !== BookCover.defaultDrawable

        if (hasRealImage) {
            super.onDraw(canvas)
            canvas.drawRect(1f, 1f, w - 1f, h - 1f, outerBorderPaint)
            return
        }

        // 无封面或正在回退时：绘制 1-bit 纯黑白线性装帧书封 + 书名作者
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(1.2f, 1.2f, w - 1.2f, h - 1.2f, outerBorderPaint)

        val spineX = (w * 0.11f).coerceAtLeast(7f)
        canvas.drawLine(spineX, 1.2f, spineX, h - 1.2f, innerLinePaint)
        val footerY = h * 0.78f
        canvas.drawLine(spineX, footerY, w - 1.2f, footerY, innerLinePaint)

        val bookTitle = (name ?: "书籍").trim().ifEmpty { "书籍" }
        val contentLeft = spineX + (w * 0.08f).coerceAtLeast(5f)
        val contentWidth = (w - contentLeft - (w * 0.08f).coerceAtLeast(5f)).toInt().coerceAtLeast(14)

        titlePaint.textSize = (w * 0.135f).coerceIn(14f, 32f)
        val displayTitle = if (bookTitle.length > 12) bookTitle.take(11) + "…" else bookTitle
        val titleLayout = StaticLayout(
            displayTitle,
            titlePaint,
            contentWidth,
            Layout.Alignment.ALIGN_NORMAL,
            1.12f,
            0f,
            false
        )
        canvas.save()
        canvas.translate(contentLeft, h * 0.10f)
        titleLayout.draw(canvas)
        canvas.restore()

        val bookAuthor = (author ?: "").trim()
        if (bookAuthor.isNotEmpty() && BookCover.drawBookAuthor) {
            authorPaint.textSize = (w * 0.095f).coerceIn(11f, 22f)
            val shortAuthor = if (bookAuthor.length > 6) bookAuthor.take(5) + "…" else bookAuthor
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
        val resolvedPath = FanqieLegadoBridge.resolveCachedCoverSync(book) ?: book.getDisplayCover()
        load(resolvedPath, book.name, book.author, loadOnlyWifi, book.origin, fragment, lifecycle, onLoadFinish)

        // 若该书尚未缓存本地封面或 coverUrl 缺失，后台自动补全并刷新封面
        if (resolvedPath.isNullOrBlank() || resolvedPath.startsWith("http")) {
            val expectedBookUrl = book.bookUrl
            FanqieLegadoBridge.ensureBookCoverAsync(book) { repairedPath ->
                post {
                    if (isAttachedToWindow && (this.name == book.name.replace(AppPattern.bdRegex, "").trim() || expectedBookUrl == book.bookUrl)) {
                        load(repairedPath, book.name, book.author, loadOnlyWifi, book.origin, fragment, lifecycle, onLoadFinish)
                    }
                }
            }
        }
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
        this.author = author?.replace(AppPattern.bdRegex, "")?.trim()
        this.name = name?.replace(AppPattern.bdRegex, "")?.trim()
        val normalizedPath = if (path != null && (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//"))) {
            FanqieCrawler.normalizeCoverUrl(path)
        } else {
            path?.trim()
        }
        this.bitmapPath = normalizedPath

        if (AppConfig.useDefaultCover || normalizedPath.isNullOrBlank()) {
            loadFailed = true
            try {
                Glide.with(context).clear(this)
            } catch (_: Throwable) {
            }
            setImageDrawable(null)
            onLoadFinish?.invoke()
            invalidate()
            return
        }

        loadFailed = false
        val listener = object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                loadFailed = true
                onLoadFinish?.invoke()
                post { invalidate() }
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                loadFailed = false
                onLoadFinish?.invoke()
                post { invalidate() }
                return false
            }
        }

        // 若是本地文件或番茄图片直链，直接交由 Glide 加载，避免经过书源解密器导致失败
        val localFile = if (!normalizedPath.startsWith("http") && !normalizedPath.startsWith("content://") && !normalizedPath.startsWith("data:")) {
            val cleanFilePath = if (normalizedPath.startsWith("file://")) normalizedPath.removePrefix("file://") else normalizedPath
            File(cleanFilePath).takeIf { it.isFile && it.length() > 128L }
        } else null

        if (localFile != null) {
            Glide.with(context)
                .load(localFile)
                .dontAnimate()
                .centerCrop()
                .listener(listener)
                .into(this)
            return
        }

        if (sourceOrigin == null || sourceOrigin == BookType.localTag || normalizedPath.contains("byteimg.com") || normalizedPath.contains("fqnovel")) {
            Glide.with(context)
                .load(normalizedPath)
                .dontAnimate()
                .centerCrop()
                .listener(listener)
                .into(this)
            return
        }

        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        val builder = if (fragment != null && lifecycle != null) {
            ImageLoader.load(fragment, lifecycle, normalizedPath)
        } else {
            ImageLoader.load(context, normalizedPath)
        }
        builder.apply(options)
            .dontAnimate()
            .centerCrop()
            .listener(listener)
            .into(this)
    }
}
