package com.diegonmarcos.superapp.analytics.sink

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

/**
 * How one analytics event becomes one HTTP POST, for BOTH self-hosted backends: Umami
 * (privacy-first, JSON) and Matomo (full sessions, form-encoded). Moved here from libs:analytics
 * (#871) with its wire formats unchanged, so the engine and the old in-app sender are
 * byte-identical for the same event.
 *
 * Why not the JS snippet the front pages use: that snippet only runs inside a WebView, so it
 * would see the handful of bundled HTML surfaces and none of the native UI. It also loads Matomo
 * through Tag Manager, whose container is the exact piece that was serving an empty body. Both
 * backends expose a plain HTTP tracking API, so this speaks that directly.
 *
 * Request (built by libs:analytics' Analytics): app, base (the endpoint), site, name, props
 * (string map), visitor, ua. [umami] and [matomo] are pure: they only BUILD the request, so the
 * wire format is executable by a JVM test; [send] is the only part that touches the network.
 */
internal object SinkTransport {

    /** One POST, ready to send. */
    class Prepared(val url: String, val body: String, val contentType: String, val ua: String)

    fun umami(req: JSONObject): Prepared {
        val app = req.getString("app")
        val screen = screen(req)
        val name = req.optString("name")
        val payload = JSONObject().apply {
            put("website", req.getString("site"))
            put("hostname", app)
            put("url", "/$app/$screen")
            put("title", screen)
            if (name != "pageview") put("name", name)
            val props = req.optJSONObject("props")
            if (props != null && props.length() > 0) put("data", props)
        }
        val body = JSONObject().apply {
            put("type", "event")
            put("payload", payload)
        }.toString()
        return Prepared(req.getString("base") + "/api/send", body, "application/json", req.optString("ua"))
    }

    fun matomo(req: JSONObject, rand: Int = Random.nextInt(1_000_000)): Prepared {
        val app = req.getString("app")
        val screen = screen(req)
        val name = req.optString("name")
        val params = StringBuilder()
            .append("idsite=").append(enc(req.getString("site")))
            .append("&rec=1&apiv=1")
            .append("&_id=").append(req.optString("visitor", "0000000000000000"))
            .append("&rand=").append(rand)
            .append("&action_name=").append(enc("$app/$screen"))
            .append("&url=").append(enc("app://$app/$screen"))
        if (name != "pageview") {
            params.append("&e_c=").append(enc(app))
                .append("&e_a=").append(enc(name))
        }
        // Raw Tracking API (matomo.php), NOT the Tag Manager container — the container is the
        // part that serves an empty body, and it needs a browser to execute it anyway.
        return Prepared(req.getString("base") + "/matomo.php", params.toString(), "application/x-www-form-urlencoded", req.optString("ua"))
    }

    /** The screen key an event reports under: its `screen` property, else its own name. */
    private fun screen(req: JSONObject): String =
        req.optJSONObject("props")?.takeIf { it.has("screen") }?.getString("screen") ?: req.optString("name")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Null when [url] may be POSTed to, else why not: https, and one of the fleet's own analytics hosts. */
    fun refusal(url: String, hosts: List<String> = BuildConfig.SINK_HOSTS.split(',')): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "not a URL"
        if (uri.scheme != "https") return "not https"
        return if (uri.host in hosts) null else "host ${uri.host} is not one of the fleet's analytics hosts"
    }

    /** POST [p]; answers the JSON the client reads: {"ok":true} or {"ok":false,"error":...}. */
    fun send(p: Prepared): String {
        val why = refusal(p.url)
        if (why != null) return JSONObject().put("ok", false).put("error", "refused: $why").toString()
        return try {
            val conn = (URL(p.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", p.contentType)
                // Umami rejects/misattributes requests that arrive without a User-Agent — it
                // derives browser and OS from it, and a missing one is treated as a bot. The
                // client supplies it: it is the HOST app's identity, not this engine's.
                setRequestProperty("User-Agent", p.ua)
            }
            conn.outputStream.use { it.write(p.body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) JSONObject().put("ok", true).toString()
            else JSONObject().put("ok", false).put("error", "HTTP $code").toString()
        } catch (e: Exception) {
            // LOG IT. A silent analytics sender is undebuggable. Never crash the host app - but
            // never fail invisibly either.
            Log.w("cloud-analytics-sink", "send failed: ${p.url} (${e.javaClass.simpleName}: ${e.message})")
            JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}").toString()
        }
    }
}
