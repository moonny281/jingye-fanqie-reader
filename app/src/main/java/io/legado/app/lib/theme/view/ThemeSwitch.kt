package io.legado.app.lib.theme.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import io.legado.app.lib.theme.TintHelper

/**
 * 1-Bit 纯黑白线性无动画开关控件 (v0.4.3)：
 * 采用纯黑白方框轨道与块状滑块，调用 jumpDrawablesToCurrentState 彻底禁用滑动过渡动画。
 */
class ThemeSwitch(context: Context, attrs: AttributeSet) : SwitchCompat(context, attrs) {

    private var isUserAction = false

    init {
        if (!isInEditMode) {
            TintHelper.setTint(this, Color.BLACK, false)
        }
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        jumpDrawablesToCurrentState()
    }

    override fun performClick(): Boolean {
        isUserAction = true
        val result = super.performClick()
        jumpDrawablesToCurrentState()
        isUserAction = false
        return result
    }

    fun setOnUserCheckedChangeListener(listener: ((Boolean) -> Unit)?) {
        if (listener == null) {
            return super.setOnCheckedChangeListener(null)
        }
        super.setOnCheckedChangeListener { _, isChecked ->
            jumpDrawablesToCurrentState()
            if (isUserAction) {
                listener.invoke(isChecked)
            }
        }
    }
}
