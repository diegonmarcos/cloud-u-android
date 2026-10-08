package com.diegonmarcos.cloudbrowser.agentapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * #913 the READ-ONLY half of the fleet agent door (see [AgentApiContract]): `call("fetch_text", extras url)`
 * answers the title and visible text of one https page. A single GET with a fixed User-Agent: no cookie is
 * sent or kept, no body is written, no form is submitted. Exported behind the signature permission; it has
 * no query, insert, update or delete.
 */
class AgentFetchProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != AgentApiContract.METHOD_FETCH_TEXT) return fail("", "unknown method: $method")
        val asked = extras?.getString(AgentApiContract.EXTRA_URL)
        val req = AgentApiContract.fetchRequest(asked, extras?.getInt(AgentApiContract.EXTRA_MAX_CHARS, 0) ?: 0)
        return when (req) {
            is AgentApiContract.FetchParsed.Refused -> fail(asked.orEmpty(), req.why)
            is AgentApiContract.FetchParsed.Ok -> runCatching { fetch(req.url, req.maxChars) }
                .getOrElse { fail(req.url, "${it.javaClass.simpleName}: ${it.message}".take(200)) }
        }
    }

    private fun fetch(first: String, maxChars: Int): Bundle {
        var url = first
        var hops = 0
        while (true) {
            AgentApiContract.hopRefusal(url)?.let { return fail(first, "$it ($url)") }
            val host = URL(url).host
            // The lookup is ours, so a name that resolves into a private range is refused here too.
            for (a in InetAddress.getAllByName(host)) AgentApiContract.addressRefusal(a)?.let { return fail(first, "$host $it") }
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "GET"
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.connectTimeout = AgentApiContract.TIMEOUT_MS
                c.readTimeout = AgentApiContract.TIMEOUT_MS
                c.setRequestProperty("User-Agent", AgentApiContract.USER_AGENT)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.8")
                c.setRequestProperty("Accept-Language", "de,en;q=0.8")
                val code = c.responseCode
                if (code in 300..399) {
                    val next = AgentApiContract.redirectTarget(url, c.getHeaderField("Location"))
                        ?: return fail(first, "HTTP $code without a usable Location")
                    if (++hops > AgentApiContract.MAX_REDIRECTS) return fail(first, "more than ${AgentApiContract.MAX_REDIRECTS} redirects")
                    url = next
                    continue
                }
                if (code !in 200..299) return fail(first, "HTTP $code").also { it.putInt(AgentApiContract.R_HTTP, code) }
                val type = c.contentType.orEmpty()
                if (type.isNotBlank() && !type.contains("html", true) && !type.contains("text/plain", true))
                    return fail(first, "not a text page: $type").also { it.putInt(AgentApiContract.R_HTTP, code) }
                val bytes = c.inputStream.use { readCapped(it, AgentApiContract.MAX_BYTES) }
                val body = String(bytes, AgentApiContract.charsetOf(type))
                val page = AgentApiContract.extract(body, maxChars)
                return Bundle().apply {
                    putBoolean(AgentApiContract.R_OK, true)
                    putString(AgentApiContract.R_URL, first)
                    putString(AgentApiContract.R_FINAL_URL, url)
                    putInt(AgentApiContract.R_HTTP, code)
                    putString(AgentApiContract.R_TITLE, page.title)
                    putString(AgentApiContract.R_TEXT, page.text)
                    putBoolean(AgentApiContract.R_TRUNCATED, page.truncated || bytes.size >= AgentApiContract.MAX_BYTES)
                }
            } finally {
                c.disconnect()
            }
        }
    }

    /** At most [cap] bytes (InputStream.readNBytes is API 33; the app's floor is 26). */
    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < cap) {
            val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun fail(url: String, why: String) = Bundle().apply {
        putBoolean(AgentApiContract.R_OK, false)
        putString(AgentApiContract.R_URL, url)
        putString(AgentApiContract.R_ERROR, why)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
