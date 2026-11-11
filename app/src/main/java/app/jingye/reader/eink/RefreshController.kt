package app.jingye.reader.eink

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import app.jingye.reader.reader.PageView

interface RefreshController {
    val name: String
    fun showPage(view: PageView, bitmap: Bitmap)
    fun cleanRegion(view: PageView, bitmap: Bitmap)
    fun onReaderEnter(activity: Activity) {}
    fun onReaderExit(activity: Activity) {}
}

class DefaultRefreshController : RefreshController {
    override val name = "default"
    private val handler = Handler(Looper.getMainLooper())

    override fun showPage(view: PageView, bitmap: Bitmap) {
        handler.removeCallbacksAndMessages(null)
        view.show(bitmap)
    }

    override fun cleanRegion(view: PageView, bitmap: Bitmap) {
        handler.removeCallbacksAndMessages(null)
        view.flash(Color.BLACK)
        handler.postDelayed({ view.flash(Color.WHITE) }, 120)
        handler.postDelayed({ view.show(bitmap) }, 240)
    }
}

object RefreshControllers {
    fun create(ctx: Context): RefreshController = DefaultRefreshController()
}
