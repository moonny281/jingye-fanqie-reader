package io.legado.app.eink

import android.view.View

enum class EpdDisplayMode(val displayName: String) {
    CLEAR("清晰模式 (A2+/GC16 高清)"),
    BALANCED("均衡模式 (推荐阅读)"),
    FAST("极速模式 (滑动浏览)")
}

object EpdSettings {
    var currentMode: EpdDisplayMode = EpdDisplayMode.CLEAR

    fun applyRefresh(view: View, forceFull: Boolean = false) {
        try {
            val clazz = view.javaClass
            val methodNames = if (forceFull) {
                listOf("requestEpdFullRefresh", "invalidateEpd", "setEpdMode")
            } else {
                listOf("invalidateEpd", "postInvalidate")
            }
            for (name in methodNames) {
                val m = clazz.methods.firstOrNull { it.name.equals(name, ignoreCase = true) && it.parameterCount == 0 }
                if (m != null) {
                    m.invoke(view)
                    break
                }
            }
        } catch (_: Throwable) {
        }
        view.postInvalidate()
    }
}
