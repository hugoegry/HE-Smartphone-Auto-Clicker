package com.he.he_smartphone_auto_clicker.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.he.he_smartphone_auto_clicker.R
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.databinding.DialogActionBinding
import com.he.he_smartphone_auto_clicker.databinding.DialogPresetBinding
import com.he.he_smartphone_auto_clicker.databinding.OverlayMenuBinding
import com.he.he_smartphone_auto_clicker.i18n.I18n
import com.he.he_smartphone_auto_clicker.i18n.tr
import com.he.he_smartphone_auto_clicker.model.ActionGroup
import com.he.he_smartphone_auto_clicker.model.ActionKind
import com.he.he_smartphone_auto_clicker.model.ClickerMode
import com.he.he_smartphone_auto_clicker.model.GroupRunMode
import com.he.he_smartphone_auto_clicker.util.dp
import com.he.he_smartphone_auto_clicker.util.screenBounds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.lang.reflect.Proxy
import kotlin.math.roundToInt

class OverlayController(private val service: ClickerAccessibilityService) {
    private val overlayCtx: Context = I18n.wrap(ContextThemeWrapper(service, R.style.Theme_HeSmartPhoneAutoClicker))
    private fun tr(@androidx.annotation.StringRes id: Int): String = overlayCtx.tr(id)
    private fun tr(@androidx.annotation.StringRes id: Int, vararg args: Any?): String = overlayCtx.tr(id, *args)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var menuView: View? = null
    private var menuBinding: OverlayMenuBinding? = null
    private val lineWindows = mutableListOf<LineWindow>()
    private val joyWindows = mutableListOf<JoyWindow>()
    private val pointWindows = mutableListOf<PointWindow>()
    private var selectedId: Long? = null
    private var openDialog: AlertDialog? = null
    private var dialogBusy = false
    private var compactMode = false
    private var moveDragging = false
    private var moveStartRawX = 0f
    private var moveStartRawY = 0f
    private var compactAnimator: ValueAnimator? = null
    private var barMidFull = -1
    private var fixedWindowH = -1
    private var passObserver: ViewTreeObserver? = null
    private var lastLandscape: Boolean? = null
    private data class PointWindow(val actionId: Long, val end: Boolean, val view: View, val params: WindowManager.LayoutParams)
    private data class LineWindow(val actionId: Long, val view: SegmentView, val params: WindowManager.LayoutParams)
    private data class JoyWindow(val actionId: Long, val view: JoyPadView, val params: WindowManager.LayoutParams)

    fun show() {
        try {
            if (ClickerStore.get(service).consumeOpenCollapsed()) {
                dismissOpenDialog()
                compactMode = true
            }
            if (menuView == null) attachMenu()
            syncPoints(animate = false)
            applyCompactUi(animate = false)
            applyMarkersState(animate = false)
            bringMenuToFront()
        } catch (e: Exception) {
            Log.e(TAG, "show", e)
        }
    }

    fun hide() {
        dismissOpenDialog()
        compactAnimator?.cancel()
        compactAnimator = null
        clearBarAnimCache()
        fixedWindowH = -1
        passObserver = null
        compactMode = false
        pointWindows.forEach { safeRemove(it.view) }
        pointWindows.clear()
        clearLines()
        safeRemove(menuView)
        menuView = null
        menuBinding = null
        selectedId = null
        setVisionStatus(null)
        setLongTimer(null)
        visionUi.dispose()
    }

    fun onRunStarted() {
        applyMarkersState(animate = false)
        setPlayIcon(true)
        setKeepScreen(true)
    }

    fun onRunStopped() {
        setPlayIcon(false)
        clearPlayPulse()
        applyMarkersState(animate = false)
    }

    fun pulseAction(actionId: Long) {
        if (openDialog?.isShowing == true) return
        pointWindows.filter { it.actionId == actionId }.forEach { holder ->
            val ring = (holder.view as? LinearLayout)?.getChildAt(0) ?: holder.view
            ring.animate().cancel()
            ring.scaleX = 1f
            ring.scaleY = 1f
            ring.alpha = 1f
            ring.animate().scaleX(0.78f).scaleY(0.78f).alpha(0.55f).setDuration(70).withEndAction {
                ring.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
            }.start()
        }
    }

    private fun clearPlayPulse() {
        pointWindows.forEach { holder ->
            holder.view.animate().cancel()
            holder.view.scaleX = 1f
            holder.view.scaleY = 1f
            val ring = (holder.view as? LinearLayout)?.getChildAt(0)
            ring?.animate()?.cancel()
            ring?.scaleX = 1f
            ring?.scaleY = 1f
            ring?.alpha = 1f
        }
    }

    fun parkHits(points: List<Pair<Float, Float>>): () -> Unit {
        if (points.isEmpty()) return {}
        data class Back(val view: View, val params: WindowManager.LayoutParams, val x: Int, val y: Int)
        val backs = ArrayList<Back>()
        fun tryPark(view: View, params: WindowManager.LayoutParams) {
            if (params.width <= 0 || params.height <= 0) return
            val right = params.x + params.width
            val bottom = params.y + params.height
            val hit = points.any { (px, py) -> px >= params.x && px < right && py >= params.y && py < bottom }
            if (!hit) return
            backs.add(Back(view, params, params.x, params.y))
            params.x -= 8000
            try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
        }
        pointWindows.forEach { tryPark(it.view, it.params) }
        lineWindows.forEach { tryPark(it.view, it.params) }
        joyWindows.forEach { tryPark(it.view, it.params) }
        val menu = menuView
        val menuLp = menu?.layoutParams as? WindowManager.LayoutParams
        if (menu != null && menuLp != null) tryPark(menu, menuLp)
        var restored = false
        return restore@{
            if (restored) return@restore
            restored = true
            for (b in backs) {
                b.params.x = b.x
                b.params.y = b.y
                try { wm.updateViewLayout(b.view, b.params) } catch (_: Exception) {}
            }
        }
    }

    fun setPlayIcon(isRunning: Boolean) {
        val icon = if (isRunning) R.drawable.ic_pause else R.drawable.ic_play
        menuBinding?.btnPlay?.setImageResource(icon)
        if (!isRunning) setKeepScreen(false)
    }

    private fun attachMenu() {
        val themed = overlayCtx
        val binding = OverlayMenuBinding.inflate(LayoutInflater.from(themed))
        listOf(binding.btnPlay, binding.btnAdd, binding.btnSwipe, binding.btnPinch, binding.btnJoystick, binding.btnBlocks, binding.btnVision, binding.btnClose, binding.btnGear, binding.btnExit, binding.btnMove).forEach {
            it.imageTintList = null
            it.backgroundTintList = null
        }

        binding.btnPlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) service.startOrStop()
            true
        }
        binding.btnAdd.setOnClickListener { addGesture(ActionKind.CLICK) }
        binding.btnSwipe.setOnClickListener { addGesture(ActionKind.SWIPE) }
        binding.btnPinch.setOnClickListener { addGesture(ActionKind.PINCH) }
        binding.btnJoystick.setOnClickListener { addGesture(ActionKind.JOYSTICK) }
        binding.btnBlocks.setOnClickListener { showBlocksPanel() }
        binding.btnVision.setOnClickListener { visionUi.showPanel() }
        binding.btnClose.setOnClickListener { onRemove() }
        binding.btnGear.setOnClickListener { showPresetPanel() }
        binding.btnExit.setOnClickListener { service.closeFromOverlay() }
        binding.btnMove.setOnTouchListener { _, event -> onMoveOrToggle(event) }
        fixedWindowH = -1
        clearBarAnimCache()
        applyBarOrientation(binding)
        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        placeMenu(params)
        wm.addView(binding.root, params)
        menuView = binding.root
        menuBinding = binding
        lastLandscape = isLandscape()
        applyCompactUi(animate = false)
        binding.root.post { keepBarOnScreen() }
    }

    private fun isLandscape(): Boolean {
        val b = screenBounds(service)
        return b.width() > b.height()
    }

    private fun applyBarOrientation(binding: OverlayMenuBinding) {
        val land = isLandscape()
        val panel = binding.menuPanel
        val mid = binding.menuMid
        val root = binding.root
        val rootLp = (root.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (land) {
            rootLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            root.layoutParams = rootLp
            panel.orientation = LinearLayout.HORIZONTAL
            mid.orientation = LinearLayout.HORIZONTAL
            panel.gravity = Gravity.CENTER_VERTICAL
            mid.gravity = Gravity.CENTER_VERTICAL
            (panel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                panel.layoutParams = lp
            }
            mid.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            panel.setPadding(service.dp(6), service.dp(4), service.dp(6), service.dp(4))
            listOf(binding.btnPlay, binding.btnAdd, binding.btnSwipe, binding.btnPinch, binding.btnJoystick, binding.btnBlocks, binding.btnVision, binding.btnClose, binding.btnGear, binding.btnExit, binding.btnMove).forEach { styleMenuBtn(it, true) }
        } else {
            rootLp.width = service.dp(52)
            rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            root.layoutParams = rootLp
            panel.orientation = LinearLayout.VERTICAL
            mid.orientation = LinearLayout.VERTICAL
            panel.gravity = Gravity.CENTER_HORIZONTAL
            mid.gravity = Gravity.CENTER_HORIZONTAL
            (panel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.BOTTOM
                panel.layoutParams = lp
            }
            mid.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            panel.setPadding(service.dp(4), service.dp(8), service.dp(4), service.dp(8))
            listOf(binding.btnPlay, binding.btnAdd, binding.btnSwipe, binding.btnPinch, binding.btnJoystick, binding.btnBlocks, binding.btnVision, binding.btnClose, binding.btnGear, binding.btnExit, binding.btnMove).forEach { styleMenuBtn(it, false) }
            (binding.btnMove.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = service.dp(4); binding.btnMove.layoutParams = it }
        }
    }

    private fun styleMenuBtn(btn: ImageButton, land: Boolean) {
        val lp = btn.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (land) {
            lp.width = service.dp(36)
            lp.height = service.dp(36)
            lp.topMargin = 0
            lp.marginStart = service.dp(1)
            lp.marginEnd = service.dp(1)
        } else {
            lp.width = service.dp(42)
            lp.height = service.dp(40)
            lp.topMargin = service.dp(2)
            lp.marginStart = 0
            lp.marginEnd = 0
        }
        btn.layoutParams = lp
    }

    private fun placeMenu(params: WindowManager.LayoutParams) {
        val b = screenBounds(service)
        val land = b.width() > b.height()
        val store = ClickerStore.get(service)
        val menuW = service.dp(if (land) 420 else 52)
        val menuH = service.dp(if (land) 52 else 300)
        if (store.menuX >= 0f && store.menuY >= 0f) {
            params.x = (store.menuX * b.width()).toInt().coerceIn(0, (b.width() - menuW).coerceAtLeast(0))
            params.y = (store.menuY * b.height()).toInt().coerceIn(0, (b.height() - menuH).coerceAtLeast(0))
        } else if (land) {
            params.x = service.dp(8)
            params.y = (b.height() - service.dp(64)).coerceAtLeast(0)
        } else {
            params.x = service.dp(8)
            params.y = (b.height() * 0.18f).toInt()
        }
    }

    fun onOrientationChanged() {
        val land = isLandscape()
        if (lastLandscape == land) return
        lastLandscape = land
        if (menuView == null) return
        dismissOpenDialog()
        compactAnimator?.cancel()
        compactAnimator = null
        val keepCompact = compactMode
        safeRemove(menuView)
        menuView = null
        menuBinding = null
        fixedWindowH = -1
        clearBarAnimCache()
        passObserver = null
        compactMode = keepCompact
        attachMenu()
        applyCompactUi(animate = false)
        syncPoints(animate = false)
        applyMarkersState(animate = false)
        bringMenuToFront()
    }

    private fun onMoveOrToggle(event: MotionEvent): Boolean {
        val menu = menuView ?: return true
        val lp = menu.layoutParams as? WindowManager.LayoutParams ?: return true
        val slop = service.dp(8).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moveDragging = false
                moveStartRawX = event.rawX
                moveStartRawY = event.rawY
                menu.tag = floatArrayOf(event.rawX - lp.x, event.rawY - lp.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moveDragging && (abs(event.rawX - moveStartRawX) > slop || abs(event.rawY - moveStartRawY) > slop)) {
                    moveDragging = true
                }
                if (!moveDragging) return true
                val off = menu.tag as? FloatArray ?: return true
                val b = screenBounds(service)
                val land = b.width() > b.height()
                val w = if (menu.width > 0) menu.width else service.dp(if (land) 220 else 52)
                val h = if (menu.height > 0) menu.height else service.dp(if (land) 52 else if (compactMode) 140 else 300)
                val panelH = menuBinding?.menuPanel?.height?.takeIf { it > 0 } ?: h
                val gap = if (land) 0 else (h - panelH).coerceAtLeast(0)
                lp.x = (event.rawX - off[0]).toInt().coerceIn(0, (b.width() - w).coerceAtLeast(0))
                lp.y = (event.rawY - off[1]).toInt().coerceIn(-gap, (b.height() - h).coerceAtLeast(-gap))
                try { wm.updateViewLayout(menu, lp) } catch (_: Exception) {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moveDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    toggleCompactMode()
                } else if (moveDragging) {
                    val b = screenBounds(service)
                    if (b.width() > 0 && b.height() > 0) {
                        val store = ClickerStore.get(service)
                        store.menuX = lp.x / b.width().toFloat()
                        store.menuY = lp.y / b.height().toFloat()
                    }
                }
            }
        }
        return true
    }

    private fun toggleCompactMode() {
        compactMode = !compactMode
        applyCompactUi(animate = true)
        applyMarkersState(animate = true)
        keepBarOnScreen()
    }

    private fun keepBarOnScreen() {
        if (!isLandscape()) return
        val menu = menuView ?: return
        val lp = menu.layoutParams as? WindowManager.LayoutParams ?: return
        val b = screenBounds(service)
        menu.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val w = menu.measuredWidth.coerceAtLeast(1)
        val h = menu.measuredHeight.coerceAtLeast(1)
        val oldX = lp.x
        val oldY = lp.y
        lp.width = w
        lp.height = h
        lp.x = lp.x.coerceIn(0, (b.width() - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (b.height() - h).coerceAtLeast(0))
        try { wm.updateViewLayout(menu, lp) } catch (_: Exception) {}
        if ((lp.x != oldX || lp.y != oldY) && b.width() > 0 && b.height() > 0) {
            val store = ClickerStore.get(service)
            store.menuX = lp.x / b.width().toFloat()
            store.menuY = lp.y / b.height().toFloat()
        }
    }

    private fun applyCompactUi(animate: Boolean) {
        val binding = menuBinding ?: return
        val menu = menuView ?: return
        val lp = menu.layoutParams as? WindowManager.LayoutParams ?: return
        val mid = binding.menuMid
        val panel = binding.menuPanel
        compactAnimator?.cancel()
        compactAnimator = null
        resetMidChildrenVisual(mid)
        panel.translationY = 0f
        if (isLandscape()) {
            clearBarAnimCache()
            fixedWindowH = -1
            mid.alpha = 1f
            setMidHeight(mid, ViewGroup.LayoutParams.WRAP_CONTENT)
            mid.visibility = if (compactMode) View.GONE else View.VISIBLE
            keepBarOnScreen()
            installPassThrough(menu, panel)
            return
        }
        if (!animate) {
            clearBarAnimCache()
            mid.visibility = View.VISIBLE
            if (compactMode) {
                setMidHeight(mid, 0)
                mid.alpha = 0f
            } else {
                setMidHeight(mid, ViewGroup.LayoutParams.WRAP_CONTENT)
                mid.alpha = 1f
            }
            freezeWindow(menu, lp, binding)
            installPassThrough(menu, panel)
            return
        }

        freezeWindow(menu, lp, binding)
        val menuW = if (menu.width > 0) menu.width else service.dp(52)
        val laid = when {
            mid.visibility != View.VISIBLE -> 0
            mid.layoutParams.height > 0 -> mid.layoutParams.height
            else -> mid.height.coerceAtLeast(0)
        }
        if (barMidFull <= 0) barMidFull = measureMidFull(mid, menuW)
        val to = if (compactMode) 0 else barMidFull
        if (laid == to) {
            setMidHeight(mid, to)
            mid.alpha = if (compactMode) 0f else 1f
            return
        }
        mid.visibility = View.VISIBLE
        mid.clipChildren = true
        setMidHeight(mid, laid)
        mid.alpha = laid / barMidFull.toFloat()
        val anim = ValueAnimator.ofFloat(laid.toFloat(), to.toFloat())
        anim.duration = 380
        anim.interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        anim.addUpdateListener { va ->
            val h = (va.animatedValue as Float).roundToInt().coerceIn(0, barMidFull)
            setMidHeight(mid, h)
            mid.alpha = h / barMidFull.toFloat()
        }
        var dropped = false
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) { dropped = true }
            override fun onAnimationEnd(animation: Animator) {
                if (dropped) return
                val end = if (compactMode) 0 else barMidFull
                setMidHeight(mid, end)
                mid.alpha = if (compactMode) 0f else 1f
                mid.visibility = View.VISIBLE
                resetMidChildrenVisual(mid)
                compactAnimator = null
            }
        })
        compactAnimator = anim
        anim.start()
    }

    private fun clearBarAnimCache() { barMidFull = -1 }

    private fun freezeWindow(menu: View, lp: WindowManager.LayoutParams, binding: OverlayMenuBinding) {
        val w = service.dp(52)
        if (fixedWindowH <= 0) {

            fixedWindowH = measureExpanded(menu, binding.menuMid, w)

            val b = screenBounds(service)
            lp.y = lp.y.coerceAtMost((b.height() - fixedWindowH).coerceAtLeast(0))
        }
        if (fixedWindowH <= 0) return
        if (lp.width == w && lp.height == fixedWindowH) {
            try { wm.updateViewLayout(menu, lp) } catch (_: Exception) {}
            return
        }
        lp.width = w
        lp.height = fixedWindowH
        try { wm.updateViewLayout(menu, lp) } catch (_: Exception) {}
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun installPassThrough(root: View, panel: View) {
        val observer = root.viewTreeObserver
        if (!observer.isAlive || passObserver === observer) return
        try {
            val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val regionField = infoClass.getDeclaredField("touchableRegion").apply { isAccessible = true }
            val setInsets = infoClass.getDeclaredMethod("setTouchableInsets", Int::class.javaPrimitiveType).apply { isAccessible = true }
            val regionMode = infoClass.getDeclaredField("TOUCHABLE_INSETS_REGION").getInt(null)
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        val info = args?.getOrNull(0) ?: return@newProxyInstance null
                        val region = regionField.get(info) as Region
                        val xy = IntArray(2)
                        panel.getLocationInWindow(xy)
                        if (panel.width > 0 && panel.height > 0) region.set(xy[0], xy[1], xy[0] + panel.width, xy[1] + panel.height)
                        setInsets.invoke(info, regionMode)
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> args?.getOrNull(0) === proxy
                    else -> null
                }
            }
            val add = ViewTreeObserver::class.java.getDeclaredMethod("addOnComputeInternalInsetsListener", listenerClass)
            add.isAccessible = true
            add.invoke(observer, listener)
            passObserver = observer
        } catch (e: Exception) {
            Log.e(TAG, "pass through", e)
        }
    }

    private fun resetMidChildrenVisual(mid: ViewGroup) {
        for (i in 0 until mid.childCount) {
            val child = mid.getChildAt(i)
            child.alpha = 1f
            child.translationY = 0f
            child.scaleX = 1f
            child.scaleY = 1f
        }
    }

    private fun setMidHeight(mid: View, height: Int) {
        val p = mid.layoutParams ?: return
        if (p.height == height) return
        p.height = height
        mid.layoutParams = p
    }

    private fun measureViewHeight(view: View, width: Int): Int {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return view.measuredHeight
    }

    private fun measureExpanded(root: View, mid: View, width: Int): Int {
        val lp = mid.layoutParams
        val oldH = lp.height
        val oldV = mid.visibility
        mid.visibility = View.VISIBLE
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = root.measuredHeight.coerceAtLeast(1)
        lp.height = oldH
        mid.visibility = oldV
        mid.layoutParams = lp
        return h
    }

    private fun measureMidFull(mid: View, width: Int): Int {
        val lp = mid.layoutParams
        val oldH = lp.height
        val oldV = mid.visibility
        mid.visibility = View.VISIBLE
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        val h = measureViewHeight(mid, width).coerceAtLeast(1)
        lp.height = oldH
        mid.visibility = oldV
        mid.layoutParams = lp
        return h
    }

    private fun applyMarkersState(animate: Boolean, touchAlpha: Boolean = true) {
        val store = ClickerStore.get(service)
        val actions = store.actionList()
        val groupVis = store.groupList().associate { it.id to it.visible }
        val running = service.isRunning()
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        pointWindows.forEach { holder ->
            val groupId = actions.firstOrNull { it.id == holder.actionId }?.groupId
            val blockOn = groupId == null || groupVis[groupId] != false
            val show = !compactMode && blockOn
            val blockTouch = !show || running
            holder.params.flags = if (blockTouch) holder.params.flags or flag else holder.params.flags and flag.inv()
            try { wm.updateViewLayout(holder.view, holder.params) } catch (_: Exception) {}
            if (!touchAlpha) return@forEach
            val targetAlpha = if (show) 1f else 0f
            holder.view.animate().cancel()
            if (animate) {
                holder.view.animate().alpha(targetAlpha).setDuration(260).setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
            } else {
                holder.view.alpha = targetAlpha
            }
            holder.view.visibility = View.VISIBLE
        }
        lineWindows.forEach { holder ->
            val groupId = actions.firstOrNull { it.id == holder.actionId }?.groupId
            val blockOn = groupId == null || groupVis[groupId] != false
            val show = !compactMode && blockOn
            holder.params.flags = holder.params.flags or flag
            try { wm.updateViewLayout(holder.view, holder.params) } catch (_: Exception) {}
            if (!touchAlpha) return@forEach
            val targetAlpha = if (show) 1f else 0f
            holder.view.animate().cancel()
            if (animate) {
                holder.view.animate().alpha(targetAlpha).setDuration(260).setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
            } else {
                holder.view.alpha = targetAlpha
            }
        }
        joyWindows.forEach { holder ->
            val groupId = actions.firstOrNull { it.id == holder.actionId }?.groupId
            val blockOn = groupId == null || groupVis[groupId] != false
            val show = !compactMode && blockOn
            val blockTouch = !show || running
            holder.params.flags = if (blockTouch) holder.params.flags or flag else holder.params.flags and flag.inv()
            try { wm.updateViewLayout(holder.view, holder.params) } catch (_: Exception) {}
            if (!touchAlpha) return@forEach
            val targetAlpha = if (show) 1f else 0f
            holder.view.animate().cancel()
            if (animate) {
                holder.view.animate().alpha(targetAlpha).setDuration(260).setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
            } else {
                holder.view.alpha = targetAlpha
            }
        }
    }

    private data class PointSpec(val actionId: Long, val end: Boolean, val mark: String, val x: Float, val y: Float, val groupId: Long, val showLabel: Boolean, val doubleRing: Boolean = false)

    private fun syncPoints(animate: Boolean) {
        val store = ClickerStore.get(service)
        val actions = store.actionList()
        if (selectedId != null && actions.none { it.id == selectedId }) selectedId = null
        val specs = pointSpecs(actions)
        val wanted = specs.map { it.actionId to it.end }.toSet()
        val doomed = pointWindows.filter { (it.actionId to it.end) !in wanted }
        doomed.forEach { holder ->
            pointWindows.remove(holder)
            if (animate) fadeOutRemove(holder.view) else safeRemove(holder.view)
        }
        val lineIds = actions.filter { it.hasGuideLine() }.map { it.id }.toSet()
        val deadLines = lineWindows.filter { it.actionId !in lineIds }
        deadLines.forEach { holder ->
            lineWindows.remove(holder)
            if (animate) fadeOutRemove(holder.view) else safeRemove(holder.view)
        }
        val joyIds = actions.filter { it.kind == ActionKind.JOYSTICK }.map { it.id }.toSet()
        val deadJoy = joyWindows.filter { it.actionId !in joyIds }
        deadJoy.forEach { holder ->
            joyWindows.remove(holder)
            if (animate) fadeOutRemove(holder.view) else safeRemove(holder.view)
        }
        val b = screenBounds(service)
        for (action in actions) {
            if (!action.hasGuideLine()) continue
            if (lineWindows.none { it.actionId == action.id }) {
                addLineWindow(action.id, action.x * b.width(), action.y * b.height(), action.x2 * b.width(), action.y2 * b.height())
            }
            if (action.kind == ActionKind.JOYSTICK && joyWindows.none { it.actionId == action.id }) {
                addJoyWindow(action)
            }
        }
        for (spec in specs) {
            val holder = pointWindows.firstOrNull { it.actionId == spec.actionId && it.end == spec.end }
            if (holder == null) {
                addPointWindow(spec.actionId, spec.end, spec.mark, spec.x, spec.y, spec.showLabel, spec.groupId, spec.doubleRing)
                if (animate) {
                    val created = pointWindows.lastOrNull { it.actionId == spec.actionId && it.end == spec.end }?.view
                    val endAlpha = markerAlphaFor(spec.groupId)
                    created?.alpha = 0f
                    created?.scaleX = 0.86f
                    created?.scaleY = 0.86f
                    created?.animate()?.alpha(endAlpha)?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
                } else {
                    pointWindows.lastOrNull { it.actionId == spec.actionId && it.end == spec.end }?.view?.alpha = markerAlphaFor(spec.groupId)
                }
            } else {
                restylePoint(holder.view, spec, animate)
            }
        }
        lineIds.forEach { updateLineFromPoints(it) }
        joyIds.forEach { updateJoyFromAction(it) }
        setPlayIcon(service.isRunning())
        applyMarkersState(animate = false, touchAlpha = !animate)
        if (openDialog?.isShowing == true) hideMarkersUnderDialog()
    }

    private fun pointSpecs(actions: List<com.he.he_smartphone_auto_clicker.model.ClickerAction>): List<PointSpec> {
        val localNo = mutableMapOf<Long, Int>()
        val out = mutableListOf<PointSpec>()
        for (action in actions) {
            val mark = centerMarkFor(action, localNo)
            val dbl = action.kind == ActionKind.DOUBLE_CLICK
            if (action.isTwoPoint()) {
                out.add(PointSpec(action.id, false, mark, action.x, action.y, action.groupId, true, dbl))
                out.add(PointSpec(action.id, true, mark, action.x2, action.y2, action.groupId, false, dbl))
            } else {
                out.add(PointSpec(action.id, false, mark, action.x, action.y, action.groupId, true, dbl))
            }
        }
        return out
    }

    private fun restylePoint(root: View, spec: PointSpec, animate: Boolean) {
        val box = root as? LinearLayout ?: return
        val ring = box.getChildAt(0) as? FrameLayout ?: return
        val markView = ring.getChildAt(0) as? TextView
        val badge = if (box.childCount > 1) box.getChildAt(1) as? TextView else null
        val (blockName, blockColor) = blockStyle(spec.groupId)
        val markNow = markView?.text?.toString()
        val nameNow = badge?.text?.toString()
        val colorNow = ring.tag as? Int
        val markChanged = markView != null && markNow != spec.mark
        val nameChanged = badge != null && nameNow != blockName
        val colorChanged = colorNow != blockColor
        val changed = markChanged || nameChanged || colorChanged
        fun apply() {
            markView?.text = spec.mark
            markView?.setTextColor(blockColor)
            markView?.textSize = when {
                spec.mark == "R" -> 16f
                spec.mark.length >= 2 -> 13f
                else -> 16f
            }
            badge?.text = blockName
            badge?.setTextColor(blockColor)
            ring.background = pointBg(spec.end, blockColor, spec.doubleRing)
            ring.tag = blockColor
            root.contentDescription = "Target ${spec.mark} Â· $blockName"
        }
        if (!changed) return
        if (!animate) {
            apply()
            return
        }
        val endAlpha = markerAlphaFor(spec.groupId)
        root.animate().cancel()
        root.animate().scaleX(0.86f).scaleY(0.86f).alpha(0.45f).setDuration(90).withEndAction {
            apply()
            root.animate().scaleX(1f).scaleY(1f).alpha(endAlpha).setDuration(150).start()
        }.start()
    }

    private fun markerAlphaFor(groupId: Long): Float {
        if (compactMode) return 0f
        val on = ClickerStore.get(service).groupList().firstOrNull { it.id == groupId }?.visible != false
        return if (on) 1f else 0f
    }

    private fun fadeOutRemove(view: View) {
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(0.72f).scaleY(0.72f).setDuration(160).withEndAction { safeRemove(view) }.start()
    }

    private fun centerMarkFor(action: com.he.he_smartphone_auto_clicker.model.ClickerAction, localNo: MutableMap<Long, Int>): String {
        val mode = ClickerStore.get(service).groupList().firstOrNull { it.id == action.groupId }?.mode ?: GroupRunMode.SEQUENCE
        if (mode == GroupRunMode.RANDOM) {
            return when (action.kind) {
                ActionKind.DOUBLE_CLICK -> "RD"
                ActionKind.PINCH -> "P"
                ActionKind.MULTI_TOUCH -> "M"
                ActionKind.JOYSTICK -> "J"
                else -> "R"
            }
        }
        val n = (localNo[action.groupId] ?: 0) + 1
        localNo[action.groupId] = n
        return when (action.kind) {
            ActionKind.DOUBLE_CLICK -> "${n}D"
            ActionKind.JOYSTICK -> "${n}J"
            else -> n.toString()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addPointWindow(actionId: Long, end: Boolean, centerMark: String, xRatio: Float, yRatio: Float, showLabel: Boolean, groupId: Long, doubleRing: Boolean = false) {
        val size = pointSize()
        val badgeH = if (showLabel) service.dp(16) else 0
        val totalH = size + badgeH
        val b = screenBounds(service)
        val (blockName, blockColor) = blockStyle(groupId)
        val root = LinearLayout(service)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        val ring = FrameLayout(service)
            ring.background = pointBg(end, blockColor, doubleRing)
            ring.tag = blockColor
            ring.layoutParams = LinearLayout.LayoutParams(size, size)
        if (showLabel) {
            val numberView = TextView(service)
            numberView.text = centerMark
            numberView.setTextColor(blockColor)
            numberView.textSize = when {
                centerMark == "R" -> 16f
                centerMark.length >= 2 -> 13f
                else -> 16f
            }
            numberView.gravity = Gravity.CENTER
            numberView.paint.isFakeBoldText = true
            numberView.setShadowLayer(2f, 0f, 1f, Color.parseColor("#99000000"))
            numberView.setOnTouchListener { _, event -> root.onTouchEvent(event) }
            ring.addView(numberView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val badge = TextView(service)
            badge.text = blockName
            badge.setTextColor(blockColor)
            badge.textSize = 9f
            badge.gravity = Gravity.CENTER
            badge.paint.isFakeBoldText = true
            badge.setShadowLayer(2f, 0f, 1f, Color.parseColor("#CC000000"))
            badge.maxLines = 1
            badge.ellipsize = android.text.TextUtils.TruncateAt.END
            badge.setOnTouchListener { _, event -> root.onTouchEvent(event) }
            root.addView(ring)
            root.addView(badge, LinearLayout.LayoutParams(size, badgeH))
            root.contentDescription = "Target $centerMark Â· $blockName"
        } else {
            root.addView(ring)
            root.contentDescription = "Swipe end Â· $blockName"
        }
        val params = baseParams(size, totalH, true)
        params.x = (xRatio * b.width() - size / 2f).toInt()
        params.y = (yRatio * b.height() - size / 2f).toInt()
        root.setOnTouchListener { _, event ->
            if (service.isRunning()) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    root.tag = floatArrayOf(event.rawX, event.rawY, event.rawX - params.x, event.rawY - params.y, 0f)
                    select(actionId)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val tag = root.tag as? FloatArray ?: return@setOnTouchListener true
                    val slop = service.dp(8).toFloat()
                    if (abs(event.rawX - tag[0]) > slop || abs(event.rawY - tag[1]) > slop) tag[4] = 1f
                    if (tag[4] == 1f) {
                        params.x = (event.rawX - tag[2]).toInt()
                        params.y = (event.rawY - tag[3]).toInt()
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                        updateLineFromPoints(actionId)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dragged = (root.tag as? FloatArray)?.get(4) == 1f
                    if (dragged) savePoint(actionId, end, params) else showEditor(actionId)
                    true
                }
                else -> true
            }
        }
        wm.addView(root, params)
        pointWindows.add(PointWindow(actionId, end, root, params))
    }

    private fun select(id: Long) { selectedId = id }
    private fun blockStyle(groupId: Long): Pair<String, Int> {
        val groups = ClickerStore.get(service).groupList()
        val index = groups.indexOfFirst { it.id == groupId }.coerceAtLeast(0)
        val group = groups.getOrNull(index)
        val name = when {
            group == null -> "?"
            group.main -> tr(R.string.block_tag_main)
            else -> group.name.trim().ifEmpty { "B${index + 1}" }.take(7)
        }
        return name to BLOCK_COLORS[index % BLOCK_COLORS.size]
    }

    private fun pointBg(endPoint: Boolean, color: Int, doubleRing: Boolean = false): GradientDrawable {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        if (endPoint) {
            bg.setColor(color)
            bg.setStroke(0, Color.TRANSPARENT)
        } else {
            bg.setColor(Color.TRANSPARENT)
            bg.setStroke(service.dp(if (doubleRing) 6 else 4), color)
        }
        return bg
    }

    private fun savePoint(actionId: Long, end: Boolean, params: WindowManager.LayoutParams) {
        val b = screenBounds(service)
        val s = pointSize()
        var cx = params.x + s / 2f
        var cy = params.y + s / 2f
        cx = cx.coerceIn(s / 2f, b.width() - s / 2f)
        cy = cy.coerceIn(s / 2f, b.height() - s / 2f)
        params.x = (cx - s / 2f).toInt()
        params.y = (cy - s / 2f).toInt()
        val holder = pointWindows.firstOrNull { it.actionId == actionId && it.end == end }
        if (holder != null) {
            try { wm.updateViewLayout(holder.view, params) } catch (_: Exception) {}
        }
        val rx = cx / b.width().toFloat()
        val ry = cy / b.height().toFloat()
        val store = ClickerStore.get(service)
        if (actionId == ClickerStore.SINGLE_ID) store.updateSingle(rx, ry) else store.updatePoint(actionId, end, rx, ry)
        updateLineFromPoints(actionId)
    }

    private fun updateLineFromPoints(actionId: Long) {
        val holder = lineWindows.firstOrNull { it.actionId == actionId } ?: return
        val pts = pointWindows.filter { it.actionId == actionId }
        val start = pts.find { !it.end } ?: return
        val end = pts.find { it.end } ?: return
        val half = pointSize() / 2f
        layoutLine(holder, start.params.x + half, start.params.y + half, end.params.x + half, end.params.y + half)
    }

    private fun clearLines() {
        lineWindows.forEach { safeRemove(it.view) }
        lineWindows.clear()
        joyWindows.forEach { safeRemove(it.view) }
        joyWindows.clear()
    }

    private fun addJoyWindow(action: com.he.he_smartphone_auto_clicker.model.ClickerAction) {
        val view = JoyPadView(service)
        val params = baseParams(1, 1, true, false)
        val holder = JoyWindow(action.id, view, params)
        wireJoy(holder)
        paintJoy(holder, action)
        layoutJoy(holder, action)
        wm.addView(view, params)
        joyWindows.add(holder)
    }

    private fun wireJoy(holder: JoyWindow) {
        val view = holder.view
        val id = holder.actionId
        view.canInteract = { !service.isRunning() && !compactMode }
        view.onTap = {
            select(id)
            showEditor(id)
        }
        view.onMoveCenter = { screenCx, screenCy ->
            select(id)
            val b = screenBounds(service)
            val rx = (screenCx / b.width()).coerceIn(0.05f, 0.95f)
            val ry = (screenCy / b.height()).coerceIn(0.05f, 0.95f)
            ClickerStore.get(service).updatePoint(id, false, rx, ry)
            updateJoyFromAction(id)
        }
        view.onMoveAngleFrom = { ang ->
            select(id)
            ClickerStore.get(service).updateJoy(id, angleFrom = ang)
            updateJoyFromAction(id)
        }
        view.onMoveAngleTo = { ang ->
            select(id)
            ClickerStore.get(service).updateJoy(id, angleTo = ang)
            updateJoyFromAction(id)
        }
    }

    private fun paintJoy(holder: JoyWindow, action: com.he.he_smartphone_auto_clicker.model.ClickerAction) {
        val store = ClickerStore.get(service)
        val (blockName, color) = blockStyle(action.groupId)
        val mode = store.groupList().firstOrNull { it.id == action.groupId }?.mode ?: GroupRunMode.SEQUENCE
        val mark = if (mode == GroupRunMode.RANDOM) "J" else {
            val n = store.actionsInGroup(action.groupId).indexOfFirst { it.id == action.id }.let { if (it < 0) 1 else it + 1 }
            "${n}J"
        }
        holder.view.color = color
        holder.view.angleFrom = action.angleFrom
        holder.view.angleTo = action.angleTo
        holder.view.label = mark
        holder.view.blockName = blockName
    }

    private fun updateJoyFromAction(actionId: Long) {
        val action = ClickerStore.get(service).actionList().firstOrNull { it.id == actionId } ?: return
        if (action.kind != ActionKind.JOYSTICK) return
        val holder = joyWindows.firstOrNull { it.actionId == actionId } ?: run {
            addJoyWindow(action)
            return
        }
        paintJoy(holder, action)
        layoutJoy(holder, action)
    }

    private fun layoutJoy(holder: JoyWindow, action: com.he.he_smartphone_auto_clicker.model.ClickerAction) {
        val b = screenBounds(service)
        val rPx = (action.radius.coerceIn(0.02f, 0.45f) * b.width()).toInt().coerceAtLeast(service.dp(28))
        val pad = service.dp(14)
        val size = rPx * 2 + pad * 2
        val cx = action.x * b.width()
        val cy = action.y * b.height()
        holder.params.width = size
        holder.params.height = size
        holder.params.x = (cx - size / 2f).toInt()
        holder.params.y = (cy - size / 2f).toInt()
        if (holder.view.parent != null) {
            try { wm.updateViewLayout(holder.view, holder.params) } catch (_: Exception) {}
        }
        holder.view.invalidate()
    }

    private fun addLineWindow(actionId: Long, x1: Float, y1: Float, x2: Float, y2: Float) {
        val view = SegmentView(service)
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val params = baseParams(1, 1, false, false)
        val holder = LineWindow(actionId, view, params)
        layoutLine(holder, x1, y1, x2, y2)
        wm.addView(view, params)
        lineWindows.add(holder)
    }

    private fun layoutLine(holder: LineWindow, x1: Float, y1: Float, x2: Float, y2: Float) {
        val pad = service.dp(24)
        val left = min(x1, x2)
        val top = min(y1, y2)
        val width = (max(x1, x2) - left).toInt() + pad * 2
        val height = (max(y1, y2) - top).toInt() + pad * 2
        val originX = left - pad
        val originY = top - pad
        holder.view.setEnds(x1 - originX, y1 - originY, x2 - originX, y2 - originY)
        holder.params.x = originX.toInt()
        holder.params.y = originY.toInt()
        holder.params.width = width.coerceAtLeast(1)
        holder.params.height = height.coerceAtLeast(1)
        if (holder.view.parent != null) {
            try { wm.updateViewLayout(holder.view, holder.params) } catch (_: Exception) {}
        }
    }

    @Suppress("unused")
    private fun showAddGesturePicker() {
        if (!GESTURE_PICKER_ENABLED) return
        if (service.isRunning()) { toast(R.string.pause_first); return }
        if (ClickerStore.get(service).mode != ClickerMode.MULTI) { toast(R.string.single_one_point); return }
        if (!lockDialog()) return
        val ctx = overlayCtx
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_pick_block, null, false)
        val title = root.findViewById<TextView>(R.id.pick_title)
        val list = root.findViewById<LinearLayout>(R.id.pick_list)
        val btnCancel = root.findViewById<TextView>(R.id.btn_pick_cancel)
        title.text = tr(R.string.pick_gesture_title)
        val choices = listOf(
            ActionKind.CLICK to R.string.gesture_click,
            ActionKind.DOUBLE_CLICK to R.string.gesture_double_click,
            ActionKind.SWIPE to R.string.gesture_swipe,
            ActionKind.PINCH to R.string.gesture_pinch,
            ActionKind.MULTI_TOUCH to R.string.gesture_multi_touch
        )
        try {
            val dialog = AlertDialog.Builder(ctx).setView(root).create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener { clearDialog(dialog) }
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            choices.forEach { (kind, labelRes) ->
                val row = TextView(ctx)
                row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, service.dp(44)).also {
                    (it as LinearLayout.LayoutParams).topMargin = service.dp(8)
                }
                row.gravity = Gravity.CENTER
                row.setBackgroundResource(R.drawable.bg_dialog_btn)
                row.text = tr(labelRes)
                row.setTextColor(service.getColor(R.color.gold))
                row.textSize = 14f
                row.paint.isFakeBoldText = true
                row.setOnClickListener {
                    dialog.dismiss()
                    addGesture(kind)
                }
                list.addView(row)
            }
            btnCancel.setOnClickListener { dialog.dismiss() }
            openDialog = dialog
            showStacked(dialog)
        } catch (e: Exception) {
            unlockDialog()
            Log.e(TAG, "gesture picker", e)
        }
    }

    private fun addGesture(kind: ActionKind) {
        if (service.isRunning()) { toast(R.string.pause_first); return }
        val store = ClickerStore.get(service)
        if (store.mode != ClickerMode.MULTI) {
            toast(if (kind == ActionKind.SWIPE) R.string.swipe_needs_multi else R.string.single_one_point)
            return
        }
        val action = store.addAction(kind)
        if (action == null) { toast(R.string.max_actions); return }
        selectedId = action.id
        val mark = markForNewAction(store, action)
        val dbl = action.kind == ActionKind.DOUBLE_CLICK
        if (action.kind == ActionKind.JOYSTICK) {
            addJoyWindow(action)
        } else {
            if (action.hasGuideLine()) {
                val b = screenBounds(service)
                addLineWindow(action.id, action.x * b.width(), action.y * b.height(), action.x2 * b.width(), action.y2 * b.height())
            }
            if (action.isTwoPoint()) {
                addPointWindow(action.id, false, mark, action.x, action.y, showLabel = true, groupId = action.groupId, doubleRing = dbl)
                addPointWindow(action.id, true, mark, action.x2, action.y2, showLabel = false, groupId = action.groupId, doubleRing = dbl)
            } else {
                addPointWindow(action.id, false, mark, action.x, action.y, showLabel = true, groupId = action.groupId, doubleRing = dbl)
            }
        }
        if (openDialog?.isShowing == true) hideMarkersUnderDialog()
        else bringMenuToFront()
    }

    private fun markForNewAction(store: ClickerStore, action: com.he.he_smartphone_auto_clicker.model.ClickerAction): String {
        val mode = store.groupList().firstOrNull { it.id == action.groupId }?.mode ?: GroupRunMode.SEQUENCE
        if (mode == GroupRunMode.RANDOM) {
            return when (action.kind) {
                ActionKind.DOUBLE_CLICK -> "RD"
                ActionKind.PINCH -> "P"
                ActionKind.MULTI_TOUCH -> "M"
                ActionKind.JOYSTICK -> "J"
                else -> "R"
            }
        }
        val n = store.actionsInGroup(action.groupId).size
        return when (action.kind) {
            ActionKind.DOUBLE_CLICK -> "${n}D"
            ActionKind.JOYSTICK -> "${n}J"
            else -> n.toString()
        }
    }

    private fun onRemove() {
        if (service.isRunning()) { toast(R.string.pause_first); return }
        val store = ClickerStore.get(service)
        val list = store.actionList()
        if (list.isEmpty()) { toast(R.string.add_target_first); return }
        val id = selectedId ?: list.last().id
        store.remove(id)
        if (selectedId == id) selectedId = null
        toast(R.string.removed_action)
        syncPoints(animate = true)
    }

    private fun showBlocksPanel() {
        if (service.isRunning()) { toast(R.string.pause_first); return }
        if (!lockDialog()) return
        val ctx = overlayCtx
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_blocks, null, false)
        val list = root.findViewById<android.widget.LinearLayout>(R.id.blocks_list)
        val btnClose = root.findViewById<android.widget.ImageButton>(R.id.btn_blocks_close)
        val btnAdd = root.findViewById<TextView>(R.id.btn_add_block)
        root.findViewById<TextView>(R.id.blocks_title).text = tr(R.string.blocks_title)
        root.findViewById<TextView>(R.id.blocks_hint).text = tr(R.string.blocks_hint)
        btnAdd.text = tr(R.string.add_block)
        try {
            val dialog = AlertDialog.Builder(ctx).setView(root).create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener { clearDialog(dialog) }
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            btnClose.setOnClickListener { dialog.dismiss() }
            fun paint() {
                list.removeAllViews()
                val store = ClickerStore.get(service)
                val groups = store.groupList()
                val selected = store.selectedGroupId
                groups.forEach { group ->
                    val row = LayoutInflater.from(ctx).inflate(R.layout.item_block_row, list, false)
                    val name = row.findViewById<TextView>(R.id.block_name)
                    val meta = row.findViewById<TextView>(R.id.block_meta)
                    val visBtn = row.findViewById<android.widget.ImageButton>(R.id.btn_block_visible)
                    val modeBtn = row.findViewById<TextView>(R.id.btn_block_mode)
                    val useBtn = row.findViewById<TextView>(R.id.btn_block_select)
                    val runBtn = row.findViewById<TextView>(R.id.btn_block_run)
                    val delBtn = row.findViewById<TextView>(R.id.btn_block_delete)
                    val delayValue = row.findViewById<TextView>(R.id.block_delay_value)
                    val delayDown = row.findViewById<TextView>(R.id.btn_block_delay_down)
                    val delayUp = row.findViewById<TextView>(R.id.btn_block_delay_up)
                    row.findViewById<TextView>(R.id.block_delay_label).text = tr(R.string.block_delay_label)
                    runBtn.text = tr(R.string.run_block)
                    delBtn.text = tr(R.string.delete)
                    val count = store.actionsInGroup(group.id).size
                    val modeLabel = when (group.mode) {
                        GroupRunMode.SEQUENCE -> tr(R.string.group_mode_sequence)
                        GroupRunMode.RANDOM -> tr(R.string.group_mode_random)
                        GroupRunMode.ON_DEMAND -> tr(R.string.group_mode_ondemand)
                    }
                    name.text = group.name
                    meta.text = if (group.main) tr(R.string.group_meta_main, count, modeLabel) else tr(R.string.group_meta, count, modeLabel)
                    visBtn.setImageResource(if (group.visible) R.drawable.ic_eye else R.drawable.ic_eye_off)
                    visBtn.contentDescription = tr(if (group.visible) R.string.block_points_on else R.string.block_points_off)
                    visBtn.alpha = if (group.visible) 1f else 0.85f
                    modeBtn.text = modeLabel
                    modeBtn.setBackgroundResource(if (group.mode == GroupRunMode.ON_DEMAND) R.drawable.bg_mode_chip_demand else R.drawable.bg_mode_chip)
                    fun paintDelay() {
                        val sec = store.groupList().firstOrNull { it.id == group.id }?.delaySec ?: group.delaySec
                        delayValue.text = tr(R.string.block_delay_value, sec)
                        delayDown.alpha = if (sec <= 0) 0.35f else 1f
                        delayUp.alpha = if (sec >= 3600) 0.35f else 1f
                    }
                    paintDelay()
                    delayDown.setOnClickListener {
                        val cur = store.groupList().firstOrNull { it.id == group.id }?.delaySec ?: 0
                        store.setGroupDelaySec(group.id, (cur - 1).coerceAtLeast(0))
                        paintDelay()
                    }
                    delayUp.setOnClickListener {
                        val cur = store.groupList().firstOrNull { it.id == group.id }?.delaySec ?: 0
                        store.setGroupDelaySec(group.id, (cur + 1).coerceAtMost(3600))
                        paintDelay()
                    }
                    val active = group.id == selected
                    useBtn.text = if (active) tr(R.string.block_active) else tr(R.string.block_use)
                    useBtn.setTextColor(service.getColor(if (active) R.color.gold else R.color.gold_soft))
                    row.alpha = if (active) 1f else 0.88f
                    visBtn.setOnClickListener {
                        store.toggleGroupVisible(group.id)
                        applyMarkersState(animate = true)
                        paint()
                    }
                    modeBtn.setOnClickListener {
                        store.cycleGroupMode(group.id)
                        requestRebuildPoints()
                        paint()
                    }
                    useBtn.setOnClickListener {
                        store.selectedGroupId = group.id
                        Toast.makeText(service, tr(R.string.block_selected, group.name), Toast.LENGTH_SHORT).show()
                        paint()
                    }
                    if (group.mode == GroupRunMode.ON_DEMAND) {
                        runBtn.visibility = View.VISIBLE
                        runBtn.setOnClickListener {
                            dialog.dismiss()
                            service.runGroupOnce(group.id)
                        }
                    } else {
                        runBtn.visibility = View.GONE
                    }
                    if (!group.main) {
                        delBtn.visibility = View.VISIBLE
                        delBtn.setOnClickListener {
                            store.deleteGroup(group.id)
                            toast(R.string.group_deleted)
                            requestRebuildPoints()
                            paint()
                        }
                    } else {
                        delBtn.visibility = View.GONE
                    }
                    list.addView(row)
                }
            }
            btnAdd.setOnClickListener {

                val store = ClickerStore.get(service)
                val made = store.addGroup("Block ${store.groupList().size + 1}")
                if (made == null) toast(R.string.group_max)
                else {
                    toast(R.string.block_created)
                    paint()
                }
            }
            paint()
            openDialog = dialog
            showStacked(dialog)
        } catch (e: Exception) {
            unlockDialog()
            Log.e(TAG, "blocks panel", e)
        }
    }

    private fun showPresetPanel() {
        if (service.isRunning()) { toast(R.string.pause_first); return }
        if (!lockDialog()) return
        val store = ClickerStore.get(service)
        val hasPreset = store.hasActivePreset()
        val ctx = overlayCtx
        val binding = DialogPresetBinding.inflate(LayoutInflater.from(ctx))
        binding.presetTitle.text = tr(R.string.preset_panel_title)
        binding.btnLoadPreset.text = tr(R.string.load_preset_action)
        binding.btnOndemandBlocks.text = tr(R.string.ondemand_title)
        binding.labelStartDelay.text = tr(R.string.start_delay_label)
        binding.labelPresetName.text = tr(R.string.preset_name_title)
        binding.inputPresetName.setText(store.activePresetName.ifBlank { "" })
        binding.inputPresetName.hint = tr(R.string.preset_name_hint)
        binding.inputStartDelay.setMs(store.startDelaySec * 1000L, preferred = com.he.he_smartphone_auto_clicker.ui.TimeInputView.TimeUnit.SEC)
        binding.presetHint.text = tr(if (hasPreset) R.string.preset_hint_update else R.string.preset_hint_new)
        binding.btnPresetAction.text = tr(if (hasPreset) R.string.update_preset else R.string.save_as_preset)
        fun saveStartDelay() {
            store.startDelaySec = (binding.inputStartDelay.getMs(store.startDelaySec * 1000L, 0L, 3_600_000L) / 1000L).toInt()
        }
        try {
            val dialog = AlertDialog.Builder(ctx).setView(binding.root).create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener {
                saveStartDelay()
                clearDialog(dialog)
            }
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            binding.btnPresetClose.setOnClickListener { dialog.dismiss() }
            binding.btnLoadPreset.setOnClickListener {
                dialog.dismiss()
                try {
                    ClickerStore.get(service).overlaySuppressed = true
                    hide()
                    val intent = Intent(service, com.he.he_smartphone_auto_clicker.PresetsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.putExtra(com.he.he_smartphone_auto_clicker.PresetsActivity.EXTRA_FROM_OVERLAY, true)
                    service.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "open presets", e)
                }
            }
            binding.btnOndemandBlocks.setOnClickListener {
                dialog.dismiss()
                showOnDemandPanel()
            }
            binding.btnPresetAction.setOnClickListener {
                saveStartDelay()
                val name = binding.inputPresetName.text?.toString().orEmpty()
                if (hasPreset) {
                    if (!store.updateActivePreset(name)) {
                        toast(if (name.trim().isEmpty()) R.string.preset_need_name else R.string.preset_no_active)
                        return@setOnClickListener
                    }
                    toast(R.string.preset_updated)
                } else {
                    val preset = store.saveAsPreset(name)
                    when {
                        preset == null && name.trim().isEmpty() -> { toast(R.string.preset_need_name); return@setOnClickListener }
                        preset == null -> { toast(R.string.preset_max); return@setOnClickListener }
                        else -> toast(R.string.preset_saved)
                    }
                }
                dialog.dismiss()
            }
            openDialog = dialog
            showStacked(dialog)
        } catch (e: Exception) {
            unlockDialog()
            Log.e(TAG, "preset panel", e)
        }
    }

    private fun showOnDemandPanel() {
        if (service.isRunning()) { toast(R.string.pause_first); return }
        if (!lockDialog()) return
        val store = ClickerStore.get(service)
        val groups = store.onDemandGroups()
        val ctx = overlayCtx
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_ondemand, null, false)
        val list = root.findViewById<android.widget.LinearLayout>(R.id.ondemand_list)
        val btnClose = root.findViewById<android.widget.ImageButton>(R.id.btn_ondemand_close)
        root.findViewById<TextView>(R.id.ondemand_title).text = tr(R.string.ondemand_title)
        root.findViewById<TextView>(R.id.ondemand_hint).text = tr(R.string.ondemand_hint)
        try {
            val dialog = AlertDialog.Builder(ctx).setView(root).create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener { clearDialog(dialog) }
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            btnClose.setOnClickListener { dialog.dismiss() }
            if (groups.isEmpty()) {
                val empty = TextView(ctx)
                empty.text = tr(R.string.ondemand_empty)
                empty.setTextColor(service.getColor(R.color.text_sub))
                empty.textSize = 13f
                empty.gravity = Gravity.CENTER
                empty.setPadding(8, 16, 8, 8)
                list.addView(empty)
            } else {
                groups.forEach { group ->
                    val btn = TextView(ctx)
                    btn.text = tr(R.string.ondemand_run, group.name)
                    btn.setTextColor(service.getColor(R.color.gold))
                    btn.textSize = 14f
                    btn.gravity = Gravity.CENTER
                    btn.setBackgroundResource(R.drawable.bg_dialog_btn)
                    btn.setPadding(12, 0, 12, 0)
                    val lp = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, service.dp(42))
                    lp.topMargin = service.dp(8)
                    btn.layoutParams = lp
                    btn.setOnClickListener {
                        dialog.dismiss()
                        service.runGroupOnce(group.id)
                    }
                    list.addView(btn)
                }
            }
            openDialog = dialog
            showStacked(dialog)
        } catch (e: Exception) {
            unlockDialog()
            Log.e(TAG, "ondemand panel", e)
        }
    }

    private fun actionTitle(kind: ActionKind, n: Int): String = when (kind) {
        ActionKind.SWIPE -> tr(R.string.swipe_title, n)
        ActionKind.DOUBLE_CLICK -> tr(R.string.double_title, n)
        ActionKind.PINCH -> tr(R.string.pinch_title, n)
        ActionKind.MULTI_TOUCH -> tr(R.string.multi_touch_title, n)
        ActionKind.JOYSTICK -> tr(R.string.joystick_title, n)
        else -> tr(R.string.target_title, n)
    }

    private fun durationLabel(kind: ActionKind): Int = when (kind) {
        ActionKind.SWIPE -> R.string.swipe_duration
        ActionKind.PINCH -> R.string.pinch_duration
        ActionKind.JOYSTICK -> R.string.joy_travel_duration
        ActionKind.MULTI_TOUCH -> R.string.press_duration
        ActionKind.DOUBLE_CLICK -> R.string.press_duration
        else -> R.string.press_duration
    }

    private fun showEditor(actionId: Long) {
        if (!lockDialog()) return
        val store = ClickerStore.get(service)
        var kind = ActionKind.CLICK
        val duration: Long
        val delay: Long
        var hold = 0L
        val titleText: String
        var currentGroupId = 0L
        var pinchOption = 0
        if (actionId == ClickerStore.SINGLE_ID) {
            duration = store.singlePressMs
            delay = store.singleDelayMs
            titleText = tr(R.string.target_title, 1)
        } else {
            val list = store.actionList()
            val index = list.indexOfFirst { it.id == actionId }
            val action = list.getOrNull(index)
            if (action == null) { unlockDialog(); return }
            kind = action.kind
            duration = action.durationMs
            delay = action.delayMs
            hold = action.holdMs
            currentGroupId = action.groupId
            pinchOption = action.option
            val n = index + 1
            titleText = actionTitle(kind, n)
        }
        val ctx = overlayCtx
        val binding = DialogActionBinding.inflate(LayoutInflater.from(ctx))
        binding.dialogTitle.text = titleText
        binding.labelDuration.text = tr(durationLabel(kind))
        binding.labelHold.text = tr(R.string.swipe_hold_duration)
        binding.labelDelay.text = tr(R.string.delay_before_next)
        binding.checkDoubleClick.text = tr(R.string.double_click_check)
        binding.inputDuration.setMs(duration)
        binding.inputDelay.setMs(delay)
        binding.inputHold.setMs(hold)
        val edited = if (actionId == ClickerStore.SINGLE_ID) null else store.actionList().firstOrNull { it.id == actionId }
        var pauseWhileAction = edited?.pauseAllWhileAction == true
        var pauseWhileDelay = edited?.pauseAllWhileDelay == true
        binding.btnPauseAction.visibility = if (edited == null) View.GONE else View.VISIBLE
        binding.btnPauseDelay.visibility = if (edited == null) View.GONE else View.VISIBLE
        fun paintPauseBtns() {
            binding.btnPauseAction.text = tr(if (pauseWhileAction) R.string.pause_all_action_on else R.string.pause_all_action_off)
            binding.btnPauseDelay.text = tr(if (pauseWhileDelay) R.string.pause_all_delay_on else R.string.pause_all_delay_off)
            val on = service.getColor(R.color.gold)
            val off = service.getColor(R.color.text_sub)
            binding.btnPauseAction.setTextColor(if (pauseWhileAction) on else off)
            binding.btnPauseDelay.setTextColor(if (pauseWhileDelay) on else off)
        }
        paintPauseBtns()
        binding.holdRow.visibility = if (kind == ActionKind.SWIPE || kind == ActionKind.JOYSTICK) View.VISIBLE else View.GONE
        if (kind == ActionKind.JOYSTICK) binding.labelHold.text = tr(R.string.swipe_hold_duration)
        val canDelete = actionId != ClickerStore.SINGLE_ID
        binding.btnDialogDelete.visibility = if (canDelete) View.VISIBLE else View.GONE
        val groups = store.groupList()
        var pickedGroupId = currentGroupId
        var option = pinchOption
        val moveLike = kind == ActionKind.SWIPE || kind == ActionKind.PINCH || kind == ActionKind.JOYSTICK
        val durMin = if (moveLike) 50L else 1L
        binding.btnPauseAction.setOnClickListener {
            pauseWhileAction = !pauseWhileAction
            val holdVal = if (kind == ActionKind.SWIPE || kind == ActionKind.JOYSTICK) binding.inputHold.getMs(hold, 0L, 10000L) else null
            store.updateTiming(actionId, binding.inputDuration.getMs(duration, durMin, 10000L), binding.inputDelay.getMs(delay, 0L, 600000L), holdVal, pauseWhileAction, pauseWhileDelay)
            paintPauseBtns()
        }
        binding.btnPauseDelay.setOnClickListener {
            pauseWhileDelay = !pauseWhileDelay
            val holdVal = if (kind == ActionKind.SWIPE || kind == ActionKind.JOYSTICK) binding.inputHold.getMs(hold, 0L, 10000L) else null
            store.updateTiming(actionId, binding.inputDuration.getMs(duration, durMin, 10000L), binding.inputDelay.getMs(delay, 0L, 600000L), holdVal, pauseWhileAction, pauseWhileDelay)
            paintPauseBtns()
        }
        val isSimpleTap = kind == ActionKind.CLICK || kind == ActionKind.DOUBLE_CLICK
        if (isSimpleTap && canDelete) {
            binding.checkDoubleClick.visibility = View.VISIBLE
            binding.checkDoubleClick.isChecked = kind == ActionKind.DOUBLE_CLICK
            binding.checkDoubleClick.setOnCheckedChangeListener { _, checked ->
                kind = if (checked) ActionKind.DOUBLE_CLICK else ActionKind.CLICK
                store.updateKind(actionId, kind)
                val n = store.actionsInGroup(pickedGroupId).indexOfFirst { it.id == actionId }.let { if (it < 0) 1 else it + 1 }
                binding.dialogTitle.text = actionTitle(kind, n)
                syncPoints(animate = true)
            }
        } else {
            binding.checkDoubleClick.visibility = View.GONE
        }
        if (kind == ActionKind.PINCH) {
            binding.optionRow.visibility = View.VISIBLE
            binding.joyRow.visibility = View.GONE
            binding.labelPinchMode.text = tr(R.string.pinch_mode_label)
            fun paintPinch() {
                binding.btnPinchMode.text = tr(if (option == 1) R.string.pinch_mode_out else R.string.pinch_mode_in)
            }
            paintPinch()
            binding.btnPinchMode.setOnClickListener {
                option = if (option == 1) 0 else 1
                store.updateOption(actionId, option)
                paintPinch()
            }
        } else if (kind == ActionKind.JOYSTICK) {
            binding.optionRow.visibility = View.GONE
            binding.joyRow.visibility = View.VISIBLE
            val joyAction = store.actionList().firstOrNull { it.id == actionId }
            binding.labelJoyMode.text = tr(R.string.joy_mode_label)
            binding.labelJoyRadius.text = tr(R.string.joy_radius_label)
            binding.joyHint.text = tr(R.string.joy_hint)
            fun paintJoyMode() {
                binding.btnJoyMode.text = tr(when (option) {
                    1 -> R.string.joy_mode_fixed
                    2 -> R.string.joy_mode_sweep
                    else -> R.string.joy_mode_random
                })
            }
            option = joyAction?.option?.coerceIn(0, 2) ?: 0
            paintJoyMode()

            val startPct = ((joyAction?.radius ?: 0.10f) * 100f).toInt().coerceIn(2, 45)
            binding.seekJoyRadius.max = 43
            binding.seekJoyRadius.progress = (startPct - 2).coerceIn(0, 43)
            fun paintRadius() {
                val pct = binding.seekJoyRadius.progress + 2
                binding.joyRadiusValue.text = tr(R.string.joy_radius_value, pct)
            }
            paintRadius()
            binding.btnJoyMode.setOnClickListener {
                option = (option + 1) % 3
                store.updateJoy(actionId, option = option)
                paintJoyMode()
                syncPoints(animate = true)
            }
            binding.seekJoyRadius.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    paintRadius()
                    if (!fromUser) return
                    val pct = progress + 2
                    store.updateJoy(actionId, radius = pct / 100f, option = option)
                    syncPoints(animate = false)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        } else {
            binding.optionRow.visibility = View.GONE
            binding.joyRow.visibility = View.GONE
        }
        fun autoSave(rebuild: Boolean = false) {
            val dur = binding.inputDuration.getMs(duration, durMin, 10000L)
            val wait = binding.inputDelay.getMs(delay, 0L, 600000L)
            val holdVal = if (kind == ActionKind.SWIPE || kind == ActionKind.JOYSTICK) binding.inputHold.getMs(hold, 0L, 10000L) else null
            if (actionId == ClickerStore.SINGLE_ID) {
                store.updateSingleTiming(dur, wait)
            } else {
                store.updateTiming(actionId, dur, wait, holdVal)
                if (pickedGroupId != currentGroupId) {
                    store.moveActionToGroup(actionId, pickedGroupId)
                    currentGroupId = pickedGroupId
                    if (rebuild) requestRebuildPoints()
                }
            }
        }
        binding.inputDuration.setOnValueChangedListener { autoSave() }
        binding.inputDelay.setOnValueChangedListener { autoSave() }
        binding.inputHold.setOnValueChangedListener { autoSave() }
        if (canDelete && groups.isNotEmpty()) {
            binding.blockRow.visibility = View.VISIBLE
            binding.labelBlock.text = tr(R.string.action_block_label)
            fun paintIndex(mode: GroupRunMode) {
                val ordered = mode != GroupRunMode.RANDOM
                binding.indexRow.visibility = if (ordered) View.VISIBLE else View.GONE
                if (!ordered) return
                val chunk = store.actionsInGroup(pickedGroupId)
                val pos = chunk.indexOfFirst { it.id == actionId }
                val n = if (pos < 0) 1 else pos + 1
                binding.indexValue.text = tr(R.string.action_index_value, n, chunk.size.coerceAtLeast(1))
                binding.btnIndexDown.alpha = if (pos <= 0) 0.35f else 1f
                binding.btnIndexUp.alpha = if (pos < 0 || pos >= chunk.lastIndex) 0.35f else 1f
                binding.dialogTitle.text = actionTitle(kind, n)
            }
            fun paintBlockBtn() {
                val g = groups.firstOrNull { it.id == pickedGroupId } ?: groups.first()
                pickedGroupId = g.id
                binding.btnPickBlock.text = g.name
                val modeRes = when (g.mode) {
                    GroupRunMode.RANDOM -> R.string.group_mode_random
                    GroupRunMode.ON_DEMAND -> R.string.group_mode_ondemand
                    else -> R.string.group_mode_sequence
                }
                binding.blockModeHint.text = tr(modeRes)
                paintIndex(g.mode)
            }
            paintBlockBtn()
            binding.btnIndexDown.setOnClickListener {
                store.move(actionId, -1)
                paintBlockBtn()
                syncPoints(animate = true)
            }
            binding.btnIndexUp.setOnClickListener {
                store.move(actionId, 1)
                paintBlockBtn()
                syncPoints(animate = true)
            }
            binding.btnPickBlock.setOnClickListener {
                showPickBlockDialog(ctx, groups, pickedGroupId) { chosen ->
                    pickedGroupId = chosen
                    paintBlockBtn()
                    autoSave(rebuild = true)
                }
            }
        } else {
            binding.blockRow.visibility = View.GONE
        }
        try {
            val dialog = AlertDialog.Builder(ctx).setView(binding.root).create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener {
                autoSave(rebuild = true)
                clearDialog(dialog)
            }
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            binding.btnDialogClose.setOnClickListener { dialog.dismiss() }
            binding.btnDialogDelete.setOnClickListener {
                if (!canDelete) return@setOnClickListener
                store.remove(actionId)
                if (selectedId == actionId) selectedId = null
                requestRebuildPoints()
                dialog.dismiss()
            }
            openDialog = dialog
            showStacked(dialog)
        } catch (e: Exception) {
            unlockDialog()
            Log.e(TAG, "editor", e)
            toast(R.string.editor_failed)
        }
    }

    private fun lockDialog(): Boolean {
        if (dialogBusy || openDialog?.isShowing == true) return false
        dialogBusy = true
        return true
    }

    private fun showPickBlockDialog(ctx: Context, groups: List<ActionGroup>, selectedId: Long, titleText: String? = null, onPick: (Long) -> Unit) {
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_pick_block, null, false)
        if (titleText != null) root.findViewById<TextView>(R.id.pick_title).text = titleText
        val list = root.findViewById<LinearLayout>(R.id.pick_list)
        val btnCancel = root.findViewById<TextView>(R.id.btn_pick_cancel)
        val pick = AlertDialog.Builder(ctx).setView(root).create()
        pick.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        pick.window?.setBackgroundDrawableResource(android.R.color.transparent)
        groups.forEach { group ->
            val row = TextView(ctx)
            row.text = group.name
            row.gravity = Gravity.CENTER
            row.setTextColor(service.getColor(if (group.id == selectedId) R.color.black else R.color.gold))
            row.textSize = 14f
            row.paint.isFakeBoldText = true
            row.setBackgroundResource(if (group.id == selectedId) R.drawable.bg_dialog_btn_primary else R.drawable.bg_dialog_btn)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, service.dp(42))
            lp.topMargin = service.dp(8)
            row.layoutParams = lp
            row.setOnClickListener {
                onPick(group.id)
                pick.dismiss()
            }
            list.addView(row)
        }
        btnCancel.setOnClickListener { pick.dismiss() }
        pick.setOnDismissListener { clearDialog(pick) }
        openDialog = pick
        showStacked(pick)
    }

    internal val uiCtx: Context get() = overlayCtx
    internal val uiWm: WindowManager get() = wm
    internal fun uiLock(): Boolean = lockDialog()
    internal fun uiUnlock() = unlockDialog()
    internal fun uiClear(dialog: AlertDialog) = clearDialog(dialog)
    internal fun uiFullParams(touchable: Boolean): WindowManager.LayoutParams = baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable)
    internal fun uiOpen(dialog: AlertDialog) {
        openDialog = dialog
        showStacked(dialog)
    }

    internal fun uiReclaim(dialog: AlertDialog) {
        openDialog = dialog
        dialogBusy = true
        hideMarkersUnderDialog()
    }
    internal fun uiPickBlock(groups: List<ActionGroup>, selectedId: Long, titleText: String, onPick: (Long) -> Unit) = showPickBlockDialog(overlayCtx, groups, selectedId, titleText, onPick)

    internal fun uiParkAll(): () -> Unit {
        class Back(val view: View, val params: WindowManager.LayoutParams, val x: Int)
        val backs = ArrayList<Back>()
        fun park(view: View?, params: WindowManager.LayoutParams?) {
            if (view == null || params == null) return
            backs.add(Back(view, params, params.x))
            params.x -= 8000
            try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
        }
        pointWindows.forEach { park(it.view, it.params) }
        lineWindows.forEach { park(it.view, it.params) }
        joyWindows.forEach { park(it.view, it.params) }
        park(menuView, menuView?.layoutParams as? WindowManager.LayoutParams)
        park(statusView, statusView?.layoutParams as? WindowManager.LayoutParams)
        park(longTimerView, longTimerView?.layoutParams as? WindowManager.LayoutParams)
        var restored = false
        return restore@{
            if (restored) return@restore
            restored = true
            for (b in backs) {
                b.params.x = b.x
                try { wm.updateViewLayout(b.view, b.params) } catch (_: Exception) {}
            }
        }
    }

    private var statusView: TextView? = null
    private var longTimerView: TextView? = null

    fun setLongTimer(text: String?) {
        if (text.isNullOrBlank()) {
            longTimerView?.let { safeRemove(it) }
            longTimerView = null
            return
        }
        val existing = longTimerView
        if (existing != null) {
            if (existing.text == text) return
            existing.text = text
            fitTimer(existing)
            return
        }
        try {
            val chip = TextView(overlayCtx)
            chip.text = text
            chip.setTextColor(service.getColor(R.color.gold))
            chip.textSize = 30f
            chip.typeface = android.graphics.Typeface.DEFAULT_BOLD
            chip.gravity = Gravity.CENTER
            chip.setPadding(service.dp(18), service.dp(10), service.dp(18), service.dp(10))
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#F0141008"))
            bg.setStroke(service.dp(2), service.getColor(R.color.gold))
            bg.cornerRadius = service.dp(16).toFloat()
            chip.background = bg
            val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false)
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = service.dp(48)
            wm.addView(chip, params)
            longTimerView = chip
            chip.post { fitTimer(chip) }
        } catch (e: Exception) {
            Log.e(TAG, "long timer", e)
            longTimerView = null
        }
    }

    private fun fitTimer(chip: TextView) {
        chip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val lp = chip.layoutParams as? WindowManager.LayoutParams ?: return
        val w = chip.measuredWidth.coerceAtLeast(1)
        val h = chip.measuredHeight.coerceAtLeast(1)
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        try { wm.updateViewLayout(chip, lp) } catch (_: Exception) {}
    }

    fun setVisionStatus(text: String?) {
        if (text.isNullOrEmpty()) {
            statusView?.let { safeRemove(it) }
            statusView = null
            return
        }
        val existing = statusView
        if (existing != null) {
            existing.text = text
            return
        }
        try {
            val chip = TextView(overlayCtx)
            chip.text = text
            chip.setTextColor(service.getColor(R.color.gold_soft))
            chip.textSize = 12f
            chip.setBackgroundResource(R.drawable.bg_mode_chip)
            chip.setPadding(service.dp(10), service.dp(4), service.dp(10), service.dp(4))
            val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false)

            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = service.dp(6)
            wm.addView(chip, params)
            statusView = chip
        } catch (e: Exception) {
            Log.e(TAG, "vision chip", e)
            statusView = null
        }
    }

    private val visionUi: VisionUi by lazy { VisionUi(service, this) }

    private fun requestRebuildPoints() { syncPoints(animate = true) }

    private fun unlockDialog() {
        dialogBusy = false
        openDialog = null
        applyMarkersState(animate = false)
    }

    private fun clearDialog(dialog: AlertDialog) {
        if (openDialog === dialog || openDialog == null) unlockDialog()
    }

    private fun dismissOpenDialog() {
        try { openDialog?.dismiss() } catch (_: Exception) {}
        unlockDialog()
    }

    private fun showStacked(dialog: AlertDialog) {
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        hideMarkersUnderDialog()
        dialog.show()
        fitOverlayDialog(dialog)
    }

    private fun fitOverlayDialog(dialog: AlertDialog) {
        val win = dialog.window ?: return
        val b = screenBounds(service)
        val land = b.width() > b.height()
        val targetW = if (land) min((b.width() * 0.62f).toInt(), service.dp(480)) else min((b.width() * 0.92f).toInt(), service.dp(380))

        val scrollCap = (b.height() * if (land) 0.52f else 0.55f).toInt().coerceAtLeast(service.dp(100))
        win.setLayout(targetW, WindowManager.LayoutParams.WRAP_CONTENT)
        win.setGravity(Gravity.CENTER)

        val fullCap = (b.height() * 0.86f).toInt().coerceAtLeast(service.dp(120))
        win.decorView.post { clampScrollViews(win.findViewById(android.R.id.content) ?: return@post, scrollCap, fullCap) }
    }

    private fun clampScrollViews(root: View, capNormal: Int, capFull: Int) {
        if (capNormal <= 0) return
        if (root is android.widget.ScrollView) {
            val maxH = if (root.tag == "full") capFull else capNormal
            val child = root.getChildAt(0)
            val need = child?.let { if (it.height > 0) it.height else it.measuredHeight } ?: 0
            val cur = if (root.height > 0) root.height else root.measuredHeight
            val target = when {
                need > 0 -> min(need, maxH)
                cur > maxH -> maxH
                else -> return
            }
            if (cur != target && (need > maxH || cur > maxH)) {
                val lp = root.layoutParams ?: return
                lp.height = target
                root.layoutParams = lp
            }
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) clampScrollViews(root.getChildAt(i), capNormal, capFull)
        }
    }

    private fun hideMarkersUnderDialog() {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        pointWindows.forEach { holder ->
            holder.view.animate().cancel()
            holder.view.alpha = 0f
            holder.params.flags = holder.params.flags or flag
            try { wm.updateViewLayout(holder.view, holder.params) } catch (_: Exception) {}
        }
        lineWindows.forEach { holder ->
            holder.view.animate().cancel()
            holder.view.alpha = 0f
            holder.params.flags = holder.params.flags or flag
            try { wm.updateViewLayout(holder.view, holder.params) } catch (_: Exception) {}
        }
        joyWindows.forEach { holder ->
            holder.view.animate().cancel()
            holder.view.alpha = 0f
            holder.params.flags = holder.params.flags or flag
            try { wm.updateViewLayout(holder.view, holder.params) } catch (_: Exception) {}
        }
    }

    private fun bringMenuToFront() {
        if (openDialog?.isShowing == true) return
        val menu = menuView ?: return
        val lp = menu.layoutParams as? WindowManager.LayoutParams ?: return
        safeRemove(menu)
        try {
            wm.addView(menu, lp)
            menuView = menu
            menuBinding?.menuPanel?.let { panel -> menu.post { installPassThrough(menu, panel) } }
        } catch (e: Exception) {
            Log.e(TAG, "menu front", e)
            menuView = null
            menuBinding = null
        }
    }

    private fun setKeepScreen(on: Boolean) {
        val menu = menuView ?: return
        val lp = menu.layoutParams as? WindowManager.LayoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        lp.flags = if (on) lp.flags or flag else lp.flags and flag.inv()
        try { wm.updateViewLayout(menu, lp) } catch (_: Exception) {}
    }

    private fun baseParams(width: Int, height: Int, touchable: Boolean, hardware: Boolean = true): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (hardware) flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.alpha = 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            val flagsField = WindowManager.LayoutParams::class.java.getField("privateFlags")
            val noMove = WindowManager.LayoutParams::class.java.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION").getInt(null)
            flagsField.setInt(params, flagsField.getInt(params) or noMove)
        } catch (_: Exception) {}
        return params
    }

    private fun pointSize(): Int = service.dp(44)
    private fun safeRemove(view: View?) {
        if (view == null) return
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    private fun toast(res: Int) { Toast.makeText(service, tr(res), Toast.LENGTH_SHORT).show() }
    private inner class SegmentView(context: Context) : View(context) {
        private var x1 = 0f
        private var y1 = 0f
        private var x2 = 0f
        private var y2 = 0f

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88D4AF37")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = service.dp(22).toFloat()
        }

        init { setWillNotDraw(false); setBackgroundColor(Color.TRANSPARENT) }

        fun setEnds(ax: Float, ay: Float, bx: Float, by: Float) {
            x1 = ax
            y1 = ay
            x2 = bx
            y2 = by
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawLine(x1, y1, x2, y2, stroke)
        }
    }

    companion object {
        private const val TAG = "HeClickerOverlay"
        private const val GESTURE_PICKER_ENABLED = false
        private val BLOCK_COLORS = intArrayOf(
            Color.parseColor("#D4AF37"),
            Color.parseColor("#5B9BD5"),
            Color.parseColor("#E07A5F"),
            Color.parseColor("#81B29A"),
            Color.parseColor("#9B5DE5"),
            Color.parseColor("#F4A261")
        )
    }
}