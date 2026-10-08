package com.diegonmarcos.cloudbrowser.agentapi

import java.net.InetAddress
import java.net.URI

/**
 * #913 THE FLEET AGENT DOOR TO THE BROWSER, as a contract with no Android in it so a plain JVM test holds it.
 *
 * Cloud Search's Agents tab needs two things from the browser and nothing more:
 *
 *  1. OPEN a URL for the person, optionally in a named tab group: the exported activity
 *     [ACTION_OPEN] (extras [EXTRA_URL], [EXTRA_GROUP]). It shows a page; it submits nothing.
 *  2. READ the text of a URL, read-only: the provider `content://<applicationId>.agentapi`, method
 *     [METHOD_FETCH_TEXT] (extras [EXTRA_URL], [EXTRA_MAX_CHARS]). One plain GET, no cookies sent, no
 *     request body, https only, redirects re-checked hop by hop, private and loopback addresses refused.
 *
 * Both are guarded by the constellation's SIGNATURE permission ([PERMISSION]) in the manifest, the way
 * AuthMissionActivity is, so only an app signed with the one fleet key can call them. There is no click,
 * no form fill and no load-wait over this door, on purpose: those are the write half of browsing.
 */
object AgentApiContract {
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
    const val ACTION_OPEN = "com.diegonmarcos.cloudbrowser.action.AGENT_OPEN"
    const val AUTHORITY_SUFFIX = "agentapi"
    const val METHOD_FETCH_TEXT = "fetch_text"

    const val EXTRA_URL = "url"
    const val EXTRA_GROUP = "group"
    const val EXTRA_MAX_CHARS = "max_chars"

    /** The Bundle keys of a fetch answer. */
    const val R_OK = "ok"
    const val R_URL = "url"
    const val R_FINAL_URL = "final_url"
    const val R_HTTP = "http"
    const val R_TITLE = "title"
    const val R_TEXT = "text"
    const val R_TRUNCATED = "truncated"
    const val R_ERROR = "error"

    const val DEFAULT_MAX_CHARS = 20_000
    const val MAX_MAX_CHARS = 200_000
    const val MAX_BYTES = 2_000_000
    const val MAX_REDIRECTS = 3
    const val GROUP_MAX_CHARS = 40
    const val TIMEOUT_MS = 15_000
    const val USER_AGENT = "CloudBrowserAgentFetch/1 (read-only; +https://github.com/diegonmarcos/cloud-u-android)"

    sealed interface OpenParsed {
        data class Ok(val url: String, val group: String) : OpenParsed
        data class Refused(val why: String) : OpenParsed
    }

    /** An open request: an http(s) URL with a host, and a group that is blank or at most [GROUP_MAX_CHARS] chars. */
    fun openRequest(url: String?, group: String?): OpenParsed {
        val u = url?.trim().orEmpty()
        val uri = runCatching { URI(u) }.getOrNull()
        if (u.isEmpty() || uri == null || uri.host.isNullOrBlank() || uri.scheme?.lowercase() !in setOf("http", "https"))
            return OpenParsed.Refused("url must be an http(s) address with a host")
        val g = group?.trim().orEmpty().replace(Regex("\\s+"), " ")
        if (g.length > GROUP_MAX_CHARS) return OpenParsed.Refused("group is longer than $GROUP_MAX_CHARS chars")
        return OpenParsed.Ok(u, g)
    }

    sealed interface FetchParsed {
        data class Ok(val url: String, val maxChars: Int) : FetchParsed
        data class Refused(val why: String) : FetchParsed
    }

    /** A fetch request: https only, a public-looking host, [maxChars] 0 = the default, else 1..[MAX_MAX_CHARS]. */
    fun fetchRequest(url: String?, maxChars: Int): FetchParsed {
        val u = url?.trim().orEmpty()
        hopRefusal(u)?.let { return FetchParsed.Refused(it) }
        if (maxChars < 0 || maxChars > MAX_MAX_CHARS) return FetchParsed.Refused("max_chars must be 1..$MAX_MAX_CHARS")
        return FetchParsed.Ok(u, if (maxChars == 0) DEFAULT_MAX_CHARS else maxChars)
    }

    /** Why [url] may not be fetched (checked on the first URL and on every redirect hop), or null. */
    fun hopRefusal(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "not a URL"
        if (uri.scheme?.lowercase() != "https") return "only https is fetched"
        if (uri.userInfo != null) return "a URL with credentials is refused"
        val host = uri.host?.lowercase().orEmpty()
        return hostRefusal(host)
    }

    /** A name that points at this phone or its network, or an IP literal, is refused before any lookup. */
    fun hostRefusal(host: String): String? {
        if (host.isBlank()) return "no host"
        if (host.startsWith("[") || host.contains(':')) return "an IP address is refused"
        if (Regex("^[0-9.]+$").matches(host)) return "an IP address is refused"
        if (!host.contains('.')) return "a single-label host is refused"
        val local = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".localdomain", ".corp", ".intranet", ".wg")
        if (host == "localhost" || local.any { host.endsWith(it) }) return "a local host is refused"
        return null
    }

    /** After the lookup: an address in a private, loopback, link-local or any-local range is refused. */
    fun addressRefusal(a: InetAddress): String? = when {
        a.isAnyLocalAddress || a.isLoopbackAddress -> "resolves to this phone"
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress -> "resolves to a private network"
        a.address.size == 4 && (a.address[0].toInt() and 0xff) == 100 && (a.address[1].toInt() and 0xc0) == 64 -> "resolves to a carrier-grade private range"
        a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc -> "resolves to a private network"
        else -> null
    }

    /** The absolute URL a redirect's Location header names, relative to [base]; null if it cannot be resolved. */
    fun redirectTarget(base: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        return runCatching { URI(base).resolve(location.trim()).toString() }.getOrNull()
    }

    /** charset=… of a Content-Type header, or UTF-8. */
    fun charsetOf(contentType: String?): java.nio.charset.Charset {
        val name = contentType?.let { Regex("charset=\"?([A-Za-z0-9_\\-:.]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return runCatching { if (name != null) java.nio.charset.Charset.forName(name) else Charsets.UTF_8 }.getOrDefault(Charsets.UTF_8)
    }

    data class Extracted(val title: String, val text: String, val truncated: Boolean)

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "auml" to "ä", "ouml" to "ö",
        "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß", "euro" to "€", "ndash" to "–",
        "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»", "sect" to "§",
    )

    /** `&amp;`, `&auml;`, `&#228;`, `&#xE4;` → the character; an unknown entity is left as written. */
    fun unescape(s: String): String = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { cp(it) } ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }

    private fun cp(n: Int): String = if (n in 1..0x10FFFF && n !in 0xD800..0xDFFF) String(Character.toChars(n)) else ""

    /** The page's title and its visible text: scripts, styles and comments dropped, blocks as lines, entities decoded, cut at [maxChars]. */
    fun extract(html: String, maxChars: Int): Extracted {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.let { unescape(it.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim() }.orEmpty()
        val flat = html
            .replace(Regex("(?s)<!--.*?-->"), "")
            .replace(Regex("(?is)<(script|style|noscript|template|svg|head)\\b.*?</\\1\\s*>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|ul|ol|table|blockquote|dd|dt|header|footer)\\s*>"), "\n")
            .replace(Regex("(?i)</(td|th)\\s*>"), " ")
            .replace(Regex("<[^>]+>"), "")
        val lines = unescape(flat).lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
        val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length > maxChars) Extracted(title, text.take(maxChars), true) else Extracted(title, text, false)
    }
}
