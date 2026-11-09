package com.he.he_smartphone_auto_clicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.model.ClickerMode
import com.he.he_smartphone_auto_clicker.service.ClickerAccessibilityService

object OverlayStarter {
    fun startCreate(activity: Activity) {
        val store = ClickerStore.get(activity)
        store.createBlankSession()
        enableOverlay(activity, ClickerMode.MULTI)
    }

    fun startLoaded(activity: Activity) {
        enableOverlay(activity, ClickerMode.MULTI)
    }

    fun resumeIfPending(activity: Activity) {
        val store = ClickerStore.get(activity)
        if (!store.sessionOpen) return
        val pending = store.pendingMode
        if (pending == ClickerMode.NONE) return
        if (!canOverlay(activity) || !ClickerAccessibilityService.isEnabled(activity)) return
        store.openOverlaySession(pending)
        ClickerAccessibilityService.requestRefresh(activity)
        Toast.makeText(activity, R.string.overlay_ready, Toast.LENGTH_SHORT).show()
        activity.moveTaskToBack(true)
    }

    private fun enableOverlay(activity: Activity, mode: ClickerMode) {
        val store = ClickerStore.get(activity)
        if (!canOverlay(activity)) {
            store.markPendingOverlay(mode)
            Toast.makeText(activity, R.string.need_overlay, Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
            return
        }
        if (!ClickerAccessibilityService.isEnabled(activity)) {
            store.markPendingOverlay(mode)
            Toast.makeText(activity, R.string.need_accessibility, Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        store.openOverlaySession(mode)
        ClickerAccessibilityService.requestRefresh(activity)
        Toast.makeText(activity, R.string.overlay_ready, Toast.LENGTH_SHORT).show()

        activity.moveTaskToBack(true)
    }

    private fun canOverlay(activity: Activity): Boolean = Settings.canDrawOverlays(activity)
}
