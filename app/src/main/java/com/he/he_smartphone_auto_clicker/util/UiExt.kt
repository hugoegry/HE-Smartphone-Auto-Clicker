package com.he.he_smartphone_auto_clicker.util

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

fun parseMs(raw: String, fallback: Long, min: Long, max: Long): Long {
    val n = raw.trim().toLongOrNull() ?: fallback
    return n.coerceIn(min, max)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun screenBounds(ctx: Context): Rect {
    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wm.currentWindowMetrics.bounds
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
}
