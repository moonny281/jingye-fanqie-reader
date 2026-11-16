package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.help.CacheManager
import io.legado.app.help.DefaultData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.io.File

@Keep
@Suppress("ConstPropertyName")
object BookCover {

    private const val coverRuleConfigKey = "legadoCoverRuleConfig"
    const val configFileName = "coverRule.json"

    var drawBookName = true
        private set
    var drawBookAuthor = true
        private set
    lateinit var defaultDrawable: Drawable
        private set

    init {
        upDefaultCover()
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun upDefaultCover() {
        drawBookName = appCtx.getPrefBoolean(PreferKey.coverShowName, true)
        drawBookAuthor = appCtx.getPrefBoolean(PreferKey.coverShowAuthor, true)
        val path = appCtx.getPrefString(PreferKey.defaultCover)
        // 默认占位图使用纯黑白线性装帧框 bg_eink_default_cover_linear，真实书籍封面正常通过 Glide 加载
        defaultDrawable = runCatching {
            BitmapUtils.decodeBitmap(path!!, 600, 900)!!.toDrawable(appCtx.resources)
        }.getOrDefault(appCtx.resources.getDrawable(R.drawable.bg_eink_default_cover_linear, null))
    }

    /**
     * 加载图书封面（禁用渐变淡入动画 dontAnimate，无封面时回退到纯黑白线性占位框）
     */
    fun load(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        onLoadFinish: (() -> Unit)? = null,
    ): RequestBuilder<Drawable> {
        if (AppConfig.useDefaultCover) {
            return ImageLoader.load(context, defaultDrawable)
                .dontAnimate()
                .centerCrop()
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        var builder = ImageLoader.load(context, path)
            .apply(options)
            .dontAnimate()
        if (onLoadFinish != null) {
            builder = builder.addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable?>,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }
            })
        }
        return builder.placeholder(defaultDrawable)
            .error(defaultDrawable)
            .dontAnimate()
            .centerCrop()
    }

    fun loadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        transformation: Transformation<Bitmap>? = null,
    ): RequestBuilder<Drawable> {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(OkHttpModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(context, path)
            .apply(options)
            .dontAnimate()
            .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(true).let {
                if (transformation != null) {
                    it.transform(transformation)
                } else {
                    it
                }
            }
    }

    fun preloadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<File?> {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(OkHttpModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return Glide.with(context)
            .downloadOnly()
            .apply(options)
            .load(path)
    }

    fun loadBlur(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<Drawable> {
        val loadBlur = ImageLoader.load(context, defaultDrawable)
            .dontAnimate()
            .transform(BlurTransformation(25), CenterCrop())
        if (AppConfig.useDefaultCover) {
            return loadBlur
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(context, path)
            .apply(options)
            .dontAnimate()
            .transform(BlurTransformation(25), CenterCrop())
            .thumbnail(loadBlur)
    }

    fun getCoverRule(): CoverRule {
        return getConfig() ?: DefaultData.coverRule
    }

    fun getConfig(): CoverRule? {
        return GSON.fromJsonObject<CoverRule>(CacheManager.get(coverRuleConfigKey))
            .getOrNull()
    }

    suspend fun searchCover(book: Book): String? {
        val config = getCoverRule()
        if (!config.enable || config.searchUrl.isBlank() || config.coverRule.isBlank()) {
            return null
        }
        val analyzeUrl = AnalyzeUrl(
            config.searchUrl,
            book.name,
            source = config,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        val res = analyzeUrl.getStrResponseAwait()
        val analyzeRule = AnalyzeRule(book, config)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        analyzeRule.setContent(res.body)
        analyzeRule.setRedirectUrl(res.url)
        return analyzeRule.getString(config.coverRule, isUrl = true)
    }

    fun saveCoverRule(config: CoverRule) {
        val json = GSON.toJson(config)
        saveCoverRule(json)
    }

    fun saveCoverRule(json: String) {
        CacheManager.put(coverRuleConfigKey, json)
    }

    fun delCoverRule() {
        CacheManager.delete(coverRuleConfigKey)
    }

    @Keep
    data class CoverRule(
        var enable: Boolean = true,
        var searchUrl: String,
        var coverRule: String,
        override var concurrentRate: String? = null,
        override var loginUrl: String? = null,
        override var loginUi: String? = null,
        override var header: String? = null,
        override var jsLib: String? = null,
        override var enabledCookieJar: Boolean? = false,
    ) : BaseSource {

        override fun getTag(): String {
            return "CoverRule"
        }

        override fun getKey(): String {
            return searchUrl
        }
    }
}