package app.jingye.reader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class PageView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    enum class Zone { LEFT, CENTER, RIGHT }

    var onTapZone: ((Zone) -> Unit)? = null
    var swapZones = false

    private var page: Bitmap? = null
    private var flashColor: Int? = null
    private val flashPaint = Paint()

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
        if (e.action == MotionEvent.ACTION_UP) {
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
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
