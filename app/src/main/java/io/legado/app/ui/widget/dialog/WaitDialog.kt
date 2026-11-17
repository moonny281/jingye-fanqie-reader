package io.legado.app.ui.widget.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import io.legado.app.databinding.DialogWaitBinding
import io.legado.app.eink.EinkRefreshHelper

@Suppress("unused")
class WaitDialog(context: Context) : Dialog(context) {

    val binding = DialogWaitBinding.inflate(layoutInflater)

    init {
        setCanceledOnTouchOutside(false)
        setContentView(binding.root)
        val hostDecor = (context as? Activity)?.window?.decorView
        EinkRefreshHelper.applyEinkDialogStyle(this, hostDecor)
    }

    fun setText(text: String): WaitDialog {
        binding.tvMsg.text = text
        return this
    }

    fun setText(res: Int): WaitDialog {
        binding.tvMsg.setText(res)
        return this
    }
}
