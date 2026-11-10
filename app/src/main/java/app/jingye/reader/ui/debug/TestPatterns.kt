package app.jingye.reader.ui.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.jingye.reader.reader.ReaderStyle
import app.jingye.reader.reader.buildLayout

object TestPatterns {
    fun solid(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.RGB_565).also {
            Canvas(it).drawColor(color)
        }

    fun checker(w: Int, h: Int, cell: Int = 64): Bitmap {
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val p = Paint()
        for (y in 0 until safeH step cell) {
            for (x in 0 until safeW step cell) {
                p.color = if (((x / cell) + (y / cell)) % 2 == 0) Color.BLACK else Color.WHITE
                c.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), p)
            }
        }
        return bmp
    }

    fun grayRamp(w: Int, h: Int, steps: Int = 16): Bitmap {
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val p = Paint()
        val bw = safeW / steps.toFloat()
        for (i in 0 until steps) {
            val g = i * 255 / (steps - 1)
            p.color = Color.rgb(g, g, g)
            c.drawRect(i * bw, 0f, (i + 1) * bw, safeH.toFloat(), p)
        }
        return bmp
    }

    fun sampleText(w: Int, h: Int, style: ReaderStyle, pageNo: Int = 1): Bitmap {
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val sample = "【海信 A7 墨水屏文字样张 · 第 $pageNo 帧】\n" +
            "　　墨水屏残影来自电泳微胶囊粒子的残余位移。净页通过预渲染位图、纯黑白绘制与文字区局部闪烁（黑→白→内容），在不触发整屏闪烁的前提下抑制残影累积。\n" +
            "　　天地玄黄，宇宙洪荒。日月盈昃，辰宿列张。寒来暑往，秋收冬藏。闰余成岁，律吕调阳。"
        val layout = buildLayout(
            sample,
            0,
            sample.length,
            style.newPaint(),
            (safeW - style.marginPx * 2).coerceAtLeast(1),
            style.lineSpacingMult
        )
        c.translate(style.marginPx.toFloat(), style.marginPx.toFloat())
        layout.draw(c)
        return bmp
    }
}
