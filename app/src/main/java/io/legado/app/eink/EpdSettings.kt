package io.legado.app.eink

import android.graphics.Rect
import android.view.View

enum class EpdDisplayMode(val displayName: String) {
    CLEAR("高清精排模式 (GC16/REGAL 深度消残影)"),
    BALANCED("均衡刷新模式 (GU/DU 局刷白场增强)"),
    FAST("极速黑白模式 (A2/DU 1-Bit 线性无残影)")
}

object EpdSettings {
    var currentMode: EpdDisplayMode = EpdDisplayMode.CLEAR

    /**
     * 调用系统底层 EPD 硬件驱动（兼容海信 Hisense、文石 Onyx BOOX、掌阅 iReader、博阅 Boyue/Meebook、汉王 Hanvon、RK3566 等墨水屏接口）
     */
    fun applyRefresh(view: View, forceFull: Boolean = false) {
        try {
            invokeOnyxController(view, full = forceFull)
            val clazz = view.javaClass
            val methodNames = if (forceFull) {
                listOf(
                    "requestEpdFullRefresh",
                    "invalidateEpd",
                    "resetEpdMode",
                    "setEpdFullMode",
                    "setEpdMode",
                    "refreshScreen"
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
        view.postInvalidateOnAnimation()
    }

    /**
     * 针对指定矩形区域执行墨水屏局部刷新（Region Partial Refresh）
     */
    fun applyPartialRefresh(view: View, dirtyRect: Rect? = null) {
        val rect = dirtyRect ?: Rect(0, 0, view.width, view.height)
        try {
            val clazz = view.javaClass
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
                invokeOnyxController(view, full = false)
            }
        } catch (_: Throwable) {
        }
        if (rect.width() > 0 && rect.height() > 0) {
            view.postInvalidate(rect.left, rect.top, rect.right, rect.bottom)
        } else {
            view.postInvalidate()
        }
    }

    private fun invokeOnyxController(view: View, full: Boolean) {
        // 1. 尝试 Onyx BOOX EpdController.invalidate(View, UpdateMode)
        try {
            val epdCls = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            val modeCls = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
            val targetEnumName = if (full) "GC" else "GU"
            val modeConst = modeCls.enumConstants?.firstOrNull {
                it.toString().equals(targetEnumName, ignoreCase = true) ||
                    (full && it.toString().contains("GC")) ||
                    (!full && (it.toString().contains("GU") || it.toString().contains("DU")))
            }
            if (modeConst != null) {
                val invMethod = epdCls.methods.firstOrNull {
                    it.name == "invalidate" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0].isAssignableFrom(View::class.java) &&
                        it.parameterTypes[1] == modeCls
                }
                if (invMethod != null) {
                    invMethod.invoke(null, view, modeConst)
                    return
                }
            }
        } catch (_: Throwable) {
        }

        // 2. 尝试通用静态控制器反射（海信 / RK3566 / 博阅）
        val controllerClasses = listOf(
            "com.onyx.android.sdk.api.device.epd.EpdController",
            "android.hardware.epd.EpdManager",
            "com.hmct.epd.EpdManager",
            "android.eink.EinkManager"
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
