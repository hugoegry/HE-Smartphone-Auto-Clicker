package com.he.he_smartphone_auto_clicker.vision

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object TextQuery {

    val BANNER_ZONE = floatArrayOf(0.02f, 0.05f, 0.98f, 0.32f)
    const val TIP = "laissez un pourboire pour une viree sans nuage"
    const val BAR = "la barre est dirigee par"

    const val SAMPLE = "laissez un pourboire pour une viree sans nuage\nla barre est dirigee par"

    fun phrases(raw: String): List<String> = raw.split('\n', '|', ';').map { it.trim() }.filter { norm(it).length >= 6 }

    fun best(hay: String, query: String): Float {
        if (hay.isBlank()) return 0f
        val list = phrases(query)
        if (list.isEmpty()) return 0f
        var top = 0f
        for (p in list) {
            val s = phraseScore(hay, p)
            if (s > top) top = s
        }
        return top
    }

    fun norm(raw: String): String {
        val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (ch in nfd) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            val c = ch.lowercaseChar()
            if (c in 'a'..'z' || c in '0'..'9') sb.append(c)
            else if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        return sb.toString().trim()
    }

    private fun phraseScore(hayRaw: String, phraseRaw: String): Float {
        val hay = norm(hayRaw)
        if (hay.isEmpty()) return 0f
        val phrase = norm(phraseRaw)
        if (phrase.isEmpty()) return 0f
        if (hay.contains(phrase)) return 1f
        val compact = hay.replace(" ", "")
        val phraseCompact = phrase.replace(" ", "")
        if (phraseCompact.length >= 12 && compact.contains(phraseCompact)) return 1f
        val words = phrase.split(' ').filter { it.length >= 3 }
        if (words.isEmpty()) return 0f
        var hit = 0
        for (w in words) if (wordHit(hay, compact, w)) hit++
        val raw = hit.toFloat() / words.size
        val anchors = words.filter { it.length >= 6 }
        if (anchors.isEmpty()) return raw
        val ah = anchors.count { wordHit(hay, compact, it) }.toFloat() / anchors.size

        if (ah >= 1f && raw >= 0.75f) return 1f
        if (ah >= 0.5f && raw >= 0.5f) return max(raw, 0.84f)
        if (ah <= 0f) return min(raw, 0.25f)
        return min(raw, 0.6f)
    }

    private fun wordHit(hay: String, compact: String, word: String): Boolean {
        if (word.length >= 5) {
            if (compact.contains(word)) return true
            val tol = if (word.length >= 8) 2 else 1
            for (t in hay.split(' ')) {
                if (t.isEmpty() || abs(t.length - word.length) > tol) continue
                if (lev(t, word) <= tol) return true
            }
            return false
        }
        for (t in hay.split(' ')) if (t == word) return true
        return false
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = min(min(cur[j] + 1, prev[j + 1] + 1), prev[j] + cost)
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length]
    }
}
