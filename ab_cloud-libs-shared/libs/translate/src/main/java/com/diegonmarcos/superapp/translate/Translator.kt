package com.diegonmarcos.superapp.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Translate orchestrator for the keyboard (translate bar, long-press TRANSLATE,
 * voice bar). All engine calls run on one background thread; callbacks fire on
 * the main looper.
 *
 * Reliability chain per request (why the bar used to feel flaky):
 *  - explicit source → [TranslateEngineClient.translateFrom], no detection;
 *  - auto → detect; if the engine answers "und" (short text: ML Kit wants ≥0.5
 *    confidence) retry with [hint] — the ACTIVE KEYBOARD LANGUAGE, which is
 *    what the user is typing in nearly always — as the explicit source;
 *  - results are cached (LRU) so backspacing/retyping never re-hits the engine;
 *  - live requests carry a generation: a queued request that was superseded by
 *    a newer keystroke is skipped before it touches the engine, and a late
 *    result for an old generation is dropped;
 *  - every failure surfaces as a [Result.error] string instead of a silent null.
 *
 * The engine implementation is registered per-app in Application.onCreate
 * (LocalTranslateEngineClient in-process ML Kit, or AidlTranslateEngineClient
 * binding the cloud-keyboard-libs companion). Lives in libs:translate; the
 * cloud-keyboard tree (libs/keyboard) consumes it.
 */
object Translator {
    const val AUTO = "auto"
    /** Shown by the bar before the first keystroke too — the fix is an install, not a retry. */
    const val NOT_CONNECTED = "Translate engine not connected — install/update the Cloud Keyboard Libs companion app"

    /** One translate outcome: [text] non-null = success; otherwise [error] says why (user-readable). */
    class Result(@JvmField val text: String?, @JvmField val detected: String?, @JvmField val error: String?) {
        val ok: Boolean get() = text != null
    }

    // ponytail: one worker thread. A binder call already in flight cannot be
    // aborted; the service-side ML Kit timeouts (TranslateEngine) bound it.
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()

    private const val CACHE_SIZE = 64
    private val cache = object : LinkedHashMap<String, Result>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?): Boolean = size > CACHE_SIZE
    }

    /** Engine reply = {sourceTag, text[, errorMessage]}; slot 2 is the engine's own reason for a failure. */
    private fun Array<String>.reason(): String? = getOrNull(2)?.takeIf { it.isNotEmpty() }

    private fun translateBlocking(client: TranslateEngineClient, text: String, from: String, to: String, hint: String?): Result {
        val key = "$from|$to|$hint|$text"
        synchronized(cache) { cache[key] }?.let { return it }
        if (!client.isConnected()) return Result(null, null, NOT_CONNECTED)
        val r: Result = try {
            var res = if (from == AUTO) client.translate(text, to) else client.translateFrom(text, from, to)
            var detected = res.getOrNull(0) ?: "und"
            if (detected == "und" && from == AUTO && res.reason() == null && !hint.isNullOrEmpty()) {
                res = client.translateFrom(text, hint, to)
                detected = res.getOrNull(0) ?: "und"
            }
            val out = res.getOrNull(1).orEmpty()
            val why = res.reason()
            when {
                why != null -> Result(null, null, why)
                detected == "und" && from == AUTO -> Result(null, null, "Couldn't detect the language — pick a source")
                detected == "und" -> Result(null, null, "Engine failed — offline model still downloading?")
                out.isEmpty() -> Result(null, detected, "No translation returned")
                else -> Result(out, detected, null)
            }
        } catch (e: Exception) {
            Result(null, null, "Translate failed: " + (e.message ?: e.javaClass.simpleName))
        }
        if (r.ok) synchronized(cache) { cache[key] = r }
        return r
    }

    /**
     * Live (non-committing) translate for the bars. [from] is a language tag or
     * [AUTO]; [hint] is the explicit-source fallback when detection fails.
     * Callback on the main thread; superseded requests never call back.
     */
    @JvmStatic
    fun liveTranslate(text: String, from: String, to: String, hint: String?, onResult: (Result) -> Unit) {
        val client = TranslateEngines.client
        if (client == null) { onResult(Result(null, null, "No translate engine registered")); return }
        if (text.isBlank()) { onResult(Result("", null, null)); return }
        val gen = generation.incrementAndGet()
        executor.execute {
            if (gen != generation.get()) return@execute   // superseded while queued — skip the engine call
            val r = translateBlocking(client, text, from, to, hint)
            main.post { if (gen == generation.get()) onResult(r) }
        }
    }

    /** Auto-detect convenience (voice bar): null = failure, "" = blank input. */
    @JvmStatic
    fun liveTranslate(text: String, targetLang: String, onResult: (String?) -> Unit) =
        liveTranslate(text, AUTO, targetLang, null) { onResult(it.text) }

    /**
     * One-shot, in place: translate the selection (or the whole field) and
     * overwrite it. Long-press on the TRANSLATE toolbar key. Honours the
     * default-target setting; [keyboardLang] (the active keyboard language) is
     * the fallback target AND the detection fallback source (same chain as the bar).
     *
     * Stale-reply guard (TextEnhancer's rule): the engine may take up to the
     * download timeout; if the field no longer holds what was sent, the reply
     * is dropped instead of select-all + overwrite wiping what the user typed
     * meanwhile. A newer long-press supersedes an older one.
     */
    @JvmStatic
    fun translate(context: Context, ic: InputConnection?, keyboardLang: String) {
        if (ic == null) return
        val appCtx = context.applicationContext
        val client = TranslateEngines.client
        if (client == null) { toast(appCtx, "Translate: no engine registered"); return }
        val target = TranslatePrefs.defaultTarget(appCtx).ifEmpty { keyboardLang }
        val selected = ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val text = (selected ?: fieldText(ic))?.trim()
        if (text.isNullOrBlank()) { toast(appCtx, "Translate: nothing to translate"); return }
        val hadSelection = selected != null
        val hint = keyboardLang.takeIf { it.isNotEmpty() && it != target }
        val gen = oneShot.incrementAndGet()
        toast(appCtx, "Translating → ${target.uppercase()}…")
        executor.execute {
            if (gen != oneShot.get()) return@execute
            val r = translateBlocking(client, text, AUTO, target, hint)
            main.post {
                if (gen != oneShot.get()) return@post
                val out = r.text
                if (out == null) { toast(appCtx, r.error ?: "Translate failed"); return@post }
                val now = (if (hadSelection) ic.getSelectedText(0)?.toString() else fieldText(ic))?.trim()
                if (now != text) { toast(appCtx, "Field changed while translating — nothing replaced"); return@post }
                replaceInField(ic, hadSelection, out)
            }
        }
    }

    private val oneShot = AtomicInteger()
    private fun fieldText(ic: InputConnection): String? = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()

    /** Overwrite the selection, or select-all + overwrite the whole field. */
    @JvmStatic
    fun replaceInField(ic: InputConnection, hadSelection: Boolean, text: String) {
        ic.beginBatchEdit()
        if (!hadSelection) ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
