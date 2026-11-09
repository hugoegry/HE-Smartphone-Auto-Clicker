package com.he.he_smartphone_auto_clicker.data

import android.content.Context
import com.he.he_smartphone_auto_clicker.model.ActionGroup
import com.he.he_smartphone_auto_clicker.model.ActionKind
import com.he.he_smartphone_auto_clicker.model.ClickerAction
import com.he.he_smartphone_auto_clicker.model.ClickerMode
import com.he.he_smartphone_auto_clicker.model.ClickerPreset
import com.he.he_smartphone_auto_clicker.model.Detector
import com.he.he_smartphone_auto_clicker.model.DetectorPause
import com.he.he_smartphone_auto_clicker.model.GroupRunMode
import com.he.he_smartphone_auto_clicker.util.JoyMath
import org.json.JSONArray
import org.json.JSONObject

class ClickerStore private constructor(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences(APP_CODE, Context.MODE_PRIVATE)
    private val lock = Any()
    private var seq = System.currentTimeMillis()

    var mode: ClickerMode
        get() = readMode(KEY_MODE)
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    var pendingMode: ClickerMode
        get() = readMode(KEY_PENDING)
        set(value) { prefs.edit().putString(KEY_PENDING, value.name).apply() }

    var sessionOpen: Boolean
        get() = prefs.getBoolean(KEY_SESSION_OPEN, false)
        set(value) { prefs.edit().putBoolean(KEY_SESSION_OPEN, value).apply() }

    var activePresetId: Long
        get() = prefs.getLong(KEY_ACTIVE_PRESET, NO_PRESET)
        set(value) { prefs.edit().putLong(KEY_ACTIVE_PRESET, value).apply() }

    var activePresetName: String
        get() = prefs.getString(KEY_ACTIVE_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ACTIVE_NAME, value).apply() }

    var singlePressMs: Long
        get() = prefs.getLong(KEY_SINGLE_PRESS, 100L)
        set(value) { prefs.edit().putLong(KEY_SINGLE_PRESS, value).apply() }

    var singleDelayMs: Long
        get() = prefs.getLong(KEY_SINGLE_DELAY, 100L)
        set(value) { prefs.edit().putLong(KEY_SINGLE_DELAY, value).apply() }

    var singleX: Float
        get() = prefs.getFloat(KEY_SINGLE_X, 0.5f)
        set(value) { prefs.edit().putFloat(KEY_SINGLE_X, value).apply() }

    var singleY: Float
        get() = prefs.getFloat(KEY_SINGLE_Y, 0.42f)
        set(value) { prefs.edit().putFloat(KEY_SINGLE_Y, value).apply() }

    var defaultPressMs: Long
        get() = prefs.getLong(KEY_DEF_PRESS, 100L)
        set(value) { prefs.edit().putLong(KEY_DEF_PRESS, value).apply() }

    var defaultDelayMs: Long
        get() = prefs.getLong(KEY_DEF_DELAY, 200L)
        set(value) { prefs.edit().putLong(KEY_DEF_DELAY, value).apply() }

    var defaultSwipeMs: Long
        get() = prefs.getLong(KEY_DEF_SWIPE, 400L)
        set(value) { prefs.edit().putLong(KEY_DEF_SWIPE, value).apply() }

    var repeatCount: Int
        get() = prefs.getInt(KEY_REPEAT, 0)
        set(value) { prefs.edit().putInt(KEY_REPEAT, value).apply() }

    var startDelaySec: Int
        get() = prefs.getInt(KEY_START_DELAY, 0)
        set(value) { prefs.edit().putInt(KEY_START_DELAY, value.coerceIn(0, 3600)).apply() }

    var menuX: Float
        get() = prefs.getFloat(KEY_MENU_X, -1f)
        set(value) { prefs.edit().putFloat(KEY_MENU_X, value).apply() }

    var menuY: Float
        get() = prefs.getFloat(KEY_MENU_Y, -1f)
        set(value) { prefs.edit().putFloat(KEY_MENU_Y, value).apply() }

    var selectedGroupId: Long
        get() = prefs.getLong(KEY_SELECTED_GROUP, 0L)
        set(value) { prefs.edit().putLong(KEY_SELECTED_GROUP, value).apply() }

    var openCollapsed: Boolean
        get() = prefs.getBoolean(KEY_OPEN_COLLAPSED, false)
        set(value) { prefs.edit().putBoolean(KEY_OPEN_COLLAPSED, value).apply() }

    var overlaySuppressed: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_SUPPRESSED, false)
        set(value) { prefs.edit().putBoolean(KEY_OVERLAY_SUPPRESSED, value).apply() }

    var visionAiKey: String
        get() = prefs.getString(KEY_VISION_AI, "").orEmpty().trim()
        set(value) { prefs.edit().putString(KEY_VISION_AI, value.trim()).apply() }

    fun consumeOpenCollapsed(): Boolean {
        if (!openCollapsed) return false
        openCollapsed = false
        return true
    }

    fun groupList(): List<ActionGroup> = synchronized(lock) { ensureGroupsLocked() }

    fun actionList(): List<ClickerAction> = synchronized(lock) {
        ensureGroupsLocked()
        loadActions()
    }

    fun actionsInGroup(groupId: Long): List<ClickerAction> = actionList().filter { it.groupId == groupId }

    fun detectorList(): List<Detector> = synchronized(lock) { loadDetectors() }

    fun usableDetectors(): List<Detector> = detectorList().filter { it.enabled && (it.query.isNotBlank() || (it.tplW > 0 && it.tplH > 0 && DetectorFiles.exists(appCtx, it.id))) }

    fun hasUsableDetectors(): Boolean = usableDetectors().isNotEmpty()

    fun newDetectorId(): Long = synchronized(lock) { nextId() }

    fun saveDetector(d: Detector): Boolean = synchronized(lock) {
        val list = loadDetectors()
        val i = list.indexOfFirst { it.id == d.id }
        if (i < 0) {
            if (list.size >= MAX_DETECTORS) return false
            list.add(d)
        } else {
            list[i] = d
        }
        saveDetectors(list)
        true
    }

    fun setDetectorEnabled(id: Long, enabled: Boolean) = synchronized(lock) {
        val list = loadDetectors()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(enabled = enabled)
        saveDetectors(list)
    }

    fun removeDetector(id: Long) = synchronized(lock) {
        saveDetectors(loadDetectors().filter { it.id != id })
        gcDetectorFilesLocked()
    }

    fun detectorFilesExist(id: Long): Boolean = DetectorFiles.exists(appCtx, id)

    fun currentActions(): List<ClickerAction> {
        return when (mode) {
            ClickerMode.SINGLE -> listOf(ClickerAction(id = SINGLE_ID, kind = ActionKind.CLICK, x = singleX, y = singleY, x2 = singleX, y2 = singleY, durationMs = singlePressMs, delayMs = singleDelayMs, groupId = mainGroupId()))
            ClickerMode.MULTI -> buildAutoPlayPlan()
            ClickerMode.NONE -> emptyList()
        }
    }

    fun buildAutoPlayPlan(): List<ClickerAction> = synchronized(lock) {
        ensureGroupsLocked()
        val groups = loadGroups()
        val actions = loadActions()
        val out = mutableListOf<ClickerAction>()
        for (group in groups) {
            if (group.mode == GroupRunMode.ON_DEMAND) continue
            val chunk = actions.filter { it.groupId == group.id }
            if (chunk.isEmpty()) continue
            when (group.mode) {
                GroupRunMode.SEQUENCE -> out.addAll(chunk)
                GroupRunMode.RANDOM -> out.addAll(chunk.shuffled())
                GroupRunMode.ON_DEMAND -> {}
            }
        }
        out
    }

    fun buildGroupPlan(groupId: Long): List<ClickerAction> = synchronized(lock) {
        ensureGroupsLocked()
        val group = loadGroups().firstOrNull { it.id == groupId } ?: return emptyList()
        val chunk = loadActions().filter { it.groupId == groupId }
        if (chunk.isEmpty()) return emptyList()
        when (group.mode) {
            GroupRunMode.SEQUENCE, GroupRunMode.ON_DEMAND -> chunk
            GroupRunMode.RANDOM -> chunk.shuffled()
        }
    }

    fun onDemandGroups(): List<ActionGroup> = groupList().filter { it.mode == GroupRunMode.ON_DEMAND }

    fun hasActivePreset(): Boolean = activePresetId != NO_PRESET

    fun createBlankSession() {
        mode = ClickerMode.NONE
        pendingMode = ClickerMode.NONE
        singlePressMs = defaultPressMs
        singleDelayMs = defaultDelayMs
        singleX = 0.5f
        singleY = 0.42f
        repeatCount = 0
        synchronized(lock) {
            val main = ActionGroup(id = nextId(), name = "Main", mode = GroupRunMode.SEQUENCE, main = true)
            saveGroups(listOf(main))
            saveActions(emptyList())
            saveDetectors(emptyList())
            gcDetectorFilesLocked()
            selectedGroupId = main.id
        }
        activePresetId = NO_PRESET
        activePresetName = ""
        sessionOpen = true
    }

    fun closeSession() {
        mode = ClickerMode.NONE
        pendingMode = ClickerMode.NONE
        sessionOpen = false
        activePresetId = NO_PRESET
        activePresetName = ""
    }

    fun resetToHub() {
        prefs.edit().putString(KEY_MODE, ClickerMode.NONE.name).putString(KEY_PENDING, ClickerMode.NONE.name).putBoolean(KEY_SESSION_OPEN, false).commit()
    }

    fun openOverlaySession(mode: ClickerMode) {
        prefs.edit().putString(KEY_MODE, mode.name).putString(KEY_PENDING, ClickerMode.NONE.name).putBoolean(KEY_SESSION_OPEN, true).commit()
    }

    fun markPendingOverlay(mode: ClickerMode) {
        prefs.edit().putString(KEY_MODE, ClickerMode.NONE.name).putString(KEY_PENDING, mode.name).putBoolean(KEY_SESSION_OPEN, true).commit()
    }

    fun listPresets(): List<ClickerPreset> = synchronized(lock) {
        loadPresets().sortedBy { it.name.lowercase() }
    }

    fun loadPreset(id: Long): Boolean = synchronized(lock) {
        val preset = loadPresets().firstOrNull { it.id == id } ?: return false
        applyPreset(preset)
        activePresetId = preset.id
        activePresetName = preset.name
        sessionOpen = true
        mode = ClickerMode.NONE
        pendingMode = ClickerMode.NONE
        openCollapsed = true
        true
    }

    fun saveAsPreset(name: String): ClickerPreset? = synchronized(lock) {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val list = loadPresets()
        if (list.size >= MAX_PRESETS) return null
        val preset = snapshot(nextId(), clean)
        list.add(preset)
        savePresets(list)
        activePresetId = preset.id
        activePresetName = preset.name
        sessionOpen = true
        preset
    }

    fun updateActivePreset(name: String? = null): Boolean = synchronized(lock) {
        val id = activePresetId
        if (id == NO_PRESET) return false
        val list = loadPresets()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        val clean = name?.trim()
        if (clean != null && clean.isEmpty()) return false
        val finalName = clean ?: list[i].name
        list[i] = snapshot(id, finalName)
        savePresets(list)
        activePresetName = finalName
        true
    }

    fun deletePreset(id: Long) = synchronized(lock) {
        savePresets(loadPresets().filter { it.id != id })
        if (activePresetId == id) {
            activePresetId = NO_PRESET
            activePresetName = ""
        }
        gcDetectorFilesLocked()
    }

    fun duplicatePreset(id: Long): ClickerPreset? = synchronized(lock) {
        val list = loadPresets()
        if (list.size >= MAX_PRESETS) return null
        val src = list.firstOrNull { it.id == id } ?: return null
        val remapped = remapPresetIds(src, null)
        val copy = remapped.copy(name = uniquePresetName("${src.name} copy", list.map { it.name }))
        list.add(copy)
        savePresets(list)
        copy
    }

    fun exportPresetPayload(id: Long): String? = synchronized(lock) {
        val preset = loadPresets().firstOrNull { it.id == id } ?: return null
        JSONObject().apply {
            put("format", PRESET_FILE_FORMAT)
            put("version", PRESET_FILE_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("preset", presetToJson(preset, embedTemplates = true))
        }.toString(2)
    }

    fun importPresetPayload(raw: String): ClickerPreset? = synchronized(lock) {
        val list = loadPresets()
        if (list.size >= MAX_PRESETS) return null
        val root = try { JSONObject(raw.trim().removePrefix("\uFEFF")) } catch (_: Exception) { return null }
        val presetObj = when {
            root.optString("format") == PRESET_FILE_FORMAT && root.has("preset") -> root.getJSONObject("preset")
            root.has("preset") && root.get("preset") is JSONObject -> root.getJSONObject("preset")
            root.has("name") && (root.has("actions") || root.has("groups")) -> root
            else -> return null
        }
        val templates = HashMap<Long, ByteArray>()
        val parsed = parsePresetObject(presetObj, templates) ?: return null
        val remapped = remapPresetIds(parsed, templates)
        val unique = remapped.copy(name = uniquePresetName(remapped.name, list.map { it.name }))
        list.add(unique)
        savePresets(list)
        unique
    }

    fun safePresetFileName(name: String): String {
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").replace(Regex("\\s+"), "_").trim('_').take(48)
        val base = if (clean.isEmpty()) "preset" else clean
        return "$base.hepreset.json"
    }

    fun renameActivePreset(name: String): Boolean = synchronized(lock) {
        val clean = name.trim()
        if (clean.isEmpty() || activePresetId == NO_PRESET) return false
        val list = loadPresets()
        val i = list.indexOfFirst { it.id == activePresetId }
        if (i < 0) return false
        list[i] = list[i].copy(name = clean)
        savePresets(list)
        activePresetName = clean
        true
    }

    fun addGroup(name: String): ActionGroup? = synchronized(lock) {
        ensureGroupsLocked()
        val groups = loadGroups()
        if (groups.size >= MAX_GROUPS) return null
        val clean = name.trim().ifEmpty { "Group ${groups.size + 1}" }
        val group = ActionGroup(id = nextId(), name = clean, mode = GroupRunMode.SEQUENCE, main = false)
        groups.add(group)
        saveGroups(groups)
        selectedGroupId = group.id
        group
    }

    fun renameGroup(id: Long, name: String): Boolean = synchronized(lock) {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val groups = loadGroups()
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return false
        groups[i] = groups[i].copy(name = clean)
        saveGroups(groups)
        true
    }

    fun setGroupMode(id: Long, mode: GroupRunMode): Boolean = synchronized(lock) {
        val groups = loadGroups()
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return false
        groups[i] = groups[i].copy(mode = mode)
        saveGroups(groups)
        true
    }

    fun cycleGroupMode(id: Long): GroupRunMode? = synchronized(lock) {
        val groups = loadGroups()
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return null
        val next = when (groups[i].mode) {
            GroupRunMode.SEQUENCE -> GroupRunMode.RANDOM
            GroupRunMode.RANDOM -> GroupRunMode.ON_DEMAND
            GroupRunMode.ON_DEMAND -> GroupRunMode.SEQUENCE
        }
        groups[i] = groups[i].copy(mode = next)
        saveGroups(groups)
        next
    }

    fun deleteGroup(id: Long): Boolean = synchronized(lock) {
        ensureGroupsLocked()
        val groups = loadGroups()
        val target = groups.firstOrNull { it.id == id } ?: return false
        if (target.main) return false
        val mainId = groups.first { it.main }.id
        val actions = loadActions().map { if (it.groupId == id) it.copy(groupId = mainId) else it }
        saveActions(actions)

        saveDetectors(loadDetectors().map { if (it.groupId == id) it.copy(groupId = 0L) else it })
        saveGroups(groups.filter { it.id != id })
        if (selectedGroupId == id) selectedGroupId = mainId
        true
    }

    fun moveGroup(id: Long, direction: Int): Boolean = synchronized(lock) {
        val groups = loadGroups()
        val i = groups.indexOfFirst { it.id == id }
        val j = i + direction
        if (i < 0 || j !in groups.indices) return false
        val item = groups.removeAt(i)
        groups.add(j, item)
        saveGroups(groups)
        true
    }

    fun setGroupVisible(id: Long, visible: Boolean): Boolean = synchronized(lock) {
        val groups = loadGroups()
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return false
        groups[i] = groups[i].copy(visible = visible)
        saveGroups(groups)
        true
    }

    fun setGroupDelaySec(id: Long, delaySec: Int): Boolean = synchronized(lock) {
        val groups = loadGroups()
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return false
        groups[i] = groups[i].copy(delaySec = delaySec.coerceIn(0, 3600))
        saveGroups(groups)
        true
    }

    fun toggleGroupVisible(id: Long): Boolean? = synchronized(lock) {
        val groups = loadGroups()
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return null
        val next = !groups[i].visible
        groups[i] = groups[i].copy(visible = next)
        saveGroups(groups)
        next
    }

    fun addClick(groupId: Long? = null): ClickerAction? = addAction(ActionKind.CLICK, groupId)
    fun addSwipe(groupId: Long? = null): ClickerAction? = addAction(ActionKind.SWIPE, groupId)
    fun addAction(kind: ActionKind, groupId: Long? = null): ClickerAction? = synchronized(lock) {
        ensureGroupsLocked()
        val list = loadActions()
        if (list.size >= MAX_ACTIONS) return null
        val gid = resolveGroupId(groupId)
        val i = list.size
        val action = when (kind) {
            ActionKind.CLICK, ActionKind.DOUBLE_CLICK -> {
                val x = (0.30f + (i % 4) * 0.12f).coerceAtMost(0.82f)
                val y = (0.30f + (i % 5) * 0.08f).coerceAtMost(0.78f)
                ClickerAction(id = nextId(), kind = kind, x = x, y = y, x2 = 0f, y2 = 0f, durationMs = defaultPressMs, delayMs = defaultDelayMs, groupId = gid)
            }
            ActionKind.SWIPE -> {
                val y = (0.48f + (i % 4) * 0.07f).coerceAtMost(0.8f)
                ClickerAction(id = nextId(), kind = kind, x = 0.22f, y = y, x2 = 0.78f, y2 = y, durationMs = defaultSwipeMs, delayMs = defaultDelayMs, groupId = gid)
            }
            ActionKind.PINCH -> {
                val cx = 0.50f
                val cy = (0.42f + (i % 3) * 0.08f).coerceAtMost(0.72f)
                ClickerAction(id = nextId(), kind = kind, x = cx - 0.16f, y = cy - 0.10f, x2 = cx + 0.16f, y2 = cy + 0.10f, durationMs = defaultSwipeMs, delayMs = defaultDelayMs, groupId = gid, option = 0)
            }
            ActionKind.MULTI_TOUCH -> {
                val y = (0.40f + (i % 4) * 0.08f).coerceAtMost(0.76f)
                ClickerAction(id = nextId(), kind = kind, x = 0.34f, y = y, x2 = 0.66f, y2 = y, durationMs = defaultPressMs, delayMs = defaultDelayMs, groupId = gid)
            }
            ActionKind.JOYSTICK -> {
                val cx = 0.33f
                val cy = 0.80f
                val radius = 0.10f
                val ang = 0f
                val (tx, ty) = JoyMath.tipFromCenter(cx, cy, radius, ang, 1080f, 2340f)
                ClickerAction(id = nextId(), kind = kind, x = cx, y = cy, x2 = tx, y2 = ty, durationMs = 120L, delayMs = defaultDelayMs, groupId = gid, option = 0, holdMs = 800L, radius = radius, angleFrom = ang, angleTo = 360f)
            }
        }
        list.add(action)
        saveActions(list)
        selectedGroupId = gid
        action
    }

    fun updateTiming(id: Long, durationMs: Long, delayMs: Long, holdMs: Long? = null, pauseAllWhileAction: Boolean? = null, pauseAllWhileDelay: Boolean? = null) = synchronized(lock) {
        val list = loadActions()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = list[i]
        list[i] = cur.copy(durationMs = durationMs, delayMs = delayMs, holdMs = holdMs?.coerceIn(0L, 10000L) ?: cur.holdMs, pauseAllWhileAction = pauseAllWhileAction ?: cur.pauseAllWhileAction, pauseAllWhileDelay = pauseAllWhileDelay ?: cur.pauseAllWhileDelay)
        saveActions(list)
    }

    fun updateOption(id: Long, option: Int) = synchronized(lock) {
        val list = loadActions()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = list[i]
        val max = if (cur.kind == ActionKind.JOYSTICK) 2 else 1
        list[i] = cur.copy(option = option.coerceIn(0, max))
        saveActions(list)
    }

    fun updateJoy(id: Long, radius: Float? = null, angleFrom: Float? = null, angleTo: Float? = null, option: Int? = null) = synchronized(lock) {
        val list = loadActions()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = list[i]
        if (cur.kind != ActionKind.JOYSTICK) return
        val r = (radius ?: cur.radius).coerceIn(0.02f, 0.45f)
        val a0 = JoyMath.normDeg(angleFrom ?: cur.angleFrom)
        val a1 = JoyMath.normDeg(angleTo ?: cur.angleTo)
        val mode = (option ?: cur.option).coerceIn(0, 2)
        val (tx, ty) = JoyMath.tipFromCenter(cur.x, cur.y, r, a0, 1080f, 2340f)
        list[i] = cur.copy(radius = r, angleFrom = a0, angleTo = a1, option = mode, x2 = tx, y2 = ty)
        saveActions(list)
    }

    fun updateKind(id: Long, kind: ActionKind) = synchronized(lock) {
        val list = loadActions()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = list[i]
        if (cur.kind == kind) return
        list[i] = cur.copy(kind = kind)
        saveActions(list)
    }

    fun updateSingleTiming(durationMs: Long, delayMs: Long) {
        singlePressMs = durationMs
        singleDelayMs = delayMs
    }

    fun updateSingle(x: Float, y: Float) {
        singleX = x.coerceIn(0.02f, 0.98f)
        singleY = y.coerceIn(0.02f, 0.98f)
    }

    fun updatePoint(id: Long, end: Boolean, x: Float, y: Float) = synchronized(lock) {
        val list = loadActions()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val rx = x.coerceIn(0.02f, 0.98f)
        val ry = y.coerceIn(0.02f, 0.98f)
        val cur = list[i]
        if (cur.kind == ActionKind.JOYSTICK) {
            val sw = 1080f
            val sh = 2340f
            if (end) {
                val r = JoyMath.radiusFromTip(cur.x, cur.y, rx, ry, sw, sh)
                val ang = JoyMath.angleFromDelta((rx - cur.x) * sw, (ry - cur.y) * sh)
                val (tx, ty) = JoyMath.tipFromCenter(cur.x, cur.y, r, ang, sw, sh)
                list[i] = cur.copy(x2 = tx, y2 = ty, radius = r, angleFrom = ang)
            } else {
                val (tx, ty) = JoyMath.tipFromCenter(rx, ry, cur.radius, cur.angleFrom, sw, sh)
                list[i] = cur.copy(x = rx, y = ry, x2 = tx, y2 = ty)
            }
        } else {
            list[i] = if (end) cur.copy(x2 = rx, y2 = ry) else cur.copy(x = rx, y = ry)
        }
        saveActions(list)
    }

    fun remove(id: Long) = synchronized(lock) {
        saveActions(loadActions().filter { it.id != id })
    }

    fun move(id: Long, direction: Int) = synchronized(lock) {
        val groups = loadGroups()
        val list = loadActions()
        val action = list.firstOrNull { it.id == id } ?: return
        val chunk = list.filter { it.groupId == action.groupId }.toMutableList()
        val pos = chunk.indexOfFirst { it.id == id }
        val target = pos + direction
        if (pos < 0 || target !in chunk.indices) return
        val item = chunk.removeAt(pos)
        chunk.add(target, item)
        val out = mutableListOf<ClickerAction>()
        for (g in groups) {
            if (g.id == action.groupId) out.addAll(chunk) else out.addAll(list.filter { it.groupId == g.id })
        }
        saveActions(out)
    }

    fun moveActionToGroup(actionId: Long, groupId: Long): Boolean = synchronized(lock) {
        ensureGroupsLocked()
        if (loadGroups().none { it.id == groupId }) return false
        val list = loadActions()
        val i = list.indexOfFirst { it.id == actionId }
        if (i < 0) return false
        val item = list.removeAt(i).copy(groupId = groupId)
        list.add(item)
        saveActions(list)
        true
    }

    private fun mainGroupId(): Long = synchronized(lock) {
        ensureGroupsLocked().first { it.main }.id
    }

    private fun resolveGroupId(groupId: Long?): Long {
        val groups = ensureGroupsLocked()
        if (groupId != null && groups.any { it.id == groupId }) return groupId
        val selected = selectedGroupId
        if (groups.any { it.id == selected }) return selected
        return groups.first { it.main }.id
    }

    private fun snapshot(id: Long, name: String): ClickerPreset {
        ensureGroupsLocked()
        return ClickerPreset(
            id = id,
            name = name,
            singlePressMs = singlePressMs,
            singleDelayMs = singleDelayMs,
            singleX = singleX,
            singleY = singleY,
            defaultPressMs = defaultPressMs,
            defaultDelayMs = defaultDelayMs,
            defaultSwipeMs = defaultSwipeMs,
            repeatCount = repeatCount,
            startDelaySec = startDelaySec,
            groups = loadGroups().map { it.copy() },
            actions = loadActions().map { it.copy() },
            detectors = loadDetectors().map { it.copy() }
        )
    }

    private fun applyPreset(preset: ClickerPreset) {
        singlePressMs = preset.singlePressMs
        singleDelayMs = preset.singleDelayMs
        singleX = preset.singleX
        singleY = preset.singleY
        defaultPressMs = preset.defaultPressMs
        defaultDelayMs = preset.defaultDelayMs
        defaultSwipeMs = preset.defaultSwipeMs
        repeatCount = preset.repeatCount
        startDelaySec = preset.startDelaySec
        val groups = if (preset.groups.isEmpty()) {
            listOf(ActionGroup(id = nextId(), name = "Main", mode = GroupRunMode.SEQUENCE, main = true))
        } else {
            preset.groups.map { it.copy(id = if (it.id <= 0) nextId() else it.id) }
        }
        val mainId = groups.firstOrNull { it.main }?.id ?: groups.first().id
        saveGroups(groups)
        saveActions(preset.actions.map { a ->
            val gid = if (groups.any { it.id == a.groupId }) a.groupId else mainId
            a.copy(id = if (a.id == SINGLE_ID) nextId() else a.id, groupId = gid)
        })
        saveDetectors(preset.detectors.map { d ->
            d.copy(groupId = if (groups.any { it.id == d.groupId }) d.groupId else 0L)
        })
        selectedGroupId = mainId
        gcDetectorFilesLocked()
    }

    private fun ensureGroupsLocked(): MutableList<ActionGroup> {
        val groups = loadGroups()
        if (groups.isNotEmpty()) {
            if (!groups.any { it.main }) {
                groups[0] = groups[0].copy(main = true)
                saveGroups(groups)
            }
            return groups
        }
        val main = ActionGroup(id = nextId(), name = "Main", mode = GroupRunMode.SEQUENCE, main = true)
        val actions = loadActions().map { if (it.groupId == 0L) it.copy(groupId = main.id) else it }
        saveGroups(listOf(main))
        saveActions(actions)
        selectedGroupId = main.id
        return mutableListOf(main)
    }

    private fun nextId(): Long {
        seq += 1
        return seq
    }

    private fun readMode(key: String): ClickerMode {
        val raw = prefs.getString(key, ClickerMode.NONE.name) ?: ClickerMode.NONE.name
        return runCatching { ClickerMode.valueOf(raw) }.getOrDefault(ClickerMode.NONE)
    }

    private fun loadActions(): MutableList<ClickerAction> {
        return try {
            val raw = prefs.getString(KEY_ACTIONS, "[]") ?: "[]"
            parseActions(JSONArray(raw), fallbackGroupId = 0L)
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun parseActions(arr: JSONArray, fallbackGroupId: Long): MutableList<ClickerAction> {
        val out = mutableListOf<ClickerAction>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kind = runCatching { ActionKind.valueOf(o.getString("kind")) }.getOrNull() ?: continue
            val gid = o.optLong("groupId", fallbackGroupId)
            var radius = o.optDouble("radius", 0.10).toFloat().coerceIn(0.02f, 0.45f)
            var angleFrom = o.optDouble("angleFrom", 0.0).toFloat()
            var angleTo = o.optDouble("angleTo", 360.0).toFloat()
            var x2 = o.optDouble("x2", 0.0).toFloat()
            var y2 = o.optDouble("y2", 0.0).toFloat()
            val x = o.getDouble("x").toFloat()
            val y = o.getDouble("y").toFloat()
            if (kind == ActionKind.JOYSTICK) {
                if (!o.has("radius") && (x2 != 0f || y2 != 0f)) {
                    radius = JoyMath.radiusFromTip(x, y, x2, y2, 1080f, 2340f)
                    angleFrom = JoyMath.angleFromDelta((x2 - x) * 1080f, (y2 - y) * 2340f)
                }
                val tip = JoyMath.tipFromCenter(x, y, radius, angleFrom, 1080f, 2340f)
                x2 = tip.first
                y2 = tip.second
            }
            out.add(ClickerAction(id = o.getLong("id"), kind = kind, x = x, y = y, x2 = x2, y2 = y2, durationMs = o.optLong("durationMs", 50L), delayMs = o.optLong("delayMs", 100L), groupId = gid, option = o.optInt("option", 0), holdMs = o.optLong("holdMs", 0L), radius = radius, angleFrom = JoyMath.normDeg(angleFrom), angleTo = JoyMath.normDeg(angleTo), pauseAllWhileAction = o.optBoolean("pauseAllWhileAction", false), pauseAllWhileDelay = o.optBoolean("pauseAllWhileDelay", false)))
        }
        return out
    }

    private fun actionsToJson(list: List<ClickerAction>): JSONArray {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("kind", a.kind.name)
                put("x", a.x.toDouble())
                put("y", a.y.toDouble())
                put("x2", a.x2.toDouble())
                put("y2", a.y2.toDouble())
                put("durationMs", a.durationMs)
                put("delayMs", a.delayMs)
                put("groupId", a.groupId)
                put("option", a.option)
                put("holdMs", a.holdMs)
                put("radius", a.radius.toDouble())
                put("angleFrom", a.angleFrom.toDouble())
                put("angleTo", a.angleTo.toDouble())
                put("pauseAllWhileAction", a.pauseAllWhileAction)
                put("pauseAllWhileDelay", a.pauseAllWhileDelay)
            })
        }
        return arr
    }

    private fun saveActions(list: List<ClickerAction>) {
        prefs.edit().putString(KEY_ACTIONS, actionsToJson(list).toString()).apply()
    }

    private fun loadGroups(): MutableList<ActionGroup> {
        return try {
            val raw = prefs.getString(KEY_GROUPS, "[]") ?: "[]"
            parseGroups(JSONArray(raw))
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun parseGroups(arr: JSONArray): MutableList<ActionGroup> {
        val out = mutableListOf<ActionGroup>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mode = runCatching { GroupRunMode.valueOf(o.optString("mode", GroupRunMode.SEQUENCE.name)) }.getOrDefault(GroupRunMode.SEQUENCE)
            out.add(ActionGroup(id = o.getLong("id"), name = o.optString("name", "Group"), mode = mode, main = o.optBoolean("main", false), visible = o.optBoolean("visible", true), delaySec = o.optInt("delaySec", 0)))
        }
        return out
    }

    private fun groupsToJson(list: List<ActionGroup>): JSONArray {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("mode", g.mode.name)
                put("main", g.main)
                put("visible", g.visible)
                put("delaySec", g.delaySec)
            })
        }
        return arr
    }

    private fun saveGroups(list: List<ActionGroup>) {
        prefs.edit().putString(KEY_GROUPS, groupsToJson(list).toString()).apply()
    }

    private fun loadPresets(): MutableList<ClickerPreset> {
        return try {
            val raw = prefs.getString(KEY_PRESETS, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val out = mutableListOf<ClickerPreset>()
            for (i in 0 until arr.length()) {
                parsePresetObject(arr.getJSONObject(i))?.let { out.add(it) }
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun loadDetectors(): MutableList<Detector> {
        return try {
            parseDetectors(JSONArray(prefs.getString(KEY_DETECTORS, "[]") ?: "[]"), null)
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveDetectors(list: List<Detector>) {
        prefs.edit().putString(KEY_DETECTORS, detectorsToJson(list, false).toString()).apply()
    }

    private fun parseDetectors(arr: JSONArray, templates: MutableMap<Long, ByteArray>?): MutableList<Detector> {
        val out = mutableListOf<Detector>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            if (id == 0L) continue
            val pause = runCatching { DetectorPause.valueOf(o.optString("pause", DetectorPause.ACTION.name)) }.getOrDefault(DetectorPause.ACTION)
            if (templates != null) {
                val b64 = o.optString("templateB64", "")
                if (b64.isNotEmpty()) DetectorFiles.fromBase64(b64)?.let { templates[id] = it }
            }
            val l = o.optDouble("zoneL", 0.0).toFloat().coerceIn(0f, 1f)
            val t = o.optDouble("zoneT", 0.0).toFloat().coerceIn(0f, 1f)
            val r = o.optDouble("zoneR", 1.0).toFloat().coerceIn(0f, 1f)
            val b = o.optDouble("zoneB", 1.0).toFloat().coerceIn(0f, 1f)
            out.add(
                Detector(
                    id = id,
                    name = o.optString("name", "Detector").ifBlank { "Detector" },
                    enabled = o.optBoolean("enabled", true),
                    tplW = o.optInt("tplW", 0),
                    tplH = o.optInt("tplH", 0),
                    zoneL = minOf(l, r), zoneT = minOf(t, b), zoneR = maxOf(l, r), zoneB = maxOf(t, b),
                    threshold = o.optInt("threshold", 85).coerceIn(50, 99),
                    delaySec = o.optInt("delaySec", 0).coerceIn(0, 86400),
                    groupId = o.optLong("groupId", 0L),
                    pause = pause,
                    repeatSec = o.optInt("repeatSec", 0).coerceIn(0, 86400),
                    query = o.optString("query", "")
                )
            )
        }
        return out
    }

    private fun detectorsToJson(list: List<Detector>, embedTemplates: Boolean): JSONArray {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("enabled", d.enabled)
                put("tplW", d.tplW)
                put("tplH", d.tplH)
                put("zoneL", d.zoneL.toDouble())
                put("zoneT", d.zoneT.toDouble())
                put("zoneR", d.zoneR.toDouble())
                put("zoneB", d.zoneB.toDouble())
                put("threshold", d.threshold)
                put("delaySec", d.delaySec)
                put("groupId", d.groupId)
                put("pause", d.pause.name)
                put("repeatSec", d.repeatSec)
                put("query", d.query)
                if (embedTemplates) DetectorFiles.toBase64(appCtx, d.id)?.let { put("templateB64", it) }
            })
        }
        return arr
    }

    private fun gcDetectorFilesLocked() {
        val keep = HashSet<Long>()
        loadDetectors().forEach { keep.add(it.id) }
        loadPresets().forEach { p -> p.detectors.forEach { keep.add(it.id) } }
        DetectorFiles.gc(appCtx, keep)
    }

    private fun parsePresetObject(o: JSONObject, templates: MutableMap<Long, ByteArray>? = null): ClickerPreset? {
        return try {
            val groups = parseGroups(o.optJSONArray("groups") ?: JSONArray())
            val mainFallback = groups.firstOrNull { it.main }?.id ?: groups.firstOrNull()?.id ?: 0L
            val actions = parseActions(o.optJSONArray("actions") ?: JSONArray(), fallbackGroupId = mainFallback)
            val finalGroups = if (groups.isEmpty()) {
                val mid = nextId()
                mutableListOf(ActionGroup(id = mid, name = "Main", mode = GroupRunMode.SEQUENCE, main = true))
            } else groups
            val mid = finalGroups.firstOrNull { it.main }?.id ?: finalGroups.first().id
            val name = o.optString("name", "").trim().ifEmpty { "Preset" }
            ClickerPreset(
                id = o.optLong("id", nextId()),
                name = name,
                singlePressMs = o.optLong("singlePressMs", 50L),
                singleDelayMs = o.optLong("singleDelayMs", 100L),
                singleX = o.optDouble("singleX", 0.5).toFloat(),
                singleY = o.optDouble("singleY", 0.42).toFloat(),
                defaultPressMs = o.optLong("defaultPressMs", 50L),
                defaultDelayMs = o.optLong("defaultDelayMs", 200L),
                defaultSwipeMs = o.optLong("defaultSwipeMs", 400L),
                repeatCount = o.optInt("repeatCount", 0),
                startDelaySec = o.optInt("startDelaySec", 0),
                groups = finalGroups.map { it.copy() },
                actions = actions.map { if (it.groupId == 0L) it.copy(groupId = mid) else it },
                detectors = parseDetectors(o.optJSONArray("detectors") ?: JSONArray(), templates)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun presetToJson(p: ClickerPreset, embedTemplates: Boolean = false): JSONObject {
        return JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("singlePressMs", p.singlePressMs)
            put("singleDelayMs", p.singleDelayMs)
            put("singleX", p.singleX.toDouble())
            put("singleY", p.singleY.toDouble())
            put("defaultPressMs", p.defaultPressMs)
            put("defaultDelayMs", p.defaultDelayMs)
            put("defaultSwipeMs", p.defaultSwipeMs)
            put("repeatCount", p.repeatCount)
            put("startDelaySec", p.startDelaySec)
            put("groups", groupsToJson(p.groups))
            put("actions", actionsToJson(p.actions))
            put("detectors", detectorsToJson(p.detectors, embedTemplates))
        }
    }

    private fun remapPresetIds(src: ClickerPreset, templates: Map<Long, ByteArray>?): ClickerPreset {
        val idMap = HashMap<Long, Long>()
        val groups = src.groups.map { g ->
            val nid = nextId()
            idMap[g.id] = nid
            g.copy(id = nid)
        }.toMutableList()
        if (groups.none { it.main }) {
            if (groups.isEmpty()) groups.add(ActionGroup(id = nextId(), name = "Main", mode = GroupRunMode.SEQUENCE, main = true))
            else groups[0] = groups[0].copy(main = true)
        }
        val mainId = groups.first { it.main }.id
        val actions = src.actions.map { a ->
            a.copy(id = nextId(), groupId = idMap[a.groupId] ?: mainId)
        }
        val detectors = src.detectors.mapNotNull { d ->
            val nid = nextId()
            val ok = d.query.isNotBlank() || (templates?.get(d.id)?.let { DetectorFiles.saveBytes(appCtx, nid, it) } ?: DetectorFiles.copy(appCtx, d.id, nid))
            if (!ok) null else d.copy(id = nid, groupId = idMap[d.groupId] ?: 0L)
        }
        return src.copy(id = nextId(), groups = groups, actions = actions, detectors = detectors)
    }

    private fun uniquePresetName(wanted: String, existing: List<String>): String {
        val base = wanted.trim().ifEmpty { "Preset" }
        if (existing.none { it.equals(base, ignoreCase = true) }) return base
        var i = 2
        while (i < 999) {
            val candidate = "$base ($i)"
            if (existing.none { it.equals(candidate, ignoreCase = true) }) return candidate
            i++
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private fun savePresets(list: List<ClickerPreset>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(presetToJson(p)) }
        prefs.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    companion object {
        const val APP_CODE = "he_smartphone_auto_clicker"
        const val SINGLE_ID = -1L
        const val NO_PRESET = -1L
        const val PRESET_FILE_FORMAT = "he_smartphone_auto_clicker_preset"
        const val PRESET_FILE_VERSION = 1
        private const val MAX_ACTIONS = 40
        private const val MAX_PRESETS = 40
        private const val MAX_GROUPS = 12
        private const val MAX_DETECTORS = 12
        private const val KEY_DETECTORS = "detectors"
        private const val KEY_MODE = "mode"
        private const val KEY_PENDING = "pending_mode"
        private const val KEY_SESSION_OPEN = "session_open"
        private const val KEY_ACTIVE_PRESET = "active_preset"
        private const val KEY_ACTIVE_NAME = "active_name"
        private const val KEY_SINGLE_PRESS = "single_press"
        private const val KEY_SINGLE_DELAY = "single_delay"
        private const val KEY_SINGLE_X = "single_x"
        private const val KEY_SINGLE_Y = "single_y"
        private const val KEY_DEF_PRESS = "def_press"
        private const val KEY_DEF_DELAY = "def_delay"
        private const val KEY_DEF_SWIPE = "def_swipe"
        private const val KEY_REPEAT = "repeat"
        private const val KEY_START_DELAY = "start_delay_sec"
        private const val KEY_MENU_X = "menu_x"
        private const val KEY_MENU_Y = "menu_y"
        private const val KEY_ACTIONS = "actions"
        private const val KEY_GROUPS = "groups"
        private const val KEY_SELECTED_GROUP = "selected_group"
        private const val KEY_OPEN_COLLAPSED = "open_collapsed"
        private const val KEY_OVERLAY_SUPPRESSED = "overlay_suppressed"
        private const val KEY_VISION_AI = "vision_ai_key"
        private const val KEY_PRESETS = "presets"

        @Volatile private var inst: ClickerStore? = null

        fun get(ctx: Context): ClickerStore {
            return inst ?: synchronized(this) { inst ?: ClickerStore(ctx).also { inst = it } }
        }
    }
}