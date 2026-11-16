package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.Theme
import io.legado.app.eink.EinkRefreshHelper
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.applyBackgroundTint
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.fullScreen
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto

@Suppress("DEPRECATION")
abstract class BaseActivity<VB : ViewBinding>(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.EInk,
    private val toolBarTheme: Theme = Theme.EInk,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = false,
    private val showOpenMenuIcon: Boolean = true
) : AppCompatActivity() {

    protected abstract val binding: VB

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        if (AppConst.menuViewNames.contains(name) && parent?.parent is FrameLayout) {
            (parent.parent as View).setBackgroundColor(Color.WHITE)
        }
        return super.onCreateView(parent, name, context, attrs)
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.disableAutoFill()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        window.setWindowAnimations(R.style.Animation_EInk_None)
        initTheme()
        super.onCreate(savedInstanceState)
        setupSystemBar()
        setContentView(binding.root)
        upBackgroundImage()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        }
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        observeLiveBus()
        onActivityCreated(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        // 页面切换或从弹窗/子界面返回时，自动执行一次墨水屏白场擦除，消除上一界面的残影
        window?.decorView?.post {
            EinkRefreshHelper.scrubAfterOverlayDismiss(binding.root)
        }
    }

    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        overridePendingTransition(0, 0)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        super.startActivity(intent, null)
        overridePendingTransition(0, 0)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        setupSystemBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindow, fullScreen)
        setupSystemBar()
    }

    abstract fun onActivityCreated(savedInstanceState: Bundle?)

    final override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val bool = onCompatCreateOptionsMenu(menu)
        menu.applyTint(this, toolBarTheme)
        findViewById<TitleBar>(R.id.title_bar)?.toolbar?.let { tb ->
            EinkRefreshHelper.attachLinearOverflowMenu(tb) { item ->
                onOptionsItemSelected(item)
            }
        }
        return bool
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.applyOpenTint(this, showOpenMenuIcon)
        return super.onMenuOpened(featureId, menu)
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) = super.onCreateOptionsMenu(menu)

    final override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return onCompatOptionsItemSelected(item)
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) = super.onOptionsItemSelected(item)

    open fun initTheme() {
        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            else -> {
                setTheme(R.style.AppTheme_Light)
                window.decorView.applyBackgroundTint(Color.WHITE)
            }
        }
    }

    open fun upBackgroundImage() {
        // 0.4.3 纯黑白线性墨水屏模式不加载任何背景图片
        binding.root.setBackgroundColor(Color.WHITE)
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            fullScreen()
        }
        setStatusBarColorAuto(Color.WHITE, false, fullScreen)
        setLightStatusBar(true)
        upNavigationBarColor()
    }

    open fun upNavigationBarColor() {
        setNavigationBarColorAuto(Color.WHITE)
    }

    open fun observeLiveBus() {
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
        overridePendingTransition(0, 0)
    }
}