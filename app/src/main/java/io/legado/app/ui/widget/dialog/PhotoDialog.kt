package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.fanqie.FanqieLegadoBridge
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 显示图片
 *
 * 0.5.0：阅读插图预览（isBook=true）改为“按屏幕分辨率自适应”的显示管线：
 *  1. 按可视区域尺寸采样解码（长边最多 2 倍可视区，兼顾放大细节与内存）；
 *  2. 小图按可视区放大到刚好铺满（高质量双线性重采样），大图保持 ≤2 倍可视区供放大查看；
 *  3. 横图在竖屏上若旋转后能放大 ≥1.6 倍则自动旋转 90°（可用“旋转”按钮切换）；
 *  4. 转灰度 + 对比度增强；可切换 Floyd–Steinberg 黑白抖动，适配 1-Bit/低灰阶墨水屏。
 */
class PhotoDialog() : BaseDialogFragment(R.layout.dialog_photo_view) {

    constructor(src: String, sourceOrigin: String? = null, isBook: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("src", src)
            putString("sourceOrigin", sourceOrigin)
            putBoolean("isBook", isBook)
        }
    }

    private val binding by viewBinding(DialogPhotoViewBinding::bind)

    /** 解码后的原图(已按可视区采样)，不做任何变换，供旋转/抖动重新渲染 */
    private var sourceBitmap: Bitmap? = null
    private var rotation = 0 // 0/90/180/270
    private var dither = false
    private var autoRotateDecided = false
    private var renderToken = 0

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @SuppressLint("CheckResult")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val arguments = arguments ?: return
        val src = arguments.getString("src") ?: return
        val isBook = arguments.getBoolean("isBook")
        setupControls(isBook)
        if (isBook) {
            binding.photoView.post { loadBookImage(src) }
            return
        }
        // 非阅读插图(封面/验证码等)：保持原有加载逻辑
        ImageProvider.get(src)?.let {
            binding.photoView.setImageBitmap(it)
            return
        }
        ImageLoader.load(requireContext(), src).apply {
            arguments.getString("sourceOrigin")?.let { sourceOrigin ->
                apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin))
            }
        }.error(R.drawable.image_loading_error)
            .dontTransform()
            .downsample(DownsampleStrategy.NONE)
            .into(binding.photoView)
    }

    // ---------------------------------------------------------------- 控制按钮

    private fun setupControls(isBook: Boolean) {
        val pv = binding.photoView
        pv.setMaxScale(5f)
        pv.setAnimDuring(120)
        binding.btnZoomIn.setOnClickListener { pv.zoomToScale(pv.currentScale() * 1.6f) }
        binding.btnZoomOut.setOnClickListener { pv.zoomToScale(pv.currentScale() / 1.6f) }
        binding.btnZoomFit.setOnClickListener { pv.zoomToScale(1f) }
        binding.btnPhotoClose.setOnClickListener { dismissAllowingStateLoss() }
        if (isBook) {
            binding.btnPhotoRotate.setOnClickListener {
                rotation = (rotation + 90) % 360
                autoRotateDecided = true
                renderCurrent()
            }
            binding.btnPhotoDither.setOnClickListener {
                dither = !dither
                updateDitherLabel()
                renderCurrent()
            }
            updateDitherLabel()
        } else {
            binding.btnPhotoRotate.visibility = View.GONE
            binding.btnPhotoDither.visibility = View.GONE
        }
    }

    private fun updateDitherLabel() {
        // 按钮文字表示“点击后切换到的模式”
        binding.btnPhotoDither.text = if (dither) "灰阶" else "黑白点"
    }

    // ---------------------------------------------------------------- 加载

    private fun areaSize(): Pair<Int, Int> {
        val pv = binding.photoView
        val dm = resources.displayMetrics
        val w = if (pv.width > 0) pv.width else dm.widthPixels
        val h = if (pv.height > 0) pv.height else (dm.heightPixels * 0.85f).toInt()
        return w to h
    }

    private fun loadBookImage(src: String) {
        val (aw, ah) = areaSize()
        val maxDecode = max(aw, ah) * 2
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeBookImage(src, maxDecode) }
            if (bmp == null) {
                binding.photoView.setImageResource(R.drawable.image_loading_error)
                return@launch
            }
            sourceBitmap = bmp
            renderCurrent()
        }
    }

    /** 优先本地缓存文件(按可视区采样解码)，其次内存缓存，最后网络(Glide)。失败返回 null */
    private fun decodeBookImage(src: String, maxDecode: Int): Bitmap? {
        val book = ReadBook.book
        if (book != null) {
            val file: File? = BookHelp.getImage(book, src).takeIf { it.isFile && it.length() > 128L }
                ?: FanqieLegadoBridge.findCachedInlineImage(book, src)
            if (file != null && file.isFile) {
                decodeFileSampled(file, maxDecode)?.let { return it }
            }
        }
        ImageProvider.get(src)?.takeIf { !it.isRecycled }?.let { return it }
        return runCatching {
            com.bumptech.glide.Glide.with(requireContext().applicationContext)
                .asBitmap().load(src).submit(maxDecode, maxDecode).get()
        }.getOrNull()
    }

    private fun decodeFileSampled(file: File, maxDecode: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return@runCatching null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDecode) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    // ---------------------------------------------------------------- 渲染管线

    private fun renderCurrent() {
        val src = sourceBitmap ?: return
        val (aw, ah) = areaSize()
        if (!autoRotateDecided) {
            autoRotateDecided = true
            rotation = if (shouldAutoRotate(src.width, src.height, aw, ah)) 90 else 0
        }
        val rot = rotation
        val useDither = dither
        val token = ++renderToken
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { render(src, rot, useDither, aw, ah) }
            if (token != renderToken || !isAdded) return@launch
            binding.photoView.setImageBitmap(result)
        }
    }

    /** 横图在竖屏(或反之)上，旋转 90° 后放大倍数 ≥1.6 倍则自动旋转 */
    private fun shouldAutoRotate(bw: Int, bh: Int, aw: Int, ah: Int): Boolean {
        val fitNormal = min(aw.toFloat() / bw, ah.toFloat() / bh)
        val fitRotated = min(aw.toFloat() / bh, ah.toFloat() / bw)
        return fitRotated / fitNormal >= 1.6f
    }

    private fun render(src: Bitmap, rot: Int, useDither: Boolean, aw: Int, ah: Int): Bitmap {
        var bmp = src
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val fit = min(aw.toFloat() / bmp.width, ah.toFloat() / bmp.height)
        // 抖动模式始终在“刚好铺满可视区”的尺寸上做(像素与屏幕 1:1，点阵最清晰)
        // 灰阶模式：小图放大到铺满(最多 4 倍)，大图保持 ≤2 倍可视区供放大查看
        val targetScale = when {
            useDither -> fit.coerceAtMost(4f)
            fit > 1f -> fit.coerceAtMost(4f)
            else -> 1f
        }
        if (abs(targetScale - 1f) > 0.01f) {
            val tw = (bmp.width * targetScale).roundToInt().coerceAtLeast(1)
            val th = (bmp.height * targetScale).roundToInt().coerceAtLeast(1)
            bmp = Bitmap.createScaledBitmap(bmp, tw, th, true)
        }
        val gray = toGrayContrast(bmp)
        return if (useDither) ditherBlackWhite(gray) else gray
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v

    private fun toGrayContrast(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE) // 透明 PNG 铺白底
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val c = 1.2f
        val t = (-0.5f * c + 0.5f) * 255f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Floyd–Steinberg 误差扩散：灰度 → 纯黑白点阵 */
    private fun ditherBlackWhite(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = lum[i]
                val nw = if (old < 128f) 0f else 255f
                val err = old - nw
                if (x + 1 < w) lum[i + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) lum[i + w - 1] += err * 3f / 16f
                    lum[i + w] += err * 5f / 16f
                    if (x + 1 < w) lum[i + w + 1] += err * 1f / 16f
                }
                px[i] = if (nw > 0f) Color.WHITE else Color.BLACK
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

}
