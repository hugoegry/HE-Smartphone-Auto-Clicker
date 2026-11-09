package com.he.he_smartphone_auto_clicker.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class OcrPage(val text: String, val lines: String)

object TextFinder {
    private const val TAG = "HeOcr"
    private const val WAIT_MS = 2200L
    private const val WARM_MS = 12000L

    @Volatile private var recognizerRef: TextRecognizer? = null
    private val recognizer: TextRecognizer
        get() = recognizerRef ?: synchronized(this) { recognizerRef ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizerRef = it } }
    @Volatile private var ocrPool: ExecutorService = newOcrPool()
    @Volatile private var warmStarted = false
    @Volatile private var failUntil = 0L
    @Volatile var dumpDir: java.io.File? = null
    private var lastDumpAt = 0L

    fun warm() {
        if (warmStarted) return
        warmStarted = true
        val b = Bitmap.createBitmap(80, 32, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        val page = see(b, 0f, 0f, 1f, 1f, WARM_MS)
        b.recycle()
        Log.i(TAG, "warm ${page.lines.ifBlank { page.text.ifBlank { "vide" } }}")
    }

    fun see(src: Bitmap, l: Float, t: Float, r: Float, b: Float, waitMs: Long = WAIT_MS): OcrPage {
        val now = SystemClock.elapsedRealtime()
        if (now < failUntil) return OcrPage("", "ocr en pause")
        val crop = try { cropRaw(src, l, t, r, b) } catch (e: Exception) {
            Log.w(TAG, "crop", e)
            return OcrPage("", "crop")
        } ?: return OcrPage("", "zone vide")
        val bands = try { textBands(crop) } catch (e: Exception) {
            Log.w(TAG, "bandes", e)
            emptyList()
        }
        return try {
            maybeDump(crop, "last_raw.jpg")
            val pages = ArrayList<OcrPage>()
            if (bands.isEmpty()) {
                Log.i(TAG, "pas de ligne texte")
            } else {
                for ((i, band) in bands.withIndex()) {
                    val page = read(band, waitMs)
                    Log.i(TAG, "bande$i ${band.width}x${band.height} \"${page.text.replace('\n', ' ')}\"")
                    maybeDump(band, "band$i.jpg", true)
                    if (page.lines == "timeout") {
                        for (j in i + 1 until bands.size) try { bands[j].recycle() } catch (_: Exception) {}
                        Log.w(TAG, "bande gardee, ocr bloque")
                        return OcrPage(pages.map { it.text }.filter { it.isNotBlank() }.joinToString("\n"), "timeout")
                    }
                    band.recycle()
                    pages.add(page)
                }
            }
            val gray = try { grayAbove(crop) } catch (e: Exception) { Log.w(TAG, "gris", e); null }
            if (gray != null) {
                val page = read(gray, waitMs)
                Log.i(TAG, "gris ${gray.width}x${gray.height} \"${page.text.replace('\n', ' ')}\"")
                maybeDump(gray, "gray.jpg", true)
                if (page.lines == "timeout") {
                    Log.w(TAG, "gris garde, ocr bloque")
                    return OcrPage(pages.map { it.text }.filter { it.isNotBlank() }.joinToString("\n"), "timeout")
                }
                gray.recycle()
                pages.add(page)
            }
            val text = pages.map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
            val lines = pages.joinToString(" || ") { it.lines }
            Log.i(TAG, "pass ${bands.size} bande \"${text.replace('\n', ' ')}\"")
            OcrPage(text, lines)
        } catch (e: Throwable) {
            Log.e(TAG, "see", e)
            OcrPage("", "see")
        } finally { crop.recycle() }
    }

    private fun textBands(src: Bitmap): List<Bitmap> {
        val w = src.width
        val h = src.height
        if (w < 32 || h < 28) return emptyList()
        val x0 = (w * 0.18f).toInt().coerceIn(0, w - 2)
        val x1 = (w * 0.82f).toInt().coerceIn(x0 + 2, w)
        val span = ((x1 - x0) / 2).coerceAtLeast(1)
        val letterMax = (span / 6).coerceAtLeast(8)
        val all = IntArray(w * h)
        src.getPixels(all, 0, w, 0, 0, w, h)
        val rows = IntArray(h)
        val runs = IntArray(h)
        for (y in 0 until h) {
            var white = 0
            var nrun = 0
            var on = false
            var runStart = x0
            val row = y * w
            var x = x0
            while (x < x1) {
                val p = all[row + x]
                val rr = (p shr 16) and 255
                val gg = (p shr 8) and 255
                val bb = p and 255
                val mx = maxOf(rr, gg, bb)
                val mn = minOf(rr, gg, bb)
                val hit = mx >= 170 && mx - mn <= 55
                if (hit) {
                    white++
                    if (!on) { on = true; runStart = x }
                } else if (on) {
                    on = false
                    val rw = x - runStart
                    if (rw in 2..letterMax) nrun++
                }
                x += 2
            }
            if (on) {
                val rw = x1 - runStart
                if (rw in 2..letterMax) nrun++
            }
            rows[y] = white
            runs[y] = nrun
        }
        val minWhite = maxOf(12, (span * 0.018f).toInt())
        val found = ArrayList<IntArray>()
        var s = -1
        var score = 0
        var runSum = 0
        var gap = 0
        for (y in 0..h) {
            val v = if (y < h) rows[y] else 0
            if (y < h && v >= minWhite) {
                if (s < 0) s = y
                score += v
                runSum += runs[y]
                gap = 0
            } else if (s >= 0) {
                gap++
                if (gap > 8 || y == h) {
                    found.add(intArrayOf(s, y - gap, score, runSum))
                    s = -1
                    score = 0
                    runSum = 0
                    gap = 0
                }
            }
        }
        val merged = ArrayList<IntArray>()
        for (bn in found) {
            val last = merged.lastOrNull()
            if (last != null && bn[0] - last[1] <= 26) {
                last[1] = bn[1]
                last[2] += bn[2]
                last[3] += bn[3]
            } else merged.add(bn)
        }
        val kept = ArrayList<IntArray>()
        for (bn in merged) {
            val bh = (bn[1] - bn[0] + 1).coerceAtLeast(1)
            if (bh < 8) continue
            kept.add(bn)
        }

        val slices = ArrayList<IntArray>()
        for (bn in kept) {
            val yFrom = bn[0].coerceIn(0, h - 1)
            val yTo = bn[1].coerceIn(yFrom + 1, h)
            val bh = (yTo - yFrom).coerceAtLeast(1)
            var bestY = yFrom
            var best = 0
            var sum = 0
            for (yy in yFrom until yTo) {
                sum += runs[yy]
                if (runs[yy] > best) { best = runs[yy]; bestY = yy }
            }
            val avg = sum / bh
            if (best < 8) continue
            if (bh > 48 && best < avg * 2) continue
            val top = if (bh <= 48) yFrom else (bestY - 14).coerceAtLeast(0)
            val bot = if (bh <= 48) yTo else (bestY + 16).coerceAtMost(h)
            slices.add(intArrayOf(top, bot, best, best))
        }
        val pick = slices.sortedByDescending { it[2] }.take(2).sortedBy { it[0] }
        Log.i(TAG, "bandes crop ${w}x${h} min=$minWhite trouve=${found.size} garde=${pick.size}")
        return pick.map { bn ->
            val top = (bn[0] - 4).coerceAtLeast(0)
            val bot = (bn[1] + 6).coerceAtMost(h)
            Log.i(TAG, "bande y=$top-$bot score=${bn[2]}")
            blow(src, x0, top, x1, bot)
        }
    }

    private fun grayAbove(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        if (w < 40 || h < 48) return null
        val x0 = (w * 0.22f).toInt().coerceIn(0, w - 2)
        val x1 = (w * 0.78f).toInt().coerceIn(x0 + 2, w)
        var peak = -1
        var peakRuns = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var runs = 0
            var on = false
            var x = x0
            while (x < x1) {
                val p = row[x]
                val rr = (p shr 16) and 255
                val gg = (p shr 8) and 255
                val bb = p and 255
                val hit = maxOf(rr, gg, bb) >= 185 && maxOf(rr, gg, bb) - minOf(rr, gg, bb) <= 40
                if (hit) {
                    if (!on) { on = true; runs++ }
                } else on = false
                x += 2
            }
            if (runs > peakRuns) { peakRuns = runs; peak = y }
        }
        if (peakRuns < 18 || peak < 70) return null
        val bot = (peak - 50).coerceAtLeast(20)
        val top = (bot - 42).coerceAtLeast(0)
        if (bot - top < 16) return null
        val gx0 = (w * 0.26f).toInt()
        val gx1 = (w * 0.74f).toInt()
        Log.i(TAG, "gris y=$top-$bot peak=$peak runs=$peakRuns")
        return stretch(src, gx0, top, gx1, bot)
    }

    private fun stretch(src: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Bitmap {
        val cw = (x1 - x0).coerceAtLeast(1)
        val ch = (y1 - y0).coerceAtLeast(1)
        val px = IntArray(cw * ch)
        src.getPixels(px, 0, cw, x0, y0, cw, ch)
        val lums = ArrayList<Int>(px.size / 3)
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            if (maxOf(r, g, b) - minOf(r, g, b) <= 45) lums.add((r * 3 + g * 6 + b) / 10)
            i += 3
        }
        lums.sort()
        val lo = if (lums.isEmpty()) 24 else lums[(lums.size * 0.55f).toInt().coerceIn(0, lums.size - 1)]
        val span = 46
        val outPx = IntArray(px.size)
        for (k in px.indices) {
            val p = px[k]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val lum = (r * 3 + g * 6 + b) / 10
            var v = (lum - lo) * 255 / span
            if (v < 0) v = 0
            if (v > 255) v = 255
            outPx[k] = (0xFF000000.toInt()) or (v shl 16) or (v shl 8) or v
        }
        val gray = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        gray.setPixels(outPx, 0, cw, 0, 0, cw, ch)
        var scale = 160f / ch
        if (scale < 3.2f) scale = 3.2f
        var tw = (cw * scale).toInt().coerceAtLeast(1)
        var th = (ch * scale).toInt().coerceAtLeast(1)
        if (tw > 3200) {
            val kk = 3200f / tw
            tw = 3200
            th = (th * kk).toInt().coerceAtLeast(96)
        }
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(gray, Rect(0, 0, cw, ch), Rect(0, 0, tw, th), Paint(Paint.FILTER_BITMAP_FLAG))
        gray.recycle()
        return out
    }

    private fun blow(src: Bitmap, x: Int, y: Int, r: Int, b: Int): Bitmap {
        val cw = (r - x).coerceAtLeast(1)
        val ch = (b - y).coerceAtLeast(1)
        var scale = 110f / ch
        if (scale < 2.6f) scale = 2.6f
        var tw = (cw * scale).toInt().coerceAtLeast(1)
        var th = (ch * scale).toInt().coerceAtLeast(1)
        if (tw > 2800) {
            val k = 2800f / tw
            tw = 2800
            th = (th * k).toInt().coerceAtLeast(72)
        }
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val c = 1.12f
        val shift = 128f * (1f - c)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, shift, 0f, c, 0f, 0f, shift, 0f, 0f, c, 0f, shift, 0f, 0f, 0f, 1f, 0f)))
        canvas.drawBitmap(src, Rect(x, y, r, b), Rect(0, 0, tw, th), paint)
        return out
    }

    private fun maybeDump(bmp: Bitmap, name: String, always: Boolean = false) {
        val dir = dumpDir ?: return
        if (bmp.isRecycled) return
        val now = SystemClock.elapsedRealtime()
        if (!always && now - lastDumpAt < 1200L) return
        if (!always) lastDumpAt = now
        try {
            java.io.FileOutputStream(java.io.File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) {
            Log.w(TAG, "dump", e)
        }
    }

    private fun cropRaw(src: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap? {
        val sw = src.width
        val sh = src.height
        if (sw < 8 || sh < 8) return null
        val x0 = (l.coerceIn(0f, 1f) * sw).toInt().coerceIn(0, sw - 1)
        val y0 = (t.coerceIn(0f, 1f) * sh).toInt().coerceIn(0, sh - 1)
        val x1 = (r.coerceIn(0f, 1f) * sw).toInt().coerceIn(x0 + 1, sw)
        val y1 = (b.coerceIn(0f, 1f) * sh).toInt().coerceIn(y0 + 1, sh)
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 8 || ch < 8) return null
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(x0, y0, x1, y1), Rect(0, 0, cw, ch), null)
        return out
    }

    private fun read(bmp: Bitmap, waitMs: Long): OcrPage {
        val client = recognizer
        val pool = ocrPool
        val fut = try { pool.submit<OcrPage> { readNow(client, bmp, waitMs) } } catch (e: Exception) {
            Log.w(TAG, "ocr submit", e)
            return OcrPage("", "submit")
        }
        return try {
            fut.get(waitMs + 500L, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            Log.w(TAG, "ocr timeout")
            failUntil = SystemClock.elapsedRealtime() + 700L
            dropOcr(pool, client)
            OcrPage("", "timeout")
        } catch (e: Exception) {
            val msg = (e.cause?.message ?: e.message ?: e.javaClass.simpleName).take(160)
            Log.w(TAG, "ocr fail $msg")
            OcrPage("", msg)
        }
    }

    private fun readNow(client: TextRecognizer, bmp: Bitmap, waitMs: Long): OcrPage {
        return try {
            val task = client.process(InputImage.fromBitmap(bmp, 0))
            val res = Tasks.await(task, waitMs, TimeUnit.MILLISECONDS)
            val lines = StringBuilder()
            for (block in res.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox
                    if (lines.isNotEmpty()) lines.append(" | ")
                    lines.append(line.text)
                    if (box != null) lines.append(" @").append(box.left).append(',').append(box.top)
                }
            }
            val text = res.text ?: ""
            Log.i(TAG, "lu ${bmp.width}x${bmp.height} \"$text\"")
            OcrPage(text, lines.toString())
        } catch (e: Exception) {
            val msg = (e.cause?.message ?: e.message ?: e.javaClass.simpleName).take(160)
            if (msg.contains("download", true)) failUntil = SystemClock.elapsedRealtime() + 800L
            Log.w(TAG, "ocr fail $msg")
            OcrPage("", msg)
        }
    }

    @Synchronized
    private fun dropOcr(deadPool: ExecutorService, deadClient: TextRecognizer) {
        if (ocrPool === deadPool) ocrPool = newOcrPool()
        if (recognizerRef === deadClient) recognizerRef = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try { deadPool.shutdownNow() } catch (_: Exception) {}
        Log.w(TAG, "client ocr jete")
    }

    private fun newOcrPool(): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "he-ocr").also { it.isDaemon = true } }
}
