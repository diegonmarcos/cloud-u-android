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

    /**
     * Summarise [text] through the same AI Model Routing provider [enhance] uses — "AI Resume" /
     * "Text Resume", the owner's name for condensing a message, never a CV. [summaryId] defaults
     * to the shape pinned in Text Resume settings.
     */
    fun summarise(text: String, summaryId: String = TextTools.SUMMARY_CONFIGURED): TextTools.Result =
        call("summarise") { it.summarise(text, summaryId) }

    /**
     * Rewrite [text] against a prompt and a model THIS app chose — the call for an app that
     * holds its own AI Model Routing and Text Enhancement settings.
     *
     * [enhance] asks the serving app to decide; this one decides here and sends the decision.
     * That is the whole difference, and it is the difference between having settings and only
     * being shown someone else's. What is still not sent, in either direction, is the API key.
     *
     * [systemPrompt] must be fully composed by the caller — preamble, style, tone, length and
     * language already joined. Nothing on the far side adds to it.
     */
    fun enhanceWith(
        text: String,
        systemPrompt: String,
        providerId: String,
        modelId: String,
    ): TextTools.Result = call("enhanceWith") { it.enhanceWith(text, systemPrompt, providerId, modelId) }

    /**
     * Summarise [text] against a prompt and a model THIS app chose — [enhanceWith] for Text
     * Resume. [bullets] tells the far side whether this caller's prompt asked for a list, which
     * is now the only side that knows.
     */
    fun summariseWith(
        text: String,
        systemPrompt: String,
        bullets: Boolean,
        providerId: String,
        modelId: String,
    ): TextTools.Result =
        call("summariseWith") { it.summariseWith(text, systemPrompt, bullets, providerId, modelId) }

    /** What to name the provider in progress and error text; null when nothing is bound. */
    fun enhanceProviderLabel(): String? =
        boundOrRebind()?.let { runCatching { it.enhanceProviderLabel() }.getOrNull() }

    /** Readable name of [providerId] — what to call THIS app's chosen provider on screen. */
    fun providerLabelFor(providerId: String): String? =
        boundOrRebind()?.let { runCatching { it.providerLabelFor(providerId) }.getOrNull() }

    /**
     * The serving app's current text-tool choices as JSON, or null when nothing is bound.
     *
     * FOR SEEDING A COPY OF THEM, ONCE. A caller that reads this on every use has not got its
     * own settings, it has a cache of somebody else's. Contains no credential, by construction
     * on the far side — see `ITextTools.settingsSnapshot`.
     */
    fun settingsSnapshot(): String? =
        boundOrRebind()?.let { runCatching { it.settingsSnapshot() }.getOrNull() }

    /** Target languages the translation engine can reach; empty when nothing is bound. */
    fun translateLanguages(): List<String> =
        boundOrRebind()?.let { runCatching { it.translateLanguages() }.getOrNull() }.orEmpty()

    /**
     * Is the serving app INSTALLED at all — asked of the package manager, not of the binding.
     *
     * [isConnected] cannot answer this. It is false both for a keyboard that is not on the phone
     * and for one that is installed but force-stopped or still binding, and a fleet console that
     * reported those as one thing would tell the owner to install an app they already have. This
     * asks the only question that separates them.
     *
     * It resolves the SERVICE rather than calling getPackageInfo because that is precisely what
     * this module's `<queries>` grants: Android 11+ package visibility is scoped to the ITextTools
     * intent, so the service resolves while a bare package lookup can still come back empty and
     * look like an uninstall.
     */
    fun isServingAppInstalled(): Boolean = runCatching {
        app.packageManager.queryIntentServices(
            Intent(TextTools.ACTION).apply { setPackage(TextTools.SERVICE_PKG) },
            0,
        ).isNotEmpty()
    }.getOrDefault(false)

    /**
     * The serving app's AI-Routing state as JSON — providers, their models, which model is chosen
     * and whether a key is held. Null when nothing is bound OR when the serving app is too old to
     * know the call.
     *
     * THE NULL IS TWO DIFFERENT FACTS and the caller has to separate them with [isConnected]: not
     * bound means the peer is absent or asleep, bound-and-null means it answered nothing because
     * its build predates this method. Both are honest states for a console to name and they are not
     * the same repair — one is an install, the other is an update.
     *
     * NO CREDENTIAL IS IN THIS. Key presence and a four-character hint travel; the key does not.
     * See `ITextTools.aiRoutingSnapshot`.
     *
     * BLOCKS, like everything else here. Callers put it on a background thread AND give it a
     * deadline — a peer that has wedged will never answer, and a settings page with no deadline
     * waits for it forever.
     */
    fun aiRoutingSnapshot(): String? =
        boundOrRebind()?.let { runCatching { it.aiRoutingSnapshot() }.getOrNull() }

    /**
     * Set the serving app's provider key and/or chosen model.
     *
     * Empty [apiKey] leaves the stored key alone and empty [modelId] leaves the stored model alone,
     * so either can be sent by itself; [clearKey] is the only way to remove a key, because a blank
     * field on a console must never be a command to delete a credential.
     */
    fun setAiRouting(
        providerId: String,
        apiKey: String = "",
        modelId: String = "",
        clearKey: Boolean = false,
    ): TextTools.Result = call("setAiRouting") { it.setAiRouting(providerId, apiKey, modelId, clearKey) }

    /**
     * The serving app's key for [providerId], in plaintext — a deliberate reveal, never a page load.
     *
     * NEVER LOG OR TOAST WHAT THIS RETURNS. This fleet uploads logcat from its diagnostics screens,
     * so a key that reaches the log leaves the phone; a toast outlives the screen that showed it and
     * is readable over a shoulder. Put it on the widget that asked for it and nowhere else.
     */
    fun revealAiKey(providerId: String): TextTools.Result =
        call("revealAiKey") { it.revealAiKey(providerId) }

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
