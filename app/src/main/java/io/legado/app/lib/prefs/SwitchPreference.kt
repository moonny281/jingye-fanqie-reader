package io.legado.app.lib.prefs

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import io.legado.app.R
import io.legado.app.lib.theme.TintHelper

class SwitchPreference(context: Context, attrs: AttributeSet) :
    SwitchPreferenceCompat(context, attrs) {

    private val isBottomBackground: Boolean
    private var onLongClick: ((preference: SwitchPreference) -> Boolean)? = null

    init {
        layoutResource = R.layout.view_preference
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.Preference)
        isBottomBackground = typedArray.getBoolean(R.styleable.Preference_isBottomBackground, false)
        typedArray.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        val v = Preference.bindView<SwitchCompat>(
            context, holder, icon, title, summary,
            widgetLayoutResource,
            androidx.preference.R.id.switchWidget,
            isBottomBackground = isBottomBackground
        )
        super.onBindViewHolder(holder)
        val sw = v ?: (holder.findViewById(androidx.preference.R.id.switchWidget) as? SwitchCompat)
        if (sw != null && !sw.isInEditMode) {
            TintHelper.setTint(sw, Color.BLACK, false)
            sw.jumpDrawablesToCurrentState()
        }
        onLongClick?.let { listener ->
            holder.itemView.setOnLongClickListener {
                listener.invoke(this)
            }
        }
    }

    fun onLongClick(listener: (preference: SwitchPreference) -> Boolean) {
        onLongClick = listener
    }
}
