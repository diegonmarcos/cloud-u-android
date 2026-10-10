package com.diegonmarcos.superapp.browser

import android.content.Context
import com.diegonmarcos.superapp.autofill.AutofillCandidates
import com.diegonmarcos.superapp.autofill.AutofillSotClient
import java.io.File

/**
 * The three kinds of data a clear can touch, kept apart (a0_docs/eng-specs/autofill-3-tier.md §3.1):
 *
 *  1. COOKIES — per site and all.
 *  2. SITE DATA — cache, localStorage/sessionStorage, IndexedDB, service workers / Cache Storage, Web SQL,
 *     WebView's own form entries (all WebView storage), plus what the browser itself keeps about pages
 *     (history, previews, tab state, offline copies, downloads).
 *  3. AUTOFILL DATA — profiles, addresses, site/form mapping rules, snippets. NOT in WebView storage at
 *     all: it is the Cloud Account SOT (read over its provider, cached in memory only). The one
 *     browser-local piece is the legacy imported profile (`browser_autofill`), which Android's
 *     "Clear storage" of this app does remove — the screen says so.
 *
 * Every id a cookie/site clear knows maps to 1 or 2; only [AUTOFILL_LOCAL] maps to 3, and it is cleared
 * by [clearLocal] alone, from its own section with its own confirm. Nothing here can delete a SOT row:
 * editing and deleting them is Cloud Account's (the provider refuses a foreign delete).
 */
object BrowserClearCategories {
    const val COOKIES = "cookies"
    const val SITE_DATA = "site_data"
    const val AUTOFILL = "autofill"
    const val AUTOFILL_LOCAL = "autofill_local"

    /** The per-site clear's boxes: this site's cookies, this site's storage. Both default on. */
    const val SITE_COOKIES = "site_cookies"
    const val SITE_STORAGE = "site_storage"
    val SITE_BOXES = listOf(SITE_COOKIES to "Cookies of this site", SITE_STORAGE to "Site storage of this site (local/session storage, IndexedDB, service workers, cache storage)")

    fun of(id: String): String = when (id) {
        "cookies", SITE_COOKIES -> COOKIES
        "cache", "dom", "indexeddb", "storage", "form", SITE_STORAGE, "history", "previews", "downloads", "offline", "tabstate" -> SITE_DATA
        AUTOFILL_LOCAL -> AUTOFILL
        else -> "unknown"
    }

    /** What a clear screen pre-ticks: never autofill data. */
    fun defaults(ids: Collection<String>): Set<String> = ids.filter { of(it) != AUTOFILL }.toSet()

    /** The origins a per-site storage clear deletes: [host]'s own (and its www twin), nothing else. */
    fun siteOrigins(host: String): List<String> {
        val h = host.lowercase().trim().trimEnd('.')
        if (h.isEmpty()) return emptyList()
        val twins = if (h.startsWith("www.")) listOf(h, h.removePrefix("www.")) else listOf(h, "www.$h")
        return twins.flatMap { listOf("https://$it", "http://$it") }
    }
}

/** Category 3 as the browser can see and touch it. */
object BrowserAutofillData {
    data class Summary(
        val accountInstalled: Boolean,
        val profiles: Int, val addresses: Int, val rules: Int, val siteRules: Int, val snippets: Int,
        /** The legacy browser-local profile file, in bytes (0 = none). */
        val localBytes: Long,
    )

    /** BLOCKING (provider reads, a file stat): off the main thread. [host] = the page the screen was opened from. */
    fun summary(ctx: Context, host: String): Summary {
        val installed = AutofillSotClient.installed(ctx)
        val profiles = if (installed) AutofillSotClient.profiles(ctx) else emptyList()
        val rules = if (installed) AutofillSotClient.rules(ctx) else emptyList()
        val snippets = if (installed) AutofillSotClient.snippets(ctx) else emptyList()
        val local = File(ctx.dataDir, "shared_prefs/${BrowserProfileStore.STORE}.xml").let { if (it.exists()) it.length() else 0L }
        return Summary(installed, profiles.size, profiles.sumOf { it.addresses.size }, rules.size,
            if (host.isBlank()) 0 else AutofillCandidates.rulesFor(host, rules).size, snippets.size, local)
    }

    /**
     * Forgets the browser-local autofill data: the legacy imported profile and the in-memory SOT copy.
     * Never a cookie, never WebView storage — and never a Cloud Account row.
     */
    fun clearLocal(ctx: Context, cache: DomAutofill?) {
        runCatching { BrowserProfileStore(ctx).clear() }
        cache?.forgetCache()
    }
}
