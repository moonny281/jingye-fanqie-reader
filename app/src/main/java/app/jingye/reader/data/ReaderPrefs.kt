package app.jingye.reader.data

import android.content.Context
import android.util.TypedValue
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
        get() = sp.getString("custom_font_name", "自定字体") ?: "自定字体"
        set(v) = sp.edit().putString("custom_font_name", v).apply()

    var textSizeSp: Int
        get() = sp.getInt("text_size_sp", 20)
        set(v) = sp.edit().putInt("text_size_sp", v.coerceIn(14, 40)).apply()

    var lineSpacingX10: Int
        get() = sp.getInt("line_spacing_x10", 15)
        set(v) = sp.edit().putInt("line_spacing_x10", v.coerceIn(11, 22)).apply()

    var paraSpacingDp: Int
        get() = sp.getInt("para_spacing_dp", 10)
        set(v) = sp.edit().putInt("para_spacing_dp", v.coerceIn(0, 28)).apply()

    var letterSpacingX100: Int
        get() = sp.getInt("letter_spacing_x100", 2)
        set(v) = sp.edit().putInt("letter_spacing_x100", v.coerceIn(0, 15)).apply()

    var marginDp: Int
        get() = sp.getInt("margin_dp", 24)
        set(v) = sp.edit().putInt("margin_dp", v.coerceIn(8, 48)).apply()

    var marginVdp: Int
        get() = sp.getInt("margin_v_dp", 18)
        set(v) = sp.edit().putInt("margin_v_dp", v.coerceIn(8, 48)).apply()

    var bold: Boolean
        get() = sp.getBoolean("bold", false)
        set(v) = sp.edit().putBoolean("bold", v).apply()

    var antiAlias: Boolean
        get() = sp.getBoolean("anti_alias", true)
        set(v) = sp.edit().putBoolean("anti_alias", v).apply()

    var cleanEveryNPages: Int
        get() = sp.getInt("clean_every_n_pages", 30)
        set(v) = sp.edit().putInt("clean_every_n_pages", v).apply()

    var minTurnIntervalMs: Int
        get() = sp.getInt("min_turn_interval_ms", 250)
        set(v) = sp.edit().putInt("min_turn_interval_ms", v.coerceIn(150, 600)).apply()

    var swapTapZones: Boolean
        get() = sp.getBoolean("swap_tap_zones", false)
        set(v) = sp.edit().putBoolean("swap_tap_zones", v).apply()

    var useRemoteBackend: Boolean
        get() = sp.getBoolean("use_remote_backend", false)
        set(v) = sp.edit().putBoolean("use_remote_backend", v).apply()

    var backendUrl: String
        get() = sp.getString("backend_url", "http://127.0.0.1:8000") ?: "http://127.0.0.1:8000"
        set(v) = sp.edit().putString("backend_url", v.trim().trimEnd('/')).apply()

    var appApiIntervalSec: Int
        get() = sp.getInt("app_api_interval_sec", 6)
        set(v) = sp.edit().putInt("app_api_interval_sec", v.coerceIn(2, 15)).apply()

    /** 一键应用排版预设 */
    fun applyPreset(preset: LayoutPreset, switchFontToo: Boolean = true) {
        presetId = preset.id
        textSizeSp = preset.textSizeSp
        lineSpacingX10 = preset.lineSpacingX10
        paraSpacingDp = preset.paraSpacingDp
        letterSpacingX100 = preset.letterSpacingX100
        marginDp = preset.marginHdp
        marginVdp = preset.marginVdp
        bold = preset.bold
        if (switchFontToo && fontId != "custom") {
            fontId = preset.defaultFontId
        }
    }

    fun style() = ReaderStyle(
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat(), dm),
        lineSpacingMult = lineSpacingX10 / 10f,
        paraSpacingPx = (paraSpacingDp * dm.density).toInt(),
        letterSpacing = letterSpacingX100 / 100f,
        marginPx = (marginDp * dm.density).toInt(),
        marginVPx = (marginVdp * dm.density).toInt(),
        antiAlias = antiAlias,
        weight = if (bold) 550 else 400,
        fontId = fontId,
        customFontPath = customFontPath
    )
}
