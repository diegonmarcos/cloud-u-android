package com.diegonmarcos.cloudsearch.data

import android.content.Context
import android.os.SystemClock
import com.diegonmarcos.superapp.texttools.TextToolsClient

/**
 * The OpenRouter token, read from the fleet Account on each use (libs:text-tools
 * TextToolsClient.revealAiKey over the CONSTELLATION_DATA-guarded binder the SuperApp's Profile
 * fills from the vault). This app keeps NO copy: not in prefs, not in a file, never logged, never
 * in a debug answer — the only thing it says about the token is whether one is there.
 */
object Account {
    data class Token(val value: String?, val why: String)

    /** Tests replace the binder read. */
    @Volatile var reader: (Context, String) -> Token = { ctx, provider -> readAccount(ctx, provider) }

    fun token(ctx: Context, provider: String): Token = reader(ctx, provider)

    private const val WAIT_MS = 3_000L
    @Volatile private var tools: TextToolsClient? = null

    private fun readAccount(ctx: Context, provider: String): Token {
        val c = tools ?: synchronized(this) { tools ?: TextToolsClient(ctx.applicationContext).also { tools = it } }
        if (c.isServingAppInstalled()) {
            val until = SystemClock.elapsedRealtime() + WAIT_MS
            while (!c.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        }
        val r = c.revealAiKey(provider)
        return Token(r.text?.takeIf { it.isNotBlank() }, r.error ?: "no $provider token in the fleet Account")
    }
}
