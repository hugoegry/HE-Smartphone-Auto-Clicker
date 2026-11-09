package com.he.he_smartphone_auto_clicker.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.LayoutInflater
import androidx.annotation.StringRes
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

data class LangPack(val code: String, val name: String, val flag: String, val strings: Map<String, String>) {
    fun label(): String = "$flag $name"
}

object I18n {
    private const val PREFS = "he_i18n"
    private const val KEY_CODE = "lang_code"
    private const val ASSET_DIR = "lang"
    private const val FALLBACK = "en"

    @Volatile private var ready = false
    @Volatile private var selected = FALLBACK
    private val packs = linkedMapOf<String, LangPack>()
    private var appRef: WeakReference<Context>? = null

    fun init(app: Context) {
        appRef = WeakReference(app.applicationContext)
        selected = app.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CODE, FALLBACK) ?: FALLBACK
        reloadPacks(app.applicationContext)
        if (!packs.containsKey(selected)) selected = FALLBACK
        ready = true
    }

    fun wrap(base: Context): Context {
        if (!ready) init(base.applicationContext)
        return I18nContextWrapper(base)
    }

    fun currentCode(): String = selected

    fun listPacks(): List<LangPack> = packs.values.toList()

    fun setLang(ctx: Context, code: String) {
        if (!packs.containsKey(code)) return
        selected = code
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CODE, code).apply()
    }

    fun reloadPacks(ctx: Context) {
        packs.clear()
        val am = ctx.assets
        val files = runCatching { am.list(ASSET_DIR)?.filter { it.endsWith(".json") } }.getOrNull().orEmpty()
        for (file in files.sorted()) {
            val raw = am.open("$ASSET_DIR/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
            parsePack(raw)?.let { packs[it.code] = it }
        }
        if (!packs.containsKey(FALLBACK) && packs.isNotEmpty()) {

        }
    }

    fun lookup(key: String): String? {
        packs[selected]?.strings?.get(key)?.let { return it }
        if (selected != FALLBACK) packs[FALLBACK]?.strings?.get(key)?.let { return it }
        return null
    }

    fun t(ctx: Context, @StringRes id: Int): String {
        if (!ready) init(ctx.applicationContext)
        val app = appRef?.get() ?: ctx.applicationContext
        val key = runCatching { app.resources.getResourceEntryName(id) }.getOrNull()
            ?: runCatching { ctx.resources.getResourceEntryName(id) }.getOrNull()
        if (key != null) lookup(key)?.let { return it }
        return runCatching { unwrap(ctx).resources.getString(id) }.getOrElse {
            runCatching { app.getString(id) }.getOrDefault("")
        }
    }

    fun t(ctx: Context, @StringRes id: Int, vararg args: Any?): String {
        val template = t(ctx, id)
        return try {
            if (args.isEmpty()) template else String.format(Locale.US, template, *args)
        } catch (_: Exception) {
            template
        }
    }

    private fun unwrap(ctx: Context): Context {
        var c: Context = ctx
        while (c is I18nContextWrapper) c = c.baseContext
        return c
    }

    private fun parsePack(raw: String): LangPack? {
        return try {
            val root = JSONObject(raw)
            val code = root.optString("code").trim()
            if (code.isEmpty()) return null
            val name = root.optString("name", code)
            val flag = root.optString("flag", "")
            val map = linkedMapOf<String, String>()
            val arr = root.optJSONObject("strings")
            if (arr != null) {
                val keys = arr.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = arr.optString(k, "")
                }
            }
            LangPack(code, name, flag, map)
        } catch (_: Exception) {
            null
        }
    }
}

private class I18nContextWrapper(base: Context) : ContextWrapper(base) {
    private val i18nRes: Resources by lazy { I18nResources(base.resources) }

    override fun getResources(): Resources = i18nRes

    override fun getSystemService(name: String): Any? {
        if (LAYOUT_INFLATER_SERVICE == name) {
            return LayoutInflater.from(baseContext).cloneInContext(this)
        }
        return super.getSystemService(name)
    }
}

@Suppress("DEPRECATION")
private class I18nResources(private val raw: Resources) : Resources(raw.assets, raw.displayMetrics, raw.configuration) {
    override fun getText(id: Int): CharSequence {
        val key = runCatching { raw.getResourceEntryName(id) }.getOrNull()
        val hit = key?.let { I18n.lookup(it) }
        return hit ?: raw.getText(id)
    }

    override fun getString(id: Int): String = getText(id).toString()

    override fun getString(id: Int, vararg formatArgs: Any?): String {
        return String.format(Locale.US, getString(id), *formatArgs)
    }

    override fun getText(id: Int, def: CharSequence?): CharSequence {
        return runCatching { getText(id) }.getOrElse { def ?: "" }
    }
}
