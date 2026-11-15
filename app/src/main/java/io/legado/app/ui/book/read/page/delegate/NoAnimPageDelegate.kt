package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import kotlin.math.abs

class NoAnimPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    override fun onAnimStart(animationSpeed: Int) {
        val netDx = readView.touchX - readView.initialDownX
        val netDy = readView.touchY - readView.initialDownY
        val minSwipePx = 32f * readView.resources.displayMetrics.density

        // 墨水屏左右滑动手势优化：只要水平净位移超过 32dp，即判定为有效左右滑动翻页（避免抬手微抖导致取消）
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

        if (!isCancel) {
            readView.fillPage(mDirection)
        }
        stopScroll()
    }

    override fun setBitmap() {
        // E-Ink 无动画翻页无需录制过渡位图
    }

    override fun onDraw(canvas: Canvas) {
        // nothing
    }

    override fun onAnimStop() {
        // nothing
    }
}
