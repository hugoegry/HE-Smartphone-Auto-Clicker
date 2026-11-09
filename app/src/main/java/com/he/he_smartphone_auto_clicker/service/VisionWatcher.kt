package com.he.he_smartphone_auto_clicker.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.he.he_smartphone_auto_clicker.R
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.model.Detector
import com.he.he_smartphone_auto_clicker.model.DetectorPause
import com.he.he_smartphone_auto_clicker.vision.Found
import com.he.he_smartphone_auto_clicker.vision.ScreenGrabber
import com.he.he_smartphone_auto_clicker.vision.TemplateMatcher
import com.he.he_smartphone_auto_clicker.vision.TextFinder
import com.he.he_smartphone_auto_clicker.vision.TextQuery
import com.he.he_smartphone_auto_clicker.vision.VisionAsk
import com.he.he_smartphone_auto_clicker.vision.VisionMatch
import com.he.he_smartphone_auto_clicker.vision.VisionTemplate
import java.util.concurrent.Executors

class VisionWatcher(private val service: ClickerAccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    val grabber = ScreenGrabber(service)

    private enum class St { ARMED, COUNTDOWN, WAIT_EXEC, EXECUTING, WAIT_GONE, COOLDOWN }

    private class Rt(var d: Detector) {
        var st = St.ARMED
        var hits = 0
        var misses = 0
        var deadline = 0L
        var lastFire = 0L
        var held = false
        var lastScore = 0f
    }

    class Probe(val ok: Boolean, val score: Float, val x: Int, val y: Int, val blank: Boolean, val errorCode: Int, val shotW: Int, val shotH: Int, val seen: String = "")

    private val rts = LinkedHashMap<Long, Rt>()
    @Volatile private var active = false
    private var gen = 0
    private var executing: Rt? = null
    private var failStreak = 0
    private var warnedBlank = false
    private var warnedFail = false
    private var pollMs = POLL_MS
    private val aiExec = Executors.newSingleThreadExecutor()
    @Volatile private var aiBusy = false
    private var lastAiAt = 0L

    fun isActive(): Boolean = active

    fun start() {
        stop()
        val list = ClickerStore.get(service).usableDetectors()
        if (list.isEmpty()) return
        if (!grabber.supported()) {
            toast(service.trStr(R.string.vision_need_android11))
            return
        }
        active = true
        gen++
        failStreak = 0
        warnedBlank = false
        warnedFail = false
        pollMs = POLL_MS
        list.forEach { rts[it.id] = Rt(it) }
        Log.i(TAG, "watch start detectors=${list.joinToString { it.name }}")
        if (list.any { it.query.isNotBlank() }) grabber.worker.execute { TextFinder.warm() }
        val g = gen
        handler.post { poll(g) }
        handler.postDelayed({ statusTick(g) }, 400L)
    }

    fun stop() {
        active = false
        gen++
        handler.removeCallbacksAndMessages(null)
        rts.clear()
        executing = null
        service.setVisionStatus(null)
        service.setVisionLongTimer(null)
    }

    private fun needsEval(r: Rt): Boolean = r.st == St.ARMED || r.st == St.WAIT_GONE

    private fun poll(g: Int) {
        if (!active || g != gen) return
        val targets = rts.values.filter { needsEval(it) }
        if (targets.isEmpty()) {
            handler.postDelayed({ poll(g) }, pollMs)
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val snapshot = targets.map { it.d }
        grabber.grab { shot ->
            var blank = false
            var secure = false
            var results: Map<Long, Float>? = null
            var failed = shot == null
            try {
            if (shot != null) {
                blank = shot.blank
                secure = shot.secure
                if (!blank && !secure) {
                    val out = HashMap<Long, Float>()
                    val t1 = SystemClock.elapsedRealtime()
                    val textDets = ArrayList<Detector>()
                    for (d in snapshot) {
                        if (d.query.isNotBlank()) { textDets.add(d); continue }
                        val tpls = VisionMatch.templatesFor(service, d.id)
                        if (tpls.isEmpty()) continue
                        var sc = -1f
                        for (tpl in tpls) {
                            val f = VisionMatch.find(shot.bitmap, tpl, d.zoneL, d.zoneT, d.zoneR, d.zoneB, 0f)
                            Log.i(TAG, "try ${d.name} ${TemplateMatcher.lastNote}")
                            if (f == null) continue
                            if (f.score > sc) sc = f.score
                        }
                        out[d.id] = if (sc < 0f) 0f else sc
                        if (sc >= 0f && sc < d.threshold / 100f) {
                            Log.d(TAG, "near-miss ${d.name} score=${"%.3f".format(sc)} need=${d.threshold}% ${shot.bitmap.width}x${shot.bitmap.height}")
                        }
                    }
                    if (textDets.isNotEmpty()) {
                        val seenByZone = HashMap<String, String>()
                        for (d in textDets) {
                            val key = "${d.zoneL},${d.zoneT},${d.zoneR},${d.zoneB}"
                            val seen = seenByZone.getOrPut(key) {
                                try { TextFinder.see(shot.bitmap, d.zoneL, d.zoneT, d.zoneR, d.zoneB).text } catch (e: Throwable) { Log.e(TAG, "ocr", e); "" }
                            }
                            val sc = TextQuery.best(seen, d.query)
                            out[d.id] = sc
                            Log.i(TAG, "ocr ${d.name} ${"%.0f".format(sc * 100)}% lu=\"${seen.replace('\n', ' ').take(90)}\"")
                        }
                    }
                    results = out
                    if (LOG_TIMING) {
                        val scores = out.entries.joinToString { (id, sc) -> "$id=${"%.2f".format(sc)}" }
                        Log.d(TAG, "match ${snapshot.size} det in ${SystemClock.elapsedRealtime() - t1}ms win=${shot.windowOnly} [$scores]")
                    }
                } else {
                    Log.w(TAG, "skip match blank=$blank secure=$secure err=${grabber.lastError}")
                }
                val key = ClickerStore.get(service).visionAiKey
                val now = SystemClock.elapsedRealtime()
                if (!blank && !secure && key.isNotBlank() && !aiBusy && now - lastAiAt >= AI_EVERY_MS) {
                    val bytes = try { VisionAsk.jpeg(shot.bitmap) } catch (_: Exception) { null }
                    if (bytes != null && bytes.isNotEmpty()) {
                        aiBusy = true
                        lastAiAt = now
                        val items = snapshot.map { it.id to it.name }
                        aiExec.execute {
                            val ans = VisionAsk.ask(key, bytes, items)
                            handler.post { onAi(g, ans) }
                        }
                    }
                }
            }
            } catch (e: Throwable) {
                Log.e(TAG, "poll", e)
                failed = true
            } finally {
                try { shot?.bitmap?.recycle() } catch (_: Exception) {}
            }
            handler.post { onPolled(g, failed, blank, secure, results, t0) }
        }
    }

    private fun onAi(g: Int, ans: Map<Long, Boolean>?) {
        aiBusy = false
        if (!active || g != gen) return
        if (ans == null) {
            Log.w(TAG, "ai pas de reponse")
            return
        }
        for ((id, yes) in ans) {
            val r = rts[id] ?: continue
            Log.i(TAG, "ai ${r.d.name} ${if (yes) "YES" else "NO"}")
            val need = r.d.threshold / 100f

            if (yes && r.st == St.ARMED && r.lastScore >= need) fire(r, r.lastScore)
        }
    }

    private fun onPolled(g: Int, failed: Boolean, blank: Boolean, secure: Boolean, results: Map<Long, Float>?, t0: Long) {
        if (!active || g != gen) return
        if (failed) {
            failStreak++
            if (failStreak == 1 || failStreak == 6) Log.w(TAG, "pas de frame streak=$failStreak err=${grabber.lastError}")
            if (failStreak == 6 && !warnedFail) {
                warnedFail = true
                toast(service.trStr(R.string.vision_capture_failed, grabber.lastError))
            }
        } else {
            failStreak = 0
        }
        if ((blank || secure) && !warnedBlank) {
            warnedBlank = true
            toast(service.trStr(R.string.vision_black_screen))
            service.setVisionStatus(service.trStr(R.string.vision_status_secure))
        }
        if (results != null) {
            for ((id, score) in results) {
                val r = rts[id] ?: continue
                r.lastScore = score
                val hit = score >= r.d.threshold / 100f
                when (r.st) {
                    St.ARMED -> {
                if (hit) {

                    fire(r, score)
                } else {
                    r.hits = 0
                }
                    }
                    St.WAIT_GONE -> {
                        if (!hit) {
                            r.misses++
                            if (r.misses >= 2) {
                                r.st = St.ARMED
                                r.hits = 0
                                Log.i(TAG, "re-armed ${r.d.name} (element gone)")
                            }
                        } else {
                            r.misses = 0
                        }
                    }
                    else -> {}
                }
            }
        }
        val spent = SystemClock.elapsedRealtime() - t0

        val back = when {
            blank || secure -> 2000L
            failStreak > 2 -> (pollMs * 2).coerceAtMost(3000L)
            else -> pollMs
        }
        handler.postDelayed({ poll(g) }, (back - spent).coerceAtLeast(120L))
    }

    private fun fire(r: Rt, score: Float) {
        val d = r.d
        r.st = St.COUNTDOWN
        r.hits = 0
        val now = SystemClock.elapsedRealtime()
        r.lastFire = now
        r.deadline = now + d.delaySec * 1000L
        Log.i(TAG, "FIRE ${d.name} score=${"%.3f".format(score)} delay=${d.delaySec}s pause=${d.pause} group=${d.groupId}")
        toast(if (d.delaySec > 0) service.trStr(R.string.vision_fired_timer, d.name, fmt(d.delaySec * 1000L)) else service.trStr(R.string.vision_fired, d.name))
        if (d.pause == DetectorPause.ALL) hold(r)
        val g = gen
        handler.postDelayed({ if (active && g == gen) endCountdown(r) }, d.delaySec * 1000L)
    }

    private fun endCountdown(r: Rt) {
        val busy = executing
        if (busy != null && busy !== r) {
            r.st = St.WAIT_EXEC
            val g = gen
            handler.postDelayed({ if (active && g == gen) endCountdown(r) }, 250L)
            return
        }
        executing = r
        r.st = St.EXECUTING

        if (r.d.pause == DetectorPause.NONE || r.d.pause == DetectorPause.ACTION) {
            runBlock(r)
            return
        }
        waitQuiet(r, SystemClock.elapsedRealtime() + QUIET_TIMEOUT_MS)
    }

    private fun waitQuiet(r: Rt, until: Long) {
        if (!active) return
        if (service.isMainQuiet() || SystemClock.elapsedRealtime() >= until) {
            runBlock(r)
            return
        }
        val g = gen
        handler.postDelayed({ if (active && g == gen) waitQuiet(r, until) }, 60L)
    }

    private fun runBlock(r: Rt) {
        val gid = r.d.groupId

        val cutFrom = if (r.d.pause == DetectorPause.ACTION) 3 else 0
        val started = gid != 0L && service.injectBlock(gid, cutFrom) { finish(r) }
        Log.i(TAG, "RUN ${r.d.name} block=$gid started=$started")
        if (!started) finish(r)
    }

    private fun finish(r: Rt) {
        if (!active) return
        release(r)
        if (executing === r) executing = null
        r.misses = 0
        val d = r.d
        if (d.repeatSec > 0) {
            r.st = St.COOLDOWN
            val left = (d.repeatSec * 1000L - (SystemClock.elapsedRealtime() - r.lastFire)).coerceAtLeast(0L)
            val g = gen
            handler.postDelayed({
                if (active && g == gen && r.st == St.COOLDOWN) {
                    r.st = St.ARMED
                    r.hits = 0
                }
            }, left)
        } else {
            r.st = St.WAIT_GONE
        }
        Log.i(TAG, "DONE ${d.name} -> ${r.st}")
    }

    private fun hold(r: Rt) {
        if (r.held) return
        r.held = true
        service.engineHold(+1)
    }

    private fun release(r: Rt) {
        if (!r.held) return
        r.held = false
        service.engineHold(-1)
    }

    private fun statusTick(g: Int) {
        if (!active || g != gen) return
        val now = SystemClock.elapsedRealtime()
        val live = rts.values.filter { it.st == St.COUNTDOWN || it.st == St.WAIT_EXEC || it.st == St.EXECUTING }
        val text = when {
            live.isNotEmpty() -> live.joinToString("  ") { r ->
                when (r.st) {
                    St.COUNTDOWN -> "${r.d.name} ${fmt((r.deadline - now).coerceAtLeast(0L))}"
                    else -> "${r.d.name} >>"
                }
            }
            grabber.lastSecure -> service.trStr(R.string.vision_status_secure)
            else -> {
                val armed = rts.values.filter { it.st == St.ARMED }
                if (armed.isEmpty()) null else armed.joinToString("  ") { r ->
                    val sc = if (r.lastScore < 0f) "--" else "${(r.lastScore * 100).toInt()}%"
                    "${r.d.name} $sc"
                }
            }
        }
        service.setVisionStatus(text)
        val longLines = rts.values.mapNotNull { r ->
            if (r.st != St.COUNTDOWN || r.d.delaySec <= 60) return@mapNotNull null
            val left = (r.deadline - now).coerceAtLeast(0L)
            service.trStr(R.string.long_timer_block, r.d.name, fmt(left))
        }
        service.setVisionLongTimer(if (longLines.isEmpty()) null else longLines.joinToString("\n"))
        handler.postDelayed({ statusTick(g) }, 500L)
    }

    private fun fmt(ms: Long): String {
        val s = (ms + 999L) / 1000L
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun toast(msg: String) { Toast.makeText(service, msg, Toast.LENGTH_LONG).show() }

    fun probe(tpls: List<VisionTemplate>, l: Float, t: Float, r: Float, b: Float, cb: (Probe) -> Unit) {
        attemptProbe(tpls, l, t, r, b, 6, cb)
    }

    fun probeText(query: String, l: Float, t: Float, r: Float, b: Float, cb: (Probe) -> Unit) {
        attemptText(query, l, t, r, b, 6, cb)
    }

    private fun attemptText(query: String, l: Float, t: Float, r: Float, b: Float, left: Int, cb: (Probe) -> Unit) {
        grabber.grab { shot ->
            if (shot == null) {
                if (left > 0) handler.postDelayed({ attemptText(query, l, t, r, b, left - 1, cb) }, 450L)
                else handler.post { cb(Probe(false, -1f, 0, 0, false, grabber.lastError, 0, 0)) }
                return@grab
            }
            val w = shot.bitmap.width
            val h = shot.bitmap.height
            var score = -1f
            var seen = ""
            if (!shot.blank) {
                val page = try { TextFinder.see(shot.bitmap, l, t, r, b) } catch (e: Exception) { Log.e(TAG, "ocr probe", e); null }
                seen = page?.text.orEmpty()
                score = TextQuery.best(seen, query)
                Log.i(TAG, "probe text ${"%.0f".format(score * 100)}% \"$seen\"")
            }
            val blank = shot.blank
            shot.bitmap.recycle()
            handler.post { cb(Probe(score >= 0f, score, 0, 0, blank, 0, w, h, seen)) }
        }
    }

    private fun attemptProbe(tpls: List<VisionTemplate>, l: Float, t: Float, r: Float, b: Float, left: Int, cb: (Probe) -> Unit) {
        var delivered = false
        grabber.grab { shot ->
            if (shot == null) {
                if (left > 0) {
                    handler.postDelayed({ attemptProbe(tpls, l, t, r, b, left - 1, cb) }, 450L)
                } else {
                    handler.post { cb(Probe(false, -1f, 0, 0, false, grabber.lastError, 0, 0)) }
                }
                return@grab
            }
            val w = shot.bitmap.width
            val h = shot.bitmap.height
            var f: Found? = null
            if (!shot.blank) {
                for (tpl in tpls) {
                    val hit = VisionMatch.find(shot.bitmap, tpl, l, t, r, b, 0f) ?: continue
                    if (f == null || hit.score > f.score) f = hit
                }
            }
            val blank = shot.blank
            shot.bitmap.recycle()
            if (!delivered) {
                delivered = true
                handler.post { cb(Probe(f != null, f?.score ?: -1f, f?.x ?: 0, f?.y ?: 0, blank, 0, w, h)) }
            }
        }
    }

    companion object {
        private const val TAG = "HeVision"
        private const val POLL_MS = 700L
        private const val AI_EVERY_MS = 6000L
        private const val QUIET_TIMEOUT_MS = 15000L
        private const val LOG_TIMING = true
    }
}
