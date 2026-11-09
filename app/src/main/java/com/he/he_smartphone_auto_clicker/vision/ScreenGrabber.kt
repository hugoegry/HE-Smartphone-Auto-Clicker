package com.he.he_smartphone_auto_clicker.vision

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.he.he_smartphone_auto_clicker.util.screenBounds
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenGrabber(private val service: AccessibilityService) {
    class Shot(val bitmap: Bitmap, val blank: Boolean, val windowOnly: Boolean, val secure: Boolean)

    @Volatile private var pool: ExecutorService = newPool()
    val worker: Executor get() = pool

    @Volatile var lastError: Int = 0
        private set
    @Volatile var lastSecure: Boolean = false
        private set
    @Volatile private var busy = false
    private var busyAt = 0L
    private var grabSeq = 0
    private var waiter: Waiter? = null
    private val gate = Any()
    private val timer = Handler(Looper.getMainLooper())
    private var winFailStreak = 0
    private var windowRetryAt = 0L
    private var lastDumpAt = 0L
    private var lastBusyLog = 0L

    private class Waiter(val token: Int, val cb: (Shot?) -> Unit) {
        @Volatile var sent = false
        @Volatile var hw: HwHold? = null
    }

    private class HwHold(private val hw: HardwareBuffer) {
        private val once = AtomicBoolean(false)
        fun close() {
            if (!once.compareAndSet(false, true)) return
            try { hw.close() } catch (_: Exception) {}
        }
    }

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun grab(preferWindow: Boolean = true, cb: (Shot?) -> Unit) {
        if (!supported()) { cb(null); return }
        if (busy) {
            val age = SystemClock.elapsedRealtime() - busyAt
            if (age >= STUCK_MS) {
                Log.w(TAG, "capture coince ${age}ms, reset")
                fireStuck()
            } else {
                if (age > 500 && SystemClock.elapsedRealtime() - lastBusyLog > 1500) {
                    lastBusyLog = SystemClock.elapsedRealtime()
                    Log.w(TAG, "capture deja en cours ${age}ms err=$lastError")
                }
                cb(null)
                return
            }
        }
        val w: Waiter
        val exec: Executor
        synchronized(gate) {
            if (busy) { cb(null); return }
            w = Waiter(++grabSeq, cb)
            waiter = w
            busy = true
            busyAt = SystemClock.elapsedRealtime()
            exec = pool
        }
        timer.postDelayed({
            if (!w.sent && waiter === w) fireStuck()
        }, STUCK_MS)
        try {
            val winId = if (preferWindow && Build.VERSION.SDK_INT >= 34 && SystemClock.elapsedRealtime() >= windowRetryAt) pickAppWindowId() else -1
            if (winId >= 0) grabWindow(winId, exec, w) else grabDisplay(lastSecure, exec, w)
        } catch (e: Exception) {
            Log.e(TAG, "grab", e)
            deliver(w, null)
        }
    }

    private fun fireStuck() {
        val w = synchronized(gate) {
            val cur = waiter
            if (!busy || cur == null || cur.sent) {
                if (cur == null || cur.sent) busy = false
                null
            } else {
                cur.sent = true
                busy = false
                waiter = null
                windowRetryAt = SystemClock.elapsedRealtime() + 30_000L
                val old = pool
                pool = newPool()
                try { old.shutdownNow() } catch (_: Exception) {}
                try { cur.hw?.close() } catch (_: Exception) {}
                lastError = ERR_STUCK
                cur
            }
        } ?: return
        Log.w(TAG, "capture bloque, thread jete token=${w.token}")
        try { w.cb(null) } catch (e: Exception) { Log.e(TAG, "grab cb", e) }
    }

    private fun deliver(w: Waiter, shot: Shot?) {
        val run = synchronized(gate) {
            if (w.sent) false else {
                w.sent = true
                if (waiter === w) {
                    busy = false
                    waiter = null
                }
                true
            }
        }
        if (!run) {
            try { shot?.bitmap?.recycle() } catch (_: Exception) {}
            return
        }
        try { w.hw?.close() } catch (_: Exception) {}
        try { w.cb(shot) } catch (e: Exception) { Log.e(TAG, "grab cb", e) }
    }

    private fun grabDisplay(secureHint: Boolean, exec: Executor, w: Waiter) {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                finishShot(w, screenshot, windowOnly = false, secureHint = secureHint)
            }
            override fun onFailure(errorCode: Int) {
                if (w.sent) return
                lastError = errorCode
                Log.w(TAG, "screenshot fail code=$errorCode")
                deliver(w, null)
            }
        })
    }

    @android.annotation.SuppressLint("NewApi")
    private fun grabWindow(windowId: Int, exec: Executor, w: Waiter) {
        service.takeScreenshotOfWindow(windowId, exec, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                if (w.sent) { closeQuiet(screenshot.hardwareBuffer); return }
                lastSecure = false
                finishShot(w, screenshot, windowOnly = true, secureHint = false)
            }
            override fun onFailure(errorCode: Int) {
                if (w.sent) return

                lastError = errorCode
                Log.w(TAG, "window screenshot fail code=$errorCode -> display")
                val secure = errorCode == SECURE_WINDOW
                if (secure) {
                    lastSecure = true
                    winFailStreak = 0
                } else {
                    winFailStreak++
                    if (winFailStreak >= 3) {
                        winFailStreak = 0
                        windowRetryAt = SystemClock.elapsedRealtime() + 30_000L
                    }
                }
                try { grabDisplay(secure || lastSecure, exec, w) } catch (e: Exception) {
                    Log.e(TAG, "grab display", e)
                    deliver(w, null)
                }
            }
        })
    }

    private fun finishShot(w: Waiter, res: AccessibilityService.ScreenshotResult, windowOnly: Boolean, secureHint: Boolean) {
        val hw = res.hardwareBuffer
        if (w.sent) { closeQuiet(hw); return }
        val hold = HwHold(hw)
        w.hw = hold
        if (w.sent) { hold.close(); return }
        val shot = try { toShot(res, windowOnly, secureHint) } catch (e: Exception) { Log.e(TAG, "shot", e); null }
        deliver(w, shot)
    }

    private fun toShot(res: AccessibilityService.ScreenshotResult, windowOnly: Boolean, secureHint: Boolean): Shot? {
        val hw = res.hardwareBuffer
        val hwBmp = Bitmap.wrapHardwareBuffer(hw, res.colorSpace) ?: return null
        return try {
            val soft = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
            if (soft == null) return null

            val blank = isContentBlank(soft)
            val secure = secureHint && blank
            lastSecure = secure
            if (blank) maybeDump(soft, secureHint)
            Shot(soft, blank, windowOnly, secure)
        } catch (e: Exception) {
            Log.e(TAG, "shot decode", e)
            null
        } finally {
            hwBmp.recycle()
        }
    }

    private fun closeQuiet(hw: HardwareBuffer) { try { hw.close() } catch (_: Exception) {} }

    private fun newPool(): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "he-vision").also { it.isDaemon = true } }

    private fun isContentBlank(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val x0 = (w * 0.10f).toInt()
        val x1 = (w * 0.90f).toInt()
        val y0 = (h * 0.10f).toInt()
        val y1 = (h * 0.80f).toInt()
        var dark = 0
        var n = 0
        var y = y0
        val stepY = maxOf(1, (y1 - y0) / 24)
        val stepX = maxOf(1, (x1 - x0) / 24)
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = bmp.getPixel(x, y)
                val v = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                if (v < 18) dark++
                n++
                x += stepX
            }
            y += stepY
        }
        if (n <= 0) return true

        return dark * 100 >= n * 88
    }

    private fun maybeDump(bmp: Bitmap, secure: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < 8000L) return
        lastDumpAt = now
        try {
            val dir = File(service.filesDir, "vision_debug").also { it.mkdirs() }
            val f = File(dir, if (secure) "last_secure.png" else "last_blank.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            Log.i(TAG, "dump ${f.name} ${bmp.width}x${bmp.height} secure=$secure")
        } catch (e: Exception) {
            Log.w(TAG, "dump fail", e)
        }
    }

    private fun pickAppWindowId(): Int {
        val wins = try { service.windows } catch (_: Exception) { return -1 }
        val b = screenBounds(service)
        var best = -1
        var bestLayer = -1
        val r = Rect()
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.getBoundsInScreen(r)
            if (r.width() < b.width() - 4 || r.height() < b.height() - 4) continue
            if (w.layer > bestLayer) {
                bestLayer = w.layer
                best = w.id
            }
        }
        return best
    }

    companion object {
        private const val TAG = "HeVision"

        private const val SECURE_WINDOW = 6
        private const val STUCK_MS = 2500L

        private const val ERR_STUCK = 1001
    }
}
