package com.he.he_smartphone_auto_clicker.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TemplateMatcherTest {
    private class Img(val w: Int, val h: Int, val px: IntArray) {
        fun crop(x: Int, y: Int, cw: Int, ch: Int): IntArray {
            val out = IntArray(cw * ch)
            for (j in 0 until ch) for (i in 0 until cw) out[j * cw + i] = px[(y + j) * w + x + i]
            return out
        }
        fun region(x: Int, y: Int, rw: Int, rh: Int): Region {
            val x0 = x.coerceIn(0, w - 1)
            val y0 = y.coerceIn(0, h - 1)
            val x1 = (x + rw).coerceIn(x0 + 1, w)
            val y1 = (y + rh).coerceIn(y0 + 1, h)
            return Region(x1 - x0, y1 - y0, crop(x0, y0, x1 - x0, y1 - y0), x0, y0)
        }
        fun copy() = Img(w, h, px.copyOf())
    }

    private data class Spot(val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

    private val spots = listOf(
        Spot("boat", 350, 440, 230, 330),
        Spot("greenIcon", 982, 108, 30, 30),
        Spot("tipText", 297, 122, 418, 31),
        Spot("steeredBy", 430, 58, 160, 20),
        Spot("viser", 636, 400, 80, 16)
    )

    private fun load(): Img {
        val bytes = (javaClass.getResourceAsStream("/vision/boat_scene.ppm") ?: error("missing test image")).readBytes()
        var pos = 0
        fun token(): String {
            while (bytes[pos].toInt().toChar().isWhitespace()) pos++
            val sb = StringBuilder()
            while (!bytes[pos].toInt().toChar().isWhitespace()) sb.append(bytes[pos++].toInt().toChar())
            return sb.toString()
        }
        check(token() == "P6")
        val w = token().toInt()
        val h = token().toInt()
        token()
        pos++
        val px = IntArray(w * h)
        for (i in px.indices) {
            val r = bytes[pos + i * 3].toInt() and 0xFF
            val g = bytes[pos + i * 3 + 1].toInt() and 0xFF
            val b = bytes[pos + i * 3 + 2].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Img(w, h, px)
    }

    private fun tpl(img: Img, s: Spot) = VisionTemplate(s.w, s.h, img.crop(s.x, s.y, s.w, s.h))

    @Test fun `exact crops are found at the same place`() {
        val img = load()
        val full = img.region(0, 0, img.w, img.h)
        for (s in spots) {
            val hit = TemplateMatcher.find(full, tpl(img, s), 0.85f)
            assertNotNull("no hit for ${s.name}", hit)
            println("exact ${s.name}: score=${"%.3f".format(hit!!.score)} at ${hit.x},${hit.y} expected ${s.x},${s.y}")
            assertTrue("${s.name} score ${hit.score}", hit.score >= 0.97f)
            assertTrue("${s.name} pos ${hit.x},${hit.y}", abs(hit.x - s.x) <= 1 && abs(hit.y - s.y) <= 1)
        }
    }

    @Test fun `zone around the element finds it with offset math`() {
        val img = load()
        for (s in spots) {
            val pad = 60
            val zone = img.region(s.x - pad, s.y - pad, s.w + pad * 2, s.h + pad * 2)
            val hit = TemplateMatcher.find(zone, tpl(img, s), 0.85f)
            assertNotNull("no hit zone ${s.name}", hit)
            assertTrue("${s.name} zone score ${hit!!.score}", hit.score >= 0.97f)
            assertEquals(s.x, zone.ox + hit.x)
            assertEquals(s.y, zone.oy + hit.y)
        }
    }

    private fun degrade(src: Img, gain: Float, offset: Int, sigma: Float, blur: Boolean, seed: Long): Img {
        val rnd = Random(seed)
        val out = src.copy()
        if (blur) {
            val tmp = out.px.copyOf()
            for (y in 1 until out.h - 1) for (x in 1 until out.w - 1) {
                var r = 0; var g = 0; var b = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val p = tmp[(y + dy) * out.w + x + dx]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                }
                out.px[y * out.w + x] = (0xFF shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
            }
        }
        for (i in out.px.indices) {
            val p = out.px[i]
            fun ch(v: Int): Int = ((v * gain + offset + rnd.nextGaussian() * sigma).toInt()).coerceIn(0, 255)
            out.px[i] = (0xFF shl 24) or (ch((p shr 16) and 0xFF) shl 16) or (ch((p shr 8) and 0xFF) shl 8) or ch(p and 0xFF)
        }
        return out
    }

    @Test fun `robust to noise brightness and blur`() {
        val img = load()
        val cases = listOf(
            Triple("noise6", degrade(img, 1f, 0, 6f, false, 1), 0.85f),
            Triple("bright", degrade(img, 1.15f, 12, 2f, false, 2), 0.85f),
            Triple("dark", degrade(img, 0.8f, -10, 2f, false, 3), 0.85f),
            Triple("blur", degrade(img, 1f, 0, 3f, true, 4), 0.75f)
        )
        for ((label, variant, min) in cases) {
            val full = variant.region(0, 0, variant.w, variant.h)
            for (s in spots) {
                val hit = TemplateMatcher.find(full, tpl(img, s), 0.8f)
                assertNotNull("$label ${s.name} nohit", hit)
                println("$label ${s.name}: score=${"%.3f".format(hit!!.score)} at ${hit.x},${hit.y}")
                assertTrue("$label ${s.name} score ${hit.score} < $min", hit.score >= min)
                assertTrue("$label ${s.name} pos", abs(hit.x - s.x) <= 2 && abs(hit.y - s.y) <= 2)
            }
        }
    }

    @Test fun `absent element is rejected`() {
        val img = load()
        for (s in spots) {
            val blank = img.copy()
            val pad = 4
            for (y in max(0, s.y - pad) until min(img.h, s.y + s.h + pad)) {
                for (x in max(0, s.x - pad) until min(img.w, s.x + s.w + pad)) {
                    val v = 90 + (x * 3 + y * 2) % 40
                    blank.px[y * img.w + x] = (0xFF shl 24) or (v shl 16) or ((v + 10) shl 8) or (v + 40)
                }
            }
            val full = blank.region(0, 0, blank.w, blank.h)
            val hit = TemplateMatcher.find(full, tpl(img, s), 0.85f)
            val score = hit?.score ?: -1f
            println("absent ${s.name}: best=${"%.3f".format(score)}")
            assertTrue("false positive ${s.name} score=$score", score < 0.80f)
        }
    }

    @Test fun `different elements do not cross match`() {
        val img = load()
        for (a in spots) for (b in spots) {
            if (a === b) continue
            val hit = TemplateMatcher.find(img.region(b.x - 10, b.y - 10, b.w + 20, b.h + 20).takeIf { it.w >= a.w && it.h >= a.h } ?: continue, tpl(img, a), 0.85f)
            val score = hit?.score ?: -1f
            assertTrue("${a.name} matched inside ${b.name} score=$score", score < 0.85f)
        }
    }

    @Test fun `phone sized search is fast enough`() {
        val img = load()

        val w = 1080
        val h = 2340
        val px = IntArray(w * h) { (0xFF shl 24) or 0x101018 }
        val ox = 28
        val oy = 1100
        for (y in 0 until img.h) for (x in 0 until img.w) px[(oy + y) * w + ox + x] = img.px[y * img.w + x]
        val full = Region(w, h, px, 0, 0)
        for (s in spots) {
            val t = tpl(img, s)
            TemplateMatcher.find(full, t, 0.85f)
            val t0 = System.nanoTime()
            val hit = TemplateMatcher.find(full, t, 0.85f)
            val ms = (System.nanoTime() - t0) / 1e6
            println("fullscreen ${s.name}: ${"%.0f".format(ms)} ms score=${"%.3f".format(hit?.score ?: -1f)}")
            assertNotNull(hit)
            assertTrue(hit!!.score >= 0.97f)
            assertEquals(ox + s.x, hit.x)
            assertEquals(oy + s.y, hit.y)
            assertTrue("too slow ${s.name} $ms ms", ms < 900)
        }
    }

    @Test fun `flat template is flagged by std`() {
        val flat = VisionTemplate(40, 40, IntArray(1600) { 0xFF202020.toInt() })
        assertTrue(flat.stdLuma < VisionTemplate.MIN_STD)
        val img = load()
        for (s in spots) assertTrue("${s.name} std=${tpl(img, s).stdLuma}", tpl(img, s).stdLuma >= VisionTemplate.MIN_STD)
    }
}
