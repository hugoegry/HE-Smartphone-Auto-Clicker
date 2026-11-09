package com.he.he_smartphone_auto_clicker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.key
import androidx.core.view.WindowCompat
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.i18n.I18n
import com.he.he_smartphone_auto_clicker.model.ClickerMode
import com.he.he_smartphone_auto_clicker.service.ClickerAccessibilityService
import com.he.he_smartphone_auto_clicker.ui.HomeScreen
import com.he.he_smartphone_auto_clicker.ui.theme.HeTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(I18n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        val store = ClickerStore.get(this)

        if (savedInstanceState == null) {
            store.resetToHub()
            ClickerAccessibilityService.requestRefresh(this)
        }
        paintHome()
    }

    private fun paintHome() {
        setContent {
            HeTheme {
                key(I18n.currentCode()) {
                    HomeScreen(
                        onSettings = {
                            val intent = Intent(this, TimingSettingsActivity::class.java)
                            intent.putExtra(TimingSettingsActivity.EXTRA_KIND, TimingSettingsActivity.KIND_MULTI)
                            startActivity(intent)
                        },
                        onCreate = { OverlayStarter.startCreate(this) },
                        onLoad = { startActivity(Intent(this, PresetsActivity::class.java)) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val store = ClickerStore.get(this)

        if (store.pendingMode != ClickerMode.NONE) {
            OverlayStarter.resumeIfPending(this)
            return
        }

        store.resetToHub()
        ClickerAccessibilityService.requestRefresh(this)
        paintHome()
    }
}
