package com.he.he_smartphone_auto_clicker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File

object DetectorFiles {
    private fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "detectors").also { it.mkdirs() }

    fun file(ctx: Context, id: Long): File = File(dir(ctx), "$id.png")

    fun viewFiles(ctx: Context, id: Long): List<File> {
        val extra = dir(ctx).listFiles { f -> f.name.startsWith("$id.v") && f.name.endsWith(".png") && f.length() > 0L }?.sortedBy { it.name } ?: emptyList()
        val main = file(ctx, id)
        return if (main.exists() && main.length() > 0L) listOf(main) + extra else extra
    }

    fun viewCount(ctx: Context, id: Long): Int = (viewFiles(ctx, id).size - if (file(ctx, id).exists()) 1 else 0).coerceAtLeast(0)

    fun saveView(ctx: Context, id: Long, bmp: Bitmap): Boolean {
        return try {
            var n = 1
            var dest = File(dir(ctx), "$id.v$n.png")
            while (dest.exists() && n < 8) { n++; dest = File(dir(ctx), "$id.v$n.png") }
            if (dest.exists()) return false
            val tmp = File(dir(ctx), "$id.v$n.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(dest)
        } catch (_: Exception) {
            false
        }
    }

    fun exists(ctx: Context, id: Long): Boolean = file(ctx, id).let { it.exists() && it.length() > 0L }

    fun save(ctx: Context, id: Long, bmp: Bitmap): Boolean {
        return try {
            val f = file(ctx, id)
            val tmp = File(f.parentFile, "$id.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (_: Exception) {
            false
        }
    }

    fun saveBytes(ctx: Context, id: Long, bytes: ByteArray): Boolean {
        return try {

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
            file(ctx, id).writeBytes(bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(ctx: Context, id: Long): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(file(ctx, id).absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    fun readBytes(ctx: Context, id: Long): ByteArray? = try { file(ctx, id).takeIf { it.exists() }?.readBytes() } catch (_: Exception) { null }

    fun toBase64(ctx: Context, id: Long): String? = readBytes(ctx, id)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    fun fromBase64(raw: String): ByteArray? = try { Base64.decode(raw, Base64.DEFAULT) } catch (_: Exception) { null }

    fun copy(ctx: Context, fromId: Long, toId: Long): Boolean {
        return try {
            val src = file(ctx, fromId)
            if (!src.exists()) return false
            src.copyTo(file(ctx, toId), overwrite = true)
            viewFiles(ctx, fromId).filter { it.name.contains(".v") }.forEachIndexed { i, f ->
                f.copyTo(File(dir(ctx), "$toId.v${i + 1}.png"), overwrite = true)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun delete(ctx: Context, id: Long) {
        try { file(ctx, id).delete() } catch (_: Exception) {}
    }

    fun gc(ctx: Context, keep: Set<Long>) {
        try {
            dir(ctx).listFiles()?.forEach { f ->
                val id = f.name.removeSuffix(".png").removeSuffix(".tmp").substringBefore(".v").toLongOrNull()
                if (id == null || id !in keep) f.delete()
            }
        } catch (_: Exception) {}
    }
}
