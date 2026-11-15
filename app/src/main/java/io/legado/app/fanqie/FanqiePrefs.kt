package io.legado.app.fanqie

import android.content.Context
import splitties.init.appCtx

object FanqiePrefs {
    private val sp get() = appCtx.getSharedPreferences("fanqie_prefs", Context.MODE_PRIVATE)

    var useRemoteBackend: Boolean
        get() = sp.getBoolean("use_remote_backend", false)
        set(v) = sp.edit().putBoolean("use_remote_backend", v).apply()

    var backendUrl: String
        get() = sp.getString("backend_url", "http://127.0.0.1:5000") ?: "http://127.0.0.1:5000"
        set(v) = sp.edit().putString("backend_url", v.trim()).apply()

    var appApiIntervalSec: Int
        get() = sp.getInt("app_api_interval_sec", 6)
        set(v) = sp.edit().putInt("app_api_interval_sec", v.coerceIn(2, 20)).apply()
}
