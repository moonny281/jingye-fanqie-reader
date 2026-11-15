package io.legado.app.ui.welcome

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            finish()
        } else {
            // 墨水屏模式秒开直达主页，无启动页等待与动画
            startMainActivity()
        }
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(Color.WHITE, true, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        // 锁定纯白墨水屏背景
    }

    private fun startMainActivity() {
        startActivity<MainActivity>()
        if (getPrefBoolean(PreferKey.defaultToRead) && appDb.bookDao.lastReadBook != null) {
            startActivity<ReadBookActivity>()
        }
        finish()
        overridePendingTransition(0, 0)
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
class Launcher7 : WelcomeActivity()