package com.diegonmarcos.clouddrive.sync

/**
 * The Provider column's rule: which forge a repository lives on, from the host of its remote URL and the DECLARED
 * host -> provider map (build.json ui.sync.providers). No host is named here.
 */
object RepoProvider {
    const val OTHER = "other"

    /** The lower-case host of an HTTPS, ssh:// or scp-like (`git@host:owner/repo`) remote URL; "" when there is none. */
    fun hostOf(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return ""
        val authority = if (u.contains("://")) u.substringAfter("://").substringBefore('/').substringBefore('?')
        else if (u.contains('@') && u.indexOf('@') < (u.indexOf(':').takeIf { it >= 0 } ?: Int.MAX_VALUE)) u.substringBefore(':')
        else u.substringBefore('/')
        val host = authority.substringAfterLast('@')
        return (if (host.startsWith("[")) host.substringBefore(']').trimStart('[') else host.substringBefore(':')).lowercase()
    }

    /** The provider id of [url]: the declared id of its host (or of a parent domain of it), else [OTHER]; null when there is no URL at all. */
    fun idOf(url: String, hosts: Map<String, String>): String? {
        val host = hostOf(url)
        if (host.isEmpty()) return null
        hosts[host]?.let { return it }
        return hosts.entries.firstOrNull { host.endsWith("." + it.key) }?.value ?: OTHER
    }

    /** What the column prints for [id]; an id without a declared label prints as itself. */
    fun label(id: String?, labels: Map<String, String>): String? = id?.let { labels[it] ?: it }

    /** The column's cell for [url] under the declared maps. */
    fun labelOf(url: String, decl: GitHostsDecl.ProvidersDecl): String? = label(idOf(url, decl.hosts), decl.labels)
}
