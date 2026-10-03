package com.diegonmarcos.superapp.browser

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * #802 the scraper's remote engine: scrappers-api's generic crawl, declared in
 * build.json::ui.browser.addons[scraper].remote ({enabled, endpoint, why}). The contract read
 * from its source (cloud-u-containers user-data_scrappers-api main.py): `POST /scrape/crawl
 * {url, selector}` is SYNCHRONOUS and answers `{ok, written, summary: {matched}}` — the rows
 * stay in the server's data dir. Reached over the WireGuard mesh, where no token is needed.
 * Never a silent empty list: every failure comes back in words. Blocking — call off the main thread.
 */
object ScrapeRemote {
    fun crawl(remote: JSONObject?, url: String, css: String?): JSONObject {
        if (remote == null || !remote.optBoolean("enabled"))
            return JSONObject().put("ok", false).put("remote", "unconfigured")
                .put("why", remote?.optString("why")?.ifBlank { null } ?: "build.json declares no enabled scraper remote")
        val endpoint = remote.optString("endpoint")
        return runCatching {
            val c = URL(endpoint).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 10_000; c.readTimeout = remote.optInt("timeout_ms", 60_000)
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("url", url).also { if (!css.isNullOrBlank()) it.put("selector", css) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) JSONObject(text).put("http", code)
            else JSONObject().put("ok", false).put("http", code).put("error", "scrappers-api answered $code: ${text.take(300)}")
        }.getOrElse { JSONObject().put("ok", false).put("error", "scrappers-api unreachable at $endpoint (${it.javaClass.simpleName}: ${it.message}) — is the mesh up?") }
    }
}
