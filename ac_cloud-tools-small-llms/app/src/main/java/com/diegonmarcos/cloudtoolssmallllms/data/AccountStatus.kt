package com.diegonmarcos.cloudtoolssmallllms.data

import android.content.Context
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The fleet Account as a status line, NEVER its key (decisions-use guard D5: no new module reads the
 * Account's OpenRouter key; Cloud Code's Chat does the same, 19ca975f): whether the ITextTools
 * binder answers, and whether it holds an OpenRouter key (key_present, key_hint = at most the last
 * four characters), from the key-free aiRoutingSnapshot.
 */
class AccountStatus(context: Context) {
    data class State(val connected: Boolean, val keyPresent: Boolean, val keyHint: String)

    private val client by lazy { TextToolsClient(context.applicationContext) }
    private val pool = Executors.newSingleThreadExecutor()

    /** Blocks up to [DEADLINE_MS]: a wedged peer never hangs the page. Call off the main thread. */
    fun read(): State {
        val snap = runCatching {
            pool.submit(Callable { client.aiRoutingSnapshot() }).get(DEADLINE_MS, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return State(false, false, "")
        return runCatching {
            val providers = JSONObject(snap).optJSONArray("providers")
            val p = (0 until (providers?.length() ?: 0)).mapNotNull { providers?.optJSONObject(it) }.firstOrNull { it.optString("id") == PROVIDER }
            State(true, p?.optBoolean("key_present") == true, p?.optString("key_hint").orEmpty().takeLast(4))
        }.getOrElse { State(true, false, "") }
    }

    companion object {
        /** The fleet Account's provider id for OpenRouter (Cloud Search's search.ai.account_provider). */
        const val PROVIDER = "openrouter"
        private const val DEADLINE_MS = 2500L
    }
}
