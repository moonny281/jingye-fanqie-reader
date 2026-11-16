package io.legado.app.lib.theme.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.TooltipCompat
import io.legado.app.lib.theme.Selector
import io.legado.app.utils.dpToPx

class ThemeRadioNoButton(context: Context, attrs: AttributeSet) :
    AppCompatRadioButton(context, attrs) {

    init {
        buttonDrawable = null
        stateListAnimator = null
        if (!isInEditMode) {
            background = Selector.shapeBuild()
                .setCornerRadius(0)
                .setStrokeWidth(1.dpToPx())
                .setDefaultBgColor(Color.WHITE)
                .setDefaultStrokeColor(Color.BLACK)
                .setCheckedBgColor(Color.BLACK)
                .setCheckedStrokeColor(Color.BLACK)
                .setPressedBgColor(Color.BLACK)
                .setPressedStrokeColor(Color.BLACK)
                .create()
            setTextColor(
                Selector.colorBuild()
                    .setDefaultColor(Color.BLACK)
                    .setCheckedColor(Color.WHITE)
                    .setPressedColor(Color.WHITE)
                    .create()
            )
        }
        TooltipCompat.setTooltipText(this, text)
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        jumpDrawablesToCurrentState()
    }
}
