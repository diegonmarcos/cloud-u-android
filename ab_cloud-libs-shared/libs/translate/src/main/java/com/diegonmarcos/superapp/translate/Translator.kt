package com.diegonmarcos.superapp.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * WHY EVERY ENTRY POINT HERE TAKES A CONTEXT. Not one of these sentences may be
 * a Kotlin literal: the owner's phone is set to Spanish, and a literal reaches
 * her in English no matter what the phone is set to. The reasons are produced
 * deep in [translateBlocking] and surface far away — on a toast, or on the
 * bar's status line — so the Context has to be threaded all the way down rather
 * than resolved at the edge. That is the whole reason [liveTranslate] grew a
 * Context parameter it did not need for anything else.
 *
 * The engine implementation is registered per-app in Application.onCreate
 * (LocalTranslateEngineClient in-process ML Kit, or AidlTranslateEngineClient
 * binding the cloud-keyboard-libs companion). Lives in libs:translate; the
 * cloud-keyboard tree (libs/keyboard) consumes it.
 */
object Translator {
    private const val TAG = "Translator"

    const val AUTO = "auto"

    /**
     * Shown by the bar before the first keystroke too — the fix is an install,
     * not a retry. A function rather than a constant because the sentence has to
     * come out of the resource table in the language the phone is set to.
     */
    @JvmStatic
    fun notConnected(context: Context): String =
        context.getString(R.string.translate_engine_not_connected)

    /** The sentence shown when a bound engine refuses to answer at all. */
    @JvmStatic
    fun engineCallFailed(context: Context): String =
        context.getString(R.string.translate_engine_call_failed)

    /** One translate outcome: [text] non-null = success; otherwise [error] says why (user-readable). */
    class Result(@JvmField val text: String?, @JvmField val detected: String?, @JvmField val error: String?) {
        val ok: Boolean get() = text != null
    }

    /**
     * The one reason an exit from [translate] is allowed to say nothing, spelled
     * once so the silence guard can hold the list to exactly this. A superseded
     * run is not a failure the owner needs told about: the long-press that
     * superseded it is already on screen saying its own piece, and a second
     * message on top of it would describe an event she deliberately caused.
     */
    private const val SUPERSEDED = "superseded by a newer run"

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

    private fun translateBlocking(context: Context, client: TranslateEngineClient, text: String, from: String, to: String, hint: String?): Result {
        val key = "$from|$to|$hint|$text"
        synchronized(cache) { cache[key] }?.let { return it }
        if (!client.isConnected()) return Result(null, null, notConnected(context))
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
                detected == "und" && from == AUTO -> Result(null, null, context.getString(R.string.translate_no_language_detected))
                detected == "und" -> Result(null, null, context.getString(R.string.translate_engine_failed_model))
                out.isEmpty() -> Result(null, detected, context.getString(R.string.translate_empty_reply))
                else -> Result(out, detected, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "engine call threw", e)
            Result(null, null, context.getString(R.string.translate_engine_exception, e.message ?: e.javaClass.simpleName))
        }
        if (r.ok) synchronized(cache) { cache[key] = r }
        return r
    }

    /**
     * Translate [text] and hand back the result — no field, no callback, no thread of
     * its own. BLOCKS the calling thread; never call it on a main looper.
     *
     * Every other entry point here is tied to a keyboard surface: [liveTranslate] posts
     * to the main thread for a bar to draw, and [translate] writes straight back into an
     * InputConnection. A caller that is not a keyboard has neither — an app translating an
     * email it RECEIVED has no editable field at all, and a reply written into one would
     * be a bug rather than a feature. This is the same [translateBlocking] chain those two
     * already use (explicit source, or detect with [hint] as the fallback, LRU cached, the
     * engine's own reason on failure), with the surface left to the caller.
     *
     * [to] empty = the target pinned in Translation settings, falling back to [hint].
     */
    @JvmStatic
    @JvmOverloads
    fun translateNow(context: Context, text: String, to: String = "", hint: String? = null): Result {
        val appCtx = context.applicationContext
        val client = TranslateEngines.client
            ?: return Result(null, null, appCtx.getString(R.string.translate_no_engine_registered))
        if (text.isBlank()) return Result("", null, null)
        val target = to.ifEmpty { TranslatePrefs.defaultTarget(appCtx) }
            .ifEmpty { hint.orEmpty() }
        if (target.isEmpty()) return Result(null, null, appCtx.getString(R.string.translate_no_target))
        return translateBlocking(appCtx, client, text, AUTO, target, hint?.takeIf { it != target })
    }

    /**
     * Live (non-committing) translate for the bars. [from] is a language tag or
     * [AUTO]; [hint] is the explicit-source fallback when detection fails.
     * Callback on the main thread; superseded requests never call back.
     */
    @JvmStatic
    fun liveTranslate(context: Context, text: String, from: String, to: String, hint: String?, onResult: (Result) -> Unit) {
        val appCtx = context.applicationContext
        val client = TranslateEngines.client
        if (client == null) {
            onResult(Result(null, null, appCtx.getString(R.string.translate_no_engine_registered)))
            return
        }
        if (text.isBlank()) { onResult(Result("", null, null)); return }
        val gen = generation.incrementAndGet()
        executor.execute {
            if (gen != generation.get()) return@execute   // superseded while queued — skip the engine call
            val r = translateBlocking(appCtx, client, text, from, to, hint)
            main.post { if (gen == generation.get()) onResult(r) }
        }
    }

    /** Auto-detect convenience (voice bar): null = failure, "" = blank input. */
    @JvmStatic
    fun liveTranslate(context: Context, text: String, targetLang: String, onResult: (String?) -> Unit) =
        liveTranslate(context, text, AUTO, targetLang, null) { onResult(it.text) }

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
     *
     * NO EXIT FROM HERE IS SILENT, and cloud-android-silence-guard.py fails the
     * build if one becomes silent again. The bug this rule was written for: the
     * null-InputConnection clause below simply returned. The owner long-pressed
     * TRANSLATE, the toolbar key visibly depressed, and nothing whatsoever
     * followed — indistinguishable from a dead key, a missing engine, a target
     * language she had never set, and a translation that failed. It is the same
     * shape as the Enhance key defect (2ee735033) and it hid the fact that an
     * input method attached to no editor cannot translate anything at all.
     */
    @JvmStatic
    fun translate(context: Context, ic: InputConnection?, keyboardLang: String) {
        val appCtx = context.applicationContext

        // NO EDITOR MEANS NO TEXT TO READ AND NOWHERE TO WRITE. The input method
        // is on screen but bound to nothing the platform will let it read: the
        // field lost focus, the host application detached, or the surface under
        // the cursor is one no InputConnection is offered for. There is nothing
        // to attempt, which is exactly why the old code walked away — but the
        // owner cannot see the binding, only that the key did nothing.
        if (ic == null)
            return ended(appCtx, "pre-flight", "the input method has no connected editor",
                R.string.translate_no_input_connection)

        val client = TranslateEngines.client
        if (client == null)
            return ended(appCtx, "pre-flight", "no TranslateEngineClient is registered",
                R.string.translate_no_engine_registered)

        val target = TranslatePrefs.defaultTarget(appCtx).ifEmpty { keyboardLang }
        val selected = ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val text = (selected ?: fieldText(ic))?.trim()

        // BLANK MEANS TWO THINGS AND THE PLATFORM WILL NOT SAY WHICH — the field
        // is genuinely empty, or it holds text the application never exposes to
        // an input method (canvas editors, web views, custom drawing surfaces).
        // Both come back as the same empty buffer, so the message names both
        // readings rather than picking one and being wrong half the time.
        if (text.isNullOrBlank())
            return ended(appCtx, "pre-flight", "the field reads back ${text?.length ?: -1} characters",
                R.string.translate_nothing_to_translate)

        val hadSelection = selected != null
        val hint = keyboardLang.takeIf { it.isNotEmpty() && it != target }
        val gen = oneShot.incrementAndGet()
        Log.i(TAG, "run $gen: target=$target, selection=$hadSelection, chars=${text.length}")
        toast(appCtx, appCtx.getString(R.string.translate_in_progress, target.uppercase()))
        executor.execute {
            if (gen != oneShot.get()) return@execute endedQuietly("run $gen", SUPERSEDED)
            val r = translateBlocking(appCtx, client, text, AUTO, target, hint)
            main.post {
                if (gen != oneShot.get()) return@post endedQuietly("run $gen", SUPERSEDED)
                val out = r.text
                if (out == null)
                    return@post ended(appCtx, "run $gen", "the engine produced no translation: ${r.error}",
                        R.string.translate_failed, r.error ?: appCtx.getString(R.string.translate_reason_unknown))
                val now = (if (hadSelection) ic.getSelectedText(0)?.toString() else fieldText(ic))?.trim()
                if (now != text)
                    return@post ended(appCtx, "run $gen", "the field moved while translating",
                        R.string.translate_stale)
                replaceInField(ic, hadSelection, out)
            }
        }
    }

    /**
     * End a long-press without replacing any text, and say so — in the log for
     * whoever is reading a bug report, and on screen for the owner holding the
     * phone. [message] is a string resource because every sentence this keyboard
     * shows has to reach her in her own language.
     */
    private fun ended(context: Context, label: String, why: String, message: Int, vararg args: Any) {
        Log.i(TAG, "$label ended without a translation: $why")
        toast(context, context.getString(message, *args))
    }

    /** As [ended], but log-only; [why] may only ever be [SUPERSEDED]. */
    private fun endedQuietly(label: String, why: String) {
        Log.i(TAG, "$label ended without a translation and without a message: $why")
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
