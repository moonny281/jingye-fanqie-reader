package io.legado.app.eink

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
                } catch (_: Throwable) {
                    null
                }
                if (v != null) return@map Item(k, ns, v)
            }
            Item(k, null, null)
        }
    }
}
