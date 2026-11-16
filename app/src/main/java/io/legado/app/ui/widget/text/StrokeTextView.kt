package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.lib.theme.Selector
import io.legado.app.utils.dpToPx

@Suppress("unused")
open class StrokeTextView(context: Context, attrs: AttributeSet?) :
    AppCompatTextView(context, attrs) {

    init {
        stateListAnimator = null
        upBackground()
    }

    fun setRadius(radius: Int) {
        upBackground()
    }

    private fun upBackground() {
        background = Selector.shapeBuild()
            .setCornerRadius(0)
            .setStrokeWidth(1.dpToPx())
            .setDefaultBgColor(Color.WHITE)
            .setDefaultStrokeColor(Color.BLACK)
            .setDisabledBgColor(Color.WHITE)
            .setDisabledStrokeColor(Color.BLACK)
            .setSelectedBgColor(Color.BLACK)
            .setSelectedStrokeColor(Color.BLACK)
            .setPressedBgColor(Color.BLACK)
            .setPressedStrokeColor(Color.BLACK)
            .create()
        setTextColor(
            Selector.colorBuild()
                .setDefaultColor(Color.BLACK)
                .setSelectedColor(Color.WHITE)
                .setPressedColor(Color.WHITE)
                .setDisabledColor(Color.BLACK)
                .create()
        )
    }
}
