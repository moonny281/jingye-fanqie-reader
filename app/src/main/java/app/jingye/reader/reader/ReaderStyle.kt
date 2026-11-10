package app.jingye.reader.reader

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

data class ReaderStyle(
    val textSizePx: Float,
    val lineSpacingMult: Float,
    val marginPx: Int,
    val antiAlias: Boolean,
    val weight: Int = 400
) {
    fun newPaint() = TextPaint().apply {
        color = Color.BLACK
        textSize = textSizePx
        isAntiAlias = antiAlias
        typeface = when {
            Build.VERSION.SDK_INT >= 28 -> Typeface.create(Typeface.DEFAULT, weight, false)
            weight >= 500 -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }
}

data class PageSlice(val start: Int, val end: Int)

fun buildLayout(
    text: CharSequence,
    start: Int,
    end: Int,
    paint: TextPaint,
    width: Int,
    mult: Float
): StaticLayout =
    StaticLayout.Builder.obtain(text, start, end, paint, width.coerceAtLeast(1))
        .setLineSpacing(0f, mult)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
