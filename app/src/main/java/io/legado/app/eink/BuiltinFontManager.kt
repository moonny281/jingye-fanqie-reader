package io.legado.app.eink

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import java.io.File

enum class BuiltinEinkFont(
    val uriKey: String,
    val displayName: String,
    val description: String
) {
    SERIF("builtin://serif", "思源宋体 (墨水屏衬线)", "传统书卷衬线，长篇沉浸阅读首选"),
    WENKAI("builtin://wenkai", "霞鹜文楷 (手写楷体)", "温润手写楷体，古风与文学佳作绝配"),
    SANS("builtin://sans", "思源黑体 (无衬线黑体)", "现代无衬线黑体，字形干净利落"),
    EINK_BOLD("builtin://eink_bold", "墨水屏凝黑 (高对比加粗)", "专为墨水屏粒子加深字重，边缘锐利"),
    FANGSONG("builtin://fangsong", "古籍刻本 (传统书卷)", "高衬线对比与舒朗字怀，还原纸书质感"),
    ROUNDED("builtin://rounded", "护眼柔黑 (圆润舒适)", "笔画柔和舒适，适合长时间速读");

    companion object {
        fun fromUriKey(key: String): BuiltinEinkFont? =
            entries.firstOrNull { it.uriKey.equals(key.trim(), ignoreCase = true) }
    }
}

object BuiltinFontManager {
    private val assetCache = HashMap<String, Typeface?>()

    private fun loadAssetFont(ctx: Context, assetPath: String): Typeface? {
        if (assetCache.containsKey(assetPath)) return assetCache[assetPath]
        val tf = try {
            Typeface.createFromAsset(ctx.assets, assetPath)
        } catch (_: Throwable) {
            null
        }
        assetCache[assetPath] = tf
        return tf
    }

    private fun loadSystemFont(paths: List<String>): Typeface? {
        for (p in paths) {
            val f = File(p)
            if (f.isFile && f.length() > 1024L) {
                try {
                    return Typeface.createFromFile(f)
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    fun isBuiltinFontKey(fontPath: String): Boolean =
        fontPath.startsWith("builtin://", ignoreCase = true)

    fun getDisplayName(fontPath: String): String =
        BuiltinEinkFont.fromUriKey(fontPath)?.displayName ?: fontPath.substringAfterLast("/")

    fun resolveTypeface(ctx: Context, fontPath: String): Typeface? {
        val builtin = BuiltinEinkFont.fromUriKey(fontPath) ?: return null
        val baseTf = when (builtin) {
            BuiltinEinkFont.WENKAI -> {
                loadAssetFont(ctx, "fonts/wenkai.ttf")
                    ?: loadAssetFont(ctx, "fonts/serif.otf")
                    ?: loadSystemFont(
                        listOf(
                            "/system/fonts/NotoSerifCJK-Regular.ttc",
                            "/system/fonts/DroidSansFallback.ttf"
                        )
                    )
                    ?: Typeface.SERIF
            }
            BuiltinEinkFont.SERIF -> {
                loadAssetFont(ctx, "fonts/serif.otf")
                    ?: loadSystemFont(
                        listOf(
                            "/system/fonts/NotoSerifCJK-Regular.ttc",
                            "/system/fonts/SourceHanSerifSC-Regular.otf"
                        )
                    )
                    ?: Typeface.SERIF
            }
            BuiltinEinkFont.FANGSONG -> {
                loadAssetFont(ctx, "fonts/wenkai.ttf")
                    ?: loadAssetFont(ctx, "fonts/serif.otf")
                    ?: Typeface.SERIF
            }
            BuiltinEinkFont.SANS -> {
                loadSystemFont(
                    listOf(
                        "/system/fonts/NotoSansCJK-Regular.ttc",
                        "/system/fonts/Roboto-Regular.ttf"
                    )
                ) ?: Typeface.SANS_SERIF
            }
            BuiltinEinkFont.EINK_BOLD -> {
                val sans = loadSystemFont(
                    listOf("/system/fonts/NotoSansCJK-Regular.ttc")
                ) ?: Typeface.SANS_SERIF
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(sans, 700, false)
                } else {
                    Typeface.create(sans, Typeface.BOLD)
                }
            }
            BuiltinEinkFont.ROUNDED -> {
                val sans = loadSystemFont(
                    listOf("/system/fonts/NotoSansCJK-Regular.ttc")
                ) ?: Typeface.SANS_SERIF
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(sans, 450, false)
                } else {
                    Typeface.create(sans, Typeface.NORMAL)
                }
            }
        }
        return baseTf
    }
}
