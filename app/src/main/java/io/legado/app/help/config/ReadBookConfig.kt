package io.legado.app.help.config

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import io.legado.app.constant.AppLog
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.help.DefaultData
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx
import java.io.File

/**
 * 阅读界面配置（原生墨水屏模式：锁定无动画翻页、纯白背景与高对比纯黑正文，优化最大阅读面积）
 */
@Suppress("ConstPropertyName")
@Keep
object ReadBookConfig {
    const val configFileName = "readConfig.json"
    const val shareConfigFileName = "shareReadConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)
    val shareConfigFilePath = FileUtils.getPath(appCtx.filesDir, shareConfigFileName)
    val configList: ArrayList<Config> = arrayListOf()
    lateinit var shareConfig: Config
    var durConfig
        get() = getConfig(styleSelect)
        set(value) {
            configList[styleSelect] = value
            if (shareLayout) {
                shareConfig = value
            }
        }

    var isComic: Boolean = false
    var bg: Drawable? = null
    var bgMeanColor: Int = Color.WHITE
    val textColor: Int get() = Color.BLACK
    val textAccentColor: Int get() = Color.BLACK
    var isNineBgImg = false

    init {
        initConfigs()
        initShareConfig()
    }

    @Synchronized
    fun getConfig(index: Int): Config {
        if (configList.size < 5) {
            resetAll()
        }
        return configList.getOrNull(index) ?: configList[0]
    }

    fun initConfigs() {
        val migPref = appCtx.getSharedPreferences("eink_refresh_prefs", android.content.Context.MODE_PRIVATE)
        if (!migPref.getBoolean("migrated_read_config_v042", false)) {
            FileUtils.delete(configFilePath)
            FileUtils.delete(shareConfigFilePath)
            migPref.edit().putBoolean("migrated_read_config_v042", true).apply()
        }
        val configFile = File(configFilePath)
        var configs: List<Config>? = null
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                configs = GSON.fromJsonArray<Config>(json).getOrThrow()
            } catch (e: Exception) {
                AppLog.put("读取排版配置文件出错", e)
            }
        }
        (configs ?: DefaultData.readConfigs).let {
            configList.clear()
            configList.addAll(it)
            if (readStyleSelect !in configList.indices) readStyleSelect = 0
            if (comicStyleSelect !in configList.indices) comicStyleSelect = 0
        }
    }

    fun initShareConfig() {
        val configFile = File(shareConfigFilePath)
        var c: Config? = null
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                c = GSON.fromJsonObject<Config>(json).getOrThrow()
            } catch (e: Exception) {
                e.printOnDebug()
            }
        }
        shareConfig = c ?: configList.getOrNull(0) ?: Config()
    }

    fun upBg(width: Int, height: Int) {
        val drawable = Color.WHITE.toDrawable()
        bgMeanColor = Color.WHITE
        val tmp = bg
        bg = drawable
        if (tmp is BitmapDrawable) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                tmp.bitmap?.recycle()
            }
        }
    }

    fun save() {
        Coroutine.async {
            synchronized(this) {
                GSON.toJson(configList).let {
                    FileUtils.delete(configFilePath)
                    FileUtils.createFileIfNotExist(configFilePath).writeText(it)
                }
                GSON.toJson(shareConfig).let {
                    FileUtils.delete(shareConfigFilePath)
                    FileUtils.createFileIfNotExist(shareConfigFilePath).writeText(it)
                }
            }
        }
    }

    fun getAllPicBgStr(): ArrayList<String> = arrayListOf()

    fun deleteDur(): Boolean {
        if (configList.size > 5) {
            val removeIndex = styleSelect
            configList.removeAt(removeIndex)
            if (removeIndex <= readStyleSelect) {
                readStyleSelect -= 1
            }
            if (removeIndex <= comicStyleSelect) {
                comicStyleSelect -= 1
            }
            return true
        }
        return false
    }

    fun clearBgAndCache() {
        FileUtils.delete(appCtx.externalCache.getFile("readConfig"))
        val configZipPath = FileUtils.getPath(appCtx.externalCache, "readConfig.zip")
        FileUtils.delete(configZipPath)
    }

    private fun resetAll() {
        DefaultData.readConfigs.let {
            configList.clear()
            configList.addAll(it)
            save()
        }
    }

    var readBodyToLh = appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)
    var autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 10)
        set(value) {
            field = value
            appCtx.putPrefInt(PreferKey.autoReadSpeed, value)
        }
    var styleSelect: Int
        get() = if (isComic) comicStyleSelect else readStyleSelect
        set(value) {
            if (isComic) {
                comicStyleSelect = value
            } else {
                readStyleSelect = value
            }
        }
    var readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
        set(value) {
            field = value
            if (appCtx.getPrefInt(PreferKey.readStyleSelect) != value) {
                appCtx.putPrefInt(PreferKey.readStyleSelect, value)
            }
        }
    var comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect, readStyleSelect)
        set(value) {
            field = value
            if (appCtx.getPrefInt(PreferKey.comicStyleSelect) != value) {
                appCtx.putPrefInt(PreferKey.comicStyleSelect, value)
            }
        }
    var shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
        set(value) {
            field = value
            if (appCtx.getPrefBoolean(PreferKey.shareLayout) != value) {
                appCtx.putPrefBoolean(PreferKey.shareLayout, value)
            }
        }

    val textFullJustify get() = appCtx.getPrefBoolean(PreferKey.textFullJustify, true)
    val textBottomJustify get() = appCtx.getPrefBoolean(PreferKey.textBottomJustify, true)

    var hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar, true)
    var hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar, true)
    var useZhLayout = appCtx.getPrefBoolean(PreferKey.useZhLayout)

    val config get() = if (shareLayout) shareConfig else durConfig

    var bgAlpha: Int
        get() = 100
        set(value) {
            config.bgAlpha = value
        }

    var pageAnim: Int
        get() = PageAnim.noAnim
        set(@PageAnim.Anim value) {
            config.setCurPageAnim(PageAnim.noAnim)
        }

    var textFont: String
        get() = config.textFont
        set(value) {
            config.textFont = value
        }

    var textBold: Int
        get() = config.textBold
        set(value) {
            config.textBold = value
        }

    var textSize: Int
        get() = config.textSize
        set(value) {
            config.textSize = value
        }

    var letterSpacing: Float
        get() = config.letterSpacing
        set(value) {
            config.letterSpacing = value
        }

    var lineSpacingExtra: Int
        get() = config.lineSpacingExtra
        set(value) {
            config.lineSpacingExtra = value
        }

    var paragraphSpacing: Int
        get() = config.paragraphSpacing
        set(value) {
            config.paragraphSpacing = value
        }

    var titleMode: Int
        get() = config.titleMode
        set(value) {
            config.titleMode = value
        }
    var titleSize: Int
        get() = config.titleSize
        set(value) {
            config.titleSize = value
        }

    val isMiddleTitle get() = titleMode == 1

    var titleTopSpacing: Int
        get() = config.titleTopSpacing
        set(value) {
            config.titleTopSpacing = value
        }

    var titleBottomSpacing: Int
        get() = config.titleBottomSpacing
        set(value) {
            config.titleBottomSpacing = value
        }

    var paragraphIndent: String
        get() = config.paragraphIndent
        set(value) {
            config.paragraphIndent = value
        }

    var underlineMode: Int
        get() = config.underlineMode
        set(value) {
            config.underlineMode = value
        }

    var paddingBottom: Int
        get() = config.paddingBottom
        set(value) {
            config.paddingBottom = value
        }

    var paddingLeft: Int
        get() = config.paddingLeft
        set(value) {
            config.paddingLeft = value
        }

    var paddingRight: Int
        get() = config.paddingRight
        set(value) {
            config.paddingRight = value
        }

    var paddingTop: Int
        get() = config.paddingTop
        set(value) {
            config.paddingTop = value
        }

    var headerPaddingBottom: Int
        get() = config.headerPaddingBottom
        set(value) {
            config.headerPaddingBottom = value
        }

    var headerPaddingLeft: Int
        get() = config.headerPaddingLeft
        set(value) {
            config.headerPaddingLeft = value
        }

    var headerPaddingRight: Int
        get() = config.headerPaddingRight
        set(value) {
            config.headerPaddingRight = value
        }

    var headerPaddingTop: Int
        get() = config.headerPaddingTop
        set(value) {
            config.headerPaddingTop = value
        }

    var footerPaddingBottom: Int
        get() = config.footerPaddingBottom
        set(value) {
            config.footerPaddingBottom = value
        }

    var footerPaddingLeft: Int
        get() = config.footerPaddingLeft
        set(value) {
            config.footerPaddingLeft = value
        }

    var footerPaddingRight: Int
        get() = config.footerPaddingRight
        set(value) {
            config.footerPaddingRight = value
        }

    var footerPaddingTop: Int
        get() = config.footerPaddingTop
        set(value) {
            config.footerPaddingTop = value
        }

    var showHeaderLine: Boolean
        get() = config.showHeaderLine
        set(value) {
            config.showHeaderLine = value
        }

    var showFooterLine: Boolean
        get() = config.showFooterLine
        set(value) {
            config.showFooterLine = value
        }

    fun getExportConfig(): Config {
        return durConfig.copy()
    }

    fun import(byteArray: ByteArray): Config {
        val configZipPath = FileUtils.getPath(appCtx.externalCache, "readConfig.zip")
        FileUtils.delete(configZipPath)
        val zipFile = FileUtils.createFileIfNotExist(configZipPath)
        zipFile.writeBytes(byteArray)
        val configDir = appCtx.externalCache.getFile("readConfig")
        configDir.createFolderReplace()
        ZipUtils.unZipToPath(zipFile, configDir)
        val configFile = configDir.getFile(configFileName)
        val config: Config = GSON.fromJsonObject<Config>(configFile.readText()).getOrThrow()
        if (config.textFont.isNotEmpty() && !config.textFont.startsWith("builtin://")) {
            val fontName = config.textFont
            val fontPath = FileUtils.getPath(appCtx.externalFiles, "font", fontName)
            val fontFile = configDir.getFile(fontName)
            if (fontFile.exists()) {
                if (!FileUtils.exist(fontPath)) {
                    fontFile.copyTo(File(fontPath))
                }
                config.textFont = fontPath
            } else {
                config.textFont = ""
            }
        }
        return config
    }

    @Keep
    data class Config(
        var name: String = "",
        var bgStr: String = "#FFFFFF",
        var bgStrNight: String = "#FFFFFF",
        var bgStrEInk: String = "#FFFFFF",
        var bgAlpha: Int = 100,
        var bgType: Int = 0,
        var bgTypeNight: Int = 0,
        var bgTypeEInk: Int = 0,
        private var darkStatusIcon: Boolean = true,
        private var darkStatusIconNight: Boolean = true,
        private var darkStatusIconEInk: Boolean = true,
        private var textColor: String = "#000000",
        private var textColorNight: String = "#000000",
        private var textColorEInk: String = "#000000",
        private var textAccentColor: String = "#000000",
        private var textAccentColorNight: String = "#000000",
        private var textAccentColorEInk: String = "#000000",
        private var pageAnim: Int = 4,
        private var pageAnimEInk: Int = 4,
        var textFont: String = "builtin://serif",
        var textBold: Int = 0,
        var textSize: Int = 22,
        var letterSpacing: Float = 0.02f,
        var lineSpacingExtra: Int = 12,
        var paragraphSpacing: Int = 8,
        var titleMode: Int = 0,
        var titleSize: Int = 4,
        var titleTopSpacing: Int = 6,
        var titleBottomSpacing: Int = 8,
        var paragraphIndent: String = "　　",
        var underlineMode: Int = 0,
        var paddingBottom: Int = 3,
        var paddingLeft: Int = 12,
        var paddingRight: Int = 12,
        var paddingTop: Int = 4,
        var headerPaddingBottom: Int = 2,
        var headerPaddingLeft: Int = 12,
        var headerPaddingRight: Int = 12,
        var headerPaddingTop: Int = 2,
        var footerPaddingBottom: Int = 3,
        var footerPaddingLeft: Int = 12,
        var footerPaddingRight: Int = 12,
        var footerPaddingTop: Int = 2,
        var showHeaderLine: Boolean = false,
        var showFooterLine: Boolean = false,
        var tipHeaderLeft: Int = ReadTipConfig.none,
        var tipHeaderMiddle: Int = ReadTipConfig.none,
        var tipHeaderRight: Int = ReadTipConfig.none,
        var tipFooterLeft: Int = ReadTipConfig.chapterTitle,
        var tipFooterMiddle: Int = ReadTipConfig.none,
        var tipFooterRight: Int = ReadTipConfig.pageAndTotal,
        var tipColor: Int = 0,
        var tipDividerColor: Int = 0,
        var headerMode: Int = 2,
        var footerMode: Int = 0
    ) {

        fun setCurTextColor(color: Int) {
            textColorEInk = "#000000"
        }

        fun curTextColor(): Int = Color.BLACK

        fun setCurTextAccentColor(color: Int) {
            textAccentColorEInk = "#000000"
        }

        fun curTextAccentColor(): Int = Color.BLACK

        fun setCurStatusIconDark(isDark: Boolean) {
            darkStatusIconEInk = true
        }

        fun curStatusIconDark(): Boolean = true

        fun setCurPageAnim(@PageAnim.Anim anim: Int) {
            pageAnim = PageAnim.noAnim
            pageAnimEInk = PageAnim.noAnim
        }

        fun curPageAnim(): Int = PageAnim.noAnim

        fun setCurBg(bgType: Int, bg: String) {
            this.bgType = 0
            this.bgTypeEInk = 0
            this.bgStr = "#FFFFFF"
            this.bgStrEInk = "#FFFFFF"
        }

        fun curBgStr(): String = "#FFFFFF"

        fun curBgType(): Int = 0

        fun curBgDrawable(width: Int, height: Int): Drawable {
            isNineBgImg = false
            return Color.WHITE.toDrawable()
        }

        fun getBgPath(bgIndex: Int): String? = null

        fun toMap() = mapOf(
            "name" to name,
            "bgStr" to "#FFFFFF",
            "bgStrNight" to "#FFFFFF",
            "bgStrEInk" to "#FFFFFF",
            "bgAlpha" to 100,
            "bgType" to 0,
            "bgTypeNight" to 0,
            "bgTypeEInk" to 0,
            "darkStatusIcon" to true,
            "darkStatusIconNight" to true,
            "darkStatusIconEInk" to true,
            "textColor" to "#000000",
            "textColorNight" to "#000000",
            "textColorEInk" to "#000000",
            "textAccentColor" to "#000000",
            "textAccentColorNight" to "#000000",
            "textAccentColorEInk" to "#000000",
            "pageAnim" to PageAnim.noAnim,
            "pageAnimEInk" to PageAnim.noAnim,
            "textFont" to textFont,
            "textBold" to textBold,
            "textSize" to textSize,
            "letterSpacing" to letterSpacing,
            "lineSpacingExtra" to lineSpacingExtra,
            "paragraphSpacing" to paragraphSpacing,
            "titleMode" to titleMode,
            "titleSize" to titleSize,
            "titleTopSpacing" to titleTopSpacing,
            "titleBottomSpacing" to titleBottomSpacing,
            "paragraphIndent" to paragraphIndent,
            "underlineMode" to underlineMode,
            "paddingBottom" to paddingBottom,
            "paddingLeft" to paddingLeft,
            "paddingRight" to paddingRight,
            "paddingTop" to paddingTop,
            "headerPaddingBottom" to headerPaddingBottom,
            "headerPaddingLeft" to headerPaddingLeft,
            "headerPaddingRight" to headerPaddingRight,
            "headerPaddingTop" to headerPaddingTop,
            "footerPaddingBottom" to footerPaddingBottom,
            "footerPaddingLeft" to footerPaddingLeft,
            "footerPaddingRight" to footerPaddingRight,
            "footerPaddingTop" to footerPaddingTop,
            "showHeaderLine" to showHeaderLine,
            "showFooterLine" to showFooterLine,
            "tipHeaderLeft" to tipHeaderLeft,
            "tipHeaderMiddle" to tipHeaderMiddle,
            "tipHeaderRight" to tipHeaderRight,
            "tipFooterLeft" to tipFooterLeft,
            "tipFooterMiddle" to tipFooterMiddle,
            "tipFooterRight" to tipFooterRight,
            "tipColor" to tipColor,
            "tipDividerColor" to tipDividerColor,
            "headerMode" to headerMode,
            "footerMode" to footerMode
        )

    }

}