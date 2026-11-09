package com.he.he_smartphone_auto_clicker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.he.he_smartphone_auto_clicker.data.DetectorFiles
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class Found(val x: Int, val y: Int, val score: Float)

object VisionMatch {
    private val cache = HashMap<Long, Pair<Long, VisionTemplate>>()
    private val viewsCache = HashMap<Long, Pair<String, List<VisionTemplate>>>()

    fun templateFromBitmap(bmp: Bitmap): VisionTemplate {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return VisionTemplate(bmp.width, bmp.height, px)
    }

    @Synchronized
    fun templateFor(ctx: Context, id: Long): VisionTemplate? {
        val stamp = DetectorFiles.file(ctx, id).lastModified()
        cache[id]?.let { if (it.first == stamp) return it.second }
        val bmp = DetectorFiles.load(ctx, id) ?: return null
        val tpl = try { templateFromBitmap(bmp) } catch (_: Exception) { null } finally { bmp.recycle() }
        if (tpl != null) cache[id] = stamp to tpl
        return tpl
    }

    @Synchronized
    fun templatesFor(ctx: Context, id: Long): List<VisionTemplate> {
        val files = DetectorFiles.viewFiles(ctx, id)
        if (files.isEmpty()) return emptyList()
        val stamp = files.joinToString("|") { "${it.name}:${it.lastModified()}:${it.length()}" }
        viewsCache[id]?.let { if (it.first == stamp) return it.second }
        val list = ArrayList<VisionTemplate>()
        for (f in files) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: continue
            try { list.add(templateFromBitmap(bmp)) } catch (_: Exception) {} finally { bmp.recycle() }
        }
        viewsCache[id] = stamp to list
        return list
    }

    @Synchronized
    fun forget(id: Long) { cache.remove(id); viewsCache.remove(id) }

    fun zoneRect(bmpW: Int, bmpH: Int, l: Float, t: Float, r: Float, b: Float, tw: Int, th: Int): IntArray {
        var x0 = floor(l.coerceIn(0f, 1f) * bmpW).toInt()
        var y0 = floor(t.coerceIn(0f, 1f) * bmpH).toInt()
        var x1 = ceil(r.coerceIn(0f, 1f) * bmpW).toInt()
        var y1 = ceil(b.coerceIn(0f, 1f) * bmpH).toInt()

        val padX = 3
        val padY = 3
        x0 -= padX
        y0 -= padY
        x1 += padX
        y1 += padY
        if (x1 - x0 < tw) {
            val c = (x0 + x1) / 2
            x0 = c - tw / 2 - 1
            x1 = x0 + tw + 2
        }
        if (y1 - y0 < th) {
            val c = (y0 + y1) / 2
            y0 = c - th / 2 - 1
            y1 = y0 + th + 2
        }
        x0 = x0.coerceIn(0, max(0, bmpW - 1))
        y0 = y0.coerceIn(0, max(0, bmpH - 1))
        x1 = min(bmpW, max(x1, x0 + 1))
        y1 = min(bmpH, max(y1, y0 + 1))
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    fun find(bmp: Bitmap, tpl: VisionTemplate, l: Float, t: Float, r: Float, b: Float, minScore: Float): Found? {
        val rc = zoneRect(bmp.width, bmp.height, l, t, r, b, tpl.w, tpl.h)
        if (rc[2] < tpl.w || rc[3] < tpl.h) return null
        val px = IntArray(rc[2] * rc[3])
        bmp.getPixels(px, 0, rc[2], rc[0], rc[1], rc[2], rc[3])
        val hit = TemplateMatcher.find(Region(rc[2], rc[3], px, rc[0], rc[1]), tpl, minScore) ?: return null
        return Found(rc[0] + hit.x, rc[1] + hit.y, hit.score)
    }
}
