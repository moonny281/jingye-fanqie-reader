package io.legado.app.base

import android.content.DialogInterface
import android.view.Gravity
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.legado.app.R
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.utils.dpToPx

abstract class BasePrefDialogFragment : DialogFragment() {

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { win ->
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = win.attributes
            attr.dimAmount = 0.0f
            attr.windowAnimations = R.style.Animation_EInk_None
            win.attributes = attr
            win.decorView.elevation = 0f
            win.decorView.translationZ = 0f
            win.setBackgroundDrawableResource(R.color.transparent)
        }

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                val root = view
                if (root != null) {
                    when (dialog?.window?.attributes?.gravity) {
                        Gravity.TOP -> root.setBackgroundResource(R.drawable.bg_eink_border_bottom)
                        Gravity.BOTTOM -> root.setBackgroundResource(R.drawable.bg_eink_border_top)
                        else -> {
                            val padding = 2.dpToPx()
                            root.setPadding(padding, padding, padding, padding)
                            root.setBackgroundResource(R.drawable.bg_eink_border_dialog)
                        }
                    }
                    EinkRefreshHelper.scrubDialogOnShow(root)
                }
            }
        })
    }

    override fun onDismiss(dialog: DialogInterface) {
        val hostDecor = activity?.window?.decorView
        super.onDismiss(dialog)
        EinkRefreshHelper.scrubAfterOverlayDismiss(hostDecor)
    }
}
