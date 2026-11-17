package io.legado.app.base

import android.content.DialogInterface
import android.content.DialogInterface.OnDismissListener
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.LayoutRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setBackgroundKeepPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

abstract class BaseDialogFragment(
    @LayoutRes layoutID: Int,
    private val adaptationSoftKeyboard: Boolean = false
) : DialogFragment(layoutID) {

    private var onDismissListener: OnDismissListener? = null

    fun setOnDismissListener(onDismissListener: OnDismissListener?) {
        this.onDismissListener = onDismissListener
    }

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
            if (adaptationSoftKeyboard) {
                win.setBackgroundDrawableResource(R.color.transparent)
            } else {
                win.decorView.setBackgroundKeepPadding(R.color.transparent)
            }
        }
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                val root = view
                if (root != null) {
                    if (!adaptationSoftKeyboard) {
                        when (dialog?.window?.attributes?.gravity) {
                            Gravity.TOP -> root.setBackgroundResource(R.drawable.bg_eink_border_bottom)
                            Gravity.BOTTOM -> root.setBackgroundResource(R.drawable.bg_eink_border_top)
                            else -> {
                                val padding = 2.dpToPx()
                                root.setPadding(padding, padding, padding, padding)
                                root.setBackgroundResource(R.drawable.bg_eink_border_dialog)
                            }
                        }
                    } else if (root.background == null) {
                        root.setBackgroundResource(R.drawable.bg_eink_border_dialog)
                    }
                    EinkRefreshHelper.scrubDialogOnShow(root)
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            setStyle(STYLE_NO_TITLE, 0)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        EinkRefreshHelper.enforce1BitWireframeOnTree(view)
        if (adaptationSoftKeyboard) {
            view.findViewById<View>(R.id.vw_bg)?.setOnClickListener(null)
            view.setOnClickListener { dismiss() }
        }
        onFragmentCreated(view, savedInstanceState)
        observeLiveBus()
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        val hostDecor = activity?.window?.decorView
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
        // 对话框关闭后立即对底层窗口执行白场局刷擦除，彻底清除对话框线框残影
        EinkRefreshHelper.scrubAfterOverlayDismiss(hostDecor)
    }

    fun <T> execute(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(scope, context) { block() }

    open fun observeLiveBus() {
    }

    fun findParentTextInputLayout(view: View): TextInputLayout? {
        var parent = view.parent
        while (parent != null && parent !is TextInputLayout) {
            parent = parent.parent
        }
        return parent
    }
}
