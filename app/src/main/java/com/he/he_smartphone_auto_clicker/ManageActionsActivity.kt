package com.he.he_smartphone_auto_clicker

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.databinding.ActivityManageBinding
import com.he.he_smartphone_auto_clicker.databinding.DialogActionBinding
import com.he.he_smartphone_auto_clicker.databinding.ItemActionBinding
import com.he.he_smartphone_auto_clicker.databinding.ItemGroupHeaderBinding
import com.he.he_smartphone_auto_clicker.i18n.I18n
import com.he.he_smartphone_auto_clicker.model.ActionGroup
import com.he.he_smartphone_auto_clicker.model.ActionKind
import com.he.he_smartphone_auto_clicker.model.ClickerAction
import com.he.he_smartphone_auto_clicker.model.ClickerMode
import com.he.he_smartphone_auto_clicker.model.GroupRunMode
import com.he.he_smartphone_auto_clicker.service.ClickerAccessibilityService

class ManageActionsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityManageBinding
    private lateinit var store: ClickerStore

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(I18n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ClickerStore.get(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.manage_actions)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.btnAddGroup.setOnClickListener { askGroupName(create = true) }
        binding.btnAddClick.setOnClickListener {
            if (store.addClick(store.selectedGroupId) == null) Toast.makeText(this, R.string.max_actions, Toast.LENGTH_SHORT).show()
            else if (store.mode != ClickerMode.MULTI) Toast.makeText(this, R.string.saved_multi, Toast.LENGTH_SHORT).show()
            refreshOverlay()
            render()
        }
        binding.btnAddSwipe.setOnClickListener {
            if (store.addSwipe(store.selectedGroupId) == null) Toast.makeText(this, R.string.max_actions, Toast.LENGTH_SHORT).show()
            else if (store.mode != ClickerMode.MULTI) Toast.makeText(this, R.string.saved_multi, Toast.LENGTH_SHORT).show()
            refreshOverlay()
            render()
        }
        render()
    }

    private fun render() {
        val groups = store.groupList()
        val actions = store.actionList()
        val rows = mutableListOf<Row>()
        var globalIndex = 0
        for ((gi, group) in groups.withIndex()) {
            val chunk = actions.filter { it.groupId == group.id }
            rows.add(Row.Header(group, chunk.size, gi, groups.size))
            chunk.forEachIndexed { local, action ->
                globalIndex++
                rows.add(Row.Action(action, globalIndex, local, chunk.size))
            }
        }
        binding.emptyText.visibility = if (actions.isEmpty()) View.VISIBLE else View.GONE
        binding.list.adapter = GroupsAdapter(
            rows = rows,
            selectedGroupId = store.selectedGroupId,
            onSelectGroup = {
                store.selectedGroupId = it.id
                render()
            },
            onCycleMode = {
                store.cycleGroupMode(it.id)
                render()
            },
            onRename = { askGroupName(create = false, group = it) },
            onMoveGroup = { g, dir ->
                store.moveGroup(g.id, dir)
                render()
            },
            onDeleteGroup = { confirmDeleteGroup(it) },
            onRunGroup = { runGroup(it) },
            onUp = { move(it, -1) },
            onDown = { move(it, 1) },
            onEdit = { edit(it) },
            onDelete = { remove(it) },
            onMoveToGroup = { pickGroupForAction(it) }
        )
    }

    private fun askGroupName(create: Boolean, group: ActionGroup? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_group, null, false)
        val title = view.findViewById<android.widget.TextView>(R.id.group_dialog_title)
        val input = view.findViewById<android.widget.EditText>(R.id.input_group_name)
        val btnSave = view.findViewById<android.widget.TextView>(R.id.btn_group_save)
        val btnClose = view.findViewById<android.widget.ImageButton>(R.id.btn_group_dialog_close)
        title.setText(if (create) R.string.new_group_title else R.string.rename_group_title)
        input.setText(group?.name.orEmpty())
        if (!create) input.setSelection(input.text.length)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnClose.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val name = input.text.toString()
            if (create) {
                val made = store.addGroup(name)
                when {
                    made == null && name.trim().isEmpty() -> Toast.makeText(this, R.string.group_need_name, Toast.LENGTH_SHORT).show()
                    made == null -> Toast.makeText(this, R.string.group_max, Toast.LENGTH_SHORT).show()
                    else -> { dialog.dismiss(); render() }
                }
            } else if (group != null) {
                if (!store.renameGroup(group.id, name)) Toast.makeText(this, R.string.group_need_name, Toast.LENGTH_SHORT).show()
                else { dialog.dismiss(); render() }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteGroup(group: ActionGroup) {
        if (group.main) return
        val view = layoutInflater.inflate(R.layout.dialog_confirm, null, false)
        val message = view.findViewById<android.widget.TextView>(R.id.confirm_message)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btn_confirm_cancel)
        val btnOk = view.findViewById<android.widget.TextView>(R.id.btn_confirm_ok)
        message.text = getString(R.string.delete_group_confirm, group.name)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnOk.setOnClickListener {
            store.deleteGroup(group.id)
            dialog.dismiss()
            Toast.makeText(this, R.string.group_deleted, Toast.LENGTH_SHORT).show()
            refreshOverlay()
            render()
        }
        dialog.show()
    }

    private fun runGroup(group: ActionGroup) {
        val svc = ClickerAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, R.string.need_accessibility, Toast.LENGTH_SHORT).show()
            return
        }
        svc.runGroupOnce(group.id)
    }

    private fun pickGroupForAction(action: ClickerAction) {
        val groups = store.groupList()
        if (groups.size <= 1) return
        val names = groups.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to_group)
            .setItems(names) { _, which ->
                store.moveActionToGroup(action.id, groups[which].id)
                store.selectedGroupId = groups[which].id
                refreshOverlay()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun move(action: ClickerAction, dir: Int) {
        store.move(action.id, dir)
        refreshOverlay()
        render()
    }

    private fun remove(action: ClickerAction) {
        store.remove(action.id)
        refreshOverlay()
        render()
    }

    private fun edit(action: ClickerAction) {
        val items = store.actionList()
        val index = items.indexOfFirst { it.id == action.id }
        val n = if (index < 0) 1 else index + 1
        val moveLike = action.kind == ActionKind.SWIPE || action.kind == ActionKind.PINCH || action.kind == ActionKind.JOYSTICK
        val form = DialogActionBinding.inflate(layoutInflater)
        form.dialogTitle.text = when (action.kind) {
            ActionKind.SWIPE -> getString(R.string.swipe_title, n)
            ActionKind.DOUBLE_CLICK -> getString(R.string.double_title, n)
            ActionKind.PINCH -> getString(R.string.pinch_title, n)
            ActionKind.MULTI_TOUCH -> getString(R.string.multi_touch_title, n)
            ActionKind.JOYSTICK -> getString(R.string.joystick_title, n)
            else -> getString(R.string.target_title, n)
        }
        form.labelDuration.setText(when (action.kind) {
            ActionKind.SWIPE -> R.string.swipe_duration
            ActionKind.PINCH -> R.string.pinch_duration
            ActionKind.JOYSTICK -> R.string.joy_travel_duration
            else -> R.string.press_duration
        })
        form.inputDuration.setMs(action.durationMs)
        form.inputDelay.setMs(action.delayMs)
        form.btnDialogDelete.visibility = View.GONE
        form.btnDialogClose.visibility = View.GONE
        form.blockRow.visibility = View.GONE
        form.optionRow.visibility = View.GONE
        form.joyRow.visibility = View.GONE
        form.checkDoubleClick.visibility = View.GONE
        form.holdRow.visibility = if (action.kind == ActionKind.SWIPE || action.kind == ActionKind.JOYSTICK) View.VISIBLE else View.GONE
        form.inputHold.setMs(action.holdMs)
        var pauseWhileAction = action.pauseAllWhileAction
        var pauseWhileDelay = action.pauseAllWhileDelay
        fun paintPauseBtns() {
            form.btnPauseAction.setText(if (pauseWhileAction) R.string.pause_all_action_on else R.string.pause_all_action_off)
            form.btnPauseDelay.setText(if (pauseWhileDelay) R.string.pause_all_delay_on else R.string.pause_all_delay_off)
        }
        paintPauseBtns()
        form.btnPauseAction.setOnClickListener { pauseWhileAction = !pauseWhileAction; paintPauseBtns() }
        form.btnPauseDelay.setOnClickListener { pauseWhileDelay = !pauseWhileDelay; paintPauseBtns() }
        AlertDialog.Builder(this).setView(form.root).setPositiveButton(R.string.save) { _, _ ->
            val durMin = if (moveLike) 50L else 1L
            val dur = form.inputDuration.getMs(action.durationMs, durMin, 10000L)
            val wait = form.inputDelay.getMs(action.delayMs, 0L, 600000L)
            val hold = if (action.kind == ActionKind.SWIPE || action.kind == ActionKind.JOYSTICK) form.inputHold.getMs(action.holdMs, 0L, 10000L) else null
            store.updateTiming(action.id, dur, wait, hold, pauseWhileAction, pauseWhileDelay)
            refreshOverlay()
            render()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun refreshOverlay() { ClickerAccessibilityService.requestRefresh(this) }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private sealed class Row {
        data class Header(val group: ActionGroup, val count: Int, val index: Int, val total: Int) : Row()
        data class Action(val action: ClickerAction, val number: Int, val localIndex: Int, val localTotal: Int) : Row()
    }

    private class GroupsAdapter(
        private val rows: List<Row>,
        private val selectedGroupId: Long,
        private val onSelectGroup: (ActionGroup) -> Unit,
        private val onCycleMode: (ActionGroup) -> Unit,
        private val onRename: (ActionGroup) -> Unit,
        private val onMoveGroup: (ActionGroup, Int) -> Unit,
        private val onDeleteGroup: (ActionGroup) -> Unit,
        private val onRunGroup: (ActionGroup) -> Unit,
        private val onUp: (ClickerAction) -> Unit,
        private val onDown: (ClickerAction) -> Unit,
        private val onEdit: (ClickerAction) -> Unit,
        private val onDelete: (ClickerAction) -> Unit,
        private val onMoveToGroup: (ClickerAction) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        companion object {
            private const val TYPE_HEADER = 1
            private const val TYPE_ACTION = 2
        }

        class HeaderHolder(val binding: ItemGroupHeaderBinding) : RecyclerView.ViewHolder(binding.root)
        class ActionHolder(val binding: ItemActionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Action -> TYPE_ACTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(ItemGroupHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            } else {
                ActionHolder(ItemActionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> bindHeader(holder as HeaderHolder, row)
                is Row.Action -> bindAction(holder as ActionHolder, row)
            }
        }

        private fun bindHeader(holder: HeaderHolder, row: Row.Header) {
            val ctx = holder.binding.root.context
            val group = row.group
            val selected = group.id == selectedGroupId
            holder.binding.groupName.text = group.name
            val modeLabel = modeLabel(ctx, group.mode)
            holder.binding.groupMeta.text = if (group.main) ctx.getString(R.string.group_meta_main, row.count, modeLabel) else ctx.getString(R.string.group_meta, row.count, modeLabel)
            holder.binding.btnGroupMode.text = modeLabel
            holder.binding.btnGroupMode.setBackgroundResource(if (group.mode == GroupRunMode.ON_DEMAND) R.drawable.bg_mode_chip_demand else R.drawable.bg_mode_chip)
            holder.binding.root.setBackgroundResource(if (selected) R.drawable.bg_group_card_selected else R.drawable.bg_group_card)
            holder.binding.root.setOnClickListener { onSelectGroup(group) }
            holder.binding.btnGroupMode.setOnClickListener { onCycleMode(group) }
            holder.binding.btnGroupRename.setOnClickListener { onRename(group) }
            holder.binding.btnGroupUp.isEnabled = row.index > 0
            holder.binding.btnGroupDown.isEnabled = row.index < row.total - 1
            holder.binding.btnGroupUp.alpha = if (row.index > 0) 1f else 0.35f
            holder.binding.btnGroupDown.alpha = if (row.index < row.total - 1) 1f else 0.35f
            holder.binding.btnGroupUp.setOnClickListener { onMoveGroup(group, -1) }
            holder.binding.btnGroupDown.setOnClickListener { onMoveGroup(group, 1) }
            holder.binding.btnGroupDelete.visibility = if (group.main) View.GONE else View.VISIBLE
            holder.binding.btnGroupDelete.setOnClickListener { onDeleteGroup(group) }
            holder.binding.btnGroupRun.visibility = if (group.mode == GroupRunMode.ON_DEMAND) View.VISIBLE else View.GONE
            holder.binding.btnGroupRun.setOnClickListener { onRunGroup(group) }
        }

        private fun bindAction(holder: ActionHolder, row: Row.Action) {
            val ctx = holder.binding.root.context
            val action = row.action
            holder.binding.title.text = when (action.kind) {
                ActionKind.SWIPE -> ctx.getString(R.string.swipe_row, row.number)
                ActionKind.DOUBLE_CLICK -> ctx.getString(R.string.double_row, row.number)
                ActionKind.PINCH -> ctx.getString(R.string.pinch_row, row.number)
                ActionKind.MULTI_TOUCH -> ctx.getString(R.string.multi_row, row.number)
                ActionKind.JOYSTICK -> ctx.getString(R.string.joystick_row, row.number)
                else -> ctx.getString(R.string.click_row, row.number)
            }
            val detailMove = action.kind == ActionKind.SWIPE || action.kind == ActionKind.PINCH || action.kind == ActionKind.JOYSTICK
            holder.binding.subtitle.text = if (detailMove) ctx.getString(R.string.swipe_detail, action.durationMs.toInt(), action.delayMs.toInt()) else ctx.getString(R.string.click_detail, action.durationMs.toInt(), action.delayMs.toInt())
            holder.binding.btnUp.isEnabled = row.localIndex > 0
            holder.binding.btnDown.isEnabled = row.localIndex < row.localTotal - 1
            holder.binding.btnUp.setOnClickListener { onUp(action) }
            holder.binding.btnDown.setOnClickListener { onDown(action) }
            holder.binding.btnEdit.setOnClickListener { onEdit(action) }
            holder.binding.btnDelete.setOnClickListener { onDelete(action) }
            holder.binding.root.setOnLongClickListener { onMoveToGroup(action); true }
        }

        private fun modeLabel(ctx: android.content.Context, mode: GroupRunMode): String = when (mode) {
            GroupRunMode.SEQUENCE -> ctx.getString(R.string.group_mode_sequence)
            GroupRunMode.RANDOM -> ctx.getString(R.string.group_mode_random)
            GroupRunMode.ON_DEMAND -> ctx.getString(R.string.group_mode_ondemand)
        }
    }
}