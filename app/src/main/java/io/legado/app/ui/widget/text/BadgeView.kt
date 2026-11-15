package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.R
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

/**
 * 墨水屏纯黑白线性角标组件 (v0.4.3)：
 * 去除彩色/灰阶圆角底色，采用 1-bit 纯黑白线性方框（高亮时黑底白字，普通时白底黑框黑字）。
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class BadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    var isHideOnNull = true
        set(hideOnNull) {
            field = hideOnNull
            text = text
        }

    val badgeCount: Int?
        get() {
            if (text == null) return null
            return kotlin.runCatching { Integer.parseInt(text.toString()) }.getOrNull()
        }

    var badgeGravity: Int
        get() = (layoutParams as LayoutParams).gravity
        set(gravity) {
            val params = layoutParams as LayoutParams
            params.gravity = gravity
            layoutParams = params
        }

    val badgeMargin: IntArray
        get() {
            val params = layoutParams as LayoutParams
            return intArrayOf(
                params.leftMargin,
                params.topMargin,
                params.rightMargin,
                params.bottomMargin
            )
        }

    init {
        if (layoutParams !is LayoutParams) {
            setLayoutParams(
                LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dip2Px(6f), dip2Px(1f), dip2Px(6f), dip2Px(1f))
        gravity = Gravity.CENTER
        isHideOnNull = true
        setHighlight(false)
        setBadgeCount(0)
        minWidth = dip2Px(18f)
        minHeight = dip2Px(18f)
    }

    override fun setBackgroundColor(color: Int) {
        setHighlight(color == Color.BLACK)
    }

    fun setBackground(dipRadius: Float, badgeColor: Int) {
        setHighlight(badgeColor == Color.BLACK)
    }

    override fun setText(text: CharSequence, type: BufferType) {
        if (isHideOnNull && TextUtils.isEmpty(text)) {
            invisible()
        } else {
            visible()
        }
        super.setText(text, type)
    }

    fun setBadgeCount(count: Int) {
        text = if (count == 0) "" else count.toString()
    }

    fun setHighlight(highlight: Boolean) {
        if (highlight) {
            setBackgroundResource(R.drawable.bg_eink_button_solid)
            setTextColor(Color.WHITE)
        } else {
            setBackgroundResource(R.drawable.bg_eink_button_outline)
            setTextColor(Color.BLACK)
        }
    }

    fun setBadgeMargin(dipMargin: Int) {
        setBadgeMargin(dipMargin, dipMargin, dipMargin, dipMargin)
    }

    fun setBadgeMargin(
        leftDipMargin: Int,
        topDipMargin: Int,
        rightDipMargin: Int,
        bottomDipMargin: Int
    ) {
        val params = layoutParams as LayoutParams
        params.leftMargin = dip2Px(leftDipMargin.toFloat())
        params.topMargin = dip2Px(topDipMargin.toFloat())
        params.rightMargin = dip2Px(rightDipMargin.toFloat())
        params.bottomMargin = dip2Px(bottomDipMargin.toFloat())
        layoutParams = params
    }

    fun incrementBadgeCount(increment: Int) {
        val count = badgeCount
        if (count == null) {
            setBadgeCount(increment)
        } else {
            setBadgeCount(increment + count)
        }
    }

    fun decrementBadgeCount(decrement: Int) {
        incrementBadgeCount(-decrement)
    }

    fun setTargetView(target: View?) {
        if (parent != null) {
            (parent as ViewGroup).removeView(this)
        }
        if (target == null) return
        if (target.parent is FrameLayout) {
            (target.parent as FrameLayout).addView(this)
        } else if (target.parent is ViewGroup) {
            val parentContainer = target.parent as ViewGroup
            val groupIndex = parentContainer.indexOfChild(target)
            parentContainer.removeView(target)
            val badgeContainer = FrameLayout(context)
            val parentLayoutParams = target.layoutParams
            badgeContainer.layoutParams = parentLayoutParams
            target.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            parentContainer.addView(badgeContainer, groupIndex, parentLayoutParams)
            badgeContainer.addView(target)
            badgeContainer.addView(this)
        }
    }

    private fun dip2Px(dip: Float): Int {
        return (dip * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
