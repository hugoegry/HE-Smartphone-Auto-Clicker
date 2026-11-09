package com.he.he_smartphone_auto_clicker.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Region(val w: Int, val h: Int, val argb: IntArray, val ox: Int, val oy: Int)

data class Hit(val x: Int, val y: Int, val score: Float)

class Plane(val w: Int, val h: Int, val v: FloatArray)

object LumaOps {
    fun luma(argb: IntArray): FloatArray {
        val out = FloatArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((r * 77 + g * 150 + b * 29) shr 8).toFloat()
        }
        return out
    }

    fun greenExcess(p: Int): Float {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (g - max(r, b)).toFloat()
    }

    fun channel(argb: IntArray, shift: Int): FloatArray {
        val out = FloatArray(argb.size)
        for (i in argb.indices) out[i] = ((argb[i] shr shift) and 0xFF).toFloat()
        return out
    }

    fun box(src: FloatArray, w: Int, h: Int, s: Int): Plane {
        if (s <= 1) return Plane(w, h, src)
        val ow = w / s
        val oh = h / s
        val out = FloatArray(ow * oh)
        val inv = 1f / (s * s)
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                var sum = 0f
                val y0 = oy * s
                val x0 = ox * s
                for (dy in 0 until s) {
                    val row = (y0 + dy) * w + x0
                    for (dx in 0 until s) sum += src[row + dx]
                }
                out[oy * ow + ox] = sum * inv
            }
        }
        return Plane(ow, oh, out)
    }
}

class VisionTemplate(val w: Int, val h: Int, val argb: IntArray) {
    val luma: FloatArray = LumaOps.luma(argb)

    val mask: BooleanArray = iconMask(argb, w, h)

    val glyph: BooleanArray
    val hasGlyph: Boolean
    val meanR: Float
    val meanG: Float
    val meanB: Float
    val stdLuma: Float

    private val chans = arrayOfNulls<CenteredChannel>(3)
    private val coarseCache = HashMap<Int, CoarseTpl>()
    private var sampled: SampledTpl? = null

    init {
        require(w > 0 && h > 0 && argb.size == w * h) { "bad template" }
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var l = 0.0
        var l2 = 0.0
        for (i in argb.indices) {
            val p = argb[i]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            val lv = luma[i].toDouble()
            l += lv
            l2 += lv * lv
        }
        val n = argb.size.toDouble()
        meanR = (r / n).toFloat()
        meanG = (g / n).toFloat()
        meanB = (b / n).toFloat()
        val variance = (l2 / n - (l / n) * (l / n)).coerceAtLeast(0.0)
        stdLuma = sqrt(variance).toFloat()
        val glyphPx = BooleanArray(argb.size)
        var ng = 0
        var nc = 0
        var cardL = 0.0
        for (i in argb.indices) {
            if (!mask[i]) continue
            if (LumaOps.greenExcess(argb[i]) > 35f) {
                glyphPx[i] = true
                ng++
            } else {
                nc++
                cardL += luma[i]
            }
        }
        glyph = glyphPx
        val cardMean = if (nc > 0) (cardL / nc).toFloat() else 0f
        val ratio = if (ng + nc > 0) ng.toFloat() / (ng + nc) else 1f

        hasGlyph = ng >= 12 && nc >= 24 && cardMean >= 165f && ratio < 0.40f
    }

    class CenteredChannel(val c: FloatArray, val energy: Double)
    class CoarseTpl(val w: Int, val h: Int, val c: FloatArray, val energy: Double)
    class SampledTpl(val offs: IntArray, val xs: IntArray, val ys: IntArray, val c: FloatArray, val energy: Double)

    fun channelCentered(idx: Int): CenteredChannel {
        chans[idx]?.let { return it }
        val shift = when (idx) { 0 -> 16; 1 -> 8; else -> 0 }
        val raw = LumaOps.channel(argb, shift)
        var m = 0.0
        for (v in raw) m += v
        m /= raw.size
        var e = 0.0
        val c = FloatArray(raw.size)
        for (i in raw.indices) {
            val d = (raw[i] - m).toFloat()
            c[i] = d
            e += d.toDouble() * d
        }
        return CenteredChannel(c, e).also { chans[idx] = it }
    }

    fun coarse(s: Int): CoarseTpl {
        coarseCache[s]?.let { return it }
        val p = LumaOps.box(luma, w, h, s)
        var m = 0.0
        for (v in p.v) m += v
        m /= p.v.size
        var e = 0.0
        val c = FloatArray(p.v.size)
        for (i in p.v.indices) {
            val d = (p.v[i] - m).toFloat()
            c[i] = d
            e += d.toDouble() * d
        }
        return CoarseTpl(p.w, p.h, c, e).also { coarseCache[s] = it }
    }

    fun sampled(): SampledTpl {
        sampled?.let { return it }
        val stride = max(1, ceil(sqrt(w.toDouble() * h / SAMPLE_MAX)).toInt())
        val xsL = ArrayList<Int>()
        val ysL = ArrayList<Int>()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                xsL.add(x)
                ysL.add(y)
                x += stride
            }
            y += stride
        }
        val nAll = xsL.size
        val keep = ArrayList<Int>()
        for (i in 0 until nAll) {
            val off = ysL[i] * w + xsL[i]
            if (mask[off]) keep.add(i)
        }
        val use = if (keep.size >= 12) keep else (0 until nAll).toList()
        val n = use.size
        val xs = IntArray(n) { xsL[use[it]] }
        val ys = IntArray(n) { ysL[use[it]] }
        val offs = IntArray(n) { ys[it] * w + xs[it] }
        var m = 0.0
        for (i in 0 until n) m += luma[offs[i]]
        m /= n
        var e = 0.0
        val c = FloatArray(n)
        for (i in 0 until n) {
            val d = (luma[offs[i]] - m).toFloat()
            c[i] = d
            e += d.toDouble() * d
        }
        return SampledTpl(offs, xs, ys, c, e).also { sampled = it }
    }

    companion object {
        const val SAMPLE_MAX = 3500.0
        const val MIN_STD = 7f

        fun iconMask(argb: IntArray, w: Int, h: Int): BooleanArray {
            val m = BooleanArray(argb.size)
            val er = ArrayList<Int>()
            val eg = ArrayList<Int>()
            val eb = ArrayList<Int>()
            fun add(p: Int) {
                er.add((p shr 16) and 0xFF)
                eg.add((p shr 8) and 0xFF)
                eb.add(p and 0xFF)
            }
            for (x in 0 until w) {
                add(argb[x])
                add(argb[(h - 1) * w + x])
            }
            for (y in 1 until h - 1) {
                add(argb[y * w])
                add(argb[y * w + w - 1])
            }
            if (er.isEmpty()) {
                m.fill(true)
                return m
            }
            er.sort(); eg.sort(); eb.sort()
            val mr = er[er.size / 2]
            val mg = eg[eg.size / 2]
            val mb = eb[eb.size / 2]
            var n = 0
            for (i in argb.indices) {
                val p = argb[i]
                val dr = ((p shr 16) and 0xFF) - mr
                val dg = ((p shr 8) and 0xFF) - mg
                val db = (p and 0xFF) - mb
                val far = dr * dr + dg * dg + db * db > 28 * 28
                m[i] = far
                if (far) n++
            }
            if (n < argb.size * 0.12f) m.fill(true)
            return m
        }
    }
}

object TemplateMatcher {
    var lastNote: String = ""
    private const val COARSE_MAX_PX = 900
    private const val COARSE_MIN_DIM = 8
    private const val MAX_CANDIDATES = 8
    private var detailNote: String = ""

    private val SEARCH_SCALES = floatArrayOf(0.78f, 0.86f, 0.93f, 1f, 1.07f, 1.14f, 1.25f)

    private val SMALL_SCALES = floatArrayOf(0.62f, 0.74f, 0.86f, 1f, 1.14f, 1.28f, 1.42f)

    fun pickCoarseFactor(tw: Int, th: Int): Int {
        var s = (min(tw, th) / COARSE_MIN_DIM).coerceIn(1, 6)
        while (s < 12 && (tw / s) * (th / s) > COARSE_MAX_PX) s++

        while (s > 1 && (min(tw, th) / s) < 4) s--
        return s
    }

    fun find(region: Region, tpl: VisionTemplate, minScore: Float): Hit? {
        var best: Hit? = null
        val tight = region.w <= tpl.w * 4 + 16 && region.h <= tpl.h * 4 + 16

        val glyphZone = tpl.hasGlyph && region.w * region.h <= 200_000
        val scales = when {
            glyphZone -> floatArrayOf(0.45f, 0.60f, 0.75f, 0.90f, 1f, 1.2f, 1.5f, 1.9f)
            tight && min(tpl.w, tpl.h) <= 96 -> floatArrayOf(0.55f, 0.72f, 0.90f, 1f, 1.25f, 1.6f, 2.1f)
            tight -> floatArrayOf(0.85f, 1f, 1.2f)
            min(tpl.w, tpl.h) <= 96 -> SMALL_SCALES
            else -> SEARCH_SCALES
        }
        for (scale in scales) {
            val scaled = if (abs(scale - 1f) < 0.001f) tpl else scaleTemplate(tpl, scale) ?: continue
            if (region.w < scaled.w || region.h < scaled.h) continue
            val zoneTight = region.w <= scaled.w * 4 + 16 && region.h <= scaled.h * 4 + 16
            val hit = when {
                glyphZone && scaled.hasGlyph -> findGlyph(region, scaled)
                zoneTight -> findDense(region, scaled)
                else -> findExactScale(region, scaled, minScore)
            }
            if (hit == null) continue
            if (best == null || hit.score > best.score) {
                best = hit
                lastNote = "scale=$scale tight=$zoneTight $detailNote hit=${hit.score}"
            }
            if (hit.score >= 0.97f) break
        }
        if (best == null) lastNote = "nohit tight=$tight"
        return best
    }

    private fun findGlyph(region: Region, tpl: VisionTemplate): Hit? {
        val gxs = ArrayList<Int>()
        val gys = ArrayList<Int>()
        val tex = ArrayList<Float>()
        val cxs = ArrayList<Int>()
        val cys = ArrayList<Int>()
        for (i in tpl.argb.indices) {
            val x = i % tpl.w
            val y = i / tpl.w
            if (tpl.glyph[i]) {
                gxs.add(x); gys.add(y); tex.add(LumaOps.greenExcess(tpl.argb[i]))
            } else if (tpl.mask[i]) {
                cxs.add(x); cys.add(y)
            }
        }
        val ng = gxs.size
        val nc = cxs.size
        if (ng < 8 || nc < 8) return null
        var mt = 0.0
        for (v in tex) mt += v
        mt /= ng
        var eT = 0.0
        val tc = FloatArray(ng)
        for (i in 0 until ng) {
            val d = (tex[i] - mt).toFloat()
            tc[i] = d
            eT += d.toDouble() * d
        }
        if (eT < 1.0) return null
        val gx = IntArray(ng) { gxs[it] }
        val gy = IntArray(ng) { gys[it] }
        val cx = IntArray(nc) { cxs[it] }
        val cy = IntArray(nc) { cys[it] }
        val maxX = region.w - tpl.w
        val maxY = region.h - tpl.h
        if (maxX < 0 || maxY < 0) return null
        val step = if (maxX <= 36 && maxY <= 36) 1 else if (maxX > 90 || maxY > 90) 3 else 2
        var best = -2f
        var bx = 0
        var by = 0
        var y = 0
        while (y <= maxY) {
            var x = 0
            while (x <= maxX) {
                val sc = glyphAt(region, x, y, gx, gy, cx, cy, tc, eT)
                if (sc > best) { best = sc; bx = x; by = y }
                x += step
            }
            y += step
        }
        if (best < 0.50f) {
            detailNote = "glyph=$best"
            return null
        }
        var rb = best
        var rx = bx
        var ry = by
        for (yy in max(0, by - step)..min(maxY, by + step)) {
            for (xx in max(0, bx - step)..min(maxX, bx + step)) {
                val sc = glyphAt(region, xx, yy, gx, gy, cx, cy, tc, eT)
                if (sc > rb) { rb = sc; rx = xx; ry = yy }
            }
        }
        if (rb < 0.55f) return null
        detailNote = "glyph=$rb"
        return Hit(rx, ry, rb.coerceIn(0f, 1f))
    }

    private fun glyphAt(region: Region, x: Int, y: Int, gx: IntArray, gy: IntArray, cx: IntArray, cy: IntArray, tc: FloatArray, eT: Double): Float {
        val ng = gx.size
        val nc = cx.size
        var gSum = 0.0
        var sI2 = 0.0
        var num = 0.0
        for (i in 0 until ng) {
            val ex = LumaOps.greenExcess(region.argb[(y + gy[i]) * region.w + x + gx[i]]).toDouble()
            gSum += ex
            sI2 += ex * ex
            num += ex * tc[i]
        }
        var cSum = 0.0
        var lSum = 0.0
        for (i in 0 until nc) {
            val p = region.argb[(y + cy[i]) * region.w + x + cx[i]]
            cSum += LumaOps.greenExcess(p)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lSum += (r * 77 + g * 150 + b * 29) shr 8
        }
        val gm = gSum / ng
        val cm = cSum / nc
        val cl = lSum / nc
        if (cl < 150.0 || gm < 28.0 || gm < cm + 18.0) return -1f
        val varI = sI2 - gSum * gSum / ng
        if (varI < 1e-3 * ng) return -1f
        return (num / sqrt(varI * eT)).toFloat()
    }

    private fun findDense(region: Region, tpl: VisionTemplate): Hit? {
        val img = LumaOps.luma(region.argb)
        val sample = tpl.sampled()
        val maxX = region.w - tpl.w
        val maxY = region.h - tpl.h
        if (maxX < 0 || maxY < 0) return null
        val step = if (maxX <= 28 && maxY <= 28) 1 else 2
        var best = -2f
        var bx = 0
        var by = 0
        var y = 0
        while (y <= maxY) {
            var x = 0
            while (x <= maxX) {
                val z = sampleScore(img, region.w, x, y, sample)
                if (z > best) {
                    best = z
                    bx = x
                    by = y
                }
                x += step
            }
            y += step
        }
        if (best < 0.22f) return null
        return refine(img, region, tpl, sample, bx, by, step + 2)
    }

    private fun sampleScore(img: FloatArray, w: Int, x: Int, y: Int, sample: VisionTemplate.SampledTpl): Float {
        val n = sample.offs.size
        val nD = n.toDouble()
        var sI = 0.0
        var sI2 = 0.0
        var num = 0.0
        for (i in 0 until n) {
            val v = img[(y + sample.ys[i]) * w + x + sample.xs[i]].toDouble()
            sI += v
            sI2 += v * v
            num += v * sample.c[i]
        }
        val varI = sI2 - sI * sI / nD
        if (varI < 1e-3 * nD || sample.energy < 1e-3) return -1f
        return (num / sqrt(varI * sample.energy)).toFloat()
    }

    private fun findExactScale(region: Region, tpl: VisionTemplate, minScore: Float): Hit? {
        val s = pickCoarseFactor(tpl.w, tpl.h)
        val img = LumaOps.luma(region.argb)
        val cands = coarseCandidates(img, region.w, region.h, tpl, s, max(0.28f, minScore - 0.40f))
        if (cands.isEmpty()) return null
        var best: Hit? = null
        val sample = tpl.sampled()
        for ((cx, cy) in cands) {
            val hit = refine(img, region, tpl, sample, cx * s, cy * s, s + 2) ?: continue
            if (best == null || hit.score > best.score) best = hit
            if (hit.score >= 0.985f) break
        }
        return best
    }

    private fun scaleTemplate(tpl: VisionTemplate, scale: Float): VisionTemplate? {
        val nw = max(8, (tpl.w * scale).roundToInt())
        val nh = max(8, (tpl.h * scale).roundToInt())
        if (nw == tpl.w && nh == tpl.h) return tpl
        if (nw * nh > 220_000) return null
        val out = IntArray(nw * nh)
        val xRatio = (tpl.w - 1).toFloat() / (nw - 1).coerceAtLeast(1).toFloat()
        val yRatio = (tpl.h - 1).toFloat() / (nh - 1).coerceAtLeast(1).toFloat()
        for (y in 0 until nh) {
            val fy = y * yRatio
            val y0 = fy.toInt().coerceIn(0, tpl.h - 1)
            val y1 = (y0 + 1).coerceAtMost(tpl.h - 1)
            val ty = fy - y0
            for (x in 0 until nw) {
                val fx = x * xRatio
                val x0 = fx.toInt().coerceIn(0, tpl.w - 1)
                val x1 = (x0 + 1).coerceAtMost(tpl.w - 1)
                val tx = fx - x0
                out[y * nw + x] = bilerpArgb(tpl.argb[y0 * tpl.w + x0], tpl.argb[y0 * tpl.w + x1], tpl.argb[y1 * tpl.w + x0], tpl.argb[y1 * tpl.w + x1], tx, ty)
            }
        }
        return try { VisionTemplate(nw, nh, out) } catch (_: Exception) { null }
    }

    private fun bilerpArgb(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Int {
        fun chan(shift: Int): Int {
            val v00 = (c00 shr shift) and 0xFF
            val v10 = (c10 shr shift) and 0xFF
            val v01 = (c01 shr shift) and 0xFF
            val v11 = (c11 shr shift) and 0xFF
            val a = v00 + (v10 - v00) * tx
            val b = v01 + (v11 - v01) * tx
            return (a + (b - a) * ty).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (chan(16) shl 16) or (chan(8) shl 8) or chan(0)
    }

    private fun coarseCandidates(img: FloatArray, w: Int, h: Int, tpl: VisionTemplate, s: Int, thr: Float): List<Pair<Int, Int>> {
        val ci = LumaOps.box(img, w, h, s)
        val ct = tpl.coarse(s)
        val cw = ci.w
        val chh = ci.h
        if (cw < ct.w || chh < ct.h || ct.energy < 1e-3) return emptyList()
        val px = ci.v
        val iw = cw + 1
        val sum = DoubleArray(iw * (chh + 1))
        val sq = DoubleArray(iw * (chh + 1))
        for (y in 0 until chh) {
            var rs = 0.0
            var rq = 0.0
            for (x in 0 until cw) {
                val v = px[y * cw + x].toDouble()
                rs += v
                rq += v * v
                sum[(y + 1) * iw + x + 1] = sum[y * iw + x + 1] + rs
                sq[(y + 1) * iw + x + 1] = sq[y * iw + x + 1] + rq
            }
        }
        val outW = cw - ct.w + 1
        val outH = chh - ct.h + 1
        val scores = FloatArray(outW * outH)
        val tw = ct.w
        val th = ct.h
        val n = (tw * th).toDouble()
        val tc = ct.c
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val a = y * iw + x
                val b = y * iw + x + tw
                val c = (y + th) * iw + x
                val d = (y + th) * iw + x + tw
                val sI = sum[d] - sum[b] - sum[c] + sum[a]
                val sI2 = sq[d] - sq[b] - sq[c] + sq[a]
                val varI = sI2 - sI * sI / n
                if (varI < 1e-3 * n) {
                    scores[y * outW + x] = -1f
                    continue
                }
                var num = 0f
                for (ty in 0 until th) {
                    val ib = (y + ty) * cw + x
                    val tb = ty * tw
                    var row = 0f
                    for (tx in 0 until tw) row += px[ib + tx] * tc[tb + tx]
                    num += row
                }
                scores[y * outW + x] = (num / sqrt(varI * ct.energy)).toFloat()
            }
        }

        val out = ArrayList<Pair<Int, Int>>()
        val sup = max(2, min(tw, th) / 2)
        val mark = BooleanArray(scores.size)
        repeat(MAX_CANDIDATES) {
            var bi = -1
            var bv = thr
            for (i in scores.indices) {
                if (!mark[i] && scores[i] > bv) {
                    bv = scores[i]
                    bi = i
                }
            }
            if (bi < 0) return out
            val bx = bi % outW
            val by = bi / outW
            out.add(bx to by)
            for (yy in max(0, by - sup)..min(outH - 1, by + sup)) {
                for (xx in max(0, bx - sup)..min(outW - 1, bx + sup)) mark[yy * outW + xx] = true
            }
        }
        return out
    }

    private fun refine(img: FloatArray, region: Region, tpl: VisionTemplate, sample: VisionTemplate.SampledTpl, x0: Int, y0: Int, radius: Int): Hit? {
        val w = region.w
        val maxX = region.w - tpl.w
        val maxY = region.h - tpl.h
        var bestScore = -2f
        var bx = -1
        var by = -1
        val n = sample.offs.size
        val nD = n.toDouble()
        for (y in max(0, y0 - radius)..min(maxY, y0 + radius)) {
            for (x in max(0, x0 - radius)..min(maxX, x0 + radius)) {
                var sI = 0.0
                var sI2 = 0.0
                var num = 0.0
                for (i in 0 until n) {
                    val v = img[(y + sample.ys[i]) * w + x + sample.xs[i]].toDouble()
                    sI += v
                    sI2 += v * v
                    num += v * sample.c[i]
                }
                val varI = sI2 - sI * sI / nD
                if (varI < 1e-3 * nD || sample.energy < 1e-3) continue
                val z = (num / sqrt(varI * sample.energy)).toFloat()
                if (z > bestScore) {
                    bestScore = z
                    bx = x
                    by = y
                }
            }
        }
        if (bx < 0) return null
        val rgb = exactRgb(region, tpl, bx, by) ?: return null
        val shape = bestScore.coerceIn(-1f, 1f)

        if (rgb < 0.40f && shape < 0.70f) return null

        val score = if (rgb >= 0.42f && shape >= 0.72f) max(rgb, min(shape * 0.90f, rgb + 0.30f)) else rgb
        detailNote = "rgb=$rgb shape=$shape"
        return Hit(bx, by, score.coerceIn(-1f, 1f))
    }

    fun exactRgb(region: Region, tpl: VisionTemplate, x: Int, y: Int): Float? {
        val keep = tpl.mask
        var total = 0f
        var meanDiff = 0f
        var chans = 0
        for (ch in 0 until 3) {
            val shift = when (ch) { 0 -> 16; 1 -> 8; else -> 0 }
            var sI = 0.0
            var sT = 0.0
            var sI2 = 0.0
            var sT2 = 0.0
            var cross = 0.0
            var n = 0
            for (ty in 0 until tpl.h) {
                val rowImg = (y + ty) * region.w + x
                val rowT = ty * tpl.w
                for (tx in 0 until tpl.w) {
                    val ti = rowT + tx
                    if (!keep[ti]) continue
                    val v = ((region.argb[rowImg + tx] shr shift) and 0xFF).toDouble()
                    val u = ((tpl.argb[ti] shr shift) and 0xFF).toDouble()
                    sI += v
                    sT += u
                    sI2 += v * v
                    sT2 += u * u
                    cross += v * u
                    n++
                }
            }
            if (n < 8) continue
            val nd = n.toDouble()
            val varI = sI2 - sI * sI / nd
            val varT = sT2 - sT * sT / nd
            val num = cross - sI * sT / nd
            val z = if (varI < 1e-3 * nd || varT < 1e-3 * nd) 0f else (num / sqrt(varI * varT)).toFloat()
            total += z
            meanDiff += abs((sI / nd).toFloat() - (sT / nd).toFloat())
            chans++
        }
        if (chans == 0) return null
        val avg = (total / chans.toFloat()).coerceIn(-1f, 1f)
        val mean = meanDiff / chans

        if (mean > 80f && avg < 0.72f) return null

        if (avg < 0.60f) return null
        return avg
    }
}
