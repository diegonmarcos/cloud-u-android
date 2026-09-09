package com.diegonmarcos.superapp.texttools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Binds [TextTools.SERVICE_PKG]'s [ITextTools] and calls it.
 *
 * The bind lifecycle is the translate client's, deliberately, because each branch of
 * it is a way that client went "not connected" for good until its process died:
 *  - the service's app was not installed when this client was constructed and was
 *    installed later: the initial bindService() returns FALSE with no exception, and
 *    nothing ever retried. Every use with no binder re-attempts, rate-limited to one
 *    try per [REBIND_MS].
 *  - the service's app was updated: Android reports that as onBindingDied, NOT
 *    onServiceDisconnected, and a died binding never comes back on its own — it has
 *    to be unbound and bound again.
 *  - the service's process was killed: onServiceDisconnected; BIND_AUTO_CREATE
 *    reconnects by itself, the explicit rebind only makes it immediate.
 *
 * Every call BLOCKS on the calling thread — see [ITextTools]. Callers put it on a
 * background dispatcher and show progress; this class does no threading of its own,
 * because a client that quietly moved the work would also quietly swallow the point
 * at which the caller is supposed to say "working…".
 */
class TextToolsClient(context: Context) {

    private val app = context.applicationContext

    @Volatile private var tools: ITextTools? = null
    @Volatile private var lastBindAttempt = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            tools = ITextTools.Stub.asInterface(binder)
            Log.i(TAG, "connected to $name")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            tools = null
            bind()
        }

        override fun onBindingDied(name: ComponentName) {
            tools = null
            runCatching { app.unbindService(this) }
            Log.w(TAG, "binding to $name died — rebinding")
            bind()
        }

        override fun onNullBinding(name: ComponentName) {
            Log.w(TAG, "$name returned a null binder — a build without TextToolsService?")
        }
    }

    init { bind() }

    @Synchronized
    private fun bind() {
        lastBindAttempt = SystemClock.elapsedRealtime()
        val result = runCatching {
            app.bindService(
                Intent(TextTools.ACTION).apply { setPackage(TextTools.SERVICE_PKG) },
                conn,
                Context.BIND_AUTO_CREATE,
            )
        }
        if (!result.getOrDefault(false)) {
            // bindService answers false rather than throwing when the target cannot be
            // resolved, so a failed bind and a pending one look identical from outside
            // unless this is written down.
            Log.w(TAG, "bindService to ${TextTools.SERVICE_PKG}/${TextTools.ACTION} failed " +
                "(exception=${result.exceptionOrNull()}) — is Cloud Keyboard installed?")
        }
    }

    /** The binder if bound; otherwise kick a rate-limited rebind and answer null. */
    private fun boundOrRebind(): ITextTools? {
        tools?.let { return it }
        if (SystemClock.elapsedRealtime() - lastBindAttempt > REBIND_MS) bind()
        return tools
    }

    /**
     * True when the tools can actually be called right now. Not "the client object
     * exists": this client is constructible, and constructed, on a device with no
     * keyboard installed at all. Status lines must ask this one.
     */
    fun isConnected(): Boolean = boundOrRebind() != null

    /**
     * Rewrite [text] through AI Model Routing — the OpenRouter path, never the
     * translator. [styleId] defaults to whatever the user pinned in Text Enhancements.
     */
    fun enhance(text: String, styleId: String = TextTools.STYLE_CONFIGURED): TextTools.Result =
        call("enhance") { it.enhance(text, styleId) }

    /**
     * Translate [text] through the translation library — never the LLM provider.
     * [targetTag] defaults to the target pinned in Translation settings.
     */
    fun translate(text: String, targetTag: String = TextTools.TARGET_CONFIGURED): TextTools.Result =
        call("translate") { it.translate(text, targetTag) }

    /** What to name the provider in progress and error text; null when nothing is bound. */
    fun enhanceProviderLabel(): String? =
        boundOrRebind()?.let { runCatching { it.enhanceProviderLabel() }.getOrNull() }

    /** Target languages the translation engine can reach; empty when nothing is bound. */
    fun translateLanguages(): List<String> =
        boundOrRebind()?.let { runCatching { it.translateLanguages() }.getOrNull() }.orEmpty()

    private inline fun call(what: String, body: (ITextTools) -> Array<String>?): TextTools.Result {
        val t = boundOrRebind() ?: return TextTools.Result.failed(TextTools.NOT_INSTALLED)
        return runCatching { TextTools.Result.of(body(t)) }.getOrElse {
            // A binder that dies mid-call throws here rather than answering; the user
            // gets the reason, not a no-op.
            Log.w(TAG, "$what call failed", it)
            TextTools.Result.failed("Text tools call failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "TextToolsClient"
        const val REBIND_MS = 5_000L
    }
}
