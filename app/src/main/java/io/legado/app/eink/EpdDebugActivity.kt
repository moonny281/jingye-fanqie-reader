package io.legado.app.eink

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R

class EpdDebugActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epd_debug)

        val patternView = findViewById<TestPatterns>(R.id.patternView)
        val tvStepValue = findViewById<TextView>(R.id.tvStepValue)

        fun updateStepLabel() {
            val n = EinkRefreshHelper.cleanEveryNPages
            tvStepValue.text = if (n <= 0) "关闭" else "${n}页"
        }
        updateStepLabel()

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(0, 0)
        }

        findViewById<Button>(R.id.btnStepMinus).setOnClickListener {
            EinkRefreshHelper.cleanEveryNPages = (EinkRefreshHelper.cleanEveryNPages - 2).coerceAtLeast(0)
            updateStepLabel()
        }

        findViewById<Button>(R.id.btnStepPlus).setOnClickListener {
            EinkRefreshHelper.cleanEveryNPages = (EinkRefreshHelper.cleanEveryNPages + 2).coerceAtMost(60)
            updateStepLabel()
        }

        findViewById<Button>(R.id.btnSwitchPattern).setOnClickListener {
            patternView.patternIndex += 1
        }

        findViewById<Button>(R.id.btnForceFullRefresh).setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            patternView.flashColor = Color.BLACK
            handler.postDelayed({ patternView.flashColor = Color.WHITE }, 120)
            handler.postDelayed({ patternView.flashColor = null }, 240)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
