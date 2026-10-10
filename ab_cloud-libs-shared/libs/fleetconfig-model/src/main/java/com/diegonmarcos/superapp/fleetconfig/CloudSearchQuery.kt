package com.diegonmarcos.superapp.fleetconfig

import android.app.SearchManager
import android.content.Intent

/**
 * How a query reaches Cloud Search (ac_cloud-search) from another app: the one contract the sender
 * (SuperApp's Cloud > Apps search row, Search "<text>" in Cloud Search) and the receiver (Cloud
 * Search's MainActivity) both compile, so neither spells the extra or the limit on its own. Here,
 * not libs:core (at its ceiling), beside CredentialProviderStatus, the other two-app contract.
 *
 * Cloud Search takes a query from ACTION_SEARCH and ACTION_WEB_SEARCH (SearchManager.QUERY), from
 * ACTION_SEND of plain text (Share > Cloud Search), and from [EXTRA_QUERY] on any intent. A blank
 * query is no query; runs of whitespace fold to one space; the text is cut at [MAX_LENGTH].
 */
object CloudSearchQuery {

    /** The explicit extra; [intent] sets it next to SearchManager.QUERY. */
    const val EXTRA_QUERY = "com.diegonmarcos.cloudsearch.extra.QUERY"

    /** A shared article stays a query, not a page of text. */
    const val MAX_LENGTH = 500

    private val SPACES = Regex("\\s+")

    /** The query as Cloud Search runs it, or null when there is nothing to search for. */
    fun clean(raw: CharSequence?): String? {
        val s = raw?.toString()?.replace(SPACES, " ")?.trim().orEmpty()
        return if (s.isEmpty()) null else s.take(MAX_LENGTH).trimEnd()
    }

    /** The query an incoming intent carries, or null (a plain launch, a blank text, a non-text share). */
    fun from(intent: Intent?): String? {
        if (intent == null) return null
        val explicit = clean(intent.getStringExtra(EXTRA_QUERY))
        return when (intent.action) {
            Intent.ACTION_SEARCH, Intent.ACTION_WEB_SEARCH -> clean(intent.getStringExtra(SearchManager.QUERY)) ?: explicit
            Intent.ACTION_SEND ->
                if (intent.type?.startsWith("text/plain") == true) clean(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)) ?: explicit
                else explicit
            else -> explicit
        }
    }

    /** The intent that hands [query] to the Cloud Search installed as [pkg]. */
    fun intent(pkg: String, query: String): Intent {
        val q = clean(query).orEmpty()
        return Intent(Intent.ACTION_SEARCH).setPackage(pkg)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(EXTRA_QUERY, q)
    }
}
