package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.CheckResult
import androidx.annotation.ColorInt
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.legado.app.R

/**
 * 1-Bit 纯黑白线性控件样式注入器 (v0.4.3)：
 * 将全局 Switch、SwitchCompat、CheckBox、RadioButton、SeekBar、Button 统一替换为无动画 1-bit 纯黑白线性 UI。
 */
@Suppress("MemberVisibilityCanBePrivate", "UNUSED_PARAMETER")
object TintHelper {

    private fun oneBitTextColorList(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(Color.WHITE, Color.WHITE, Color.WHITE, Color.BLACK)
        )
    }

    fun setTintSelector(view: View, @ColorInt color: Int, darker: Boolean, useDarkTheme: Boolean) {
        view.stateListAnimator = null
        view.elevation = 0f
        when (view) {
            is Button -> {
                view.setBackgroundResource(R.drawable.selector_eink_button)
                view.setTextColor(oneBitTextColorList())
            }
            is FloatingActionButton -> {
                view.rippleColor = Color.TRANSPARENT
                view.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                if (view.drawable != null) {
                    view.setImageDrawable(createTintedDrawable(view.drawable, Color.BLACK))
                }
            }
            else -> {
                if (view is TextView) {
                    view.setTextColor(Color.BLACK)
                }
            }
        }
    }

    fun setTintAuto(
        view: View,
        @ColorInt color: Int,
        isBackground: Boolean,
        isDark: Boolean
    ) {
        view.stateListAnimator = null
        var isBg = isBackground
        if (!isBg) {
            when (view) {
                is RadioButton -> setTint(view, Color.BLACK, false)
                is SeekBar -> setTint(view, Color.BLACK, false)
                is ProgressBar -> setTint(view, Color.BLACK)
                is AppCompatEditText -> setTint(view, Color.BLACK, false)
                is CheckBox -> setTint(view, Color.BLACK, false)
                is CheckedTextView -> setTint(view, Color.BLACK, false)
                is ImageView -> setTint(view, Color.BLACK)
                is Switch -> setTint(view, Color.BLACK, false)
                is SwitchCompat -> setTint(view, Color.BLACK, false)
                is SearchView -> {
                    val iconIdS = intArrayOf(
                        androidx.appcompat.R.id.search_button,
                        androidx.appcompat.R.id.search_close_btn,
                        androidx.appcompat.R.id.search_go_btn,
                        androidx.appcompat.R.id.search_voice_btn,
                        androidx.appcompat.R.id.search_mag_icon
                    )
                    for (iconId in iconIdS) {
                        val icon = view.findViewById<ImageView>(iconId)
                        if (icon != null) {
                            setTint(icon, Color.BLACK)
                        }
                    }
                }
                else -> isBg = true
            }
        }
        if (isBg) {
            if (view is FloatingActionButton || view is Button) {
                setTintSelector(view, Color.BLACK, false, false)
            } else if (view.background != null) {
                var drawable: Drawable? = view.background
                if (drawable != null) {
                    drawable = createTintedDrawable(drawable, color)
                    ViewUtils.setBackgroundCompat(view, drawable)
                }
            }
        }
    }

    @SuppressLint("PrivateResource")
    fun setTint(radioButton: RadioButton, @ColorInt color: Int, useDarker: Boolean) {
        radioButton.buttonDrawable = ContextCompat.getDrawable(radioButton.context, R.drawable.eink_radiobutton_1bit)?.mutate()
        radioButton.buttonTintList = null
        radioButton.background = null
        radioButton.stateListAnimator = null
        radioButton.setTextColor(Color.BLACK)
        radioButton.jumpDrawablesToCurrentState()
    }

    fun setTint(seekBar: SeekBar, @ColorInt color: Int, useDarker: Boolean) {
        seekBar.progressDrawable = ContextCompat.getDrawable(seekBar.context, R.drawable.eink_seekbar_progress_1bit)?.mutate()
        seekBar.thumb = ContextCompat.getDrawable(seekBar.context, R.drawable.eink_seekbar_thumb_1bit)?.mutate()
        seekBar.thumbTintList = null
        seekBar.progressTintList = null
        seekBar.secondaryProgressTintList = null
        seekBar.splitTrack = false
        seekBar.background = null
        seekBar.stateListAnimator = null
    }

    @JvmOverloads
    fun setTint(
        progressBar: ProgressBar, @ColorInt color: Int,
        skipIndeterminate: Boolean = false
    ) {
        val sl = ColorStateList.valueOf(Color.BLACK)
        progressBar.progressTintList = sl
        progressBar.secondaryProgressTintList = sl
        if (!skipIndeterminate)
            progressBar.indeterminateTintList = sl
    }

    @SuppressLint("RestrictedApi")
    fun setTint(editText: AppCompatEditText, @ColorInt color: Int, useDarker: Boolean) {
        val sl = ColorStateList.valueOf(Color.BLACK)
        editText.supportBackgroundTintList = sl
        editText.setTextColor(Color.BLACK)
        setCursorTint(editText, Color.BLACK)
    }

    @SuppressLint("PrivateResource")
    fun setTint(box: CheckBox, @ColorInt color: Int, useDarker: Boolean) {
        box.buttonDrawable = ContextCompat.getDrawable(box.context, R.drawable.eink_checkbox_1bit)?.mutate()
        box.buttonTintList = null
        box.background = null
        box.stateListAnimator = null
        box.setTextColor(Color.BLACK)
        box.jumpDrawablesToCurrentState()
    }

    @SuppressLint("PrivateResource")
    fun setTint(checkedTextView: CheckedTextView, @ColorInt color: Int, useDarker: Boolean) {
        val sl = ColorStateList.valueOf(Color.BLACK)
        checkedTextView.checkMarkTintList = sl
        checkedTextView.setTextColor(Color.BLACK)
        TextViewCompat.setCompoundDrawableTintList(checkedTextView, sl)
    }

    fun setTint(image: ImageView, @ColorInt color: Int) {
        image.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_ATOP)
    }

    fun setTint(
        @SuppressLint("UseSwitchCompatOrMaterialCode") switchView: Switch,
        @ColorInt color: Int,
        useDarker: Boolean
    ) {
        switchView.trackDrawable = ContextCompat.getDrawable(switchView.context, R.drawable.eink_switch_track_1bit)?.mutate()
        switchView.thumbDrawable = ContextCompat.getDrawable(switchView.context, R.drawable.eink_switch_thumb_1bit)?.mutate()
        switchView.trackTintList = null
        switchView.thumbTintList = null
        switchView.background = null
        switchView.stateListAnimator = null
        switchView.setTextColor(Color.BLACK)
        switchView.jumpDrawablesToCurrentState()
    }

    fun setTint(switchView: SwitchCompat, @ColorInt color: Int, useDarker: Boolean) {
        switchView.trackDrawable = ContextCompat.getDrawable(switchView.context, R.drawable.eink_switch_track_1bit)?.mutate()
        switchView.thumbDrawable = ContextCompat.getDrawable(switchView.context, R.drawable.eink_switch_thumb_1bit)?.mutate()
        switchView.trackTintList = null
        switchView.thumbTintList = null
        switchView.splitTrack = false
        switchView.showText = false
        switchView.background = null
        switchView.stateListAnimator = null
        switchView.setTextColor(Color.BLACK)
        switchView.jumpDrawablesToCurrentState()
    }

    @CheckResult
    fun createTintedDrawable(drawable: Drawable?, @ColorInt color: Int): Drawable? {
        var drawable1: Drawable? = drawable ?: return null
        drawable1 = DrawableCompat.wrap(drawable1!!.mutate())
        DrawableCompat.setTintMode(drawable1, PorterDuff.Mode.SRC_IN)
        DrawableCompat.setTint(drawable1, color)
        return drawable1
    }

    @CheckResult
    fun createTintedDrawable(drawable: Drawable?, sl: ColorStateList): Drawable? {
        var drawable1: Drawable? = drawable ?: return null
        drawable1 = DrawableCompat.wrap(drawable1!!.mutate())
        DrawableCompat.setTintList(drawable1, sl)
        return drawable1
    }

    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    fun setCursorTint(editText: EditText, @ColorInt color: Int) {
        try {
            val fCursorDrawableRes = TextView::class.java.getDeclaredField("mCursorDrawableRes")
            fCursorDrawableRes.isAccessible = true
            val mCursorDrawableRes = fCursorDrawableRes.getInt(editText)
            val fEditor = TextView::class.java.getDeclaredField("mEditor")
            fEditor.isAccessible = true
            val editor = fEditor.get(editText)
            val clazz = editor.javaClass
            val fCursorDrawable = clazz.getDeclaredField("mCursorDrawable")
            fCursorDrawable.isAccessible = true
            val drawables = arrayOfNulls<Drawable>(2)
            drawables[0] = ContextCompat.getDrawable(editText.context, mCursorDrawableRes)
            drawables[0] = createTintedDrawable(drawables[0], color)
            drawables[1] = ContextCompat.getDrawable(editText.context, mCursorDrawableRes)
            drawables[1] = createTintedDrawable(drawables[1], color)
            fCursorDrawable.set(editor, drawables)
        } catch (_: Exception) {
        }
    }
}
