package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import splitties.init.appCtx

@Keep
object ThemeConfig {
    const val configFileName = "themeConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)

    private val einkOnlyConfig = Config(
        themeName = "原生墨水屏",
        isNightTheme = false,
        primaryColor = "#FFFFFF",
        accentColor = "#000000",
        backgroundColor = "#FFFFFF",
        bottomBackground = "#FFFFFF",
        transparentNavBar = false,
        backgroundImgPath = null,
        backgroundImgBlur = 0
    )

    val configList: ArrayList<Config> = arrayListOf(einkOnlyConfig)

    fun getTheme(): Theme = Theme.EInk

    fun isDarkTheme(): Boolean = false

    fun applyDayNight(context: Context) {
        applyTheme(context)
        initNightMode()
        BookCover.upDefaultCover()
        postEvent(EventBus.RECREATE, "")
    }

    fun applyDayNightInit(context: Context) {
        applyTheme(context)
        initNightMode()
    }

    private fun initNightMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }

    fun getBgImage(context: Context, metrics: DisplayMetrics): Drawable? = null

    fun upConfig() {
        configList.clear()
        configList.add(einkOnlyConfig)
    }

    fun save() {
        // 锁定唯一原生墨水屏主题，无需保存其它主题
    }

    fun delConfig(index: Int) {
        // 不可删除唯一墨水屏主题
    }

    fun addConfig(json: String): Boolean = false

    fun addConfig(newConfig: Config) {}

    fun addConfigs(newConfigs: List<Config>?) {}

    fun applyConfig(context: Context, config: Config) {
        applyDayNight(context)
    }

    fun getDurConfig(context: Context): Config = einkOnlyConfig

    fun saveDayTheme(context: Context, name: String) {}

    fun saveNightTheme(context: Context, name: String) {}

    /**
     * 强制应用纯白高对比原生墨水屏主题
     */
    fun applyTheme(context: Context) = with(context) {
        ThemeStore.editTheme(this)
            .primaryColor(Color.WHITE)
            .accentColor(Color.BLACK)
            .backgroundColor(Color.WHITE)
            .bottomBackground(Color.WHITE)
            .transparentNavBar(false)
            .apply()
    }

    fun clearBg(context: Context) {}

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String,
        var transparentNavBar: Boolean,
        var backgroundImgPath: String?,
        var backgroundImgBlur: Int
    ) {

        override fun hashCode(): Int {
            return GSON.toJson(this).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName
                        && other.isNightTheme == isNightTheme
                        && other.primaryColor == primaryColor
                        && other.accentColor == accentColor
                        && other.backgroundColor == backgroundColor
                        && other.bottomBackground == bottomBackground
                        && other.transparentNavBar == transparentNavBar
                        && other.backgroundImgPath == backgroundImgPath
                        && other.backgroundImgBlur == backgroundImgBlur
            }
            return false
        }

        fun toMap() = mapOf(
            "themeName" to themeName,
            "isNightTheme" to isNightTheme,
            "primaryColor" to primaryColor,
            "accentColor" to accentColor,
            "backgroundColor" to backgroundColor,
            "bottomBackground" to bottomBackground,
            "transparentNavBar" to transparentNavBar,
            "backgroundImgPath" to backgroundImgPath,
            "backgroundImgBlur" to backgroundImgBlur
        )

    }

}