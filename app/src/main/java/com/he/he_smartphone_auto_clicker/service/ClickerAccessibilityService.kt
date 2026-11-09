package com.he.he_smartphone_auto_clicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.he.he_smartphone_auto_clicker.R
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.i18n.I18n
import com.he.he_smartphone_auto_clicker.i18n.tr
import com.he.he_smartphone_auto_clicker.model.ActionGroup
import com.he.he_smartphone_auto_clicker.model.ActionKind
import com.he.he_smartphone_auto_clicker.model.ClickerAction
import com.he.he_smartphone_auto_clicker.model.ClickerMode
import com.he.he_smartphone_auto_clicker.model.GroupRunMode
import com.he.he_smartphone_auto_clicker.util.JoyMath
import com.he.he_smartphone_auto_clicker.util.screenBounds
import com.he.he_smartphone_auto_clicker.vision.TextFinder
import com.he.he_smartphone_auto_clicker.vision.TextQuery
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

class ClickerAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null
    @Volatile private var running = false
    @Volatile private var waitingStart = false
    private var runToken = 0
    private var playGen = 0
    private val lastRandomOrder = HashMap<Long, List<Long>>()
    private val rng = java.util.Random()
    private lateinit var i18nCtx: Context
    private var lastDispatchAt = 0L
    private var cancelStreak = 0
    private var widgetSlot = 0
    private var lastReleaseAt = 0L
    private var dispatchWindowStart = 0L
    private var dispatchCountInWindow = 0
    private val pendingNodes = ArrayList<AccessibilityNodeInfo>()

    private val vision: VisionWatcher by lazy { VisionWatcher(this) }
    private var ocrRcOn = false
    private val ocrRc = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RUN_TEXT -> armAndStart()
                ACTION_START_RUN -> if (!running) startRun()
                ACTION_STOP_RUN -> {
                    stopRun()
                    Log.i("HeOcr", "stop joysticks relaches")
                }
                else -> debugReadText(intent?.getStringExtra("q"))
            }
        }
    }
    private var engineFingers: MutableList<BlockFinger> = ArrayList()
    @Volatile private var holdCount = 0
    private var actionTimerText: String? = null
    private var visionTimerText: String? = null
    private var shownTimerText: String? = null
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        i18nCtx = I18n.wrap(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!::i18nCtx.isInitialized) i18nCtx = I18n.wrap(this)
        instance = this
        overlay = OverlayController(this)
        hookOcrTest()
        applyMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && overlay != null) applyMode()
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopRun() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onOrientationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelLoop()
        unhookOcrTest()
        overlay?.hide()
        overlay = null
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelLoop()
        unhookOcrTest()
        overlay?.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun requestApply() { handler.post { applyMode() } }

    fun isRunning(): Boolean = running

    fun trStr(@androidx.annotation.StringRes id: Int, vararg args: Any?): String = i18nCtx.tr(id, *args)

    fun visionWatcher(): VisionWatcher = vision

    private fun hookOcrTest() {
        if (ocrRcOn) return
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        TextFinder.dumpDir = File(filesDir, "ocr").also { it.mkdirs() }
        val filter = IntentFilter().apply {
            addAction(ACTION_OCR_TEST)
            addAction(ACTION_RUN_TEXT)
            addAction(ACTION_START_RUN)
            addAction(ACTION_STOP_RUN)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ocrRc, filter, RECEIVER_EXPORTED) else registerReceiver(ocrRc, filter)
        ocrRcOn = true
    }

    private fun unhookOcrTest() {
        if (!ocrRcOn) return
        ocrRcOn = false
        try { unregisterReceiver(ocrRc) } catch (_: Exception) {}
    }

    private fun debugReadText(query: String?) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val q = query?.takeIf { it.isNotBlank() } ?: TextQuery.SAMPLE
        Log.i("HeOcr", "test demande")
        vision.grabber.grab { shot ->
            if (shot == null) {
                Log.w("HeOcr", "pas de capture err=${vision.grabber.lastError}")
                return@grab
            }
            val w = shot.bitmap.width
            val h = shot.bitmap.height
            if (shot.blank || shot.secure) {
                Log.w("HeOcr", "noir blank=${shot.blank} secure=${shot.secure} ${w}x${h}")
                shot.bitmap.recycle()
                return@grab
            }
            val z = TextQuery.BANNER_ZONE
            val t0 = SystemClock.elapsedRealtime()
            val page = TextFinder.see(shot.bitmap, z[0], z[1], z[2], z[3], 12000L)
            val ms = SystemClock.elapsedRealtime() - t0
            val sc = TextQuery.best(page.text, q)
            saveShot(shot.bitmap, "full.jpg")
            Log.i("HeOcr", "banniere score=${"%.2f".format(sc)} ${ms}ms ${w}x${h} lu=\"${page.text.replace('\n', ' ')}\" lines=${page.lines}")
            if (sc < 0.7f) {
                val wide = TextFinder.see(shot.bitmap, 0f, 0f, 1f, 0.45f)
                val sc2 = TextQuery.best(wide.text, q)
                Log.i("HeOcr", "haut score=${"%.2f".format(sc2)} lu=\"${wide.text.replace('\n', ' ')}\" lines=${wide.lines}")
            }
            shot.bitmap.recycle()
        }
    }

    private fun armAndStart() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val store = ClickerStore.get(this)
        val z = TextQuery.BANNER_ZONE
        var n = 0
        for (d in store.detectorList()) {
            val key = d.name.lowercase()
            val q = when {
                key.contains("tip") || key.contains("pourboire") -> TextQuery.TIP
                key.contains("steer") || key.contains("barre") || key.contains("dirig") -> TextQuery.BAR
                else -> null
            } ?: continue
            store.saveDetector(d.copy(enabled = true, query = q, threshold = 60, zoneL = z[0], zoneT = z[1], zoneR = z[2], zoneB = z[3]))
            n++
            Log.i("HeOcr", "arm ${d.name} -> $q")
        }
        Log.i("HeOcr", "armed=$n running=$running")
        val launch = packageManager.getLaunchIntentForPackage("com.metallic.chiaki")
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(launch) } catch (e: Exception) { Log.w("HeOcr", "chiaki", e) }
        }
        if (running) stopRun()
        handler.postDelayed({ if (!running) startRun() }, 700L)
    }

    private fun saveShot(bmp: Bitmap, name: String) {
        val dir = TextFinder.dumpDir ?: return
        try { FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) } } catch (e: Exception) { Log.w("HeOcr", "save", e) }
    }

    fun setVisionStatus(text: String?) { overlay?.setVisionStatus(text) }

    fun engineHold(delta: Int) {
        holdCount = (holdCount + delta).coerceAtLeast(0)
        Log.i(TAG, "engine hold=$holdCount")
    }

    fun isMainQuiet(): Boolean {
        return engineFingers.none { !it.injected && (it.active != null || it.outX != 0f || it.outY != 0f) } && !gestureInFlight && !dispatchQueued && openPtrs.isEmpty() && pendingOpenPtrs.isEmpty()
    }

    fun injectBlock(groupId: Long, cutFrom: Int = 0, onDone: () -> Unit): Boolean {
        if (!running) return false
        val store = ClickerStore.get(this)
        val group = store.groupList().firstOrNull { it.id == groupId } ?: return false
        val chunk = store.actionsInGroup(groupId)
        if (chunk.isEmpty()) return false
        val queue = if (group.mode == GroupRunMode.RANDOM) shuffleBlock(groupId, chunk).toMutableList() else chunk.toMutableList()
        engineFingers.add(BlockFinger(group = group, queue = queue, injected = true, onDone = onDone, cutFrom = cutFrom.coerceAtLeast(0)))
        Log.i(TAG, "inject block=${group.name} actions=${queue.size} cutFrom=$cutFrom")
        return true
    }

    fun startOrStop() {
        if (running) {
            val wasWaiting = waitingStart
            stopRun()
            if (wasWaiting) Toast.makeText(this, R.string.start_delay_cancelled, Toast.LENGTH_SHORT).show()
        } else {
            startRun()
        }
    }

    fun closeFromOverlay() {
        cancelLoop()
        val store = ClickerStore.get(this)
        store.mode = ClickerMode.NONE
        store.pendingMode = ClickerMode.NONE
        store.sessionOpen = false
        overlay?.hide()
    }

    fun applyMode() {
        cancelLoop()
        val store = ClickerStore.get(this)
        if (store.sessionOpen && store.mode != ClickerMode.NONE && !store.overlaySuppressed) overlay?.show() else overlay?.hide()
    }

    private fun startRun() {
        val store = ClickerStore.get(this)
        val plan = store.buildAutoPlayPlan()
        if (plan.isEmpty() && !store.hasUsableDetectors()) {
            Toast.makeText(this, R.string.add_target_first, Toast.LENGTH_SHORT).show()
            return
        }
        val delaySec = store.startDelaySec.coerceIn(0, 3600)
        running = true
        waitingStart = delaySec > 0
        val token = ++runToken
        overlay?.onRunStarted()
        Log.i(TAG, "run start actions=${plan.size} delaySec=$delaySec")
        if (delaySec <= 0) {
            waitingStart = false
            handler.postDelayed({ beginAutoRun(token) }, 80L)
            return
        }
        Toast.makeText(this, i18nCtx.getString(R.string.start_delay_armed, delaySec), Toast.LENGTH_LONG).show()
        tickStartDelay(delaySec, token)
    }

    private fun tickStartDelay(leftSec: Int, token: Int) {
        if (!running || token != runToken) return
        if (leftSec <= 0) {
            waitingStart = false
            actionTimerText = null
            pushLongTimer()
            beginAutoRun(token)
            return
        }
        showStartClock(leftSec)
        if (leftSec <= 5 || leftSec % 5 == 0) {
            Toast.makeText(this, i18nCtx.getString(R.string.start_delay_tick, leftSec), Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({ tickStartDelay(leftSec - 1, token) }, 1000L)
    }

    private fun beginAutoRun(token: Int) {
        handler.postDelayed({
            if (!running || token != runToken) return@postDelayed
            startEngine(token, oneShot = false, onlyGroup = null, cycle = 0)
            vision.start()
        }, 90)
    }

    fun runGroupOnce(groupId: Long) {
        if (running) {
            Toast.makeText(this, R.string.pause_first, Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = ClickerStore.get(this).buildGroupPlan(groupId)
        if (snapshot.isEmpty()) {
            Toast.makeText(this, R.string.group_empty_run, Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        val token = ++runToken
        overlay?.onRunStarted()
        Log.i(TAG, "group run id=$groupId count=${snapshot.size}")
        handler.postDelayed({
            if (!running || token != runToken) return@postDelayed
            startEngine(token, oneShot = true, onlyGroup = groupId, cycle = 0)
        }, 90)
    }

    fun stopRun() {
        val was = running
        cancelLoop()
        if (was) {
            overlay?.onRunStopped()
            Log.i(TAG, "run stop")
        }
    }

    private fun cancelLoop() {
        playGen++
        running = false
        waitingStart = false
        runToken++
        cancelStreak = 0
        gestureInFlight = false
        dispatchQueued = false
        handler.removeCallbacksAndMessages(null)
        vision.stop()
        clearLongTimer()
        holdCount = 0
        engineFingers = ArrayList()
        val dropped = pendingNodes.toList()
        pendingNodes.clear()
        widgetSlot = 0
        dropped.forEach { try { it.recycle() } catch (_: Exception) {} }
        lastRandomOrder.clear()
        val hadOpen = openPtrs.isNotEmpty() || pendingOpenPtrs.isNotEmpty()
        if (hadOpen) sealOpenPointers() else dropOpenState()
    }

    private data class OpenPtr(val stroke: GestureDescription.StrokeDescription, val x: Float, val y: Float, val upX: Float = x, val upY: Float = y)
    private var openPtrs: List<OpenPtr> = emptyList()
    private var pendingOpenPtrs: List<OpenPtr> = emptyList()
    private var pendingRelease = ArrayList<ReleaseStep>()

    private class ReleaseStep(val active: ActiveStroke, val stroke: GestureDescription.StrokeDescription, val tx: Float, val ty: Float, val lift: Boolean)
    @Volatile private var gestureInFlight = false
    @Volatile private var dispatchQueued = false

    private fun dropOpenState() {
        openPtrs = emptyList()
        pendingOpenPtrs = emptyList()
    }

    private fun sealOpenPointers() {
        val open = openPtrs
        openPtrs = emptyList()
        pendingOpenPtrs = emptyList()
        if (open.isEmpty()) return
        try {
            val builder = GestureDescription.Builder()
            var n = 0
            val max = GestureDescription.getMaxStrokeCount()
            for (p in open) {
                if (n >= max) break
                try {
                    val far = abs(p.upX - p.x) >= 4f || abs(p.upY - p.y) >= 4f
                    val path = if (far) movePath(p.x, p.y, p.upX, p.upY) else stillPath(p.x, p.y)
                    builder.addStroke(p.stroke.continueStroke(path, 0L, if (far) 220L else 24L, false))
                    n++
                } catch (e: Exception) {
                    Log.w(TAG, "seal ptr skip (deja mort)", e)
                }
            }
            if (n == 0) return
            gestureInFlight = true
            val sent = dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    Log.i(TAG, "seal done n=$n")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    Log.w(TAG, "seal cancelled")
                }
            }, null)
            if (!sent) gestureInFlight = false
            Log.i(TAG, "sealed pointers=$n sent=$sent")
        } catch (e: Exception) {
            gestureInFlight = false
            Log.e(TAG, "seal", e)
        }
    }

    private data class BlockFinger(
        val group: ActionGroup,
        var queue: MutableList<ClickerAction>,
        var index: Int = 0,
        var doneCycle: Boolean = false,
        var cooldownMs: Long = 0L,
        var delayAction: ClickerAction? = null,
        var active: ActiveStroke? = null,
        val injected: Boolean = false,
        val onDone: (() -> Unit)? = null,
        val cutFrom: Int = 0,
        var cutOn: Boolean = false,
        var outX: Float = 0f,
        var outY: Float = 0f,
        var outCx: Float = 0f,
        var outCy: Float = 0f
    )

    private data class ActiveStroke(
        val action: ClickerAction,
        val kind: StrokeKind,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val moveMs: Long,
        val totalMs: Long,
        var elapsed: Long = 0L,
        var stroke: GestureDescription.StrokeDescription? = null,
        var x: Float = 0f,
        var y: Float = 0f,
        var sealTap: Boolean = false,
        var nodeClicked: Boolean = false,
        var joyRadius: Float = 0f,
        var joyAng0: Float = 0f,
        var joyAng1: Float = 0f,
        var joyReleaseMs: Long = 0L,
        var returning: Boolean = false,
        var handUp: Boolean = false,
        var releaseTries: Int = 0,
        var parkFromX: Float = 0f,
        var parkFromY: Float = 0f,
        var joyHover: Boolean = false,
        var readyAt: Long = 0L
    )

    private enum class StrokeKind { TAP, SWIPE, JOY, PINCH_A, PINCH_B, MULTI_A, MULTI_B }
    private fun startEngine(token: Int, oneShot: Boolean, onlyGroup: Long?, cycle: Int) {
        if (!running || token != runToken) return
        val fingers = buildFingers(onlyGroup).toMutableList()

        val watchOnly = fingers.isEmpty() && !oneShot && ClickerStore.get(this).hasUsableDetectors()
        if (fingers.isEmpty() && !watchOnly) {
            stopRun()
            return
        }
        engineFingers = fingers
        Log.i(TAG, "engine cycle=$cycle fingers=${fingers.size} watchOnly=$watchOnly modes=${fingers.joinToString { "${it.group.name}:${it.group.mode}:${it.queue.size}" }}")
        tickEngine(fingers, token, oneShot, onlyGroup, cycle, mainLoops = 0)
    }

    private fun buildFingers(onlyGroup: Long?): List<BlockFinger> {
        val store = ClickerStore.get(this)
        val groups = store.groupList().filter { g ->
            if (onlyGroup != null) g.id == onlyGroup else g.mode != GroupRunMode.ON_DEMAND
        }
        val actions = store.actionList()
        val out = ArrayList<BlockFinger>()
        for (group in groups) {
            val chunk = actions.filter { it.groupId == group.id }
            if (chunk.isEmpty()) continue
            val queue = when (group.mode) {
                GroupRunMode.RANDOM -> shuffleBlock(group.id, chunk).toMutableList()
                else -> chunk.toMutableList()
            }
            out.add(BlockFinger(group = group, queue = queue))
        }
        return out
    }

    private fun shuffleBlock(groupId: Long, base: List<ClickerAction>): List<ClickerAction> {
        if (base.size <= 1) return base
        val prev = lastRandomOrder[groupId]
        var order = base.toMutableList()
        var tries = 0
        do {
            for (i in order.lastIndex downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = order[i]
                order[i] = order[j]
                order[j] = tmp
            }
            tries++
        } while (prev != null && order.map { it.id } == prev && tries < 32)
        if (prev != null && order.map { it.id } == prev && order.size > 1) {
            order = (order.drop(1) + order.first()).toMutableList()
        }
        lastRandomOrder[groupId] = order.map { it.id }
        return order
    }

    private fun homeLostStick(fingers: MutableList<BlockFinger>, token: Int, oneShot: Boolean, onlyGroup: Long?, cycle: Int, loops: Int): Boolean {
        val finger = fingers.firstOrNull { it.active == null && (it.outX != 0f || it.outY != 0f) } ?: return false
        val path = movePath(finger.outX, finger.outY, finger.outCx, finger.outCy)
        val gesture = try {
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, 320L, false)).build()
        } catch (e: Exception) {
            Log.e(TAG, "joy park orphan", e)
            handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, 360L)
            return true
        }
        Log.i(TAG, "joy park orphan ${finger.outX.toInt()},${finger.outY.toInt()} -> ${finger.outCx.toInt()},${finger.outCy.toInt()}")
        val t0 = SystemClock.uptimeMillis()
        dispatchSafe(gesture, 320L) { ok ->
            if (!running || token != runToken) return@dispatchSafe
            burnCooldowns(fingers, SystemClock.uptimeMillis() - t0, null)
            if (ok) {
                finger.outX = 0f
                finger.outY = 0f
                Log.i(TAG, "joy park orphan ok")
            } else Log.w(TAG, "joy park orphan cancelled")
            handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, if (ok) 40L else 360L)
        }
        return true
    }

    private fun burnCooldowns(fingers: List<BlockFinger>, ms: Long, skip: BlockFinger?) {
        if (ms <= 0L) return
        for (f in fingers) {
            if (f === skip || f.cooldownMs <= 0L) continue
            f.cooldownMs = (f.cooldownMs - ms).coerceAtLeast(0L)
            if (f.cooldownMs == 0L) f.delayAction = null
        }
    }

    private fun tickEngine(fingers: MutableList<BlockFinger>, token: Int, oneShot: Boolean, onlyGroup: Long?, cycle: Int, mainLoops: Int) {
        if (!running || token != runToken) return

        if (dispatchQueued) return
        val b = screenBounds(this)
        val sw = b.width().toFloat()
        val sh = b.height().toFloat()
        var loops = mainLoops
        if (homeLostStick(fingers, token, oneShot, onlyGroup, cycle, loops)) return

        val finished = fingers.filter { it.injected && it.doneCycle && it.active == null }
        if (finished.isNotEmpty()) {
            fingers.removeAll(finished.toSet())
            finished.forEach { f ->
                if (f.cutOn) {
                    f.cutOn = false
                    engineHold(-1)
                }
                try { f.onDone?.invoke() } catch (e: Exception) { Log.e(TAG, "inject done", e) }
            }
            if (!running || token != runToken) return
        }
        publishActionTimers(fingers)

        for (finger in fingers) {
            if (!finger.injected || finger.cutFrom <= 0 || finger.cutOn) continue
            if (finger.active != null || finger.doneCycle || finger.cooldownMs > 0L) continue
            if (finger.index >= finger.queue.size || finger.index < finger.cutFrom - 1) continue
            finger.cutOn = true
            engineHold(+1)
            Log.i(TAG, "cut others ${finger.group.name} action=${finger.index + 1}")
        }

        val pausers = fingers.filter { pausesOthers(it) }
        for (finger in fingers.toList()) {
            if (finger.active != null) continue
            if (finger.doneCycle) continue
            if (finger.cooldownMs > 0L) continue
            if (pausers.isNotEmpty() && finger !in pausers) continue
            if (holdCount > 0 && !finger.injected) continue
            if (finger.index >= finger.queue.size) {
                if (oneShot || finger.injected) {
                    finger.doneCycle = true
                    continue
                }
                when (finger.group.mode) {
                    GroupRunMode.RANDOM -> {
                        val base = ClickerStore.get(this).actionsInGroup(finger.group.id)
                        finger.queue = shuffleBlock(finger.group.id, base).toMutableList()
                        finger.index = 0
                        if (finger.queue.isEmpty()) {
                            finger.doneCycle = true
                            continue
                        }
                        Log.i(TAG, "random reshuffle block=${finger.group.name}")
                    }
                    else -> {
                        if (finger.group.main) {
                            loops += 1
                            val max = ClickerStore.get(this).repeatCount
                            if (max > 0 && loops >= max) {
                                Log.i(TAG, "repeat reached=$max")
                                stopRun()
                                return
                            }
                        }
                        finger.index = 0
                        finger.doneCycle = false
                        Log.i(TAG, "sequence loop block=${finger.group.name} mainLoops=$loops")
                    }
                }
            }
            val action = finger.queue[finger.index]
            finger.index++
            val started = startActive(action, sw, sh) ?: continue
            started.readyAt = SystemClock.uptimeMillis()
            overlay?.pulseAction(action.id)
            val blockDelay = if (finger.index == 1 && cycle == 0 && !finger.injected) finger.group.delaySec.coerceIn(0, 3600) * 1000L else 0L
            if (blockDelay > 0L) started.elapsed = -blockDelay
            finger.active = started
        }

        val pausingNow = fingers.filter { pausesOthers(it) }
        if (holdCount > 0) {
            for (f in fingers) {
                if (f.injected) continue
                val a = f.active ?: continue
                if (a.kind == StrokeKind.JOY && a.stroke == null && a.elapsed <= 0L && joyNearCenter(a)) f.active = null
            }
        }
        for (f in fingers) {
            val a = f.active ?: continue
            if (pausingNow.isNotEmpty() && f !in pausingNow) continue
            val lost = a.kind == StrokeKind.JOY && a.stroke == null && !joyNearCenter(a) && (a.releaseTries > 0 || f.outX != 0f || f.outY != 0f)
            if (lost) continue
            a.handUp = false
            a.returning = false
            a.releaseTries = 0
        }

        val actives = fingers.filter { f -> (holdCount <= 0 || f.injected) && (pausingNow.isEmpty() || f in pausingNow) }.mapNotNull { it.active }

        val releaseHeld = stuckPointers(fingers, pausingNow)
        if (releaseHeld.isNotEmpty()) {
            val dur = if (releaseHeld.all { it.returning }) 60L else 180L
            val built = buildParallelGesture(emptyList(), dur, releaseHeld)
            if (built == null) {
                handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, 40L)
                return
            }
            val steps = pendingRelease.toList()
            val t0 = SystemClock.uptimeMillis()
            dispatchSafe(built, dur) { ok ->
                if (!running || token != runToken) return@dispatchSafe
                burnCooldowns(fingers, SystemClock.uptimeMillis() - t0, null)
                if (ok) {
                    for (s in steps) {
                        s.active.x = s.tx
                        s.active.y = s.ty
                        s.active.stroke = if (s.lift) null else s.stroke
                        s.active.returning = !s.lift
                        s.active.handUp = s.lift
                        if (s.lift) {
                            s.active.releaseTries = 0

                            val owner = fingers.firstOrNull { it.active === s.active }
                            if (owner != null) {
                                owner.outX = 0f
                                owner.outY = 0f
                                if (!owner.injected) owner.active = null
                            }
                        }
                    }
                    Log.i(TAG, "joy park ok n=${steps.size} lift=${steps.all { it.lift }}")
                } else {
                    Log.w(TAG, "joy park cancelled")
                    var gaveUp = false
                    for (s in steps) {
                        s.active.stroke = null
                        s.active.handUp = false
                        s.active.releaseTries++
                        if (s.active.releaseTries >= 8) {
                            gaveUp = true
                            fingers.firstOrNull { it.active === s.active }?.active = null
                            Log.w(TAG, "joy abandon apres park")
                        }
                    }
                    val wait = if (gaveUp) 40L else 360L
                    handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, wait)
                    return@dispatchSafe
                }
                tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops)
            }
            return
        }

        val urgent = fingers.firstOrNull { f -> val a = f.active; f.injected && a != null && a.kind == StrokeKind.TAP && a.elapsed == 0L && !a.sealTap && !a.nodeClicked }
        if (urgent != null) {
            val a = urgent.active!!
            val press = realPressMs(a.action.durationMs)
            val gesture = try { GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(tapPath(a.x1, a.y1), 0L, press, false)).build() } catch (e: Exception) {
                Log.e(TAG, "inject tap", e)
                null
            }
            if (gesture != null) {
                Log.i(TAG, "finger tap ${a.x1.toInt()},${a.y1.toInt()} ${press}ms")
                val t0 = SystemClock.uptimeMillis()
                dispatchSafe(gesture, press) { ok ->
                    if (!running || token != runToken) return@dispatchSafe
                    burnCooldowns(fingers, SystemClock.uptimeMillis() - t0, urgent)
                    if (ok) {
                        val userDelay = a.action.delayMs.coerceIn(0L, MAX_USER_DELAY_MS)
                        urgent.cooldownMs = maxOf(userDelay, MIN_TAP_COOLDOWN_MS)
                        urgent.delayAction = a.action
                        urgent.active = null
                    } else Log.w(TAG, "inject tap cancelled")
                    handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, if (ok) 40L else 200L)
                }
                return
            }
        }

        val solo = actives.firstOrNull { a -> a.kind == StrokeKind.JOY && a.elapsed == 0L && a.stroke == null && a.releaseTries == 0 && joyNearCenter(a) }
        if (solo != null) {
            val owner = fingers.firstOrNull { it.active === solo }
            val path = joyFullPath(solo)
            val dur = solo.totalMs.coerceIn(200L, 8000L)
            val gesture = try { GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, dur, false)).build() } catch (e: Exception) {
                Log.e(TAG, "joy plein", e)
                null
            }
            if (gesture != null && owner != null) {
                Log.i(TAG, "joy plein ${dur}ms tip=${solo.x2.toInt()},${solo.y2.toInt()}")
                val t0 = SystemClock.uptimeMillis()
                dispatchSafe(gesture, dur) { ok ->
                    if (!running || token != runToken) return@dispatchSafe
                    burnCooldowns(fingers, SystemClock.uptimeMillis() - t0, owner)
                    if (ok) {
                        solo.x = solo.x1
                        solo.y = solo.y1
                        solo.elapsed = solo.totalMs
                        solo.stroke = null
                        solo.releaseTries = 0
                        owner.outX = 0f
                        owner.outY = 0f
                        owner.cooldownMs = solo.action.delayMs.coerceIn(0L, MAX_USER_DELAY_MS)
                        owner.delayAction = solo.action
                        owner.active = null
                        Log.i(TAG, "joy plein ok")
                    } else {
                        val edge = JoyMath.edgePx(solo.x1, solo.y1, solo.joyRadius, solo.joyAng0)
                        solo.x = edge.first
                        solo.y = edge.second
                        solo.elapsed = solo.moveMs
                        solo.stroke = null
                        solo.handUp = false
                        solo.returning = false
                        solo.releaseTries = 1
                        owner.outX = edge.first
                        owner.outY = edge.second
                        owner.outCx = solo.x1
                        owner.outCy = solo.y1
                        Log.w(TAG, "joy plein cancelled ${edge.first.toInt()},${edge.second.toInt()}")
                    }
                    handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, if (ok) 40L else 280L)
                }
                return
            }
        }
        if (actives.isEmpty() && releaseHeld.isEmpty()) {
            val allDone = fingers.isNotEmpty() && fingers.all { it.doneCycle }
            if (allDone) {
                onCycleFinished(token, oneShot, onlyGroup, cycle)
                return
            }
            var wait = 40L
            val ticking = fingers.filter { pausingNow.isEmpty() || it in pausingNow }
            for (f in ticking) if (f.cooldownMs in 1L until wait) wait = f.cooldownMs
            for (f in ticking) {
                if (f.cooldownMs > 0L) {
                    f.cooldownMs = (f.cooldownMs - wait).coerceAtLeast(0L)
                    if (f.cooldownMs == 0L) f.delayAction = null
                }
            }
            handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, wait)
            return
        }

        var slice = if (actives.isEmpty()) JOY_RELEASE_MS else MAX_SLICE_MS
        var clickSlice = 0L
        var sendingTap = false
        val swipeAlive = actives.any { (it.kind == StrokeKind.SWIPE || it.kind == StrokeKind.JOY) && it.elapsed >= 0L && it.elapsed < it.totalMs }
        val swipeContinuing = actives.any { (it.kind == StrokeKind.SWIPE || it.kind == StrokeKind.JOY) && it.stroke != null }
        val tapAt = if (swipeContinuing) 20L else 0L
        val tapTail = if (swipeAlive) TAP_TAIL_MS else 0L
        for (a in actives) {
            if (a.elapsed < 0L) {
                val left = -a.elapsed
                if (left < slice) slice = left
                continue
            }
            if (a.kind == StrokeKind.TAP) {
                val press = realPressMs(a.action.durationMs)
                if (a.action.kind == ActionKind.DOUBLE_CLICK) {
                    val secondStart = press + DOUBLE_GAP_MS
                    val phaseLeft = when {
                        a.elapsed < press -> press - a.elapsed
                        a.elapsed < secondStart -> secondStart - a.elapsed
                        else -> (a.totalMs - a.elapsed).coerceAtLeast(1L)
                    }
                    val sending = a.elapsed == 0L || a.elapsed == secondStart
                    if (sending) sendingTap = true
                    val need = phaseLeft + (if (sending) tapAt + tapTail else 0L)
                    if (need < slice) slice = need.coerceAtLeast(1L)
                    continue
                }
                if (a.elapsed == 0L && a.action.kind == ActionKind.CLICK) {
                    sendingTap = true
                    clickSlice = maxOf(clickSlice, press + tapAt + tapTail)
                }
            }
            val left = (a.totalMs - a.elapsed).coerceAtLeast(1L)
            if (left < slice) slice = left
        }
        for (f in fingers) {
            if (pausingNow.isNotEmpty() && f !in pausingNow) continue
            if (f.cooldownMs in 1L until slice) slice = f.cooldownMs
        }
        if (clickSlice > 0L) slice = clickSlice
        if (releaseHeld.isNotEmpty()) slice = maxOf(slice, JOY_RELEASE_MS)
        val someoneStillOut = actives.any { it.kind == StrokeKind.JOY && it.elapsed >= 0L && it.elapsed < joyHoldEnd(it) + it.joyReleaseMs }
        for (a in actives) {
            if (a.kind != StrokeKind.JOY || a.elapsed < 0L) continue
            if (someoneStillOut && a.elapsed >= joyHoldEnd(a) + a.joyReleaseMs) continue
            val phase = joyPhaseLeft(a)
            if (phase < slice) slice = phase
        }
        slice = slice.coerceIn(MIN_SLICE_MS, MAX_SLICE_MS)
        val swipeHolds = actives.any { (it.kind == StrokeKind.SWIPE || it.kind == StrokeKind.JOY) && it.elapsed >= 0L && it.elapsed + slice < it.totalMs }
        val pauseAfter = if (sendingTap && !swipeHolds) TAP_SETTLE_MS else 0L

        val built = buildParallelGesture(actives, slice, releaseHeld)
        val advanced = AtomicBoolean(false)
        fun after(ok: Boolean) {
            if (!advanced.compareAndSet(false, true)) return
            if (!running || token != runToken) return
            if (!ok) {
                cancelStreak++
                Log.w(TAG, "gesture fail streak=$cancelStreak")
                dropOpenState()
                var parkJoy = false
                for (finger in fingers) {
                    val a = finger.active ?: continue
                    a.stroke = null
                    if (a.kind != StrokeKind.JOY) continue
                    a.joyHover = false
                    if (a.x == 0f && a.y == 0f && a.elapsed > 0L) {
                        val p = joyPointAt(a, a.elapsed.coerceAtMost(joyHoldEnd(a)))
                        a.x = p.first
                        a.y = p.second
                    }
                    val wasOut = !joyNearCenter(a)
                    if (!wasOut) {
                        finger.outX = 0f
                        finger.outY = 0f
                        continue
                    }

                    a.returning = false
                    a.handUp = false
                    a.releaseTries++
                    finger.outX = a.x
                    finger.outY = a.y
                    finger.outCx = a.x1
                    finger.outCy = a.y1
                    Log.i(TAG, "joy lost ${a.x.toInt()},${a.y.toInt()} center=${a.x1.toInt()},${a.y1.toInt()} try=${a.releaseTries}")
                    if (a.releaseTries < 8) parkJoy = true else {
                        Log.w(TAG, "joy abandon")
                        finger.active = null
                    }
                }
                if (parkJoy) {
                    Log.i(TAG, "joy park retry streak=$cancelStreak")
                    handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, 360L)
                    return
                }
                if (cancelStreak >= 3) {
                    for (finger in fingers) {
                        val a = finger.active
                        if (a != null && a.kind == StrokeKind.JOY && !joyNearCenter(a)) {
                            a.stroke = null
                            a.handUp = false
                            a.returning = false
                            if (a.releaseTries < 1) a.releaseTries = 1
                            finger.outX = if (a.x != 0f || a.y != 0f) a.x else a.x2
                            finger.outY = if (a.x != 0f || a.y != 0f) a.y else a.y2
                            finger.outCx = a.x1
                            finger.outCy = a.y1
                            continue
                        }
                        finger.active = null
                        finger.cooldownMs = MIN_TAP_COOLDOWN_MS
                    }
                    val wait = (BACKOFF_MS * cancelStreak.coerceAtMost(10)).coerceAtMost(4000L)
                    handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, wait)
                    return
                }
                handler.postDelayed({ tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops) }, TAP_SETTLE_MS + 120L)
                return
            }
            cancelStreak = 0
            val stillPausing = fingers.filter { pausesOthers(it) }
            for (finger in fingers) {
                if (stillPausing.isNotEmpty() && finger !in stillPausing) continue
                if (finger.cooldownMs > 0L) {
                    finger.cooldownMs = (finger.cooldownMs - slice).coerceAtLeast(0L)
                    if (finger.cooldownMs == 0L) finger.delayAction = null
                }
                val a = finger.active ?: continue
                if (a.elapsed < 0L) {
                    a.elapsed += slice
                    if (a.elapsed >= 0L) a.elapsed = 0L
                    continue
                }
                if (a.kind == StrokeKind.TAP && a.elapsed == 0L && !a.sealTap && !a.nodeClicked) continue
                if (a.joyHover) {
                    a.joyHover = false
                    continue
                }
                if (a.kind == StrokeKind.JOY && a.stroke == null && a.releaseTries > 0 && !joyNearCenter(a)) continue
                if (a.sealTap) {
                    a.elapsed = a.totalMs
                    a.sealTap = false
                } else {
                    a.elapsed += slice
                }
                if (a.elapsed >= a.totalMs) {
                    if (a.kind == StrokeKind.JOY) {
                        if (!joyNearCenter(a)) {
                            finger.outX = if (a.x != 0f || a.y != 0f) a.x else a.x2
                            finger.outY = if (a.x != 0f || a.y != 0f) a.y else a.y2
                            finger.outCx = a.x1
                            finger.outCy = a.y1
                            Log.w(TAG, "joy fini hors centre ${finger.outX.toInt()},${finger.outY.toInt()}")
                        } else {
                            finger.outX = 0f
                            finger.outY = 0f
                        }
                    }
                    val userDelay = a.action.delayMs.coerceIn(0L, MAX_USER_DELAY_MS)
                    finger.cooldownMs = if (a.kind == StrokeKind.TAP) maxOf(userDelay, MIN_TAP_COOLDOWN_MS) else userDelay
                    finger.delayAction = a.action
                    finger.active = null
                }
            }
            tickEngine(fingers, token, oneShot, onlyGroup, cycle, loops)
        }

        val stagger = widgetSlot * 55L
        if (built == null) {
            if (joyContinueBroken) {
                joyContinueBroken = false
                after(false)
                return
            }
            val waitingDelay = actives.any { it.elapsed < 0L }
            val wait = if (!waitingDelay && actives.any { it.nodeClicked }) maxOf(TAP_SETTLE_MS, stagger) else slice
            handler.postDelayed({ after(true) }, wait)
            return
        }
        dispatchSafe(built, slice + pauseAfter + stagger) { ok -> after(ok) }
    }

    fun setVisionLongTimer(text: String?) {
        val next = text?.takeIf { it.isNotBlank() }
        if (next == visionTimerText) return
        visionTimerText = next
        pushLongTimer()
    }

    private fun showStartClock(leftSec: Int) {
        val big = ClickerStore.get(this).startDelaySec > 60 && leftSec > 0
        val text = if (big) i18nCtx.tr(R.string.long_timer_start, fmtClock(leftSec * 1000L)) else null
        if (text == actionTimerText) return
        actionTimerText = text
        pushLongTimer()
    }

    private fun publishActionTimers(fingers: List<BlockFinger>) {
        val lines = ArrayList<String>()
        for (f in fingers) {
            val running = f.active
            if (running != null && running.elapsed < 0L && f.group.delaySec > 60) lines.add(i18nCtx.tr(R.string.long_timer_block, f.group.name, fmtClock(-running.elapsed)))
            val owned = f.delayAction?.delayMs ?: 0L
            if (f.cooldownMs > 0L && owned > LONG_TIMER_MS) {
                val act = f.delayAction
                val pos = if (act == null) 0 else f.queue.indexOfFirst { it.id == act.id } + 1
                val line = if (pos > 0) i18nCtx.tr(R.string.long_timer_action, f.group.name, pos, fmtClock(f.cooldownMs)) else i18nCtx.tr(R.string.long_timer_block, f.group.name, fmtClock(f.cooldownMs))
                lines.add(line)
            }
        }
        val text = if (lines.isEmpty()) null else lines.joinToString("\n")
        if (text == actionTimerText) return
        actionTimerText = text
        pushLongTimer()
    }

    private fun pushLongTimer() {
        val parts = listOfNotNull(actionTimerText, visionTimerText).filter { it.isNotBlank() }
        val text = if (parts.isEmpty()) null else parts.joinToString("\n")
        if (text == shownTimerText) return
        shownTimerText = text
        overlay?.setLongTimer(text)
    }

    private fun clearLongTimer() {
        actionTimerText = null
        visionTimerText = null
        shownTimerText = null
        overlay?.setLongTimer(null)
    }

    private fun fmtClock(ms: Long): String {
        val s = (ms + 999L) / 1000L
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    private fun pausesOthers(finger: BlockFinger): Boolean {
        val running = finger.active
        if (running != null && running.elapsed >= 0L && running.action.pauseAllWhileAction) return true
        if (finger.cooldownMs > 0L && finger.delayAction?.pauseAllWhileDelay == true) return true
        return false
    }

    private fun startActive(action: ClickerAction, sw: Float, sh: Float): ActiveStroke? {
        fun px(v: Float, span: Float) = round(v * span).coerceAtLeast(0f)
        return when (action.kind) {
            ActionKind.CLICK, ActionKind.DOUBLE_CLICK -> {
                val d = realPressMs(action.durationMs)
                val x = px(action.x, sw)
                val y = px(action.y, sh)
                if (action.kind == ActionKind.DOUBLE_CLICK) {
                    ActiveStroke(action, StrokeKind.TAP, x, y, x, y, 0L, d * 2L + DOUBLE_GAP_MS)
                } else {
                    ActiveStroke(action, StrokeKind.TAP, x, y, x, y, 0L, d)
                }
            }
            ActionKind.MULTI_TOUCH -> {
                val d = realPressMs(action.durationMs)
                ActiveStroke(action, StrokeKind.MULTI_A, px(action.x, sw), px(action.y, sh), px(action.x2, sw), px(action.y2, sh), 0L, d)
            }
            ActionKind.PINCH -> {
                val dur = action.durationMs.coerceIn(50L, MAX_SWIPE_MS)
                ActiveStroke(action, StrokeKind.PINCH_A, px(action.x, sw), px(action.y, sh), px(action.x2, sw), px(action.y2, sh), dur, dur)
            }
            ActionKind.SWIPE -> {
                val x1 = px(action.x, sw)
                val y1 = px(action.y, sh)
                var x2 = px(action.x2, sw)
                var y2 = px(action.y2, sh)
                if (abs(x2 - x1) < 2f && abs(y2 - y1) < 2f) x2 += 12f
                val move = action.durationMs.coerceIn(50L, MAX_SWIPE_MS)
                val hold = action.holdMs.coerceIn(0L, MAX_HOLD_MS)
                ActiveStroke(action, StrokeKind.SWIPE, x1, y1, x2, y2, move, move + hold)
            }
            ActionKind.JOYSTICK -> {
                val cx = px(action.x, sw)
                val cy = px(action.y, sh)
                val radiusPx = (action.radius.coerceIn(0.02f, 0.45f) * sw).coerceAtLeast(12f)
                val mode = action.joyMode()
                val ang0 = when (mode) {
                    1 -> JoyMath.normDeg(action.angleFrom)
                    2 -> JoyMath.normDeg(action.angleFrom)
                    else -> JoyMath.randomInSector(action.angleFrom, action.angleTo, rng)
                }
                val ang1 = when (mode) {
                    2 -> JoyMath.normDeg(action.angleTo)
                    else -> ang0
                }
                val (ex, ey) = JoyMath.edgePx(cx, cy, radiusPx, ang0)
                val move = action.durationMs.coerceIn(50L, MAX_SWIPE_MS)
                val hold = action.holdMs.coerceIn(80L, MAX_HOLD_MS)

                val release = JOY_RELEASE_MS
                Log.i(TAG, "joy start mode=$mode ang=$ang0->$ang1 r=${radiusPx.toInt()} hold=${hold}ms release=${release}ms cx=${cx.toInt()},${cy.toInt()} tip=${ex.toInt()},${ey.toInt()}")
                ActiveStroke(action, StrokeKind.JOY, cx, cy, ex, ey, move, move + hold + release + JOY_LIFT_MS, joyRadius = radiusPx, joyAng0 = ang0, joyAng1 = ang1, joyReleaseMs = release)
            }
        }
    }

    private fun pointAt(a: ActiveStroke, atElapsed: Long): Pair<Float, Float> {
        if (a.kind == StrokeKind.JOY) return joyPointAt(a, atElapsed)
        if (a.moveMs <= 0L) return a.x2 to a.y2
        val moved = atElapsed.coerceIn(0L, a.moveMs)
        val f = moved.toFloat() / a.moveMs.toFloat()
        return (a.x1 + (a.x2 - a.x1) * f) to (a.y1 + (a.y2 - a.y1) * f)
    }

    private fun joyHoldEnd(a: ActiveStroke): Long {
        val move = a.moveMs.coerceAtLeast(1L)
        val release = a.joyReleaseMs.coerceAtLeast(0L)
        return (a.totalMs - release - JOY_LIFT_MS).coerceAtLeast(move)
    }

    private fun joyPhaseLeft(a: ActiveStroke): Long {
        val holdEnd = joyHoldEnd(a)
        val retEnd = holdEnd + a.joyReleaseMs.coerceAtLeast(0L)
        val end = when {
            a.elapsed < holdEnd -> holdEnd
            a.elapsed < retEnd -> retEnd
            else -> a.totalMs
        }
        return (end - a.elapsed).coerceAtLeast(1L)
    }

    private fun joyNearCenter(a: ActiveStroke): Boolean {
        val x = if (a.x != 0f || a.y != 0f) a.x else a.x1
        val y = if (a.x != 0f || a.y != 0f) a.y else a.y1
        return abs(x - a.x1) < 10f && abs(y - a.y1) < 10f
    }

    private fun joyFullPath(a: ActiveStroke): Path {
        val path = Path()
        val release = a.joyReleaseMs.coerceAtLeast(1L)
        val retEnd = joyHoldEnd(a) + release
        val step = 40L
        val seg = 14f
        var cx = a.x1
        var cy = a.y1
        path.moveTo(cx, cy)
        var t = step
        var flip = false
        while (t <= retEnd) {
            val p = joyPointAt(a, t)
            val nx = p.first
            val ny = p.second
            val dist = Math.hypot((nx - cx).toDouble(), (ny - cy).toDouble()).toFloat()
            if (dist < seg) {
                val ox = if (flip) seg else -seg
                flip = !flip
                path.lineTo(nx + ox, ny)
                path.lineTo(nx, ny)
            } else path.lineTo(nx, ny)
            cx = nx
            cy = ny
            t += step
        }
        path.lineTo(a.x1, a.y1)
        return path
    }

    private fun joyPointAt(a: ActiveStroke, atElapsed: Long): Pair<Float, Float> {
        val move = a.moveMs.coerceAtLeast(1L)
        val release = a.joyReleaseMs.coerceAtLeast(0L)
        val holdEnd = joyHoldEnd(a)
        val retEnd = holdEnd + release
        if (atElapsed < move) {
            val f = atElapsed.toFloat() / move.toFloat()
            val (ex, ey) = JoyMath.edgePx(a.x1, a.y1, a.joyRadius, a.joyAng0)
            return (a.x1 + (ex - a.x1) * f) to (a.y1 + (ey - a.y1) * f)
        }
        if (atElapsed >= retEnd) return a.x1 to a.y1
        if (atElapsed >= holdEnd && release > 0L) {

            val (ex, ey) = if (a.action.joyMode() == 2) {
                JoyMath.edgePx(a.x1, a.y1, a.joyRadius, a.joyAng1)
            } else {
                JoyMath.edgePx(a.x1, a.y1, a.joyRadius, a.joyAng0)
            }
            val t = ((atElapsed - holdEnd).toFloat() / release.toFloat()).coerceIn(0f, 1f)
            return (ex + (a.x1 - ex) * t) to (ey + (a.y1 - ey) * t)
        }
        val holdSpan = (holdEnd - move).coerceAtLeast(1L)
        val t = ((atElapsed - move).toFloat() / holdSpan.toFloat()).coerceIn(0f, 1f)
        val ang = if (a.action.joyMode() == 2) JoyMath.lerpClockwise(a.joyAng0, a.joyAng1, t) else a.joyAng0
        return JoyMath.edgePx(a.x1, a.y1, a.joyRadius, ang)
    }

    private fun stuckPointers(fingers: List<BlockFinger>, pausingNow: List<BlockFinger>): List<ActiveStroke> {
        val out = ArrayList<ActiveStroke>()
        for (f in fingers) {
            val a = f.active ?: continue
            if (a.handUp || a.elapsed < 0L) continue
            if (a.kind != StrokeKind.SWIPE && a.kind != StrokeKind.JOY) continue
            val needsHome = a.kind == StrokeKind.JOY && a.stroke == null && !joyNearCenter(a) && (a.releaseTries > 0 || f.outX != 0f || f.outY != 0f)
            val frozen = (holdCount > 0 && !f.injected) || (pausingNow.isNotEmpty() && f !in pausingNow)
            if (!needsHome && !frozen) continue
            if (!needsHome && a.stroke == null && a.elapsed <= 0L && !a.returning) continue
            out.add(a)
            break
        }
        return out
    }

    private fun buildParallelGesture(actives: List<ActiveStroke>, slice: Long, release: List<ActiveStroke> = emptyList()): GestureDescription? {
        pendingRelease = ArrayList()
        joyContinueBroken = false
        val builder = GestureDescription.Builder()
        var count = 0
        val max = min(GestureDescription.getMaxStrokeCount(), MAX_FINGERS)
        val b = screenBounds(this)
        val sw = b.width().toFloat()
        val sh = b.height().toFloat()
        fun px(v: Float, span: Float) = round(v * span).coerceAtLeast(0f)

        val continuing = actives.any { (it.kind == StrokeKind.SWIPE || it.kind == StrokeKind.JOY) && it.stroke != null }
        val blockExtraFingers = continuing
        val tapAt = 0L
        widgetSlot = 0
        var fingerTapUsed = false
        val nextOpen = ArrayList<OpenPtr>()

        data class JoyStrokePlan(val active: ActiveStroke, val stroke: GestureDescription.StrokeDescription, val tx: Float, val ty: Float, val willCont: Boolean)
        val joyPlans = ArrayList<JoyStrokePlan>()
        val continuedBuilt = ArrayList<OpenPtr>()
        val consumedPrev = HashSet<GestureDescription.StrokeDescription>()
        var continueBroken = false
        for (a in actives) {
            if (a.kind != StrokeKind.SWIPE && a.kind != StrokeKind.JOY) continue
            if (a.elapsed < 0L) continue
            val left = a.totalMs - a.elapsed
            if (left <= 0L) continue
            val retEnd = if (a.kind == StrokeKind.JOY) joyHoldEnd(a) + a.joyReleaseMs.coerceAtLeast(0L) else 0L
            val inLift = a.kind == StrokeKind.JOY && a.elapsed >= retEnd
            val otherStillOut = a.kind == StrokeKind.JOY && actives.any { o -> o !== a && o.kind == StrokeKind.JOY && o.elapsed >= 0L && o.elapsed < joyHoldEnd(o) + o.joyReleaseMs }
            var dur = min(slice, left).coerceAtLeast(1L)
            var willCont = a.elapsed + dur < a.totalMs
            if (a.kind == StrokeKind.JOY && a.stroke == null && a.releaseTries > 0 && !joyNearCenter(a)) continue
            val resumed = a.stroke == null && a.kind == StrokeKind.JOY && a.elapsed > 0L && (a.x != 0f || a.y != 0f)
            var fx: Float
            var fy: Float
            val start = if (a.stroke != null || resumed) a.x to a.y else a.x1 to a.y1
            fx = start.first
            fy = start.second
            a.parkFromX = fx
            a.parkFromY = fy
            var tx: Float
            var ty: Float
            a.joyHover = false
            if (inLift) {
                val atCenter = abs(fx - a.x1) < 6f && abs(fy - a.y1) < 6f
                if (!atCenter || otherStillOut) {

                    tx = a.x1
                    ty = a.y1
                    willCont = true
                    a.joyHover = true
                    if (a.elapsed + dur >= a.totalMs) dur = (a.totalMs - a.elapsed - 1L).coerceAtLeast(1L)
                } else {
                    fx = a.x1
                    fy = a.y1
                    tx = a.x1
                    ty = a.y1
                    willCont = false
                    dur = min(dur, JOY_LIFT_MS).coerceAtLeast(1L)
                }
            } else {
                val to = pointAt(a, a.elapsed + dur)
                tx = to.first
                ty = to.second
            }
            val path = movePath(fx, fy, tx, ty)
            val prev = a.stroke
            if (prev != null) {
                try {
                    val stroke = prev.continueStroke(path, 0L, dur, willCont)
                    consumedPrev.add(prev)
                    continuedBuilt.add(OpenPtr(stroke, tx, ty, if (a.kind == StrokeKind.JOY) a.x1 else tx, if (a.kind == StrokeKind.JOY) a.y1 else ty))
                    joyPlans.add(JoyStrokePlan(a, stroke, tx, ty, willCont))
                } catch (e: Exception) {
                    Log.e(TAG, if (a.kind == StrokeKind.JOY) "joy continue dead" else "swipe continue dead", e)
                    joyContinueBroken = true
                    continueBroken = true
                    break
                }
            } else {
                try {
                    joyPlans.add(JoyStrokePlan(a, GestureDescription.StrokeDescription(path, 0L, dur, willCont), tx, ty, willCont))
                } catch (e: Exception) {
                    Log.e(TAG, if (a.kind == StrokeKind.JOY) "joy stroke" else "swipe stroke", e)
                }
            }
        }
        if (continueBroken) {
            forceSealContinued(continuedBuilt)
            openPtrs = openPtrs.filter { it.stroke !in consumedPrev }
            pendingOpenPtrs = emptyList()
            if (openPtrs.isNotEmpty()) sealOpenPointers() else dropOpenState()
            for (a in actives) {
                if (a.kind == StrokeKind.SWIPE || a.kind == StrokeKind.JOY) a.stroke = null
            }
            return null
        }

        for (a in release) {
            if (count >= max) break
            val (cx, cy) = if (a.kind == StrokeKind.JOY) a.x1 to a.y1 else a.x to a.y
            val (fx, fy) = if (a.x != 0f || a.y != 0f) a.x to a.y else if (a.kind == StrokeKind.JOY) JoyMath.edgePx(a.x1, a.y1, a.joyRadius, a.joyAng0) else a.x1 to a.y1
            val near = abs(fx - cx) < 8f && abs(fy - cy) < 8f
            val prev = a.stroke

            val freshHome = prev == null && a.kind == StrokeKind.JOY && !near
            val lift = freshHome || a.kind != StrokeKind.JOY || a.returning || near
            val tx = if (freshHome || !lift) cx else fx
            val ty = if (freshHome || !lift) cy else fy
            val dur = if (freshHome) 320L else if (lift) 70L else 200L
            val willCont = !freshHome && !lift
            val path = if (freshHome) movePath(fx, fy, cx, cy) else if (!willCont && abs(fx - tx) < 1f && abs(fy - ty) < 1f) stillPath(fx, fy) else movePath(fx, fy, tx, ty)
            try {
                val stroke = if (prev != null) prev.continueStroke(path, 0L, dur, willCont) else GestureDescription.StrokeDescription(path, 0L, dur, willCont)
                builder.addStroke(stroke)
                if (willCont) nextOpen.add(OpenPtr(stroke, tx, ty, if (a.kind == StrokeKind.JOY) a.x1 else tx, if (a.kind == StrokeKind.JOY) a.y1 else ty))
                pendingRelease.add(ReleaseStep(a, stroke, tx, ty, !willCont))
                count++
                Log.i(TAG, "joy park ${if (freshHome) "home" else if (willCont) "move" else "lift"} ${fx.toInt()},${fy.toInt()} -> ${tx.toInt()},${ty.toInt()}")
            } catch (e: Exception) {
                Log.e(TAG, "joy park", e)
                a.stroke = null
                a.handUp = false
                a.releaseTries++
            }
        }

        val ordered = actives.sortedWith(compareBy<ActiveStroke>({ if (it.kind == StrokeKind.JOY || it.kind == StrokeKind.SWIPE) 0 else 1 }, { if (it.kind == StrokeKind.TAP) it.readyAt else 0L }))
        for (a in ordered) {
            if (count >= max) break
            if (a.elapsed < 0L) continue
            val left = a.totalMs - a.elapsed
            if (left <= 0L) continue
            val dur = min(slice, left).coerceAtLeast(1L)
            val fromElapsed = a.elapsed
            val toElapsed = a.elapsed + dur

            when (a.kind) {
                StrokeKind.TAP -> {
                    if (blockExtraFingers) continue
                    val press = realPressMs(a.action.durationMs)
                    val finger = press
                    if (a.action.kind == ActionKind.DOUBLE_CLICK) {
                        val firstEnd = press
                        val secondStart = press + DOUBLE_GAP_MS
                        val secondEnd = secondStart + press
                        if (fromElapsed < firstEnd && a.elapsed == 0L && count < max) {
                            if (tryWidgetClick(a)) {
                                a.nodeClicked = true
                            } else if (!fingerTapUsed) {
                                try {
                                    builder.addStroke(GestureDescription.StrokeDescription(tapPath(a.x1, a.y1), tapAt, finger, false))
                                    a.sealTap = false
                                    count++
                                    fingerTapUsed = true
                                } catch (_: Exception) {}
                            }
                        } else if (fromElapsed >= secondStart && fromElapsed < secondEnd && count < max) {
                            if (a.nodeClicked) {
                                tryWidgetClick(a)
                            } else if (!fingerTapUsed && (fromElapsed == secondStart || a.stroke == null)) {
                                try {
                                    builder.addStroke(GestureDescription.StrokeDescription(tapPath(a.x1, a.y1), tapAt, finger, false))
                                    count++
                                    fingerTapUsed = true
                                } catch (_: Exception) {}
                            }
                        }
                    } else if (a.elapsed == 0L && count < max) {
                        a.x = a.x1
                        a.y = a.y1
                        if (a.action.kind == ActionKind.CLICK && tryWidgetClick(a)) {
                            a.nodeClicked = true
                            a.sealTap = true
                        } else if (!fingerTapUsed) {
                            try {
                                builder.addStroke(GestureDescription.StrokeDescription(tapPath(a.x1, a.y1), tapAt, finger, false))
                                a.sealTap = true
                                count++
                                fingerTapUsed = true
                                Log.i(TAG, "finger tap ${a.x1.toInt()},${a.y1.toInt()} ${finger}ms")
                            } catch (e: Exception) {
                                Log.e(TAG, "tap", e)
                            }
                        }
                    }
                }
                StrokeKind.SWIPE, StrokeKind.JOY -> {
                    val plan = joyPlans.firstOrNull { it.active === a } ?: continue
                    if (count >= max) continue
                    try {
                        builder.addStroke(plan.stroke)
                        plan.active.stroke = if (plan.willCont) plan.stroke else null
                        plan.active.x = plan.tx
                        plan.active.y = plan.ty
                        count++
                        if (plan.willCont) nextOpen.add(OpenPtr(plan.stroke, plan.tx, plan.ty, plan.active.x1, plan.active.y1))
                    } catch (_: Exception) {}
                }
                StrokeKind.MULTI_A, StrokeKind.MULTI_B -> {
                    if (blockExtraFingers) continue
                    if (count + 2 > max) continue
                    val path1 = stillPath(a.x1, a.y1)
                    val path2 = stillPath(a.x2, a.y2)
                    try {
                        builder.addStroke(GestureDescription.StrokeDescription(path1, 0L, dur, false))
                        builder.addStroke(GestureDescription.StrokeDescription(path2, 0L, dur, false))
                        count += 2
                        a.stroke = null
                    } catch (_: Exception) {}
                }
                StrokeKind.PINCH_A, StrokeKind.PINCH_B -> {
                    if (blockExtraFingers) continue
                    if (count + 2 > max) continue
                    val x1 = px(a.action.x, sw)
                    val y1 = px(a.action.y, sh)
                    val x2 = px(a.action.x2, sw)
                    val y2 = px(a.action.y2, sh)
                    val midX = (x1 + x2) / 2f
                    val midY = (y1 + y2) / 2f
                    val scale = if (a.action.isPinchOut()) 1.55f else 0.42f
                    var e1x = midX + (x1 - midX) * scale
                    var e1y = midY + (y1 - midY) * scale
                    var e2x = midX + (x2 - midX) * scale
                    var e2y = midY + (y2 - midY) * scale
                    if (abs(e1x - x1) < 2f) e1x += 10f
                    if (abs(e2x - x2) < 2f) e2x -= 10f
                    val f = (toElapsed.toFloat() / a.totalMs.toFloat()).coerceIn(0f, 1f)
                    val p1x = x1 + (e1x - x1) * f
                    val p1y = y1 + (e1y - y1) * f
                    val p2x = x2 + (e2x - x2) * f
                    val p2y = y2 + (e2y - y2) * f
                    val fromF = (fromElapsed.toFloat() / a.totalMs.toFloat()).coerceIn(0f, 1f)
                    val s1x = x1 + (e1x - x1) * fromF
                    val s1y = y1 + (e1y - y1) * fromF
                    val s2x = x2 + (e2x - x2) * fromF
                    val s2y = y2 + (e2y - y2) * fromF
                    try {
                        builder.addStroke(GestureDescription.StrokeDescription(movePath(s1x, s1y, p1x, p1y), 0L, dur, false))
                        builder.addStroke(GestureDescription.StrokeDescription(movePath(s2x, s2y, p2x, p2y), 0L, dur, false))
                        count += 2
                        a.stroke = null
                    } catch (_: Exception) {}
                }
            }
        }
        pendingOpenPtrs = nextOpen
        if (count == 0) return null
        return try { builder.build() } catch (e: Exception) {
            Log.e(TAG, "build", e)
            if (continuedBuilt.isNotEmpty()) forceSealContinued(continuedBuilt)
            for (p in joyPlans) p.active.stroke = null
            pendingOpenPtrs = emptyList()
            null
        }
    }

    private fun forceSealContinued(built: List<OpenPtr>) {
        if (built.isEmpty()) return
        try {
            val builder = GestureDescription.Builder()
            var n = 0
            val max = GestureDescription.getMaxStrokeCount()
            for (p in built) {
                if (n >= max) break
                try {
                    val far = abs(p.upX - p.x) >= 4f || abs(p.upY - p.y) >= 4f
                    val path = if (far) movePath(p.x, p.y, p.upX, p.upY) else stillPath(p.x, p.y)
                    builder.addStroke(p.stroke.continueStroke(path, 0L, if (far) 220L else 24L, false))
                    n++
                } catch (_: Exception) {
                    try {
                        builder.addStroke(p.stroke)
                        n++
                    } catch (_: Exception) {}
                }
            }
            if (n == 0) return
            gestureInFlight = true
            val sent = dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { gestureInFlight = false }
                override fun onCancelled(gestureDescription: GestureDescription?) { gestureInFlight = false }
            }, null)
            if (!sent) gestureInFlight = false
            Log.w(TAG, "force seal continued n=$n sent=$sent")
        } catch (e: Exception) {
            gestureInFlight = false
            Log.e(TAG, "force seal", e)
        }
    }

    private fun realPressMs(requested: Long): Long {
        val sysTap = try { ViewConfiguration.getTapTimeout().toLong() } catch (_: Exception) { 100L }
        val floor = maxOf(sysTap, MIN_REAL_CLICK_MS)
        return requested.coerceIn(floor, 200L)
    }

    private fun stillPath(x: Float, y: Float): Path {
        val path = Path()
        val ax = x.coerceAtLeast(0f)
        val ay = y.coerceAtLeast(0f)
        path.moveTo(ax, ay)
        path.lineTo(ax + 1f, ay)
        return path
    }

    private fun tapPath(x: Float, y: Float): Path {
        val path = Path()
        val ax = x.coerceAtLeast(0f)
        val ay = y.coerceAtLeast(0f)
        path.moveTo(ax, ay)
        path.lineTo(ax + 1f, ay + 1f)
        return path
    }

    private fun tryWidgetClick(a: ActiveStroke): Boolean = clickWidgetAt(a.x1, a.y1)
    private fun clickWidgetAt(x: Float, y: Float): Boolean {
        val node = findWidget(x, y) ?: return false
        val slot = widgetSlot
        widgetSlot += 1
        val cls = node.className?.toString().orEmpty()
        val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty()
        pendingNodes.add(node)
        fun fire(): Boolean {
            if (!pendingNodes.remove(node)) return false
            val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
            try { node.recycle() } catch (_: Exception) {}
            if (ok) Log.i(TAG, "widget click ${x.toInt()},${y.toInt()} cls=$cls label=$label slot=$slot")
            else Log.w(TAG, "widget click fail ${x.toInt()},${y.toInt()} cls=$cls label=$label")
            return ok
        }
        if (slot == 0) return fire()
        handler.postDelayed({ fire() }, slot * 55L)
        return true
    }

    private fun findWidget(x: Float, y: Float): AccessibilityNodeInfo? {
        val px = x.toInt()
        val py = y.toInt()
        val screen = screenBounds(this)
        val maxArea = (screen.width().toLong() * screen.height() * 0.55f).toLong()
        val list = try { windows } catch (_: Exception) { null } ?: return null
        var found: AccessibilityNodeInfo? = null
        try {
            for (i in list.indices.reversed()) {
                val root = list[i].root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == packageName) {
                    root.recycle()
                    continue
                }
                val hit = findClickable(root, px, py, maxArea)
                if (hit != null) {
                    if (hit !== root) root.recycle()
                    found = hit
                    break
                }
                root.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "widget click", e)
        } finally {
            for (info in list) try { info.recycle() } catch (_: Exception) {}
        }
        return found
    }

    private fun findClickable(node: AccessibilityNodeInfo, x: Int, y: Int, maxArea: Long): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findClickable(child, x, y, maxArea)
            if (hit != null) {
                if (hit !== child) try { child.recycle() } catch (_: Exception) {}
                return hit
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        if (!node.isClickable || !node.isEnabled) return null
        val cls = node.className?.toString()?.lowercase() ?: ""
        if (!isAppButton(cls)) return null
        val area = rect.width().toLong() * rect.height().toLong()
        if (area <= 0L || area > maxArea) return null
        return node
    }

    private fun isAppButton(cls: String): Boolean {
        if (cls.contains("surface") || cls.contains("texture") || cls.contains("glsurface") || cls.contains("imagebutton") || cls.contains("imageview")) return false
        val simple = cls.substringAfterLast('.')
        return simple == "button" || simple == "textview" || simple == "checkbox" || simple == "switch" || simple == "radiobutton" || simple == "edittext" || simple == "compoundbutton" || simple == "chip" || simple == "togglebutton" || simple == "materialbutton" || simple == "appcompatbutton"
    }

    private fun tapNeedsFinger(a: ActiveStroke): Boolean {
        if (a.nodeClicked || a.kind != StrokeKind.TAP || a.elapsed < 0L) return false
        val press = realPressMs(a.action.durationMs)
        if (a.action.kind == ActionKind.DOUBLE_CLICK) {
            val secondStart = press + DOUBLE_GAP_MS
            return a.elapsed == 0L || (a.elapsed >= secondStart && a.elapsed < secondStart + press)
        }
        return a.elapsed == 0L && a.action.kind == ActionKind.CLICK
    }

    private fun movePath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        val path = Path()
        val ax = x1.coerceAtLeast(0f)
        val ay = y1.coerceAtLeast(0f)
        val bx = x2.coerceAtLeast(0f)
        val by = y2.coerceAtLeast(0f)
        path.moveTo(ax, ay)
        if (abs(bx - ax) >= 0.5f || abs(by - ay) >= 0.5f) path.lineTo(bx, by)
        return path
    }

    private fun dispatchSafe(gesture: GestureDescription, minElapsedMs: Long = 0L, done: (Boolean) -> Unit) {
        dispatchQueued = true
        val now = SystemClock.uptimeMillis()
        val gap = now - lastDispatchAt
        var wait = if (gap < MIN_DISPATCH_GAP_MS) MIN_DISPATCH_GAP_MS - gap else 0L
        if (gestureInFlight) wait = maxOf(wait, 80L)
        if (now - dispatchWindowStart >= 1000L) {
            dispatchWindowStart = now
            dispatchCountInWindow = 0
        }
        if (dispatchCountInWindow >= MAX_DISPATCH_PER_SEC) {
            wait = maxOf(wait, 1000L - (now - dispatchWindowStart))
        }
        handler.postDelayed({
            if (!running) {
                dispatchQueued = false
                gestureInFlight = false
                done(false)
                return@postDelayed
            }
            val sentAt = SystemClock.uptimeMillis()
            if (sentAt - dispatchWindowStart >= 1000L) {
                dispatchWindowStart = sentAt
                dispatchCountInWindow = 0
            }
            dispatchCountInWindow++
            lastDispatchAt = sentAt
            val once = AtomicBoolean(false)
            fun finish(ok: Boolean) {
                if (!once.compareAndSet(false, true)) return
                gestureInFlight = false
                dispatchQueued = false
                done(ok)
            }
            gestureInFlight = true
            val trackedOpen = pendingOpenPtrs
            val sent = try {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        openPtrs = trackedOpen
                        pendingOpenPtrs = emptyList()
                        val left = (minElapsedMs - (SystemClock.uptimeMillis() - sentAt)).coerceAtLeast(0L)
                        handler.postDelayed({ finish(true) }, left)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "gesture cancelled - drop open (pas de seal)")
                        dropOpenState()
                        handler.post { finish(false) }
                    }
                }, null)
            } catch (e: Exception) {
                Log.e(TAG, "dispatch", e)
                false
            }
            if (!sent) {
                gestureInFlight = false
                dispatchQueued = false
                pendingOpenPtrs = emptyList()
                finish(false)
                return@postDelayed
            }
            handler.postDelayed({
                if (once.get()) return@postDelayed
                dropOpenState()
                finish(true)
            }, MAX_SLICE_MS + 3000L)
        }, wait)
    }

    private fun softReleaseOneFinger() {
        if (gestureInFlight) return
        if (openPtrs.isNotEmpty() || pendingOpenPtrs.isNotEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastReleaseAt < RELEASE_COOLDOWN_MS) return
        lastReleaseAt = now
        try {
            val b = screenBounds(this)
            val x = 2f
            val y = (b.height() - 2).coerceAtLeast(2).toFloat()
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(stillPath(x, y), 0L, 20L, false))
                .build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "release", e)
        }
    }

    private fun onCycleFinished(token: Int, oneShot: Boolean, onlyGroup: Long?, cycle: Int) {
        if (!running || token != runToken) return
        if (oneShot) {
            stopRun()
            return
        }
        val next = cycle + 1
        val max = ClickerStore.get(this).repeatCount
        if (max > 0 && next >= max) {
            stopRun()
            return
        }

        handler.postDelayed({
            if (!running || token != runToken) return@postDelayed
            startEngine(token, oneShot = false, onlyGroup = onlyGroup, cycle = next)
        }, CYCLE_GAP_MS)
    }

    companion object {
        private const val TAG = "HeClicker"
        private const val DOUBLE_GAP_MS = 120L
        private const val TAP_SETTLE_MS = 180L
        private const val TAP_TAIL_MS = 140L
        private const val TAP_FINGER_MS = 80L
        private const val MIN_TAP_COOLDOWN_MS = 250L
        private const val LONG_TIMER_MS = 60_000L
        private const val MIN_SLICE_MS = 30L
        private const val MAX_SLICE_MS = 2500L
        private const val MIN_DISPATCH_GAP_MS = 120L
        private const val MAX_DISPATCH_PER_SEC = 5
        private const val BACKOFF_MS = 200L
        private const val CYCLE_GAP_MS = 200L
        private const val RELEASE_COOLDOWN_MS = 5000L
        private const val MAX_FINGERS = 10
        private const val MIN_REAL_CLICK_MS = 100L
        private const val MAX_PRESS_MS = 5000L
        private const val MAX_SWIPE_MS = 5000L
        private const val MAX_HOLD_MS = 10000L

        private const val MAX_USER_DELAY_MS = 600000L
        private const val JOY_RELEASE_MS = 240L
        private const val JOY_LIFT_MS = 80L
        private var joyContinueBroken = false
        const val ACTION_REFRESH = "he_smartphone_auto_clicker.REFRESH"
        const val ACTION_OCR_TEST = "he_smartphone_auto_clicker.OCR_TEST"
        const val ACTION_RUN_TEXT = "he_smartphone_auto_clicker.RUN_TEXT"
        const val ACTION_STOP_RUN = "he_smartphone_auto_clicker.STOP_RUN"
        const val ACTION_START_RUN = "he_smartphone_auto_clicker.START_RUN"

        @Volatile var instance: ClickerAccessibilityService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return flat.contains("ClickerAccessibilityService") && flat.contains(ctx.packageName)
        }

        fun requestRefresh(ctx: Context) {
            val running = instance
            if (running != null) {
                running.requestApply()
                return
            }
            if (!isEnabled(ctx)) return
            ctx.startService(Intent(ctx, ClickerAccessibilityService::class.java).setAction(ACTION_REFRESH))
        }
    }
}