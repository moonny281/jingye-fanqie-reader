package io.legado.app.ui.about

import android.os.Bundle
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.databinding.ActivityAboutBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    override val binding by viewBinding(ActivityAboutBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.llAbout.setBackgroundResource(R.drawable.bg_eink_border_dialog)
        binding.tvAppSummary.text = "原生墨水屏专享版 v${appInfo.versionName} · 全局零动画 · 局部/全局双模清残影"
        val fTag = "aboutFragment"
        var aboutFragment = supportFragmentManager.findFragmentByTag(fTag)
        if (aboutFragment == null) aboutFragment = AboutFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_fragment, aboutFragment, fTag)
            .commit()
    }

}
