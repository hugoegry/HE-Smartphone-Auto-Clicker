package com.he.he_smartphone_auto_clicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.he.he_smartphone_auto_clicker.util.JoyMath
import kotlin.math.abs
import kotlin.math.hypot

@SuppressLint("ClickableViewAccessibility")
class JoyPadView(ctx: Context) : View(ctx) {
    private val dens = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dens
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.4f * dens
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * dens
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 11f * dens
    }
    private val tmp = RectF()

    var color: Int = 0xFFD4AF37.toInt()
        set(value) { field = value; invalidate() }
    var angleFrom: Float = 0f
        set(value) { field = value; invalidate() }
    var angleTo: Float = 360f
        set(value) { field = value; invalidate() }
    var label: String = "J"
        set(value) { field = value; invalidate() }
    var blockName: String = ""
        set(value) { field = value; invalidate() }

    var onMoveCenter: ((Float, Float) -> Unit)? = null
    var onMoveAngleFrom: ((Float) -> Unit)? = null
    var onMoveAngleTo: ((Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var canInteract: (() -> Boolean)? = null

    private var mode = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false
    private var grabDx = 0f
    private var grabDy = 0f
    private fun padR(): Float = (minOf(width, height) / 2f) - 12f * dens
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = padR()
        if (r <= 1f) return
        fill.color = (color and 0x00FFFFFF) or 0x1A000000
        canvas.drawCircle(cx, cy, r, fill)
        ring.color = (color and 0x00FFFFFF) or 0x66FFFFFF.toInt()
        canvas.drawCircle(cx, cy, r, ring)
        arc.color = color
        val span = JoyMath.spanClockwise(angleFrom, angleTo)
        if (span < 359.5f) {
            val start = JoyMath.normDeg(angleFrom) - 90f
            tmp.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(tmp, start, span, false, arc)
        } else {
            canvas.drawCircle(cx, cy, r, arc)
        }
        fill.color = (color and 0x00FFFFFF) or 0xCC000000.toInt()
        canvas.drawCircle(cx, cy, 6.5f * dens, fill)
        fill.color = color
        canvas.drawCircle(cx, cy, 3.8f * dens, fill)
        drawKnob(canvas, cx, cy, r, angleFrom, startKnob = true)
        drawKnob(canvas, cx, cy, r, angleTo, startKnob = false)
        textPaint.color = color
        textPaint.textSize = 11f * dens
        canvas.drawText(label, cx, cy + 3.2f * dens, textPaint)
        if (blockName.isNotBlank()) {
            textPaint.textSize = 8f * dens
            textPaint.color = (color and 0x00FFFFFF) or 0xCCFFFFFF.toInt()
            canvas.drawText(blockName, cx, cy + r + 10f * dens, textPaint)
        }
    }

    private fun drawKnob(canvas: Canvas, cx: Float, cy: Float, r: Float, ang: Float, startKnob: Boolean) {
        val (kx, ky) = JoyMath.edgePx(cx, cy, r, ang)
        val rad = 7.2f * dens
        knobFill.color = 0xF0111111.toInt()
        canvas.drawCircle(kx, ky, rad, knobFill)
        knobStroke.color = color
        canvas.drawCircle(kx, ky, rad, knobStroke)
        if (startKnob) {
            fill.color = color
            canvas.drawCircle(kx, ky, 3.2f * dens, fill)
        } else {
            fill.color = color
            canvas.drawCircle(kx, ky, 2.2f * dens, fill)
            fill.color = 0xF0111111.toInt()
            canvas.drawCircle(kx, ky, 1.1f * dens, fill)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (canInteract?.invoke() == false) return false
        val cx = width / 2f
        val cy = height / 2f
        val r = padR()
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                moved = false
                val (fx, fy) = JoyMath.edgePx(cx, cy, r, angleFrom)
                val (tx, ty) = JoyMath.edgePx(cx, cy, r, angleTo)
                val dFrom = hypot((x - fx).toDouble(), (y - fy).toDouble())
                val dTo = hypot((x - tx).toDouble(), (y - ty).toDouble())
                val hit = 20.0 * dens
                mode = when {
                    dFrom <= hit && dFrom <= dTo -> 2
                    dTo <= hit -> 3
                    hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r * 0.72 -> 1
                    else -> 0
                }
                grabDx = x - cx
                grabDy = y - cy
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downRawX) > 5f * dens || abs(event.rawY - downRawY) > 5f * dens) moved = true
                if (!moved) return true
                when (mode) {
                    1 -> onMoveCenter?.invoke(event.rawX - grabDx, event.rawY - grabDy)
                    2 -> {
                        val ang = JoyMath.angleFromDelta(x - cx, y - cy)
                        angleFrom = ang
                        onMoveAngleFrom?.invoke(ang)
                    }
                    3 -> {
                        val ang = JoyMath.angleFromDelta(x - cx, y - cy)
                        angleTo = ang
                        onMoveAngleTo?.invoke(ang)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved) onTap?.invoke()
                mode = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}