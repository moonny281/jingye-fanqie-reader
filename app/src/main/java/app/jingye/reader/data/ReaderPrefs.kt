package app.jingye.reader.data

import android.content.Context
import android.util.TypedValue
import app.jingye.reader.reader.ReaderStyle

class ReaderPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val dm = ctx.resources.displayMetrics

    var textSizeSp: Int
        get() = sp.getInt("text_size_sp", 20)
        set(v) = sp.edit().putInt("text_size_sp", v.coerceIn(14, 40)).apply()

    var lineSpacingX10: Int
        get() = sp.getInt("line_spacing_x10", 15)
        set(v) = sp.edit().putInt("line_spacing_x10", v.coerceIn(12, 20)).apply()

    var marginDp: Int
        get() = sp.getInt("margin_dp", 24)
        set(v) = sp.edit().putInt("margin_dp", v.coerceIn(8, 48)).apply()

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

    // 番茄小说后端配置：false = 内置 APP 网关直连（本机无需外部服务）；true = 连接远程 FastAPI (app.py)
    var useRemoteBackend: Boolean
        get() = sp.getBoolean("use_remote_backend", false)
        set(v) = sp.edit().putBoolean("use_remote_backend", v).apply()

    var backendUrl: String
        get() = sp.getString("backend_url", "http://127.0.0.1:8000") ?: "http://127.0.0.1:8000"
        set(v) = sp.edit().putString("backend_url", v.trim().trimEnd('/')).apply()

    var appApiIntervalSec: Int
        get() = sp.getInt("app_api_interval_sec", 6)
        set(v) = sp.edit().putInt("app_api_interval_sec", v.coerceIn(2, 15)).apply()

    fun style() = ReaderStyle(
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat(), dm),
        lineSpacingMult = lineSpacingX10 / 10f,
        marginPx = (marginDp * dm.density).toInt(),
        antiAlias = antiAlias,
        weight = if (bold) 500 else 400
    )
}
