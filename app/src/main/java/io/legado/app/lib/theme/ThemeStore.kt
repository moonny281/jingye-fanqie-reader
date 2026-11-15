package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.annotation.AttrRes
import androidx.annotation.CheckResult
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.edit
import splitties.init.appCtx

/**
 * 墨水屏纯黑白 1-bit 线性主题仓库 (v0.4.3)：
 * 全局锁定背景色为纯白 (#FFFFFFFF)，强调色与文字色为纯黑 (#FF000000)，彻底杜绝任何灰阶或彩色底色。
 */
@Suppress("unused")
class ThemeStore @SuppressLint("CommitPrefEdits")
private constructor(private val mContext: Context) : ThemeStoreInterface {

    private val mEditor = prefs(mContext).edit()

    override fun primaryColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR, Color.WHITE)
        primaryColorDark(Color.WHITE)
        return this
    }

    override fun primaryColorRes(@ColorRes colorRes: Int): ThemeStore = primaryColor(Color.WHITE)

    override fun primaryColorAttr(@AttrRes colorAttr: Int): ThemeStore = primaryColor(Color.WHITE)

    override fun primaryColorDark(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR_DARK, Color.WHITE)
        return this
    }

    override fun primaryColorDarkRes(@ColorRes colorRes: Int): ThemeStore = primaryColorDark(Color.WHITE)

    override fun primaryColorDarkAttr(@AttrRes colorAttr: Int): ThemeStore = primaryColorDark(Color.WHITE)

    override fun accentColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, Color.BLACK)
        return this
    }

    override fun accentColorRes(@ColorRes colorRes: Int): ThemeStore = accentColor(Color.BLACK)

    override fun accentColorAttr(@AttrRes colorAttr: Int): ThemeStore = accentColor(Color.BLACK)

    override fun statusBarColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, Color.WHITE)
        return this
    }

    override fun statusBarColorRes(@ColorRes colorRes: Int): ThemeStore = statusBarColor(Color.WHITE)

    override fun statusBarColorAttr(@AttrRes colorAttr: Int): ThemeStore = statusBarColor(Color.WHITE)

    override fun navigationBarColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, Color.WHITE)
        return this
    }

    override fun navigationBarColorRes(@ColorRes colorRes: Int): ThemeStore = navigationBarColor(Color.WHITE)

    override fun navigationBarColorAttr(@AttrRes colorAttr: Int): ThemeStore = navigationBarColor(Color.WHITE)

    override fun textColorPrimary(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_TEXT_COLOR_PRIMARY, Color.BLACK)
        return this
    }

    override fun textColorPrimaryRes(@ColorRes colorRes: Int): ThemeStore = textColorPrimary(Color.BLACK)

    override fun textColorPrimaryAttr(@AttrRes colorAttr: Int): ThemeStore = textColorPrimary(Color.BLACK)

    override fun textColorPrimaryInverse(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_TEXT_COLOR_PRIMARY_INVERSE, Color.WHITE)
        return this
    }

    override fun textColorPrimaryInverseRes(@ColorRes colorRes: Int): ThemeStore = textColorPrimaryInverse(Color.WHITE)

    override fun textColorPrimaryInverseAttr(@AttrRes colorAttr: Int): ThemeStore = textColorPrimaryInverse(Color.WHITE)

    override fun textColorSecondary(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_TEXT_COLOR_SECONDARY, Color.BLACK)
        return this
    }

    override fun textColorSecondaryRes(@ColorRes colorRes: Int): ThemeStore = textColorSecondary(Color.BLACK)

    override fun textColorSecondaryAttr(@AttrRes colorAttr: Int): ThemeStore = textColorSecondary(Color.BLACK)

    override fun textColorSecondaryInverse(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_TEXT_COLOR_SECONDARY_INVERSE, Color.WHITE)
        return this
    }

    override fun textColorSecondaryInverseRes(@ColorRes colorRes: Int): ThemeStore = textColorSecondaryInverse(Color.WHITE)

    override fun textColorSecondaryInverseAttr(@AttrRes colorAttr: Int): ThemeStore = textColorSecondaryInverse(Color.WHITE)

    override fun backgroundColor(color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, Color.WHITE)
        return this
    }

    override fun bottomBackground(color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, Color.WHITE)
        return this
    }

    override fun transparentNavBar(transparent: Boolean): ThemeStore {
        mEditor.putBoolean(ThemeStorePrefKeys.KEY_TRANSPARENT_NAV_BAR, false)
        return this
    }

    override fun autoGeneratePrimaryDark(autoGenerate: Boolean): ThemeStore {
        mEditor.putBoolean(ThemeStorePrefKeys.KEY_AUTO_GENERATE_PRIMARYDARK, false)
        return this
    }

    override fun apply() {
        mEditor.putLong(ThemeStorePrefKeys.VALUES_CHANGED, System.currentTimeMillis())
            .putBoolean(ThemeStorePrefKeys.IS_CONFIGURED_KEY, true)
            .apply()
        accentColor = Color.BLACK
    }

    companion object {

        var accentColor: Int = Color.BLACK

        fun editTheme(context: Context): ThemeStore = ThemeStore(context)

        @CheckResult
        internal fun prefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(
                ThemeStorePrefKeys.CONFIG_PREFS_KEY_DEFAULT,
                Context.MODE_PRIVATE
            )
        }

        fun markChanged(context: Context) {
            ThemeStore(context).apply()
        }

        @CheckResult
        @ColorInt
        fun primaryColor(context: Context = appCtx): Int = Color.WHITE

        @CheckResult
        @ColorInt
        fun primaryColorDark(context: Context): Int = Color.WHITE

        @CheckResult
        @ColorInt
        fun accentColor(context: Context = appCtx): Int = Color.BLACK

        @CheckResult
        @ColorInt
        fun statusBarColor(context: Context, transparent: Boolean): Int = Color.WHITE

        @CheckResult
        @ColorInt
        fun navigationBarColor(context: Context): Int = Color.WHITE

        @CheckResult
        @ColorInt
        fun textColorPrimary(context: Context): Int = Color.BLACK

        @CheckResult
        @ColorInt
        fun textColorPrimaryInverse(context: Context): Int = Color.WHITE

        @CheckResult
        @ColorInt
        fun textColorSecondary(context: Context): Int = Color.BLACK

        @CheckResult
        @ColorInt
        fun textColorSecondaryInverse(context: Context): Int = Color.WHITE

        @CheckResult
        @ColorInt
        fun backgroundColor(context: Context = appCtx): Int = Color.WHITE

        @CheckResult
        @ColorInt
        fun bottomBackground(context: Context = appCtx): Int = Color.WHITE

        @CheckResult
        fun coloredStatusBar(context: Context): Boolean = false

        @CheckResult
        fun transparentNavBar(context: Context): Boolean = false

        @CheckResult
        fun coloredNavigationBar(context: Context): Boolean = false

        @CheckResult
        fun autoGeneratePrimaryDark(context: Context): Boolean = false

        @CheckResult
        fun isConfigured(context: Context): Boolean = true

        @SuppressLint("CommitPrefEdits")
        fun isConfigured(context: Context, version: Int): Boolean {
            val prefs = prefs(context)
            val lastVersion = prefs.getInt(ThemeStorePrefKeys.IS_CONFIGURED_VERSION_KEY, -1)
            if (version > lastVersion) {
                prefs.edit { putInt(ThemeStorePrefKeys.IS_CONFIGURED_VERSION_KEY, version) }
                return false
            }
            return true
        }
    }
}