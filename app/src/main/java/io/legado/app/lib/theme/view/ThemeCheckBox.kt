package io.legado.app.lib.theme.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatCheckBox
import io.legado.app.lib.theme.TintHelper

class ThemeCheckBox(context: Context, attrs: AttributeSet) : AppCompatCheckBox(context, attrs) {

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
