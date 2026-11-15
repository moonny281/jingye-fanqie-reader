package io.legado.app.eink

import android.graphics.Rect
import android.view.View

enum class EpdDisplayMode(val displayName: String) {
    CLEAR("高清阅读模式 (GC16/REGAL 抗残影)"),
    BALANCED("均衡刷新模式 (GU/DU 局刷增强)"),
    FAST("快速局刷模式 (A2 极速无闪)")
}

object EpdSettings {
    var currentMode: EpdDisplayMode = EpdDisplayMode.CLEAR

    /**
     * 调用系统底层 EPD 驱动（兼容海信 Hisense、文石 Onyx、博阅 Boyue、掌阅 iReader、RK3566 等墨水屏反射接口）
     */
    fun applyRefresh(view: View, forceFull: Boolean = false) {
        try {
            val clazz = view.javaClass
            val methodNames = if (forceFull) {
                listOf(
                    "requestEpdFullRefresh",
                    "invalidateEpd",
                    "resetEpdMode",
                    "setEpdFullMode",
                    "setEpdMode"
                )
            } else {
                listOf(
                    "requestEpdPartialRefresh",
                    "invalidateEpd",
                    "postInvalidate"
                )
            }
            for (name in methodNames) {
                val m = clazz.methods.firstOrNull {
                    it.name.equals(name, ignoreCase = true) && it.parameterCount == 0
                }
                if (m != null) {
                    m.invoke(view)
                    break
                }
            }
        } catch (_: Throwable) {
        }
        view.postInvalidate()
    }

    /**
     * 针对指定矩形区域执行墨水屏局部刷新（Region Partial Refresh），仅刷新文字变动区域而不触发全屏黑闪
     */
    fun applyPartialRefresh(view: View, dirtyRect: Rect? = null) {
        val rect = dirtyRect ?: Rect(0, 0, view.width, view.height)
        try {
            val clazz = view.javaClass
            // 优先尝试带区域参数的 EPD 局部刷新接口
            val rectMethod = clazz.methods.firstOrNull {
                (it.name.equals("invalidateEpd", ignoreCase = true) ||
                        it.name.equals("requestEpdPartialRefresh", ignoreCase = true) ||
                        it.name.equals("refreshEpdRegion", ignoreCase = true)) &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0] == Rect::class.java
            }
            if (rectMethod != null) {
                rectMethod.invoke(view, rect)
            } else {
                // 尝试 Onyx / RK3566 EpdController 静态局部刷新反射
                invokeVendorControllerPartial(view)
            }
        } catch (_: Throwable) {
        }
        if (rect.width() > 0 && rect.height() > 0) {
            view.postInvalidate(rect.left, rect.top, rect.right, rect.bottom)
        } else {
            view.postInvalidate()
        }
    }

    private fun invokeVendorControllerPartial(view: View) {
        val controllerClasses = listOf(
            "com.onyx.android.sdk.api.device.epd.EpdController",
            "android.hardware.epd.EpdManager",
            "com.hmct.epd.EpdManager"
        )
        for (clsName in controllerClasses) {
            try {
                val cls = Class.forName(clsName)
                val m = cls.methods.firstOrNull {
                    (it.name.contains("invalidate", ignoreCase = true) ||
                            it.name.contains("refresh", ignoreCase = true)) &&
                            it.parameterCount == 1 &&
                            it.parameterTypes[0].isAssignableFrom(View::class.java)
                }
                if (m != null) {
                    m.invoke(null, view)
                    return
                }
            } catch (_: Throwable) {
            }
        }
    }
}
