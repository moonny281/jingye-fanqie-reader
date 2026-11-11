package app.jingye.reader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class PageView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    enum class Zone { LEFT, CENTER, RIGHT }

    var onTapZone: ((Zone) -> Unit)? = null
    /**
     * 左右滑动手势回调：
     * swipeLeft = true 表示手指从右往左滑（通常为下一页）
     * swipeLeft = false 表示手指从左往右滑（通常为上一页）
     */
    var onSwipeHorizontal: ((swipeLeft: Boolean) -> Unit)? = null
    var swapZones = false
    var swipeEnabled = true

    private var page: Bitmap? = null
    private var flashColor: Int? = null
    private val flashPaint = Paint()

    private var downX = 0f
    private var downY = 0f
    private val density = resources.displayMetrics.density
    private val minSwipeDistancePx = 42f * density
    private val maxTapSlopPx = 24f * density

    init {
        isClickable = true
        background = null
    }

    fun show(bitmap: Bitmap) {
        page = bitmap
        flashColor = null
        invalidate()
    }

    fun flash(color: Int) {
        flashColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        flashColor?.let {
            flashPaint.color = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
            return
        }
        page?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                val absDx = abs(dx)
                val absDy = abs(dy)

                if (swipeEnabled && absDx >= minSwipeDistancePx && absDx > absDy * 1.2f) {
                    // dx < 0: 往左滑动 (手指从右向左) -> 下一页
                    // dx > 0: 往右滑动 (手指从左向右) -> 上一页
                    val swipeLeft = dx < 0
                    val handler = onSwipeHorizontal
                    if (handler != null) {
                        handler.invoke(swipeLeft)
                    } else {
                        var zone = if (swipeLeft) Zone.RIGHT else Zone.LEFT
                        if (swapZones) {
                            zone = if (zone == Zone.LEFT) Zone.RIGHT else Zone.LEFT
                        }
                        onTapZone?.invoke(zone)
                    }
                    performClick()
                    return true
                }

                if (absDx <= maxTapSlopPx && absDy <= maxTapSlopPx) {
                    var zone = when {
                        e.x < width / 3f -> Zone.LEFT
                        e.x > width * 2f / 3f -> Zone.RIGHT
                        else -> Zone.CENTER
                    }
                    if (swapZones) {
                        zone = when (zone) {
                            Zone.LEFT -> Zone.RIGHT
                            Zone.RIGHT -> Zone.LEFT
                            else -> zone
                        }
                    }
                    onTapZone?.invoke(zone)
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
