package com.he.he_smartphone_auto_clicker

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.he.he_smartphone_auto_clicker.data.ClickerStore
import com.he.he_smartphone_auto_clicker.databinding.ActivitySettingsFormBinding
import com.he.he_smartphone_auto_clicker.i18n.I18n
import com.he.he_smartphone_auto_clicker.i18n.LangPack
import com.he.he_smartphone_auto_clicker.i18n.tr
import com.he.he_smartphone_auto_clicker.service.ClickerAccessibilityService
import com.he.he_smartphone_auto_clicker.ui.TimeInputView
import com.he.he_smartphone_auto_clicker.util.parseMs

class TimingSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsFormBinding
    private lateinit var store: ClickerStore
    private var kind: String = KIND_SINGLE
    private var langOpen = false
    private var langAnim: ValueAnimator? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(I18n.wrap(newBase))
    }

    private fun trId(id: Int): String = tr(id)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ClickerStore.get(this)
        kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_SINGLE
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        when (kind) {
            KIND_MULTI -> bindMulti()
            KIND_COMMON -> bindCommon()
            else -> bindSingle()
        }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun bindSingle() {
        supportActionBar?.title = trId(R.string.single_title)
        binding.labelOne.text = trId(R.string.press_duration)
        binding.inputOne.visibility = View.GONE
        binding.timeOne.visibility = View.VISIBLE
        binding.timeOne.setMs(store.singlePressMs)
        binding.labelTwo.text = trId(R.string.delay_before_next)
        binding.timeTwo.setMs(store.singleDelayMs)
        binding.blockThree.visibility = View.GONE
        binding.blockLang.visibility = View.GONE
        binding.textHint.text = trId(R.string.hint_single)
        binding.btnSave.text = trId(R.string.save)
    }

    private fun bindMulti() {
        supportActionBar?.title = trId(R.string.multi_title)
        binding.labelOne.text = trId(R.string.default_press)
        binding.inputOne.visibility = View.GONE
        binding.timeOne.visibility = View.VISIBLE
        binding.timeOne.setMs(store.defaultPressMs)
        binding.labelTwo.text = trId(R.string.default_delay)
        binding.timeTwo.setMs(store.defaultDelayMs)
        binding.blockThree.visibility = View.VISIBLE
        binding.labelThree.text = trId(R.string.default_swipe)
        binding.timeThree.setMs(store.defaultSwipeMs)
        binding.blockLang.visibility = View.VISIBLE
        binding.labelLang.text = trId(R.string.app_lang_label)
        paintLangCombo()
        binding.textHint.text = trId(R.string.hint_multi)
        binding.btnSave.text = trId(R.string.save)
    }

    private fun paintLangCombo() {
        val packs = I18n.listPacks()
        val current = packs.firstOrNull { it.code == I18n.currentCode() } ?: packs.firstOrNull()
        binding.langComboValue.text = current?.label().orEmpty()
        binding.langComboArrow.rotation = 0f
        langOpen = false
        binding.langMenu.visibility = View.GONE
        binding.langMenu.layoutParams.height = 0
        fillLangMenu(packs)
        binding.langCombo.setOnClickListener { toggleLangMenu() }
    }

    private fun fillLangMenu(packs: List<LangPack>) {
        val menu = binding.langMenu
        menu.removeAllViews()
        val dens = resources.displayMetrics.density
        val rowH = (44 * dens).toInt()
        val padH = (14 * dens).toInt()
        val sep = View(this)
        sep.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dens).toInt())
        sep.setBackgroundColor(getColor(R.color.gold_dark))
        menu.addView(sep)
        packs.forEach { pack ->
            val row = TextView(this)
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(padH, 0, padH, 0)
            row.text = pack.label()
            row.setTextColor(getColor(R.color.gold))
            row.textSize = 15f
            row.paint.isFakeBoldText = true
            if (pack.code == I18n.currentCode()) row.setBackgroundColor(0x33D4AF37)
            row.setOnClickListener {
                if (pack.code != I18n.currentCode()) {
                    I18n.setLang(this, pack.code)
                    recreate()
                } else {
                    toggleLangMenu(forceClose = true)
                }
            }
            menu.addView(row)
        }
    }

    private fun toggleLangMenu(forceClose: Boolean = false) {
        val menu = binding.langMenu
        val open = if (forceClose) false else !langOpen
        langAnim?.cancel()
        menu.visibility = View.VISIBLE
        menu.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(binding.langShell.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val fullH = menu.measuredHeight.coerceAtLeast(1)
        val start = if (langOpen) menu.height.coerceAtLeast(fullH) else 0
        val target = if (open) fullH else 0
        menu.layoutParams.height = start
        menu.requestLayout()
        val anim = ValueAnimator.ofInt(start, target)
        anim.duration = 240
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            menu.layoutParams.height = it.animatedValue as Int
            menu.requestLayout()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                langOpen = open
                if (!open) {
                    menu.visibility = View.GONE
                    menu.layoutParams.height = 0
                } else {
                    menu.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                menu.requestLayout()
            }
        })
        langAnim = anim
        anim.start()
        binding.langComboArrow.animate().rotation(if (open) 180f else 0f).setDuration(240).start()
    }

    private fun bindCommon() {
        supportActionBar?.title = trId(R.string.common_title)
        binding.labelOne.text = trId(R.string.cycles_before_stop)
        binding.inputOne.visibility = View.VISIBLE
        binding.timeOne.visibility = View.GONE
        binding.inputOne.setText(store.repeatCount.toString())
        binding.blockTwo.visibility = View.VISIBLE
        binding.labelTwo.text = trId(R.string.start_delay_label)
        binding.timeTwo.setMs(store.startDelaySec * 1000L, preferred = TimeInputView.TimeUnit.SEC)
        binding.blockThree.visibility = View.GONE
        binding.blockLang.visibility = View.GONE
        binding.textHint.text = trId(R.string.hint_common)
        binding.btnSave.text = trId(R.string.save)
    }

    private fun save() {
        when (kind) {
            KIND_MULTI -> {
                store.defaultPressMs = binding.timeOne.getMs(store.defaultPressMs, 1L, 10000L)
                store.defaultDelayMs = binding.timeTwo.getMs(store.defaultDelayMs, 0L, 600000L)
                store.defaultSwipeMs = binding.timeThree.getMs(store.defaultSwipeMs, 50L, 10000L)
            }
            KIND_COMMON -> {
                store.repeatCount = parseMs(binding.inputOne.text.toString(), store.repeatCount.toLong(), 0L, 9999L).toInt()
                store.startDelaySec = (binding.timeTwo.getMs(store.startDelaySec * 1000L, 0L, 3_600_000L) / 1000L).toInt()
            }
            else -> {
                store.singlePressMs = binding.timeOne.getMs(store.singlePressMs, 1L, 10000L)
                store.singleDelayMs = binding.timeTwo.getMs(store.singleDelayMs, 0L, 600000L)
            }
        }
        ClickerAccessibilityService.requestRefresh(this)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        langAnim?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_SINGLE = "single"
        const val KIND_MULTI = "multi"
        const val KIND_COMMON = "common"
    }
}