package com.he.he_smartphone_auto_clicker.vision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object VisionAsk {
    private const val TAG = "HeVision"
    private val MODELS = arrayOf("gemini-3.5-flash")

    fun jpeg(bmp: Bitmap): ByteArray {
        val maxW = 768
        val scale = if (bmp.width > maxW) maxW.toFloat() / bmp.width else 1f
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val small = if (scale < 0.999f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val out = ByteArrayOutputStream()
        try {
            small.compress(Bitmap.CompressFormat.JPEG, 55, out)
        } finally {
            if (small !== bmp) small.recycle()
        }
        return out.toByteArray()
    }

    fun ask(key: String, jpeg: ByteArray, items: List<Pair<Long, String>>): Map<Long, Boolean>? {
        if (key.isBlank() || items.isEmpty() || jpeg.isEmpty()) return null
        val prompt = buildPrompt(items)
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = JSONObject()
        val parts = JSONArray().put(JSONObject().put("text", prompt)).put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        body.put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        val gen = JSONObject().put("temperature", 0).put("maxOutputTokens", 800)
        gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        body.put("generationConfig", gen)
        for (model in MODELS) {
            val text = post(model, key, body.toString()) ?: continue
            val parsed = parse(text, items)
            if (parsed.isNotEmpty()) return parsed
            Log.w(TAG, "ai reponse illisible model=$model ${text.take(80)}")
        }
        return null
    }

    private fun buildPrompt(items: List<Pair<Long, String>>): String {
        val lines = items.joinToString("\n") { (id, name) -> "- $id: $name" }
        return "Gameplay screenshot. Ignore the app overlay: translucent circles, Block labels, and the top black bar with percents. " +
            "For each item, answer YES only if that exact thing is clearly visible in the game, not merely a similar color. " +
            "Reply with one line per item and nothing else, format: <id> YES or <id> NO\n" + lines
    }

    private fun post(model: String, key: String, json: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "ai http $code model=$model ${raw.take(140)}")
                return null
            }
            val root = JSONObject(raw)
            val parts = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("text")) text.append(p.getString("text")).append('\n')
            }
            val out = text.toString().trim()
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            Log.w(TAG, "ai post $model ${e.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String, items: List<Pair<Long, String>>): Map<Long, Boolean> {
        val out = HashMap<Long, Boolean>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for ((id, name) in items) {
            val line = lines.firstOrNull { it.contains(id.toString()) || it.contains(name, ignoreCase = true) } ?: continue
            val yn = yesNo(line) ?: continue
            out[id] = yn
        }
        if (out.size < items.size && lines.size >= items.size) {
            items.forEachIndexed { i, (id, _) ->
                if (id in out || i >= lines.size) return@forEachIndexed
                val yn = yesNo(lines[i]) ?: return@forEachIndexed
                out[id] = yn
            }
        }
        return out
    }

    private fun yesNo(line: String): Boolean? {
        val yes = Regex("\\b(YES|OUI)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
        val no = Regex("\\b(NO|NON)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
        if (!yes && !no) return null
        return yes && !no
    }
}
