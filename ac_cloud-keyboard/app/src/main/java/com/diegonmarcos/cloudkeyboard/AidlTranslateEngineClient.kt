package com.diegonmarcos.cloudkeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.diegonmarcos.cloudkeyboardlibs.ITranslateEngine
import com.diegonmarcos.superapp.translate.TranslateEngineClient
import com.diegonmarcos.superapp.translate.Translator

/**
 * Translate engine client that binds the companion cloud-keyboard-libs
 * service over AIDL. Registered in App.onCreate so libs:translate's
 * Translator / TranslateBarView work without bundling ML Kit.
 *
 * Bind lifecycle (each one was a way the bar went "not connected" for good
 * until the keyboard process died):
 *  - companion not installed at App.onCreate, installed later: the initial
 *    bindService() returned false and nothing ever retried. Now every use with
 *    no engine re-attempts the bind, rate-limited to one try per [REBIND_MS].
 *  - companion APK updated/reinstalled: Android reports that as onBindingDied,
 *    NOT onServiceDisconnected, and a died binding never reconnects by itself —
 *    the client has to unbind and bind again. Now handled.
 *  - companion process killed (low memory): onServiceDisconnected; BIND_AUTO_CREATE
 *    reconnects on its own, the explicit rebind just makes that immediate.
 * Not connected → translate*() answer {"und", "", reason} and Translator shows
 * the reason in the bar.
 */
class AidlTranslateEngineClient(private val context: Context) : TranslateEngineClient {

    private val LIBS_PKG    = "com.diegonmarcos.cloudkeyboardlibs"
    private val LIBS_ACTION = "com.diegonmarcos.cloudkeyboardlibs.ITranslateEngine"

    @Volatile private var engine: ITranslateEngine? = null
    @Volatile private var lastBindAttempt = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = ITranslateEngine.Stub.asInterface(binder)
            Log.i(TAG, "connected to $name")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            engine = null
            // Re-bind so the client reconnects if the service restarts.
            bindService()
        }
        override fun onBindingDied(name: ComponentName) {
            // Companion updated/uninstalled: the binding is dead, not merely
            // disconnected, and the system will NOT bring it back. Drop it and
            // bind fresh (the next use retries again if this one fails too).
            engine = null
            runCatching { context.applicationContext.unbindService(this) }
            Log.w(TAG, "binding to $name died — rebinding")
            bindService()
        }
        override fun onNullBinding(name: ComponentName) {
            Log.w(TAG, "$name returned a null binder — companion build without TranslateEngineService?")
        }
    }

    init { bindService() }

    @Synchronized
    private fun bindService() {
        lastBindAttempt = SystemClock.elapsedRealtime()
        val result = runCatching {
            val intent = Intent(LIBS_ACTION).apply { setPackage(LIBS_PKG) }
            // bindService() returns false (no exception!) when the target service
            // can't be found/bound — e.g. the cloud-keyboard-libs companion app
            // isn't installed. That return value used to be silently discarded,
            // which meant a failed bind and a successful-but-pending bind were
            // indistinguishable from the outside. Log it so it's diagnosable.
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }
        val bound = result.getOrDefault(false)
        if (!bound) {
            Log.w(TAG, "bindService to $LIBS_PKG/$LIBS_ACTION failed " +
                "(exception=${result.exceptionOrNull()}) — is cloud-keyboard-libs installed?")
        }
    }

    /** Engine if bound; otherwise kick a (rate-limited) rebind and return null. */
    private fun engineOrRebind(): ITranslateEngine? {
        engine?.let { return it }
        if (SystemClock.elapsedRealtime() - lastBindAttempt > REBIND_MS) bindService()
        return null
    }

    private fun notConnected() = arrayOf("und", "", Translator.NOT_CONNECTED)

    override fun translate(text: String, targetTag: String): Array<String> {
        val e = engineOrRebind() ?: return notConnected()
        return runCatching { e.translate(text, targetTag) }.getOrNull() ?: arrayOf("und", "", "Translate engine call failed")
    }

    // A companion APK older than the translateFrom() AIDL method answers the
    // unknown transaction with an empty reply (the generated proxy then returns
    // null or throws) — fall back to the auto-detecting call so an out-of-step
    // install still translates instead of dying on a binder edge case.
    override fun translateFrom(text: String, sourceTag: String, targetTag: String): Array<String> {
        val e = engineOrRebind() ?: return notConnected()
        return runCatching { e.translateFrom(text, sourceTag, targetTag) }.getOrNull()
            ?: runCatching { e.translate(text, targetTag) }.getOrNull()
            ?: arrayOf("und", "", "Translate engine call failed")
    }

    override fun supportedLanguages(): List<String> =
        engineOrRebind()?.let { e -> runCatching { e.supportedLanguages() }.getOrNull() } ?: emptyList()

    override fun isConnected(): Boolean = engineOrRebind() != null

    private companion object {
        const val TAG = "AidlTranslateEngine"
        const val REBIND_MS = 5_000L
    }
}
