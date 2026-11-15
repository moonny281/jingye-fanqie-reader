package io.legado.app.eink

import android.content.Context
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

enum class BuiltinEinkFont(
    val id: String,
    val uriKey: String,
    val displayName: String,
    val assetPath: String,
    val fallbackFamily: String,
    val fallbackStyle: Int
) {
    SERIF(
        id = "serif",
        uriKey = "builtin://serif",
        displayName = "思源宋体 · 墨水屏正文",
        assetPath = "fonts/NotoSerifSC-Regular.otf",
        fallbackFamily = "serif",
        fallbackStyle = Typeface.NORMAL
    ),
    WENKAI(
        id = "wenkai",
        uriKey = "builtin://wenkai",
        displayName = "霞鹜文楷 · 书卷楷体",
        assetPath = "fonts/LXGWWenKai-Regular.ttf",
        fallbackFamily = "serif",
        fallbackStyle = Typeface.NORMAL
    ),
    SANS(
        id = "sans",
        uriKey = "builtin://sans",
        displayName = "思源黑体 · 现代清晰",
        assetPath = "fonts/NotoSansSC-Regular.otf",
        fallbackFamily = "sans-serif",
        fallbackStyle = Typeface.NORMAL
    ),
    EINK_BOLD(
        id = "eink_bold",
        uriKey = "builtin://eink_bold",
        displayName = "墨水屏凝黑 · 高对比加粗",
        assetPath = "fonts/NotoSansSC-Bold.otf",
        fallbackFamily = "sans-serif",
        fallbackStyle = Typeface.BOLD
    ),
    FANGSONG(
        id = "fangsong",
        uriKey = "builtin://fangsong",
        displayName = "古籍刻本 · 雅致衬线",
        assetPath = "fonts/NotoSerifSC-SemiBold.otf",
        fallbackFamily = "serif",
        fallbackStyle = Typeface.BOLD
    ),
    ROUNDED(
        id = "rounded",
        uriKey = "builtin://rounded",
        displayName = "护眼柔黑 · 长时间阅读",
        assetPath = "fonts/NotoSansSC-Medium.otf",
        fallbackFamily = "sans-serif-medium",
        fallbackStyle = Typeface.NORMAL
    );

    companion object {
        fun fromKey(key: String?): BuiltinEinkFont? {
            if (key.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.uriKey.equals(key, ignoreCase = true) || it.id.equals(key, ignoreCase = true)
            }
        }
    }
}

object BuiltinFontManager {
    private val cache = ConcurrentHashMap<String, Typeface>()

    fun isBuiltinFontKey(path: String?): Boolean {
        return BuiltinEinkFont.fromKey(path) != null
    }

    fun resolveTypeface(context: Context, fontKey: String?): Typeface {
        val font = BuiltinEinkFont.fromKey(fontKey) ?: BuiltinEinkFont.SERIF
        cache[font.id]?.let { return it }

        val loaded = loadFromAssetsOrSystem(context, font)
        cache[font.id] = loaded
        return loaded
    }

    private fun loadFromAssetsOrSystem(context: Context, font: BuiltinEinkFont): Typeface {
        try {
            val assetList = context.assets.list("fonts") ?: emptyArray()
            val fileName = font.assetPath.substringAfterLast("/")
            if (assetList.contains(fileName)) {
                return Typeface.createFromAsset(context.assets, font.assetPath)
            }
        } catch (_: Throwable) {
        }

        val candidates = when (font) {
            BuiltinEinkFont.SERIF -> listOf("noto-serif-cjk", "serif", "songti")
            BuiltinEinkFont.WENKAI -> listOf("kaiti", "stkaiti", "serif", "noto-serif-cjk")
            BuiltinEinkFont.SANS -> listOf("sans-serif", "noto-sans-cjk")
            BuiltinEinkFont.EINK_BOLD -> listOf("sans-serif-black", "sans-serif-medium", "sans-serif")
            BuiltinEinkFont.FANGSONG -> listOf("fangsong", "serif-monospace", "serif")
            BuiltinEinkFont.ROUNDED -> listOf("sans-serif-medium", "sans-serif")
        }

        for (family in candidates) {
            try {
                val tf = Typeface.create(family, font.fallbackStyle)
                if (tf != null) return tf
            } catch (_: Throwable) {
            }
        }
        return Typeface.create(Typeface.SERIF, font.fallbackStyle)
    }
}
