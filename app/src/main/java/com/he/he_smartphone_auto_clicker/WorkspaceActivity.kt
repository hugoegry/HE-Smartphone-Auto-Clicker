package com.he.he_smartphone_auto_clicker

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.databinding.ActivityWorkspaceBinding
import com.he.he_smartphone_auto_clicker.i18n.I18n
import com.he.he_smartphone_auto_clicker.model.ClickerMode
import com.he.he_smartphone_auto_clicker.service.ClickerAccessibilityService

class WorkspaceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkspaceBinding
    private lateinit var store: ClickerStore

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(I18n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ClickerStore.get(this)
        if (!store.sessionOpen) {
            finish()
            return
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        refreshTitle()
        binding.rowSingleSettings.setOnClickListener { openTiming(TimingSettingsActivity.KIND_SINGLE) }
        binding.rowInstructions.setOnClickListener { startActivity(Intent(this, InstructionsActivity::class.java)) }
        binding.rowMultiSettings.setOnClickListener { openTiming(TimingSettingsActivity.KIND_MULTI) }
        binding.rowManage.setOnClickListener { startActivity(Intent(this, ManageActionsActivity::class.java)) }
        binding.rowCommon.setOnClickListener { openTiming(TimingSettingsActivity.KIND_COMMON) }
        binding.btnSingle.setOnClickListener { onToggle(ClickerMode.SINGLE) }
        binding.btnMulti.setOnClickListener { onToggle(ClickerMode.MULTI) }
        binding.banner.setOnClickListener { onBanner() }
    }

    override fun onResume() {
        super.onResume()
        if (!store.sessionOpen) {
            finish()
            return
        }
        val pending = store.pendingMode
        if (pending != ClickerMode.NONE && canOverlay() && ClickerAccessibilityService.isEnabled(this)) {
            store.mode = pending
            store.pendingMode = ClickerMode.NONE
            ClickerAccessibilityService.requestRefresh(this)
        }
        refreshTitle()
        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_workspace, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_update_preset)?.isEnabled = store.hasActivePreset()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_save_preset -> { onSaveAsPreset(); true }
            R.id.action_update_preset -> { onUpdatePreset(); true }
            R.id.action_close_session -> { onCloseSession(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshTitle() {

        supportActionBar?.title = if (store.hasActivePreset()) store.activePresetName else getString(R.string.preset_untitled)
    }

    private fun onCloseSession() {
        store.closeSession()
        ClickerAccessibilityService.requestRefresh(this)
        Toast.makeText(this, R.string.session_closed, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun onSaveAsPreset() {
        val input = EditText(this)
        input.setText(store.activePresetName.ifBlank { getString(R.string.preset_name_hint) })
        input.setSelection(input.text.length)
        input.setTextColor(ContextCompat.getColor(this, R.color.text_title))
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_sub))
        input.setPadding(48, 32, 48, 16)
        AlertDialog.Builder(this)
            .setTitle(R.string.preset_name_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val preset = store.saveAsPreset(input.text.toString())
                when {
                    preset == null && input.text.toString().trim().isEmpty() -> Toast.makeText(this, R.string.preset_need_name, Toast.LENGTH_SHORT).show()
                    preset == null -> Toast.makeText(this, R.string.preset_max, Toast.LENGTH_SHORT).show()
                    else -> {
                        Toast.makeText(this, R.string.preset_saved, Toast.LENGTH_SHORT).show()
                        refreshTitle()
                        invalidateOptionsMenu()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onUpdatePreset() {
        if (!store.hasActivePreset()) {
            Toast.makeText(this, R.string.preset_no_active, Toast.LENGTH_SHORT).show()
            return
        }
        if (store.updateActivePreset()) {
            Toast.makeText(this, R.string.preset_updated, Toast.LENGTH_SHORT).show()
            refreshTitle()
        } else {
            Toast.makeText(this, R.string.preset_no_active, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onToggle(mode: ClickerMode) {
        if (store.mode == mode) {
            store.mode = ClickerMode.NONE
            store.pendingMode = ClickerMode.NONE
            ClickerAccessibilityService.requestRefresh(this)
            render()
            return
        }
        if (!canOverlay()) {
            store.pendingMode = mode
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!ClickerAccessibilityService.isEnabled(this)) {
            store.pendingMode = mode
            Toast.makeText(this, R.string.need_accessibility, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        store.pendingMode = ClickerMode.NONE
        store.mode = mode
        ClickerAccessibilityService.requestRefresh(this)
        val msg = if (mode == ClickerMode.SINGLE) R.string.single_on else R.string.multi_on
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        render()
    }

    private fun onBanner() {
        when {
            !canOverlay() -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            !ClickerAccessibilityService.isEnabled(this) -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun render() {
        paintButton(binding.btnSingle, store.mode == ClickerMode.SINGLE)
        paintButton(binding.btnMulti, store.mode == ClickerMode.MULTI)
        val overlayOk = canOverlay()
        val a11yOk = ClickerAccessibilityService.isEnabled(this)
        if (overlayOk && a11yOk) {
            binding.banner.visibility = View.GONE
        } else {
            binding.banner.visibility = View.VISIBLE
            binding.banner.setText(when {
                !overlayOk && !a11yOk -> R.string.banner_both
                !overlayOk -> R.string.banner_overlay
                else -> R.string.banner_a11y
            })
        }
    }

    private fun paintButton(button: com.google.android.material.button.MaterialButton, active: Boolean) {
        val color = ContextCompat.getColor(this, if (active) R.color.gray_disable else R.color.gold)
        button.backgroundTintList = ColorStateList.valueOf(color)
        button.setTextColor(ContextCompat.getColor(this, if (active) R.color.white else R.color.black))
        button.setText(if (active) R.string.disable else R.string.enable)
    }

    private fun openTiming(kind: String) {
        val intent = Intent(this, TimingSettingsActivity::class.java)
        intent.putExtra(TimingSettingsActivity.EXTRA_KIND, kind)
        startActivity(intent)
    }

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(this)
}
