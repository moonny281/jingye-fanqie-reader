package io.legado.app.eink

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 兼容旧版入口：不再展示棋盘测试格，直接呼出原生墨水屏全刷设置弹窗后关闭。
 */
class EpdDebugActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EinkRefreshHelper.showEinkSettingsDialog(this, window?.decorView)
        finish()
        overridePendingTransition(0, 0)
    }
}
