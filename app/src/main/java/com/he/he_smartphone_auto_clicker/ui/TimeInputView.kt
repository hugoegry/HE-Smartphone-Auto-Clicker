package com.he.he_smartphone_auto_clicker.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.he.he_smartphone_auto_clicker.R
import com.he.he_smartphone_auto_clicker.i18n.I18n
import kotlin.math.roundToLong

class TimeInputView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : LinearLayout(context, attrs, defStyleAttr) {
    enum class TimeUnit(val factor: Long, val labelRes: Int) {
        MS(1L, R.string.time_unit_ms),
        SEC(1000L, R.string.time_unit_sec),
        MIN(60_000L, R.string.time_unit_min),
        HOUR(3_600_000L, R.string.time_unit_hour)
    }

    private val valueInput: EditText
    private val unitBtn: TextView
    private var unit = TimeUnit.MS
    private var suppress = false
    private var changeListener: (() -> kotlin.Unit)? = null
    private var unitPopup: PopupWindow? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_dialog_input)
        LayoutInflater.from(context).inflate(R.layout.view_time_input, this, true)
        valueInput = findViewById(R.id.time_value)
        unitBtn = findViewById(R.id.time_unit)
        paintUnit()
        valueInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppress) changeListener?.invoke()
            }
        })
        unitBtn.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val other = openOwner
                if (other != null && other !== this) {
                    other.dismissUnitMenu()
                    openUnitMenu()
                    return@setOnTouchListener true
                }
                if (unitPopup?.isShowing == true) {
                    dismissUnitMenu()
                    return@setOnTouchListener true
                }
            }
            false
        }
        unitBtn.setOnClickListener { openUnitMenu() }
    }

    fun setOnValueChangedListener(listener: (() -> kotlin.Unit)?) {
        changeListener = listener
    }

    fun setMs(ms: Long, preferred: TimeUnit? = null) {
        val safe = ms.coerceAtLeast(0L)
        unit = preferred ?: pickUnit(safe)
        paintUnit()
        suppress = true
        valueInput.setText(formatValue(safe, unit))
        suppress = false
    }

    fun getMs(fallback: Long, min: Long, max: Long): Long {
        val raw = valueInput.text?.toString()?.trim().orEmpty()
        val n = raw.replace(',', '.').toDoubleOrNull()
        val ms = if (n == null) fallback else (n * unit.factor).roundToLong()
        return ms.coerceIn(min, max)
    }

    private fun dismissUnitMenu() {
        unitPopup?.dismiss()
        unitPopup = null
        if (openOwner === this) openOwner = null
    }

    private fun openUnitMenu() {
        if (unitPopup?.isShowing == true) return
        if (width <= 0 || unitBtn.width <= 0) {
            post { openUnitMenu() }
            return
        }
        openOwner?.takeIf { it !== this }?.dismissUnitMenu()
        val dens = resources.displayMetrics.density
        val padH = (10 * dens).toInt()
        val rowH = (40 * dens).toInt()
        val gap = (1 * dens).toInt()
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        list.setBackgroundResource(R.drawable.bg_time_unit_menu)
        list.setPadding(0, (4 * dens).toInt(), 0, (4 * dens).toInt())
        TimeUnit.entries.forEach { u ->
            val row = TextView(context)
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH)
            row.gravity = Gravity.CENTER
            row.setPadding(padH, 0, padH, 0)
            row.text = I18n.t(context, u.labelRes)
            row.setTextColor(context.getColor(R.color.gold))
            row.textSize = 14f
            row.paint.isFakeBoldText = true
            if (u == unit) row.setBackgroundColor(Color.parseColor("#33D4AF37"))
            row.setOnClickListener {
                applyUnit(u)
                dismissUnitMenu()
            }
            list.addView(row)
        }
        val divider: View? = getChildAt(1)
        val zoneLeft = divider?.left ?: unitBtn.left
        val menuW = (width - zoneLeft).coerceAtLeast(unitBtn.width)
        val popup = PopupWindow(list, menuW, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = false
        popup.elevation = 8f * dens
        popup.setOnDismissListener {
            if (unitPopup === popup) {
                unitPopup = null
                if (openOwner === this) openOwner = null
            }
        }
        unitPopup = popup
        openOwner = this
        val loc = IntArray(2)
        getLocationInWindow(loc)
        popup.showAtLocation(unitBtn, Gravity.NO_GRAVITY, loc[0] + zoneLeft, loc[1] + height + gap)
    }

    private fun applyUnit(next: TimeUnit) {
        val currentMs = getMs(0L, 0L, Long.MAX_VALUE / 2)
        unit = next
        paintUnit()
        suppress = true
        valueInput.setText(formatValue(currentMs, unit))
        suppress = false
        changeListener?.invoke()
    }

    private fun paintUnit() {
        unitBtn.text = I18n.t(context, unit.labelRes)
    }

    private fun pickUnit(ms: Long): TimeUnit {
        if (ms <= 0L) return TimeUnit.MS
        if (ms % TimeUnit.HOUR.factor == 0L) return TimeUnit.HOUR
        if (ms % TimeUnit.MIN.factor == 0L) return TimeUnit.MIN
        if (ms % TimeUnit.SEC.factor == 0L) return TimeUnit.SEC
        return TimeUnit.MS
    }

    private fun formatValue(ms: Long, unit: TimeUnit): String {
        if (unit == TimeUnit.MS) return ms.toString()
        val v = ms.toDouble() / unit.factor.toDouble()
        val asLong = v.roundToLong()
        return if (kotlin.math.abs(v - asLong) < 0.0001) asLong.toString() else trimDecimal(v)
    }

    private fun trimDecimal(v: Double): String {
        var s = String.format(java.util.Locale.US, "%.4f", v)
        while (s.contains('.') && (s.endsWith('0') || s.endsWith('.'))) {
            s = s.dropLast(1)
        }
        return s
    }

    override fun onDetachedFromWindow() {
        dismissUnitMenu()
        super.onDetachedFromWindow()
    }

    companion object {
        private var openOwner: TimeInputView? = null
    }
}