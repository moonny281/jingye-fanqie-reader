package app.jingye.reader.eink

import android.content.Context
import android.provider.Settings

object EpdSettings {
    val KEYS = listOf(
        "current_epd_mode",
        "ink_mode",
        "dynamic_refresh_mode",
        "eink_automatic_clearance_key",
        "hmct_epd_bleach_mode",
        "hmct_epd_boldsize",
        "hmct_epd_darkthreshold"
    )
    val PROPS = listOf(
        "sys.hmct.epd_mode",
        "persist.sys.epd.black",
        "persist.sys.epd.white",
        "persist.sys.epd.contrast"
    )

    data class Item(val key: String, val namespace: String?, val value: String?)

    /** 依次在 system / global / secure 三个命名空间里找键。 */
    fun read(ctx: Context): List<Item> {
        val cr = ctx.contentResolver
        return KEYS.map { k ->
            val finders = listOf<Pair<String, () -> String?>>(
                "system" to { Settings.System.getString(cr, k) },
                "global" to { Settings.Global.getString(cr, k) },
                "secure" to { Settings.Secure.getString(cr, k) }
            )
            for ((ns, f) in finders) {
                val v = try {
                    f()
                } catch (e: Throwable) {
                    null
                }
                if (v != null) return@map Item(k, ns, v)
            }
            Item(k, null, null)
        }
    }

    /** 反射读取系统属性；被限制时返回 null。 */
    fun prop(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java)
        (method.invoke(null, key) as? String)?.ifEmpty { null }
    } catch (e: Throwable) {
        null
    }

    /** Tier 2 实验写入（仅在调试页手动触发）。 */
    fun tryWriteSystemInt(ctx: Context, key: String, value: Int): Result<Unit> = runCatching {
        if (!Settings.System.canWrite(ctx)) {
            error("尚未授予 WRITE_SETTINGS 权限")
        }
        val ok = Settings.System.putInt(ctx.contentResolver, key, value)
        if (!ok) error("Settings.System.putInt 返回 false")
    }
}
