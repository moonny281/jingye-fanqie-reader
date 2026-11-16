@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.core.view.forEach
import io.legado.app.R
import io.legado.app.constant.Theme

@SuppressLint("RestrictedApi")
@Suppress("UsePropertyAccessSyntax")
fun Menu.applyTint(context: Context, theme: Theme = Theme.Auto): Menu = this.let { menu ->
    if (menu is MenuBuilder) {
        menu.setOptionalIconsVisible(false)
    }
    menu.forEach { item ->
        (item as? MenuItemImpl)?.let { impl ->
            if (impl.requiresOverflow()) {
                impl.icon = null
            } else {
                impl.icon?.setTintMutate(Color.BLACK)
            }
        }
    }
    return menu
}

@SuppressLint("RestrictedApi")
fun Menu.applyOpenTint(context: Context, showIcon: Boolean = false) {
    if (this is MenuBuilder) {
        this.setOptionalIconsVisible(false)
    }
    this.forEach { item ->
        (item as? MenuItemImpl)?.let { impl ->
            if (impl.requiresOverflow()) {
                impl.icon = null
            } else {
                impl.icon?.setTintMutate(Color.BLACK)
            }
        }
    }
}

fun Menu.iconItemOnLongClick(id: Int, function: (view: View) -> Unit) {
    findItem(id)?.let { item ->
        item.setActionView(R.layout.view_action_button)
        item.actionView?.run {
            contentDescription = item.title
            findViewById<ImageButton>(R.id.item).setImageDrawable(item.icon)
            setOnLongClickListener {
                function.invoke(this)
                true
            }
            setOnClickListener {
                performIdentifierAction(id, 0)
            }
        }
    }
}

@SuppressLint("RestrictedApi")
inline fun Menu.transaction(block: (Menu) -> Unit) {
    val menuBuilder = this as? MenuBuilder
    menuBuilder?.stopDispatchingItemsChanged()
    try {
        block(this)
    } finally {
        menuBuilder?.startDispatchingItemsChanged()
    }
}

object MenuExtensions {
    fun getMenuColor(
        context: Context,
        theme: Theme = Theme.Auto,
        requiresOverflow: Boolean = false
    ): Int = Color.BLACK
}
