package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 显示图片
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

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @SuppressLint("CheckResult")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val arguments = arguments ?: return
        val src = arguments.getString("src") ?: return
        val isBook = arguments.getBoolean("isBook")
        setupZoomControls(isBook)
        ImageProvider.get(src)?.let {
            binding.photoView.setImageBitmap(it)
            return
        }
        val file = if (isBook) ReadBook.book?.let { book ->
            BookHelp.getImage(book, src).takeIf { it.isFile && it.length() > 128L }
                ?: io.legado.app.fanqie.FanqieLegadoBridge.findCachedInlineImage(book, src)
        } else null
        if (file?.exists() == true) {
            ImageLoader.load(requireContext(), file)
                .error(R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(binding.photoView)
        } else {
            ImageLoader.load(requireContext(), src).apply {
                arguments.getString("sourceOrigin")?.let { sourceOrigin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin))
                }
            }.error(if (isBook) BookCover.defaultDrawable else R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .into(binding.photoView)
        }
    }


    /**
     * 0.4.9：墨水屏友好的图片预览控制：放大/缩小/关闭按钮 + 灰度对比度增强，禁用动画。
     * 双击仍可在 1 倍与最大倍数之间切换，双指缩放也保留。
     */
    private fun setupZoomControls(isBook: Boolean) {
        val pv = binding.photoView
        pv.setMaxScale(5f)
        pv.setAnimDuring(120)
        if (isBook) {
            val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            val c = 1.2f
            val t = (-0.5f * c + 0.5f) * 255f
            cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )))
            pv.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        binding.btnZoomIn.setOnClickListener { pv.zoomToScale(pv.currentScale() * 1.6f) }
        binding.btnZoomOut.setOnClickListener { pv.zoomToScale(pv.currentScale() / 1.6f) }
        binding.btnZoomFit.setOnClickListener { pv.zoomToScale(1f) }
        binding.btnPhotoClose.setOnClickListener { dismissAllowingStateLoss() }
    }

}
