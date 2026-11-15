package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import android.view.MotionEvent
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import kotlin.math.abs

/**
 * 墨水屏专用零动画翻页代理 (v0.4.3)：
 * 在手指滑动过程中（ACTION_MOVE）禁止触发任何中间态屏幕重绘，
 * 避免滑动手势向墨水屏控制器连续发送数十帧无效刷新导致波形冲突与残影，
 * 仅在手指抬起（ACTION_UP）瞬间一次性完成翻页与白场去残影脉冲。
 */
class NoAnimPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    override fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                abortAnim()
            }
            MotionEvent.ACTION_MOVE -> {
                // 仅静默记录当前触摸坐标，绝不在手指滑动途中触发 invalidate() 重绘墨水屏
                readView.setTouchPoint(event.x, event.y, invalidate = false)
                val dx = event.x - readView.initialDownX
                val dy = event.y - readView.initialDownY
                if (!isMoved && (dx * dx + dy * dy > readView.pageSlopSquare2)) {
                    isMoved = true
                    mDirection = if (dx < 0) PageDirection.NEXT else PageDirection.PREV
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onAnimStart(0)
            }
        }
    }

    override fun onAnimStart(animationSpeed: Int) {
        val netDx = readView.touchX - readView.initialDownX
        val netDy = readView.touchY - readView.initialDownY
        val minSwipePx = 28f * readView.resources.displayMetrics.density

        if (abs(netDx) >= minSwipePx && abs(netDx) >= abs(netDy)) {
            val swipeDir = if (netDx < 0) PageDirection.NEXT else PageDirection.PREV
            if ((swipeDir == PageDirection.NEXT && hasNext()) ||
                (swipeDir == PageDirection.PREV && hasPrev())
            ) {
                readView.fillPage(swipeDir)
                stopScroll()
                return
            }
        }

        if (!isCancel && mDirection != PageDirection.NONE) {
            if ((mDirection == PageDirection.NEXT && hasNext()) ||
                (mDirection == PageDirection.PREV && hasPrev())
            ) {
                readView.fillPage(mDirection)
            }
        }
        stopScroll()
    }

    override fun setBitmap() {
        // 墨水屏无动画翻页不录制过渡位图，零内存开销、零残影
    }

    override fun onAnimStop() {
        // nothing
    }

    override fun onDraw(canvas: Canvas) {
        // nothing
    }
}
