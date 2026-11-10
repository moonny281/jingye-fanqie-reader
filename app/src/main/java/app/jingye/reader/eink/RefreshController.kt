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
    /** 局部清影：黑 → 白 → 内容，仅限文字区。 */
    fun cleanRegion(view: PageView, bitmap: Bitmap)
    fun onReaderEnter(activity: Activity) {}
    fun onReaderExit(activity: Activity) {}
}

/** Tier 0：通用 View 层。 */
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
    /** 默认返回 Tier 0。Tier 2/3 经实验验证有效后，在此按设置切换。 */
    fun create(ctx: Context): RefreshController = DefaultRefreshController()
}
