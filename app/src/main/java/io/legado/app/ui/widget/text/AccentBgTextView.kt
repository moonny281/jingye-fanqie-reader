package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.lib.theme.Selector
import io.legado.app.utils.dpToPx

class AccentBgTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

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
            .setDefaultBgColor(Color.BLACK)
            .setDefaultStrokeColor(Color.BLACK)
            .setPressedBgColor(Color.WHITE)
            .setPressedStrokeColor(Color.BLACK)
            .create()
        setTextColor(
            Selector.colorBuild()
                .setDefaultColor(Color.WHITE)
                .setPressedColor(Color.BLACK)
                .create()
        )
    }
}
