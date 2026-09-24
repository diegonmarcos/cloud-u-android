package com.diegonmarcos.cloudlib.mounts

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * PROPFIND Depth:1 multistatus → entries. Namespace-agnostic (nextcloud,
 * apache, nginx, rclone serve webdav all prefix DAV: differently), and the
 * requested collection itself is dropped from the listing. Pure Kotlin on
 * javax.xml, which Android ships, so the JVM suite parses real fixtures.
 */
object WebDavParser {
    private val rfc1123 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }

    fun parseMultistatus(xml: String, requestedPath: String): List<RemoteEntry> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val out = ArrayList<RemoteEntry>()
        val responses = doc.getElementsByTagNameNS("*", "response")
        val requested = normalise(requestedPath)
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = text(r, "href") ?: continue
            val path = normalise(URLDecoder.decode(href.substringAfter("://").let { if (href.contains("://")) it.substringAfter('/', "") .let { p -> "/$p" } else href }, "UTF-8"))
            if (path == requested) continue
            val isDir = r.getElementsByTagNameNS("*", "collection").length > 0
            val size = text(r, "getcontentlength")?.trim()?.toLongOrNull() ?: -1L
            val modified = text(r, "getlastmodified")?.trim()?.let { runCatching { rfc1123.parse(it)?.time }.getOrNull() } ?: 0L
            val name = text(r, "displayname")?.takeIf { it.isNotBlank() } ?: RemotePaths.name(path)
            out += RemoteEntry(name, path, isDir, if (isDir) -1L else size, modified)
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun text(e: Element, local: String): String? {
        val n = e.getElementsByTagNameNS("*", local)
        return if (n.length == 0) null else n.item(0).textContent
    }

    /** `/a/b/` and `/a/b` are the same collection. */
    fun normalise(p: String): String { val t = p.trim(); return if (t.length > 1) t.trimEnd('/') else t.ifEmpty { "/" } }

    const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
}
