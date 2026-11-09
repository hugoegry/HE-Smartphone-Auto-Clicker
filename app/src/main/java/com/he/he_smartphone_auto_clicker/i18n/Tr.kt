package com.he.he_smartphone_auto_clicker.i18n

import android.content.Context
import android.widget.TextView
import androidx.annotation.StringRes

fun Context.tr(@StringRes id: Int): String = I18n.t(this, id)
fun Context.tr(@StringRes id: Int, vararg args: Any?): String = I18n.t(this, id, *args)
fun TextView.setTr(@StringRes id: Int) {
    text = context.tr(id)
}

fun TextView.setTr(@StringRes id: Int, vararg args: Any?) {
    text = context.tr(id, *args)
}