package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.lib.theme.Selector
import io.legado.app.utils.dpToPx

class AccentStrokeTextView(context: Context, attrs: AttributeSet) :
    AppCompatTextView(context, attrs) {

    init {
        stateListAnimator = null
        upStyle()
    }

    private fun upStyle() {
        background = Selector.shapeBuild()
            .setCornerRadius(0)
            .setStrokeWidth(1.dpToPx())
            .setDefaultBgColor(Color.WHITE)
            .setDefaultStrokeColor(Color.BLACK)
            .setDisabledBgColor(Color.WHITE)
            .setDisabledStrokeColor(Color.BLACK)
            .setPressedBgColor(Color.BLACK)
            .setPressedStrokeColor(Color.BLACK)
            .setSelectedBgColor(Color.BLACK)
            .setSelectedStrokeColor(Color.BLACK)
            .create()
        setTextColor(
            Selector.colorBuild()
                .setDefaultColor(Color.BLACK)
                .setDisabledColor(Color.BLACK)
                .setPressedColor(Color.WHITE)
                .setSelectedColor(Color.WHITE)
                .create()
        )
    }
}
