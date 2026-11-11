package app.jingye.reader.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan

data class ReaderStyle(
    val textSizePx: Float,
    val lineSpacingMult: Float,
    val paraSpacingPx: Int = 20,
    val letterSpacing: Float = 0.02f,
    val marginPx: Int,
    val marginVPx: Int = marginPx,
    val antiAlias: Boolean,
    val weight: Int = 400,
    val fontId: String = "serif",
    val customFontPath: String = ""
) {
    fun newPaint(ctx: Context? = null, isHeading: Boolean = false): TextPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = if (isHeading) textSizePx * 1.28f else textSizePx
        isAntiAlias = antiAlias
        isSubpixelText = true
        FontManager.applyFontToPaint(ctx, this, this@ReaderStyle, isHeading = isHeading)
    }
}

data class PageSlice(val start: Int, val end: Int)

/**
 * 为章节首行的【章节标题】放大加粗，并为每个自然段末尾附加精准的段间距（paraSpacingPx），
 * 无需插入空白行即可获得微信读书墨水屏版的呼吸感排版。
 */
fun formatChapterSpannable(
    rawText: CharSequence,
    style: ReaderStyle,
    ctx: Context? = null
): CharSequence {
    if (rawText.isEmpty()) return rawText
    val s = rawText.toString()
    val ssb = SpannableStringBuilder(s)

    // 1. 若首行是【章节标题】，应用大字号粗体标题样式
    val firstNl = s.indexOf('\n')
    val firstLineEnd = if (firstNl == -1) s.length else firstNl
    if (firstLineEnd in 2..70 && s.startsWith("【") && s.substring(0, firstLineEnd).endsWith("】")) {
        ssb.setSpan(
            ChapterHeadingSpan(ctx, style),
            0,
            firstLineEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    // 2. 为每个段落换行符附加段间距（避免使用双换行导致的过大空行）
    if (style.paraSpacingPx > 0) {
        var idx = 0
        while (idx < s.length) {
            val nl = s.indexOf('\n', idx)
            if (nl == -1) break
            if (nl > idx) {
                val extra = if (nl == firstLineEnd) (style.paraSpacingPx * 1.4f).toInt() else style.paraSpacingPx
                ssb.setSpan(
                    ParagraphSpacingSpan(extra),
                    nl,
                    nl + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            idx = nl + 1
        }
    }
    return ssb
}

private class ChapterHeadingSpan(
    private val ctx: Context?,
    private val style: ReaderStyle
) : MetricAffectingSpan() {
    override fun updateMeasureState(textPaint: TextPaint) {
        apply(textPaint)
    }

    override fun updateDrawState(tp: TextPaint) {
        apply(tp)
    }

    private fun apply(tp: TextPaint) {
        tp.textSize = style.textSizePx * 1.28f
        FontManager.applyFontToPaint(ctx, tp, style, isHeading = true)
    }
}

private class ParagraphSpacingSpan(private val extraSpacePx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        if (end > 0 && end <= text.length && text[end - 1] == '\n') {
            fm.descent += extraSpacePx
            fm.bottom += extraSpacePx
        }
    }
}

fun buildLayout(
    text: CharSequence,
    start: Int,
    end: Int,
    paint: TextPaint,
    width: Int,
    mult: Float
): StaticLayout =
    StaticLayout.Builder.obtain(text, start, end, paint, width.coerceAtLeast(100))
        .setLineSpacing(0f, mult)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
