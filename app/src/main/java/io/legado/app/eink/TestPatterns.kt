package io.legado.app.eink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.View

/**
 * 纯白墨水屏画布占位视图（已移除旧版棋盘测试格，避免任何网格干扰）。
 */
class TestPatterns @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var patternIndex: Int = 0
    var flashColor: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(flashColor ?: Color.WHITE)
    }
}
