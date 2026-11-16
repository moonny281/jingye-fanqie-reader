package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.widget.FrameLayout
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import io.legado.app.R
import io.legado.app.databinding.ViewSelectActionBarBinding
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.lib.theme.TintHelper
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.visible

@Suppress("unused")
class SelectActionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var callBack: CallBack? = null
    private var selMenu: PopupMenu? = null
    private var menuListener: PopupMenu.OnMenuItemClickListener? = null
    private val binding = ViewSelectActionBarBinding
        .inflate(LayoutInflater.from(context), this, true)

    init {
        if (!isInEditMode) {
            setBackgroundResource(R.drawable.bg_eink_border_top)
            elevation = 0f
            binding.cbSelectedAll.setTextColor(Color.BLACK)
            TintHelper.setTint(binding.cbSelectedAll, Color.BLACK, false)
            binding.ivMenuMore.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
            binding.cbSelectedAll.setOnUserCheckedChangeListener { isChecked ->
                callBack?.selectAll(isChecked)
            }
            binding.btnRevertSelection.setOnClickListener { callBack?.revertSelection() }
            binding.btnSelectActionMain.setOnClickListener { callBack?.onClickSelectBarMainAction() }
            binding.ivMenuMore.setOnClickListener {
                val pm = selMenu
                if (pm != null) {
                    EinkRefreshHelper.showLinearMenuPopup(binding.ivMenuMore, pm.menu, onlyOverflow = false) { item ->
                        menuListener?.onMenuItemClick(item)
                    }
                }
            }
            applyNavigationBarPadding()
        }
    }

    fun setMainActionText(text: String) = binding.run {
        btnSelectActionMain.text = text
        btnSelectActionMain.visible()
    }

    fun setMainActionText(@StringRes id: Int) = binding.run {
        btnSelectActionMain.setText(id)
        btnSelectActionMain.visible()
    }

    fun inflateMenu(@MenuRes resId: Int): Menu? {
        selMenu = PopupMenu(context, binding.ivMenuMore)
        selMenu?.inflate(resId)
        binding.ivMenuMore.visible()
        return selMenu?.menu
    }

    fun setCallBack(callBack: CallBack) {
        this.callBack = callBack
    }

    fun setOnMenuItemClickListener(listener: PopupMenu.OnMenuItemClickListener) {
        menuListener = listener
        selMenu?.setOnMenuItemClickListener(listener)
    }

    fun upCountView(selectCount: Int, allCount: Int) = binding.run {
        if (selectCount == 0) {
            cbSelectedAll.isChecked = false
        } else {
            cbSelectedAll.isChecked = selectCount >= allCount
        }

        if (cbSelectedAll.isChecked) {
            cbSelectedAll.text = context.getString(
                R.string.select_cancel_count,
                selectCount,
                allCount
            )
        } else {
            cbSelectedAll.text = context.getString(
                R.string.select_all_count,
                selectCount,
                allCount
            )
        }
        setMenuClickable(selectCount > 0)
    }

    private fun setMenuClickable(isClickable: Boolean) = binding.run {
        btnRevertSelection.isEnabled = isClickable
        btnRevertSelection.isClickable = isClickable
        btnSelectActionMain.isEnabled = isClickable
        btnSelectActionMain.isClickable = isClickable
        ivMenuMore.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
        ivMenuMore.isEnabled = isClickable
        ivMenuMore.isClickable = isClickable
    }

    interface CallBack {
        fun selectAll(selectAll: Boolean)
        fun revertSelection()
        fun onClickSelectBarMainAction() {}
    }
}
