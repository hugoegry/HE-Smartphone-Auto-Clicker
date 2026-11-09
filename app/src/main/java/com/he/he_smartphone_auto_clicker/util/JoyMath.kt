package com.he.he_smartphone_auto_clicker.util

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object JoyMath {
    fun normDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    fun spanClockwise(from: Float, to: Float): Float {
        val a = normDeg(from)
        val b = normDeg(to)
        val span = if (b >= a) b - a else (360f - a) + b
        return if (span <= 0.01f) 360f else span
    }

    fun lerpClockwise(from: Float, to: Float, t: Float): Float {
        val a = normDeg(from)
        val span = spanClockwise(from, to)
        return normDeg(a + span * t.coerceIn(0f, 1f))
    }

    fun randomInSector(from: Float, to: Float, rng: java.util.Random): Float {
        val a = normDeg(from)
        val span = spanClockwise(from, to)
        if (span >= 359.5f) return rng.nextFloat() * 360f
        return normDeg(a + rng.nextFloat() * span)
    }

    fun offsetPx(angleDeg: Float, radiusPx: Float): Pair<Float, Float> {
        val rad = Math.toRadians(normDeg(angleDeg).toDouble())
        val dx = (sin(rad) * radiusPx).toFloat()
        val dy = (-cos(rad) * radiusPx).toFloat()
        return dx to dy
    }

    fun edgePx(cx: Float, cy: Float, radiusPx: Float, angleDeg: Float): Pair<Float, Float> {
        val (dx, dy) = offsetPx(angleDeg, radiusPx)
        return (cx + dx) to (cy + dy)
    }

    fun angleFromDelta(dx: Float, dy: Float): Float {
        val deg = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        return normDeg(deg)
    }

    fun tipFromCenter(cxRatio: Float, cyRatio: Float, radiusRatio: Float, angleDeg: Float, sw: Float, sh: Float): Pair<Float, Float> {
        val rPx = (radiusRatio.coerceIn(0.02f, 0.45f) * sw).coerceAtLeast(8f)
        val (dx, dy) = offsetPx(angleDeg, rPx)
        val x2 = ((cxRatio * sw + dx) / sw).coerceIn(0.02f, 0.98f)
        val y2 = ((cyRatio * sh + dy) / sh).coerceIn(0.02f, 0.98f)
        return x2 to y2
    }

    fun radiusFromTip(cxRatio: Float, cyRatio: Float, tipX: Float, tipY: Float, sw: Float, sh: Float): Float {
        val dx = (tipX - cxRatio) * sw
        val dy = (tipY - cyRatio) * sh
        return (hypot(dx.toDouble(), dy.toDouble()) / sw).toFloat().coerceIn(0.02f, 0.45f)
    }
}