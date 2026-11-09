package com.he.he_smartphone_auto_clicker

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.databinding.ActivityPresetsBinding
import com.he.he_smartphone_auto_clicker.databinding.ItemPresetBinding
import com.he.he_smartphone_auto_clicker.i18n.I18n
import com.he.he_smartphone_auto_clicker.model.ClickerMode
import com.he.he_smartphone_auto_clicker.model.ClickerPreset
import com.he.he_smartphone_auto_clicker.service.ClickerAccessibilityService

class PresetsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPresetsBinding
    private lateinit var store: ClickerStore
    private var leavingWithLoad = false
    private var fromOverlay = false
    private var pendingExportId = ClickerStore.NO_PRESET
    private var pendingExportPayload: String? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingExportPayload
        pendingExportPayload = null
        pendingExportId = ClickerStore.NO_PRESET
        if (uri == null || payload == null) return@registerForActivityResult
        writeExport(uri, payload)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        readImport(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(I18n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPresetsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ClickerStore.get(this)
        fromOverlay = intent.getBooleanExtra(EXTRA_FROM_OVERLAY, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.load_preset)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.btnImport.setOnClickListener { openImportPicker() }

        store.overlaySuppressed = true
        ClickerAccessibilityService.requestRefresh(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { leaveWithoutLoad() }
        })
        render()
    }

    private fun render() {
        val items = store.listPresets()
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.list.adapter = PresetAdapter(
            items = items,
            onLoad = { load(it) },
            onDuplicate = { duplicate(it) },
            onExport = { export(it) },
            onDelete = { confirmDelete(it) }
        )
    }

    private fun duplicate(preset: ClickerPreset) {
        val copy = store.duplicatePreset(preset.id)
        if (copy == null) {
            val full = store.listPresets().size >= 40
            Toast.makeText(this, if (full) R.string.preset_max else R.string.preset_duplicate_fail, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.preset_duplicated, copy.name), Toast.LENGTH_SHORT).show()
        render()
    }

    private fun load(preset: ClickerPreset) {
        if (!store.loadPreset(preset.id)) return
        leavingWithLoad = true
        store.overlaySuppressed = false
        Toast.makeText(this, R.string.preset_loaded, Toast.LENGTH_SHORT).show()
        OverlayStarter.startLoaded(this)
        finish()
    }

    private fun export(preset: ClickerPreset) {
        val payload = store.exportPresetPayload(preset.id)
        if (payload == null) {
            Toast.makeText(this, R.string.preset_export_fail, Toast.LENGTH_SHORT).show()
            return
        }
        pendingExportId = preset.id
        pendingExportPayload = payload
        exportLauncher.launch(store.safePresetFileName(preset.name))
    }

    private fun openImportPicker() {
        importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream", "*/*"))
    }

    private fun writeExport(uri: Uri, payload: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: run {
                Toast.makeText(this, R.string.preset_export_fail, Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, R.string.preset_exported, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.preset_export_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun readImport(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (raw.isNullOrBlank()) {
                Toast.makeText(this, R.string.preset_import_fail, Toast.LENGTH_SHORT).show()
                return
            }
            val imported = store.importPresetPayload(raw)
            if (imported == null) {
                val full = store.listPresets().size >= 40
                Toast.makeText(this, if (full) R.string.preset_max else R.string.preset_import_fail, Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, getString(R.string.preset_imported, imported.name), Toast.LENGTH_SHORT).show()
            render()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.preset_import_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun leaveWithoutLoad() {
        store.overlaySuppressed = false
        val resumeSession = fromOverlay || (store.sessionOpen && store.mode != ClickerMode.NONE)
        if (resumeSession && store.sessionOpen && store.mode != ClickerMode.NONE) {
            ClickerAccessibilityService.requestRefresh(this)
            moveTaskToBack(true)
        }
        finish()
    }

    private fun confirmDelete(preset: ClickerPreset) {
        val view = layoutInflater.inflate(R.layout.dialog_confirm, null, false)
        val message = view.findViewById<android.widget.TextView>(R.id.confirm_message)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btn_confirm_cancel)
        val btnOk = view.findViewById<android.widget.TextView>(R.id.btn_confirm_ok)
        message.text = getString(R.string.delete_preset_confirm, preset.name)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnOk.setOnClickListener {
            store.deletePreset(preset.id)
            dialog.dismiss()
            render()
        }
        dialog.show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            leaveWithoutLoad()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        if (!leavingWithLoad && store.overlaySuppressed) {
            store.overlaySuppressed = false
            if (store.sessionOpen && store.mode != ClickerMode.NONE) {
                ClickerAccessibilityService.requestRefresh(this)
            }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_OVERLAY = "from_overlay"
    }

    private class PresetAdapter(
        private val items: List<ClickerPreset>,
        private val onLoad: (ClickerPreset) -> Unit,
        private val onDuplicate: (ClickerPreset) -> Unit,
        private val onExport: (ClickerPreset) -> Unit,
        private val onDelete: (ClickerPreset) -> Unit
    ) : RecyclerView.Adapter<PresetAdapter.Holder>() {
        class Holder(val binding: ItemPresetBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemPresetBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val preset = items[position]
            val ctx = holder.binding.root.context
            holder.binding.title.text = preset.name
            holder.binding.subtitle.text = ctx.getString(R.string.preset_groups, preset.actions.size, preset.groups.size.coerceAtLeast(1))
            holder.binding.btnLoad.setOnClickListener { onLoad(preset) }
            holder.binding.btnDuplicate.setOnClickListener { onDuplicate(preset) }
            holder.binding.btnExport.setOnClickListener { onExport(preset) }
            holder.binding.btnDelete.setOnClickListener { onDelete(preset) }
        }
    }
}