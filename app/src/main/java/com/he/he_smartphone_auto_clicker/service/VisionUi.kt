package com.he.he_smartphone_auto_clicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.he.he_smartphone_auto_clicker.R
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.data.DetectorFiles
import com.he.he_smartphone_auto_clicker.i18n.tr
import com.he.he_smartphone_auto_clicker.model.Detector
import com.he.he_smartphone_auto_clicker.model.DetectorPause
import com.he.he_smartphone_auto_clicker.ui.TimeInputView
import com.he.he_smartphone_auto_clicker.util.dp
import com.he.he_smartphone_auto_clicker.util.screenBounds
import com.he.he_smartphone_auto_clicker.vision.TextQuery
import com.he.he_smartphone_auto_clicker.vision.VisionMatch
import com.he.he_smartphone_auto_clicker.vision.VisionTemplate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VisionUi(private val service: ClickerAccessibilityService, private val host: OverlayController) {
    private val main = Handler(Looper.getMainLooper())
    private val ctx: Context get() = host.uiCtx
    private fun tr(id: Int): String = ctx.tr(id)
    private fun tr(id: Int, vararg args: Any?): String = ctx.tr(id, *args)
    private val store get() = ClickerStore.get(service)

    private class Draft(
        var id: Long,
        var name: String,
        var enabled: Boolean,
        var tpl: Bitmap?,
        var tplChanged: Boolean,
        var zone: FloatArray,
        var cx: Float,
        var cy: Float,
        var threshold: Int,
        var delaySec: Int,
        var groupId: Long,
        var pause: DetectorPause,
        var repeatSec: Int,
        var query: String = "",
        var textMode: Boolean = false
    )

    private var cropRoot: View? = null
    private var restoreOverlay: (() -> Unit)? = null
    private var capturing = false

    fun dispose() {
        closeCrop()
    }

    fun showPanel() {
        if (service.isRunning()) { toast(tr(R.string.pause_first)); return }
        if (capturing || cropRoot != null) return
        if (!host.uiLock()) return
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundResource(R.drawable.bg_dialog)
        root.setPadding(service.dp(16), service.dp(14), service.dp(16), service.dp(14))
        root.addView(title(tr(R.string.vision_title)))
        root.addView(hint(tr(R.string.vision_hint)))
        val keyInput = EditText(ctx)
        keyInput.setText(store.visionAiKey)
        keyInput.hint = tr(R.string.vision_ai_hint)
        keyInput.setSingleLine(true)
        keyInput.setTextColor(service.getColor(R.color.white))
        keyInput.setHintTextColor(service.getColor(R.color.text_sub))
        keyInput.textSize = 13f
        keyInput.setBackgroundResource(R.drawable.bg_dialog_input)
        keyInput.setPadding(service.dp(10), service.dp(8), service.dp(10), service.dp(8))
        root.addView(keyInput, matchWrap(6))
        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(ctx)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = service.dp(8) })
        limitScrollOnce(scroll, dialogScrollMaxH())
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        val btnAdd = button(tr(R.string.vision_add), true)
        val btnText = button(tr(R.string.vision_add_text), false)
        val btnClose = button(tr(R.string.vision_close), false)
        bar.addView(btnAdd, weight(1f, 0, 4))
        bar.addView(btnText, weight(1f, 4, 4))
        bar.addView(btnClose, weight(1f, 4, 0))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = service.dp(10) })
        try {
            val dialog = AlertDialog.Builder(ctx).setView(root).create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener {
                store.visionAiKey = keyInput.text?.toString()?.trim().orEmpty()
                host.uiClear(dialog)
            }
            btnClose.setOnClickListener { dialog.dismiss() }
            btnAdd.setOnClickListener {
                if (store.detectorList().size >= 12) { toast(tr(R.string.vision_max)); return@setOnClickListener }
                dialog.dismiss()
                startCapture { shot, rect -> afterTemplatePicked(shot, rect) }
            }
            btnText.setOnClickListener {
                if (store.detectorList().size >= 12) { toast(tr(R.string.vision_max)); return@setOnClickListener }
                dialog.dismiss()
                main.postDelayed({ openTextEditor() }, 120L)
            }
            fillList(list, dialog)
            host.uiOpen(dialog)
        } catch (e: Exception) {
            host.uiUnlock()
            Log.e(TAG, "vision panel", e)
        }
    }

    private fun fillList(list: LinearLayout, dialog: AlertDialog) {
        list.removeAllViews()
        val all = store.detectorList()
        if (all.isEmpty()) {
            val empty = hint(tr(R.string.vision_empty))
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, service.dp(14), 0, service.dp(6))
            list.addView(empty)
            return
        }
        val groups = store.groupList()
        all.forEach { d ->
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setBackgroundResource(R.drawable.bg_dialog_input)
            row.setPadding(service.dp(10), service.dp(6), service.dp(6), service.dp(6))
            if (d.query.isNotBlank()) {
                val badge = TextView(ctx)
                badge.text = "TXT"
                badge.gravity = Gravity.CENTER
                badge.setTextColor(service.getColor(R.color.black))
                badge.setBackgroundResource(R.drawable.bg_dialog_btn_primary)
                badge.textSize = 11f
                badge.paint.isFakeBoldText = true
                row.addView(badge, LinearLayout.LayoutParams(service.dp(40), service.dp(40)).also { it.rightMargin = service.dp(8) })
            } else {
                val thumb = ImageView(ctx)
                DetectorFiles.load(service, d.id)?.let { thumb.setImageBitmap(it) }
                thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                row.addView(thumb, LinearLayout.LayoutParams(service.dp(40), service.dp(40)).also { it.rightMargin = service.dp(8) })
            }
            val txt = LinearLayout(ctx)
            txt.orientation = LinearLayout.VERTICAL
            val name = TextView(ctx)
            name.text = d.name
            name.setTextColor(service.getColor(R.color.gold))
            name.textSize = 14f
            name.paint.isFakeBoldText = true
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            val blockName = groups.firstOrNull { it.id == d.groupId }?.name ?: tr(R.string.vision_block_none)
            val meta = TextView(ctx)
            meta.text = tr(R.string.vision_row_meta, fmtSec(d.delaySec), blockName, pauseShort(d.pause))
            meta.setTextColor(service.getColor(R.color.text_sub))
            meta.textSize = 11f
            meta.maxLines = 2
            txt.addView(name)
            txt.addView(meta)
            row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = chip(if (d.enabled) tr(R.string.vision_on) else tr(R.string.vision_off), d.enabled)
            sw.setOnClickListener {
                store.setDetectorEnabled(d.id, !d.enabled)
                fillList(list, dialog)
            }
            val ed = chip(tr(R.string.vision_edit), false)
            ed.setOnClickListener {
                dialog.dismiss()

                main.postDelayed({ openEditorFor(d) }, 120L)
            }
            val del = chip(tr(R.string.delete), false)
            del.setOnClickListener {
                store.removeDetector(d.id)
                VisionMatch.forget(d.id)
                toast(tr(R.string.vision_deleted))
                fillList(list, dialog)
            }
            row.addView(sw, chipLp())
            row.addView(ed, chipLp())
            row.addView(del, chipLp())
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = service.dp(6) })
        }
    }

    private fun startCapture(zoneMode: Boolean = false, init: FloatArray? = null, onPicked: (Bitmap, Rect) -> Unit) {
        if (capturing || cropRoot != null) return
        val watcher = service.visionWatcher()
        if (!watcher.grabber.supported()) { toast(tr(R.string.vision_need_android11)); return }
        capturing = true
        val back = host.uiParkAll()
        restoreOverlay = back
        main.postDelayed({
            watcher.grabber.grab { shot ->
                main.post {
                    capturing = false
                    if (shot == null) {
                        restoreAll()
                        toast(tr(R.string.vision_capture_failed, watcher.grabber.lastError))
                        val cb = onZoneCancel
                        onZoneCancel = null
                        cb?.invoke()
                        return@post
                    }
                    if (shot.blank) {
                        shot.bitmap.recycle()
                        restoreAll()
                        toast(tr(R.string.vision_black_screen))
                        val cb = onZoneCancel
                        onZoneCancel = null
                        cb?.invoke()
                        return@post
                    }
                    val hint = if (zoneMode) tr(R.string.vision_zone_hint) else tr(R.string.vision_capture_hint)
                    showCrop(shot.bitmap, if (zoneMode) init else null, zoneMode, hint, onPicked)
                }
            }
        }, 320L)
    }

    private fun restoreAll() {
        restoreOverlay?.invoke()
        restoreOverlay = null
    }

    private fun closeCrop() {
        cropRoot?.let { try { host.uiWm.removeView(it) } catch (_: Exception) {} }
        cropRoot = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCrop(shot: Bitmap, init: FloatArray?, zoneMode: Boolean, hintText: String, onPicked: (Bitmap, Rect) -> Unit) {
        val accent = if (zoneMode) Color.parseColor("#4FC3F7") else Color.parseColor("#E8C96A")
        val crop = CropView(ctx, shot, accent)
        val frame = FrameLayout(ctx)
        frame.setBackgroundColor(Color.BLACK)
        frame.addView(crop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val top = TextView(ctx)
        top.text = hintText
        top.setTextColor(Color.WHITE)
        top.textSize = 14f
        top.gravity = Gravity.CENTER
        top.setBackgroundColor(Color.parseColor("#B3000000"))
        top.setPadding(service.dp(12), service.dp(34), service.dp(12), service.dp(10))
        frame.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(service.dp(14), service.dp(10), service.dp(14), service.dp(40))
        bar.setBackgroundColor(Color.parseColor("#B3000000"))
        val btnCancel = button(tr(R.string.cancel), false)
        val btnOk = button(tr(R.string.vision_confirm), true)
        bar.addView(btnCancel, weight(1f, 0, 8))
        bar.addView(btnOk, weight(1f, 8, 0))
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        crop.post {
            if (init != null && crop.width > 0) {
                crop.setSelection(RectF(init[0] * crop.width, init[1] * crop.height, init[2] * crop.width, init[3] * crop.height))
            }
        }
        btnCancel.setOnClickListener {
            closeCrop()
            shot.recycle()
            restoreAll()

            val cb = onZoneCancel
            onZoneCancel = null
            cb?.invoke()
        }
        btnOk.setOnClickListener {
            val rect = crop.selectionInBitmap()
            if (rect == null || rect.width() < MIN_SIDE || rect.height() < MIN_SIDE) {
                toast(tr(R.string.vision_too_small))
                return@setOnClickListener
            }
            closeCrop()
            restoreAll()
            onPicked(shot, rect)
        }
        try {
            host.uiWm.addView(frame, host.uiFullParams(true))
            cropRoot = frame
        } catch (e: Exception) {
            Log.e(TAG, "crop window", e)
            shot.recycle()
            restoreAll()
        }
    }

    private var onZoneCancel: (() -> Unit)? = null

    private fun afterTemplatePicked(shot: Bitmap, rect: Rect) {
        val tpl = try { Bitmap.createBitmap(shot, rect.left, rect.top, rect.width(), rect.height()) } catch (_: Exception) { null }
        val sw = shot.width.toFloat()
        val sh = shot.height.toFloat()
        shot.recycle()
        if (tpl == null) { toast(tr(R.string.vision_too_small)); return }
        val vt = VisionMatch.templateFromBitmap(tpl)
        if (vt.stdLuma < VisionTemplate.MIN_STD) {
            tpl.recycle()
            toast(tr(R.string.vision_too_flat))
            return
        }
        val cx = (rect.left + rect.right) / 2f / sw
        val cy = (rect.top + rect.bottom) / 2f / sh
        val draft = Draft(
            id = store.newDetectorId(),
            name = tr(R.string.vision_name_default, store.detectorList().size + 1),
            enabled = true,
            tpl = tpl,
            tplChanged = true,
            zone = nearZone(cx, cy, tpl.width / sw, tpl.height / sh),
            cx = cx,
            cy = cy,
            threshold = 85,
            delaySec = 0,
            groupId = 0L,
            pause = DetectorPause.ACTION,
            repeatSec = 0
        )
        showEditor(draft)
    }

    private fun openTextEditor() {
        val n = store.detectorList().size + 1
        val z = TextQuery.BANNER_ZONE.copyOf()
        showEditor(Draft(id = store.newDetectorId(), name = tr(R.string.vision_text_name, n), enabled = true, tpl = null, tplChanged = false, zone = z, cx = (z[0] + z[2]) / 2f, cy = (z[1] + z[3]) / 2f, threshold = 70, delaySec = 0, groupId = 0L, pause = DetectorPause.ACTION, repeatSec = 0, query = TextQuery.SAMPLE, textMode = true))
    }

    private fun openEditorFor(d: Detector) {
        if (d.query.isNotBlank()) {
            showEditor(Draft(id = d.id, name = d.name, enabled = d.enabled, tpl = null, tplChanged = false, zone = floatArrayOf(d.zoneL, d.zoneT, d.zoneR, d.zoneB), cx = (d.zoneL + d.zoneR) / 2f, cy = (d.zoneT + d.zoneB) / 2f, threshold = d.threshold, delaySec = d.delaySec, groupId = d.groupId, pause = d.pause, repeatSec = d.repeatSec, query = d.query, textMode = true))
            return
        }
        val bmp = DetectorFiles.load(service, d.id)
        if (bmp == null) { toast(tr(R.string.vision_tpl_missing)); return }
        val draft = Draft(
            id = d.id, name = d.name, enabled = d.enabled, tpl = bmp, tplChanged = false,
            zone = floatArrayOf(d.zoneL, d.zoneT, d.zoneR, d.zoneB),
            cx = (d.zoneL + d.zoneR) / 2f, cy = (d.zoneT + d.zoneB) / 2f,
            threshold = d.threshold, delaySec = d.delaySec, groupId = d.groupId, pause = d.pause, repeatSec = d.repeatSec
        )
        showEditor(draft)
    }

    private fun nearZone(cx: Float, cy: Float, tw: Float, th: Float): FloatArray {
        val mx = max(0.08f, tw * 0.8f)
        val my = max(0.05f, th * 0.8f)
        return floatArrayOf((cx - tw / 2 - mx).coerceIn(0f, 1f), (cy - th / 2 - my).coerceIn(0f, 1f), (cx + tw / 2 + mx).coerceIn(0f, 1f), (cy + th / 2 + my).coerceIn(0f, 1f))
    }

    private fun showEditor(draft: Draft) {
        if (!host.uiLock()) { draft.tpl?.recycle(); return }
        val tplBmp = draft.tpl
        if (tplBmp == null && !draft.textMode) { host.uiUnlock(); return }
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundResource(R.drawable.bg_dialog)
        root.setPadding(service.dp(16), service.dp(12), service.dp(16), service.dp(12))
        root.addView(title(tr(R.string.vision_edit_title)))
        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(ctx)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = service.dp(6) })
        limitScrollOnce(scroll, dialogScrollMaxH())

        var addView: TextView? = null
        if (tplBmp != null) {
            val preview = ImageView(ctx)
            preview.setImageBitmap(tplBmp)
            preview.adjustViewBounds = true
            preview.maxHeight = service.dp(84)
            preview.scaleType = ImageView.ScaleType.FIT_CENTER
            preview.setBackgroundResource(R.drawable.bg_dialog_input)
            preview.setPadding(service.dp(6), service.dp(6), service.dp(6), service.dp(6))
            body.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = service.dp(6) })
            val sizeTxt = hint("${tplBmp.width} x ${tplBmp.height} px")
            sizeTxt.gravity = Gravity.CENTER
            body.addView(sizeTxt)
            val btn = button(tr(R.string.vision_add_view, DetectorFiles.viewCount(service, draft.id)), false)
            addView = btn
            body.addView(btn, matchWrap(4))
        }
        var queryInput: EditText? = null

        body.addView(label(tr(R.string.vision_name_label)))
        val nameInput = EditText(ctx)
        nameInput.setText(draft.name)
        nameInput.setSingleLine(true)
        nameInput.setTextColor(service.getColor(R.color.white))
        nameInput.textSize = 14f
        nameInput.setBackgroundResource(R.drawable.bg_dialog_input)
        nameInput.setPadding(service.dp(10), service.dp(8), service.dp(10), service.dp(8))
        body.addView(nameInput, matchWrap(4))
        if (draft.textMode) {
            body.addView(label(tr(R.string.vision_text_label)))
            val q = EditText(ctx)
            q.setText(draft.query)
            q.hint = tr(R.string.vision_text_hint)
            q.setTextColor(service.getColor(R.color.white))
            q.setHintTextColor(service.getColor(R.color.text_sub))
            q.textSize = 14f
            q.minLines = 3
            q.maxLines = 6
            q.setHorizontallyScrolling(false)
            q.gravity = Gravity.TOP or Gravity.START
            q.setBackgroundResource(R.drawable.bg_dialog_input)
            q.setPadding(service.dp(10), service.dp(8), service.dp(10), service.dp(8))
            body.addView(q, matchWrap(4))
            body.addView(hint(tr(R.string.vision_text_help)))
            queryInput = q
        }

        body.addView(label(tr(R.string.vision_zone_label)))
        val zoneTxt = hint("")
        body.addView(zoneTxt)
        val zoneBar = LinearLayout(ctx)
        zoneBar.orientation = LinearLayout.HORIZONTAL
        val zNear = button(tr(R.string.vision_zone_near), false)
        val zAll = button(tr(R.string.vision_zone_screen), false)
        val zPick = button(tr(R.string.vision_zone_pick), false)
        zoneBar.addView(zNear, weight(1f, 0, 4))
        zoneBar.addView(zAll, weight(1f, 0, 4))
        zoneBar.addView(zPick, weight(1f, 0, 0))
        body.addView(zoneBar, matchWrap(4))
        body.addView(hint(tr(if (draft.textMode) R.string.vision_text_zone_hint else R.string.vision_zone_hint)))
        fun paintZone() {
            val z = draft.zone
            zoneTxt.text = if (z[0] <= 0.001f && z[1] <= 0.001f && z[2] >= 0.999f && z[3] >= 0.999f) tr(R.string.vision_zone_all_txt) else tr(R.string.vision_zone_summary, (z[0] * 100).roundToInt(), (z[2] * 100).roundToInt(), (z[1] * 100).roundToInt(), (z[3] * 100).roundToInt())
        }
        paintZone()

        val thLabel = label("")
        body.addView(thLabel)
        val seek = SeekBar(ctx)
        seek.max = 38
        seek.progress = (draft.threshold - 60).coerceIn(0, 38)
        fun paintTh() { thLabel.text = tr(if (draft.textMode) R.string.vision_text_threshold else R.string.vision_threshold_label, draft.threshold) }
        paintTh()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                draft.threshold = 60 + p
                paintTh()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        body.addView(seek, matchWrap(2))

        val testBtn = button(tr(R.string.vision_test), false)
        val testRes = hint("")
        testRes.gravity = Gravity.CENTER
        body.addView(testBtn, matchWrap(8))
        body.addView(testRes)

        body.addView(label(tr(R.string.vision_timer_label)))
        val timer = TimeInputView(ctx)
        timer.setMs(draft.delaySec * 1000L, TimeInputView.TimeUnit.SEC)
        body.addView(timer, matchWrap(4))

        body.addView(label(tr(R.string.vision_pause_label)))
        val pauseBtn = button("", false)
        fun paintPause() { pauseBtn.text = pauseLong(draft.pause) }
        paintPause()
        pauseBtn.setOnClickListener {
            draft.pause = when (draft.pause) {
                DetectorPause.NONE -> DetectorPause.ACTION
                DetectorPause.ACTION -> DetectorPause.ALL
                DetectorPause.ALL -> DetectorPause.NONE
            }
            paintPause()
        }
        body.addView(pauseBtn, matchWrap(4))

        body.addView(label(tr(R.string.vision_block_label)))
        val blockBtn = button("", false)
        fun paintBlock() {
            val g = store.groupList().firstOrNull { it.id == draft.groupId }
            blockBtn.text = g?.name ?: tr(R.string.vision_block_none)
        }
        paintBlock()
        body.addView(blockBtn, matchWrap(4))

        body.addView(label(tr(R.string.vision_repeat_label)))
        val repeat = TimeInputView(ctx)
        repeat.setMs(draft.repeatSec * 1000L, TimeInputView.TimeUnit.SEC)
        body.addView(repeat, matchWrap(4))

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        val btnCancel = button(tr(R.string.cancel), false)
        val btnSave = button(tr(R.string.save), true)
        bar.addView(btnCancel, weight(1f, 0, 8))
        bar.addView(btnSave, weight(1f, 8, 0))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = service.dp(10) })

        try {
            val dialog = AlertDialog.Builder(ctx).setView(root).create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnDismissListener {
                host.uiClear(dialog)
                if (!handedOff) tplBmp?.recycle()
                handedOff = false
            }

            fun pull() {
                draft.name = nameInput.text?.toString()?.trim().orEmpty().ifEmpty { tr(R.string.vision_name_default, 1) }
                draft.delaySec = (timer.getMs(0L, 0L, 86_400_000L) / 1000L).toInt()
                draft.repeatSec = (repeat.getMs(0L, 0L, 86_400_000L) / 1000L).toInt()
                if (draft.textMode) draft.query = queryInput?.text?.toString()?.trim().orEmpty()
            }
            addView?.setOnClickListener {
                pull()
                handedOff = true
                dialog.dismiss()
                onZoneCancel = { showEditor(draft) }
                startCapture { shot, rect ->
                    onZoneCancel = null
                    val part = try { Bitmap.createBitmap(shot, rect.left, rect.top, rect.width(), rect.height()) } catch (_: Exception) { null }
                    shot.recycle()
                    val ok = part != null && DetectorFiles.saveView(service, draft.id, part)
                    part?.recycle()
                    VisionMatch.forget(draft.id)
                    toast(if (ok) tr(R.string.vision_view_added, DetectorFiles.viewCount(service, draft.id)) else tr(R.string.vision_save_failed))
                    showEditor(draft)
                }
            }
            zNear.setOnClickListener {
                draft.zone = if (draft.textMode || tplBmp == null) TextQuery.BANNER_ZONE.copyOf() else nearZone(draft.cx, draft.cy, tplBmp.width / screenW(), tplBmp.height / screenH())
                paintZone()
            }
            zAll.setOnClickListener {
                draft.zone = floatArrayOf(0f, 0f, 1f, 1f)
                paintZone()
            }
            zPick.setOnClickListener {
                pull()

                handedOff = true
                dialog.dismiss()
                pickZone(draft)
            }
            blockBtn.setOnClickListener {
                val groups = store.onDemandGroups()
                if (groups.isEmpty()) { toast(tr(R.string.vision_no_block)); return@setOnClickListener }
                host.uiPickBlock(groups, draft.groupId, tr(R.string.vision_block_label)) { id ->
                    draft.groupId = id
                    paintBlock()
                }

                main.postDelayed({ if (dialog.isShowing) host.uiReclaim(dialog) }, 400L)
            }
            testBtn.setOnClickListener {
                pull()
                testBtn.isEnabled = false
                testRes.text = tr(R.string.vision_test_running)
                if (draft.textMode) {
                    runTextTest(dialog, draft.query, draft.zone, draft.threshold) { msg ->
                        testBtn.isEnabled = true
                        testRes.text = msg
                    }
                    return@setOnClickListener
                }
                val bmp = tplBmp ?: run { testBtn.isEnabled = true; return@setOnClickListener }
                val tpls = ArrayList<VisionTemplate>()
                tpls.add(VisionMatch.templateFromBitmap(bmp))
                val hasMain = DetectorFiles.file(service, draft.id).exists()
                val saved = VisionMatch.templatesFor(service, draft.id)
                tpls.addAll(if (hasMain && saved.isNotEmpty()) saved.drop(1) else saved)
                runTest(dialog, tpls, draft.zone, draft.threshold) { msg ->
                    testBtn.isEnabled = true
                    testRes.text = msg
                }
            }
            btnCancel.setOnClickListener { dialog.dismiss() }
            btnSave.setOnClickListener {
                pull()
                val q = if (draft.textMode) draft.query.trim() else ""
                if (draft.textMode && TextQuery.phrases(q).isEmpty()) { toast(tr(R.string.vision_text_need)); return@setOnClickListener }
                val bmp = tplBmp
                if (!draft.textMode) {
                    if (bmp == null) { toast(tr(R.string.vision_save_failed)); return@setOnClickListener }
                    val ok = (!draft.tplChanged || DetectorFiles.save(service, draft.id, bmp))
                    if (!ok) { toast(tr(R.string.vision_save_failed)); return@setOnClickListener }
                }
                VisionMatch.forget(draft.id)
                val z = draft.zone
                val saved = store.saveDetector(Detector(draft.id, draft.name, draft.enabled, bmp?.width ?: 0, bmp?.height ?: 0, z[0], z[1], z[2], z[3], draft.threshold, draft.delaySec, draft.groupId, draft.pause, draft.repeatSec, q))
                if (!saved) { toast(tr(R.string.vision_max)); return@setOnClickListener }
                toast(tr(R.string.vision_saved))
                dialog.dismiss()
            }
            host.uiOpen(dialog)
        } catch (e: Exception) {
            host.uiUnlock()
            Log.e(TAG, "vision editor", e)
        }
    }

    private var handedOff = false

    private fun screenW(): Float = screenBounds(service).width().toFloat()
    private fun screenH(): Float = screenBounds(service).height().toFloat()

    private fun dialogScrollMaxH(): Int {
        val b = screenBounds(service)
        val ratio = if (b.width() > b.height()) 0.50f else 0.52f
        return (b.height() * ratio).toInt().coerceAtLeast(service.dp(100))
    }

    private fun limitScrollOnce(scroll: ScrollView, maxH: Int) {
        scroll.post {
            val child = scroll.getChildAt(0) ?: return@post
            val need = if (child.height > 0) child.height else child.measuredHeight
            if (need > maxH) {
                val lp = scroll.layoutParams ?: return@post
                lp.height = maxH
                scroll.layoutParams = lp
            }
        }
    }

    private fun pickZone(draft: Draft) {
        onZoneCancel = { showEditor(draft) }
        startCapture(zoneMode = true, init = draft.zone) { shot, rect ->
            val w = shot.width.toFloat()
            val h = shot.height.toFloat()
            shot.recycle()
            onZoneCancel = null
            draft.zone = floatArrayOf(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h)
            showEditor(draft)
        }

        main.postDelayed({ if (!capturing && cropRoot == null && onZoneCancel != null) { onZoneCancel = null; showEditor(draft) } }, 2500L)
    }

    private fun runTextTest(dialog: AlertDialog, query: String, zone: FloatArray, threshold: Int, done: (String) -> Unit) {
        val win = dialog.window
        val decor = win?.decorView
        val attrs = win?.attributes
        val oldDim = attrs?.dimAmount ?: 0f
        decor?.visibility = View.INVISIBLE
        if (attrs != null) {
            attrs.dimAmount = 0f
            try { win.attributes = attrs } catch (_: Exception) {}
        }
        val back = host.uiParkAll()
        main.postDelayed({
            service.visionWatcher().probeText(query, zone[0], zone[1], zone[2], zone[3]) { p ->
                back()
                decor?.visibility = View.VISIBLE
                if (attrs != null) {
                    attrs.dimAmount = oldDim
                    try { win.attributes = attrs } catch (_: Exception) {}
                }
                val seen = shortSeen(p.seen)
                val pct = (p.score * 100).roundToInt()
                val msg = when {
                    p.blank -> tr(R.string.vision_test_black)
                    p.errorCode != 0 -> tr(R.string.vision_test_fail, p.errorCode)
                    p.score * 100f >= threshold -> tr(R.string.vision_text_found, pct, seen)
                    p.seen.isBlank() -> tr(R.string.vision_text_empty)
                    else -> tr(R.string.vision_text_low, pct, threshold, seen)
                }
                done(msg)
            }
        }, 300L)
    }

    private fun shortSeen(s: String): String {
        val t = s.replace('\n', ' ').trim()
        if (t.isEmpty()) return tr(R.string.vision_text_empty)
        return if (t.length > 70) t.take(70) + "..." else t
    }

    private fun runTest(dialog: AlertDialog, tpls: List<VisionTemplate>, zone: FloatArray, threshold: Int, done: (String) -> Unit) {
        val win = dialog.window
        val decor = win?.decorView
        val attrs = win?.attributes
        val oldDim = attrs?.dimAmount ?: 0f
        decor?.visibility = View.INVISIBLE
        if (attrs != null) {
            attrs.dimAmount = 0f
            try { win.attributes = attrs } catch (_: Exception) {}
        }
        val back = host.uiParkAll()
        main.postDelayed({
            service.visionWatcher().probe(tpls, zone[0], zone[1], zone[2], zone[3]) { p ->
                back()
                decor?.visibility = View.VISIBLE
                if (attrs != null) {
                    attrs.dimAmount = oldDim
                    try { win.attributes = attrs } catch (_: Exception) {}
                }
                val msg = when {
                    p.blank -> tr(R.string.vision_test_black)
                    p.errorCode != 0 -> tr(R.string.vision_test_fail, p.errorCode)
                    p.ok && p.score * 100f >= threshold -> tr(R.string.vision_test_found, (p.score * 100).roundToInt(), p.x, p.y)
                    p.ok -> tr(R.string.vision_test_low, (p.score * 100).roundToInt(), p.x, p.y, threshold)
                    else -> tr(R.string.vision_test_not_found)
                }
                done(msg)
            }
        }, 300L)
    }

    private fun pauseShort(p: DetectorPause): String = when (p) {
        DetectorPause.NONE -> tr(R.string.vision_pause_none_short)
        DetectorPause.ALL -> tr(R.string.vision_pause_all_short)
        DetectorPause.ACTION -> tr(R.string.vision_pause_action_short)
    }

    private fun pauseLong(p: DetectorPause): String = when (p) {
        DetectorPause.NONE -> tr(R.string.vision_pause_none)
        DetectorPause.ALL -> tr(R.string.vision_pause_all)
        DetectorPause.ACTION -> tr(R.string.vision_pause_action)
    }

    private fun fmtSec(s: Int): String {
        if (s <= 0) return tr(R.string.vision_instant)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%dh%02d", h, m) else if (m > 0) (if (sec > 0) "${m}m${sec}s" else "${m}min") else "${sec}s"
    }

    private fun toast(msg: String) { Toast.makeText(service, msg, Toast.LENGTH_SHORT).show() }

    private fun title(t: String): TextView {
        val v = TextView(ctx)
        v.text = t
        v.setTextColor(service.getColor(R.color.gold))
        v.textSize = 18f
        v.paint.isFakeBoldText = true
        v.gravity = Gravity.CENTER
        return v
    }

    private fun hint(t: String): TextView {
        val v = TextView(ctx)
        v.text = t
        v.setTextColor(service.getColor(R.color.text_sub))
        v.textSize = 12f
        v.setPadding(0, service.dp(4), 0, 0)
        return v
    }

    private fun label(t: String): TextView {
        val v = TextView(ctx)
        v.text = t
        v.setTextColor(service.getColor(R.color.gold_soft))
        v.textSize = 13f
        v.paint.isFakeBoldText = true
        v.setPadding(0, service.dp(12), 0, 0)
        return v
    }

    private fun button(t: String, primary: Boolean): TextView {
        val v = TextView(ctx)
        v.text = t
        v.gravity = Gravity.CENTER
        v.textSize = 13f
        v.paint.isFakeBoldText = true
        v.setTextColor(service.getColor(if (primary) R.color.black else R.color.gold))
        v.setBackgroundResource(if (primary) R.drawable.bg_dialog_btn_primary else R.drawable.bg_dialog_btn)
        v.setPadding(service.dp(8), 0, service.dp(8), 0)
        v.minHeight = service.dp(40)
        return v
    }

    private fun chip(t: String, on: Boolean): TextView {
        val v = TextView(ctx)
        v.text = t
        v.gravity = Gravity.CENTER
        v.textSize = 11f
        v.paint.isFakeBoldText = true
        v.setTextColor(service.getColor(if (on) R.color.black else R.color.gold))
        v.setBackgroundResource(if (on) R.drawable.bg_dialog_btn_primary else R.drawable.bg_dialog_btn)
        v.setPadding(service.dp(8), 0, service.dp(8), 0)
        return v
    }

    private fun chipLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, service.dp(32))
        lp.leftMargin = service.dp(4)
        return lp
    }

    private fun weight(w: Float, left: Int, right: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, service.dp(42), w)
        lp.leftMargin = service.dp(left)
        lp.rightMargin = service.dp(right)
        return lp
    }

    private fun matchWrap(top: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = service.dp(top)
        return lp
    }

    private inner class CropView(c: Context, private val bmp: Bitmap, private val accent: Int) : View(c) {
        private var sel: RectF? = null
        private val paintBmp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply { color = Color.parseColor("#99000000") }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = service.dp(2).toFloat()
            color = accent
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private var mode = 0
        private var anchorX = 0f
        private var anchorY = 0f
        private var corner = -1
        private var lastX = 0f
        private var lastY = 0f
        private val dst = Rect()

        fun setSelection(r: RectF) {
            sel = RectF(r)
            invalidate()
        }

        fun selectionInBitmap(): Rect? {
            val s = sel ?: return null
            if (width <= 0 || height <= 0) return null
            val sx = bmp.width / width.toFloat()
            val sy = bmp.height / height.toFloat()
            val l = (s.left * sx).roundToInt().coerceIn(0, bmp.width - 1)
            val t = (s.top * sy).roundToInt().coerceIn(0, bmp.height - 1)
            val r = (s.right * sx).roundToInt().coerceIn(l + 1, bmp.width)
            val b = (s.bottom * sy).roundToInt().coerceIn(t + 1, bmp.height)
            return Rect(l, t, r, b)
        }

        override fun onDraw(canvas: Canvas) {
            dst.set(0, 0, width, height)
            canvas.drawBitmap(bmp, null, dst, paintBmp)
            val s = sel
            val w = width.toFloat()
            val h = height.toFloat()
            if (s == null) {
                canvas.drawRect(0f, 0f, w, h, dim)
                return
            }
            canvas.drawRect(0f, 0f, w, s.top, dim)
            canvas.drawRect(0f, s.bottom, w, h, dim)
            canvas.drawRect(0f, s.top, s.left, s.bottom, dim)
            canvas.drawRect(s.right, s.top, w, s.bottom, dim)
            canvas.drawRect(s, stroke)
            val r = service.dp(5).toFloat()
            canvas.drawCircle(s.left, s.top, r, fill)
            canvas.drawCircle(s.right, s.top, r, fill)
            canvas.drawCircle(s.left, s.bottom, r, fill)
            canvas.drawCircle(s.right, s.bottom, r, fill)
        }

        private fun hitCorner(s: RectF, x: Float, y: Float): Int {
            val reach = service.dp(30).toFloat()
            val pts = arrayOf(floatArrayOf(s.left, s.top), floatArrayOf(s.right, s.top), floatArrayOf(s.left, s.bottom), floatArrayOf(s.right, s.bottom))
            var best = -1
            var bestD = reach
            for (i in pts.indices) {
                val d = max(abs(pts[i][0] - x), abs(pts[i][1] - y))
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            return best
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x.coerceIn(0f, width.toFloat())
            val y = e.y.coerceIn(0f, height.toFloat())
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val s = sel
                    mode = 1
                    if (s != null) {
                        corner = hitCorner(s, x, y)
                        if (corner >= 0) {
                            mode = 3

                            anchorX = if (corner % 2 == 0) s.right else s.left
                            anchorY = if (corner < 2) s.bottom else s.top
                        } else if (s.contains(x, y)) {
                            mode = 2
                        }
                    }
                    if (mode == 1) {
                        anchorX = x
                        anchorY = y
                        sel = RectF(x, y, x, y)
                    }
                    lastX = x
                    lastY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = sel
                    if (s != null) {
                        if (mode == 1 || mode == 3) {
                            s.set(min(anchorX, x), min(anchorY, y), max(anchorX, x), max(anchorY, y))
                        } else if (mode == 2) {
                            var dx = x - lastX
                            var dy = y - lastY
                            if (s.left + dx < 0f) dx = -s.left
                            if (s.right + dx > width) dx = width - s.right
                            if (s.top + dy < 0f) dy = -s.top
                            if (s.bottom + dy > height) dy = height - s.bottom
                            s.offset(dx, dy)
                        }
                        invalidate()
                    }
                    lastX = x
                    lastY = y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
            }
            return true
        }
    }

    companion object {
        private const val TAG = "HeVisionUi"
        private const val MIN_SIDE = 20
    }
}
