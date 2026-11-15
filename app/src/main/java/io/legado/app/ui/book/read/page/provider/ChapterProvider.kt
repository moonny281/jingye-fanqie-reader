package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.net.toUri
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.eink.BuiltinFontManager
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isPad
import io.legado.app.utils.postEvent
import io.legado.app.utils.spToPx
import io.legado.app.utils.textHeight
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx

/**
 * 解析内容生成章节和页面（原生墨水屏深度重构版：支持自适应屏幕分辨率分配最大阅读面积 + 字形锐化）
 */
@Suppress("DEPRECATION", "ConstPropertyName")
object ChapterProvider {
    const val srcReplaceStr = "袮"
    const val srcReplaceChar = '袮'
    const val srcReplacementChar = '祢'
    const val reviewStr = "꧁"
    const val reviewChar = '꧁'
    const val indentChar = "　"

    @JvmStatic
    var viewWidth = 0
        private set

    @JvmStatic
    var viewHeight = 0
        private set

    @JvmStatic
    var paddingLeft = 0
        private set

    @JvmStatic
    var paddingTop = 0
        private set

    @JvmStatic
    var paddingRight = 0
        private set

    @JvmStatic
    var paddingBottom = 0
        private set

    @JvmStatic
    var visibleWidth = 0
        private set

    @JvmStatic
    var visibleHeight = 0
        private set

    @JvmStatic
    var visibleRight = 0
        private set

    @JvmStatic
    var visibleBottom = 0
        private set

    @JvmStatic
    var lineSpacingExtra = 0f
        private set

    @JvmStatic
    var paragraphSpacing = 0
        private set

    @JvmStatic
    var titleTopSpacing = 0
        private set

    @JvmStatic
    var titleBottomSpacing = 0
        private set

    @JvmStatic
    var indentCharWidth = 0f
        private set

    @JvmStatic
    var titlePaintTextHeight = 0f
        private set

    @JvmStatic
    var contentPaintTextHeight = 0f
        private set

    @JvmStatic
    var titlePaintFontMetrics = FontMetrics()

    @JvmStatic
    var contentPaintFontMetrics = FontMetrics()

    @JvmStatic
    var typeface: Typeface? = Typeface.DEFAULT
        private set

    @JvmStatic
    var titlePaint: TextPaint = TextPaint()

    @JvmStatic
    var contentPaint: TextPaint = TextPaint()

    @JvmStatic
    var reviewPaint: TextPaint = TextPaint()

    @JvmStatic
    var doublePage = false
        private set

    @JvmStatic
    var visibleRect = RectF()

    init {
        upStyle()
    }

    fun getTextChapterAsync(
        scope: CoroutineScope,
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
        chapterSize: Int,
    ): TextChapter {
        return TextChapter(
            bookChapter,
            bookChapter.index, displayTitle,
            chapterSize,
            bookContent.sameTitleRemoved,
            bookChapter.isVip,
            bookChapter.isPay,
            bookContent.effectiveReplaceRules
        ).apply {
            createLayout(scope, book, bookContent)
        }
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        typeface = getTypeface(ReadBookConfig.textFont)
        getPaints(typeface).let {
            titlePaint = it.first
            contentPaint = it.second
        }
        // 间距
        lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f
        paragraphSpacing = ReadBookConfig.paragraphSpacing
        titleTopSpacing = ReadBookConfig.titleTopSpacing.dpToPx()
        titleBottomSpacing = ReadBookConfig.titleBottomSpacing.dpToPx()
        val bodyIndent = ReadBookConfig.paragraphIndent
        indentCharWidth = if (bodyIndent.isNotEmpty()) {
            var indentWidth = StaticLayout.getDesiredWidth(bodyIndent, contentPaint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                indentWidth += contentPaint.letterSpacing * contentPaint.textSize
            }
            indentWidth / bodyIndent.length
        } else {
            0f
        }
        titlePaintTextHeight = titlePaint.textHeight
        contentPaintTextHeight = contentPaint.textHeight
        titlePaintFontMetrics = titlePaint.fontMetrics
        contentPaintFontMetrics = contentPaint.fontMetrics
        upLayout()
    }

    private fun getTypeface(fontPath: String): Typeface? {
        return kotlin.runCatching {
            when {
                BuiltinFontManager.isBuiltinFontKey(fontPath) -> {
                    BuiltinFontManager.resolveTypeface(appCtx, fontPath)
                }
                fontPath.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.contentResolver
                        .openFileDescriptor(fontPath.toUri(), "r")!!
                        .use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                }
                fontPath.isContentScheme() -> {
                    Typeface.createFromFile(RealPathUtil.getPath(appCtx, fontPath.toUri()))
                }
                fontPath.isNotEmpty() -> Typeface.createFromFile(fontPath)
                else -> when (AppConfig.systemTypefaces) {
                    1 -> Typeface.SERIF
                    2 -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
            }
        }.getOrElse {
            ReadBookConfig.textFont = ""
            ReadBookConfig.save()
            Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    private fun getPaints(typeface: Typeface?): Pair<TextPaint, TextPaint> {
        val bold = Typeface.create(typeface, Typeface.BOLD)
        val normal = Typeface.create(typeface, Typeface.NORMAL)
        val (titleFont, textFont) = when (ReadBookConfig.textBold) {
            1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(Typeface.create(typeface, 900, false), bold)
                else
                    Pair(bold, bold)
            }
            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(normal, Typeface.create(typeface, 300, false))
                else
                    Pair(normal, normal)
            }
            else -> Pair(bold, normal)
        }

        // 墨水屏高对比锐利字形画笔配置：关闭亚像素灰边、开启微调 Hinting、关闭额外上下行高冗余
        val tPaint = TextPaint().apply {
            color = ReadBookConfig.textColor
            letterSpacing = ReadBookConfig.letterSpacing
            this.typeface = titleFont
            textSize = with(ReadBookConfig) { textSize + titleSize }.toFloat().spToPx()
            isAntiAlias = true
            isSubpixelText = false
            isFilterBitmap = false
            hinting = Paint.HINTING_ON
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isElegantTextHeight = false
            }
            clearShadowLayer()
        }

        val cPaint = TextPaint().apply {
            color = ReadBookConfig.textColor
            letterSpacing = ReadBookConfig.letterSpacing
            this.typeface = textFont
            textSize = ReadBookConfig.textSize.toFloat().spToPx()
            isAntiAlias = true
            isSubpixelText = false
            isFilterBitmap = false
            hinting = Paint.HINTING_ON
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isElegantTextHeight = false
            }
            clearShadowLayer()
        }

        return Pair(tPaint, cPaint)
    }

    /**
     * 更新View尺寸（墨水屏即时响应高度/分辨率变化，无 300ms 延迟）
     */
    fun upViewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (width != viewWidth || height != viewHeight) {
            notifyViewSizeChange(width, height)
        }
    }

    private fun notifyViewSizeChange(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        upLayout()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    /**
     * 更新绘制尺寸（支持自适应屏幕分辨率动态分配阅读显示面积）
     */
    fun upLayout() {
        when (AppConfig.doublePageHorizontal) {
            "0" -> doublePage = false
            "1" -> doublePage = true
            "2" -> {
                doublePage = (viewWidth > viewHeight) && ReadBook.pageAnim() != 3
            }
            "3" -> {
                doublePage = (viewWidth > viewHeight || appCtx.isPad) && ReadBook.pageAnim() != 3
            }
        }

        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }

        if (EinkRefreshHelper.adaptiveScreenResolution) {
            val dm = appCtx.resources.displayMetrics
            val adaptive = EinkRefreshHelper.computeAdaptiveMargins(viewWidth, viewHeight, dm.density)
            paddingLeft = adaptive.paddingLeftDp.dpToPx()
            paddingRight = adaptive.paddingRightDp.dpToPx()
            paddingTop = adaptive.paddingTopDp.dpToPx()
            paddingBottom = adaptive.paddingBottomDp.dpToPx()
        } else {
            paddingLeft = ReadBookConfig.paddingLeft.dpToPx()
            paddingTop = ReadBookConfig.paddingTop.dpToPx()
            paddingRight = ReadBookConfig.paddingRight.dpToPx()
            paddingBottom = ReadBookConfig.paddingBottom.dpToPx()
        }

        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        visibleHeight = viewHeight - paddingTop - paddingBottom
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight

        if (paddingLeft >= visibleRight || paddingTop >= visibleBottom) {
            AppLog.put("边距设置过大，已自动恢复墨水屏紧凑边距", toast = true)
            setFallbackLayout()
        }

        visibleRect.set(
            paddingLeft.toFloat() - 6f,
            paddingTop.toFloat() - 6f,
            visibleRight.toFloat() + 6f,
            visibleBottom.toFloat() + 6f.dpToPx()
        )
    }

    private fun setFallbackLayout() {
        paddingLeft = 12.dpToPx()
        paddingTop = 4.dpToPx()
        paddingRight = 12.dpToPx()
        paddingBottom = 4.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        visibleHeight = viewHeight - paddingTop - paddingBottom
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight
    }

}
