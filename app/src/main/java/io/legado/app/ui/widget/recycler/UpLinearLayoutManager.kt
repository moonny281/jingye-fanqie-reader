package io.legado.app.ui.widget.recycler

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager

@Suppress("MemberVisibilityCanBePrivate", "unused")
class UpLinearLayoutManager(val context: Context) : LinearLayoutManager(context) {

    fun smoothScrollToPosition(position: Int) {
        scrollToPositionWithOffset(position, 0)
    }

    fun smoothScrollToPosition(position: Int, offset: Int) {
        scrollToPositionWithOffset(position, offset)
    }
}
