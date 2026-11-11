package app.jingye.reader.data

import android.content.Context
import app.jingye.reader.reader.LayoutPreset
import app.jingye.reader.reader.ReaderStyle

class ReaderPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val dm = ctx.resources.displayMetrics

    var presetId: String
        get() = sp.getString("preset_id", LayoutPreset.WEREAD_STD.id) ?: LayoutPreset.WEREAD_STD.id
        set(v) = sp.edit().putString("preset_id", v).apply()

    var fontId: String
        get() = sp.getString("font_id", "serif") ?: "serif"
        set(v) = sp.edit().putString("font_id", v).apply()

    var customFontPath: String
        get() = sp.getString("custom_font_path", "") ?: ""
        set(v) = sp.edit().putString("custom_font_path", v).apply()

    var customFontName: String
        get() = sp.getString("custom_font_name", "") ?: ""
        set(v) = sp.edit().putString("custom_font_name", v).apply()

    var textSizeSp: Int
        get() = sp.getInt("size_sp", 22)
        set(v) = sp.edit().putInt("size_sp", v.coerceIn(14, 42)).apply()

    var lineSpacingX10: Int
        get() = sp.getInt("line_x10", 15)
        set(v) = sp.edit().putInt("line_x10", v.coerceIn(11, 24)).apply()

    var paraSpacingDp: Int
        get() = sp.getInt("para_dp", 12)
        set(v) = sp.edit().putInt("para_dp", v.coerceIn(0, 36)).apply()

    var letterSpacingX100: Int
        get() = sp.getInt("letter_x100", 2)
        set(v) = sp.edit().putInt("letter_x100", v.coerceIn(0, 20)).apply()

    var marginDp: Int
        get() = sp.getInt("margin_dp", 22)
        set(v) = sp.edit().putInt("margin_dp", v.coerceIn(8, 48)).apply()

    var marginVdp: Int
        get() = sp.getInt("margin_v_dp", 14)
        set(v) = sp.edit().putInt("margin_v_dp", v.coerceIn(6, 40)).apply()

    var bold: Boolean
        get() = sp.getBoolean("bold", false)
        set(v) = sp.edit().putBoolean("bold", v).apply()

    var antiAlias: Boolean
        get() = sp.getBoolean("aa", true)
        set(v) = sp.edit().putBoolean("aa", v).apply()

    var cleanEveryNPages: Int
        get() = sp.getInt("clean_n", 12)
        set(v) = sp.edit().putInt("clean_n", v.coerceIn(0, 100)).apply()

    var minTurnIntervalMs: Int
        get() = sp.getInt("throttle_ms", 140)
        set(v) = sp.edit().putInt("throttle_ms", v.coerceIn(50, 600)).apply()

    var swapTapZones: Boolean
        get() = sp.getBoolean("swap_zones", false)
        set(v) = sp.edit().putBoolean("swap_zones", v).apply()

    var swipeTurnEnabled: Boolean
        get() = sp.getBoolean("swipe_turn", true)
        set(v) = sp.edit().putBoolean("swipe_turn", v).apply()

    var useRemoteBackend: Boolean
        get() = sp.getBoolean("use_remote_backend", false)
        set(v) = sp.edit().putBoolean("use_remote_backend", v).apply()

    var backendUrl: String
        get() = sp.getString("backend_url", "http://127.0.0.1:5000") ?: "http://127.0.0.1:5000"
        set(v) = sp.edit().putString("backend_url", v.trim()).apply()

    var appApiIntervalSec: Int
        get() = sp.getInt("app_api_interval_sec", 6)
        set(v) = sp.edit().putInt("app_api_interval_sec", v.coerceIn(2, 20)).apply()

    fun applyPreset(preset: LayoutPreset, switchFontToo: Boolean = true) {
        val editor = sp.edit()
            .putString("preset_id", preset.id)
            .putInt("size_sp", preset.textSizeSp)
            .putInt("line_x10", preset.lineSpacingX10)
            .putInt("para_dp", preset.paraSpacingDp)
            .putInt("letter_x100", preset.letterSpacingX100)
            .putInt("margin_dp", preset.marginHdp)
            .putInt("margin_v_dp", preset.marginVdp)
            .putBoolean("bold", preset.bold)
        if (switchFontToo) {
            editor.putString("font_id", preset.defaultFontId)
        }
        editor.apply()
    }

    fun style() = ReaderStyle(
        textSizePx = textSizeSp * dm.scaledDensity,
        lineSpacingMult = lineSpacingX10 / 10f,
        paraSpacingPx = (paraSpacingDp * dm.density).toInt(),
        letterSpacing = letterSpacingX100 / 100f,
        marginPx = (marginDp * dm.density).toInt(),
        marginVPx = (marginVdp * dm.density).toInt(),
        antiAlias = antiAlias,
        weight = if (bold) 700 else 400,
        fontId = fontId,
        customFontPath = customFontPath
    )
}
