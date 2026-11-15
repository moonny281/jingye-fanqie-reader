package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadPaddingBinding
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class PaddingConfigDialog : BaseDialogFragment(R.layout.dialog_read_padding) {

    private val binding by viewBinding(DialogReadPaddingBinding::bind)

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = it.attributes
            attr.dimAmount = 0.0f
            it.attributes = attr
        }
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    private fun initData() = binding.run {
        if (EinkRefreshHelper.adaptiveScreenResolution) {
            val dm = resources.displayMetrics
            val adaptive = EinkRefreshHelper.computeAdaptiveMargins(dm.widthPixels, dm.heightPixels, dm.density)
            dsbPaddingTop.progress = adaptive.paddingTopDp
            dsbPaddingBottom.progress = adaptive.paddingBottomDp
            dsbPaddingLeft.progress = adaptive.paddingLeftDp
            dsbPaddingRight.progress = adaptive.paddingRightDp
            dsbHeaderPaddingTop.progress = adaptive.headerPadTopDp
            dsbHeaderPaddingBottom.progress = adaptive.headerPadBottomDp
            dsbHeaderPaddingLeft.progress = adaptive.headerPadHorizontalDp
            dsbHeaderPaddingRight.progress = adaptive.headerPadHorizontalDp
            dsbFooterPaddingTop.progress = adaptive.footerPadTopDp
            dsbFooterPaddingBottom.progress = adaptive.footerPadBottomDp
            dsbFooterPaddingLeft.progress = adaptive.footerPadHorizontalDp
            dsbFooterPaddingRight.progress = adaptive.footerPadHorizontalDp
        } else {
            dsbPaddingTop.progress = ReadBookConfig.paddingTop
            dsbPaddingBottom.progress = ReadBookConfig.paddingBottom
            dsbPaddingLeft.progress = ReadBookConfig.paddingLeft
            dsbPaddingRight.progress = ReadBookConfig.paddingRight
            dsbHeaderPaddingTop.progress = ReadBookConfig.headerPaddingTop
            dsbHeaderPaddingBottom.progress = ReadBookConfig.headerPaddingBottom
            dsbHeaderPaddingLeft.progress = ReadBookConfig.headerPaddingLeft
            dsbHeaderPaddingRight.progress = ReadBookConfig.headerPaddingRight
            dsbFooterPaddingTop.progress = ReadBookConfig.footerPaddingTop
            dsbFooterPaddingBottom.progress = ReadBookConfig.footerPaddingBottom
            dsbFooterPaddingLeft.progress = ReadBookConfig.footerPaddingLeft
            dsbFooterPaddingRight.progress = ReadBookConfig.footerPaddingRight
        }
        cbShowTopLine.isChecked = ReadBookConfig.showHeaderLine
        cbShowBottomLine.isChecked = ReadBookConfig.showFooterLine
    }

    private fun initView() = binding.run {
        dsbPaddingTop.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.paddingTop = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(10, 5))
        }
        dsbPaddingBottom.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.paddingBottom = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(10, 5))
        }
        dsbPaddingLeft.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.paddingLeft = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(10, 5))
        }
        dsbPaddingRight.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.paddingRight = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(10, 5))
        }
        dsbHeaderPaddingTop.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.headerPaddingTop = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        dsbHeaderPaddingBottom.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.headerPaddingBottom = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        dsbHeaderPaddingLeft.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.headerPaddingLeft = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        dsbHeaderPaddingRight.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.headerPaddingRight = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        dsbFooterPaddingTop.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.footerPaddingTop = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        dsbFooterPaddingBottom.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.footerPaddingBottom = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        dsbFooterPaddingLeft.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.footerPaddingLeft = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        dsbFooterPaddingRight.onChanged = {
            EinkRefreshHelper.adaptiveScreenResolution = false
            ReadBookConfig.footerPaddingRight = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        cbShowTopLine.onCheckedChangeListener = { _, isChecked ->
            ReadBookConfig.showHeaderLine = isChecked
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        cbShowBottomLine.onCheckedChangeListener = { _, isChecked ->
            ReadBookConfig.showFooterLine = isChecked
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
    }

}
