package io.legado.app.lib.theme.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.R
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.lib.theme.Selector
import io.legado.app.ui.widget.text.BadgeView

/**
 * 微信读书墨水屏风格纯黑白线性底栏 (v0.4.3)：
 * 采用纯白底色 + 顶部 1.5dp 纯黑线性分割线 + 零阴影 elevation，去除水波纹与位移跳跃。
 */
class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    init {
        setBackgroundResource(R.drawable.bg_eink_border_top)
        elevation = 0f
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(Color.BLACK)
            .setSelectedColor(Color.BLACK)
            .create()
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        itemRippleColor = null
        // 0.4.9：始终显示文字标签（选中=实心图标，未选中=线框图标），墨水屏上无需依赖颜色/动画区分
        labelVisibilityMode = LABEL_VISIBILITY_LABELED
        isItemHorizontalTranslationEnabled = false
        itemBackground = Color.TRANSPARENT.toDrawable()

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    fun addBadgeView(index: Int): BadgeView {
        val menuView = getChildAt(0) as ViewGroup
        val itemView = menuView.getChildAt(index) as ViewGroup
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }
}