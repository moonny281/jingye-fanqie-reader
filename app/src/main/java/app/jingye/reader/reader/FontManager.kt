package app.jingye.reader.reader

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.TextPaint
import java.io.File

enum class BuiltinFont(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val description: String
) {
    SERIF("serif", "思源宋体", "宋体", "传统衬线书宋，横细竖粗，适合长篇沉浸阅读"),
    WENKAI("wenkai", "霞鹜文楷", "文楷", "温润手写楷书笔触，字形舒展有书卷气"),
    SANS("sans", "思源黑体", "黑体", "现代无衬线等线黑体，笔画均匀清晰"),
    EINK_BOLD("eink_bold", "墨水屏凝黑", "凝黑", "专为海信墨水屏定制高黑度加粗，无背光亦锐利"),
    FANGSONG("fangsong", "古籍刻本", "古籍", "清朗修长刻本风貌，搭配宽字距更具古韵"),
    ROUNDED("rounded", "护眼柔黑", "柔黑", "圆润笔端与柔和转角，减轻长时间阅读视觉疲劳"),
    CUSTOM("custom", "自定义字体", "自定", "使用从本机导入的 TTF / OTF 字体文件");

    companion object {
        fun fromId(id: String): BuiltinFont =
            values().firstOrNull { it.id == id } ?: SERIF
    }
}

object FontManager {
    private val cachedAssets = HashMap<String, Typeface?>()
    private var cachedCustomPath: String? = null
    private var cachedCustomTypeface: Typeface? = null

    private fun loadAssetFont(ctx: Context, assetRelPath: String): Typeface? {
        synchronized(cachedAssets) {
            if (cachedAssets.containsKey(assetRelPath)) return cachedAssets[assetRelPath]
            val tf = try {
                Typeface.createFromAsset(ctx.assets, assetRelPath)
            } catch (_: Throwable) {
                null
            }
            cachedAssets[assetRelPath] = tf
            return tf
        }
    }

    private fun loadSystemFontFile(paths: List<String>): Typeface? {
        for (p in paths) {
            val f = File(p)
            if (f.isFile && f.canRead()) {
                try {
                    return Typeface.createFromFile(f)
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    fun resolveBaseTypeface(ctx: Context, font: BuiltinFont, customFontPath: String): Typeface {
        return when (font) {
            BuiltinFont.SERIF -> {
                loadAssetFont(ctx, "fonts/serif.otf")
                    ?: loadAssetFont(ctx, "fonts/serif.ttf")
                    ?: loadSystemFontFile(
                        listOf(
                            "/system/fonts/NotoSerifCJK-Regular.ttc",
                            "/system/fonts/SourceHanSerifSC-Regular.otf",
                            "/system/fonts/DroidSerif-Regular.ttf"
                        )
                    )
                    ?: Typeface.SERIF
            }
            BuiltinFont.WENKAI -> {
                loadAssetFont(ctx, "fonts/wenkai.ttf")
                    ?: loadAssetFont(ctx, "fonts/wenkai.otf")
                    ?: loadSystemFontFile(
                        listOf(
                            "/system/fonts/KaiTi.ttf",
                            "/system/fonts/STKaiti.ttf",
                            "/system/fonts/NotoSerifCJK-Regular.ttc"
                        )
                    )
                    ?: Typeface.SERIF
            }
            BuiltinFont.SANS -> {
                loadSystemFontFile(
                    listOf(
                        "/system/fonts/NotoSansCJK-Regular.ttc",
                        "/system/fonts/DroidSansFallback.ttf"
                    )
                ) ?: Typeface.SANS_SERIF
            }
            BuiltinFont.EINK_BOLD -> {
                loadSystemFontFile(
                    listOf(
                        "/system/fonts/NotoSansCJK-Bold.ttc",
                        "/system/fonts/NotoSansCJK-Regular.ttc"
                    )
                ) ?: Typeface.DEFAULT_BOLD
            }
            BuiltinFont.FANGSONG -> {
                loadAssetFont(ctx, "fonts/serif.otf")
                    ?: loadSystemFontFile(
                        listOf(
                            "/system/fonts/NotoSerifCJK-Regular.ttc"
                        )
                    )
                    ?: Typeface.SERIF
            }
            BuiltinFont.ROUNDED -> {
                Typeface.SANS_SERIF
            }
            BuiltinFont.CUSTOM -> {
                if (customFontPath.isNotBlank()) {
                    if (cachedCustomPath == customFontPath && cachedCustomTypeface != null) {
                        return cachedCustomTypeface!!
                    }
                    val f = File(customFontPath)
                    if (f.isFile) {
                        try {
                            val tf = Typeface.createFromFile(f)
                            cachedCustomPath = customFontPath
                            cachedCustomTypeface = tf
                            return tf
                        } catch (_: Throwable) {
                        }
                    }
                }
                Typeface.SERIF
            }
        }
    }

    fun applyFontToPaint(
        ctx: Context?,
        paint: TextPaint,
        style: ReaderStyle,
        isHeading: Boolean = false
    ) {
        val font = BuiltinFont.fromId(style.fontId)
        val baseTf = if (ctx != null) {
            resolveBaseTypeface(ctx, font, style.customFontPath)
        } else {
            when (font) {
                BuiltinFont.SERIF, BuiltinFont.WENKAI, BuiltinFont.FANGSONG -> Typeface.SERIF
                BuiltinFont.EINK_BOLD -> Typeface.DEFAULT_BOLD
                else -> Typeface.SANS_SERIF
            }
        }

        val targetWeight = when {
            isHeading -> 700
            font == BuiltinFont.EINK_BOLD -> maxOf(style.weight, 600)
            font == BuiltinFont.FANGSONG && style.weight <= 400 -> 350
            else -> style.weight
        }

        paint.typeface = when {
            Build.VERSION.SDK_INT >= 28 -> Typeface.create(baseTf, targetWeight, false)
            targetWeight >= 550 -> Typeface.create(baseTf, Typeface.BOLD)
            else -> baseTf
        }

        // 针对墨水屏不同字体的笔触微调，使每种字体在黑白墨水屏上都呈现鲜明观感差异
        paint.letterSpacing = style.letterSpacing + when (font) {
            BuiltinFont.FANGSONG -> 0.03f
            BuiltinFont.WENKAI -> 0.015f
            else -> 0f
        }

        paint.textSkewX = when {
            font == BuiltinFont.WENKAI && cachedAssets["fonts/wenkai.ttf"] == null -> -0.05f
            else -> 0f
        }

        val extraStroke = when {
            isHeading -> 0.7f
            font == BuiltinFont.EINK_BOLD -> 0.55f
            style.weight >= 500 -> 0.35f
            font == BuiltinFont.ROUNDED -> 0.2f
            else -> 0f
        }

        if (extraStroke > 0f) {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = extraStroke
            if (font == BuiltinFont.ROUNDED) {
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
            } else {
                paint.strokeCap = Paint.Cap.BUTT
                paint.strokeJoin = Paint.Join.MITER
            }
        } else {
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        }
    }

    /** 导入用户本地选择的 .ttf / .otf 字体文件到私有目录。 */
    fun importCustomFont(ctx: Context, uri: Uri): Pair<String, String> {
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "custom_font.ttf"

        val ext = if (name.lowercase().endsWith(".otf")) ".otf" else ".ttf"
        val fontsDir = File(ctx.filesDir, "fonts").apply { mkdirs() }
        val outFile = File(fontsDir, "user_custom$ext")
        val input = ctx.contentResolver.openInputStream(uri) ?: error("无法读取所选字体文件")
        input.use { ins ->
            outFile.outputStream().use { outs ->
                ins.copyTo(outs)
            }
        }
        // 验证字体文件是否可被 Android Typeface 解析
        val tf = Typeface.createFromFile(outFile)
        cachedCustomPath = outFile.absolutePath
        cachedCustomTypeface = tf
        val cleanName = name.removeSuffix(".ttf").removeSuffix(".TTF")
            .removeSuffix(".otf").removeSuffix(".OTF").take(12)
        return outFile.absolutePath to cleanName
    }
}
