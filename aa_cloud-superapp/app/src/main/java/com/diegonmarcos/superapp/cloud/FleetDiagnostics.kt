package com.diegonmarcos.superapp.cloud

import org.json.JSONObject

/**
 * The fleet-diagnostics bridge, as pure rules a JVM test can pin: which methods exist, how the argument is read
 * and what comes back. [FleetDiagnosticsProvider] is the Android half (permission gate, the real data sources).
 *
 * The consumer is cloud-c3-webserver's cloud.app page (:8003), which shows this app's About, Logcat and Diagnose
 * to a browser; build.json of that app declares the authority it asks (`<pkg>.fleetdiag`).
 *
 * READ-ONLY by construction: three methods, each returning text. Nothing a caller sends is written, executed or
 * used as a path; the argument only picks a line count and two flags.
 */
object FleetDiagnostics {
    const val SUFFIX = "fleetdiag"
    const val METHOD_ABOUT = "about"
    const val METHOD_LOGCAT = "logcat"
    const val METHOD_DIAGNOSE = "diagnose"
    val METHODS = listOf(METHOD_ABOUT, METHOD_LOGCAT, METHOD_DIAGNOSE)

    const val KEY_OK = "ok"
    const val KEY_MIME = "mime"
    const val KEY_PAYLOAD = "payload"
    const val KEY_ERROR = "error"

    /** A reply crosses Binder, whose per-transaction budget is 1 MiB for everything in flight; stay well under it. */
    const val MAX_PAYLOAD = 700_000
    const val LOG_LINES_DEFAULT = 500
    const val LOG_LINES_MAX = 5000

    /** Where the three answers come from. The provider wires the real ones; the test wires fakes. */
    class Source(
        val about: () -> String,
        val logcat: (lines: Int, allApps: Boolean, errorsOnly: Boolean) -> String,
        val diagnose: () -> String,
    )

    data class Answer(val ok: Boolean, val mime: String, val payload: String, val error: String)

    /** `n=500&scope=all&errors=1` -> map. Unknown keys are ignored by the callers; nothing here is trusted. */
    fun parseArg(arg: String?): Map<String, String> =
        arg.orEmpty().split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i).trim() to p.substring(i + 1).trim()
        }.toMap()

    fun answer(method: String, arg: String?, src: Source): Answer {
        val q = parseArg(arg)
        return runCatching {
            when (method) {
                METHOD_ABOUT -> json(slimAbout(src.about()))
                METHOD_DIAGNOSE -> json(src.diagnose())
                METHOD_LOGCAT -> {
                    val n = (q["n"]?.toIntOrNull() ?: LOG_LINES_DEFAULT).coerceIn(1, LOG_LINES_MAX)
                    Answer(true, "text/plain", clampTail(src.logcat(n, q["scope"] == "all", q["errors"] == "1")), "")
                }
                else -> fail("unknown method '$method' (have ${METHODS.joinToString()})")
            }
        }.getOrElse { fail("${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}") }
    }

    private fun fail(why: String) = Answer(false, "", "", why)

    private fun json(text: String): Answer =
        if (text.length > MAX_PAYLOAD) fail("reply is ${text.length} characters, over the ${MAX_PAYLOAD} the bridge carries")
        else Answer(true, "application/json", text, "")

    /**
     * About's `trees` (folder tree, sitemap, AST) are build-time dumps of several hundred KiB; they make the reply
     * cross Binder's budget and nobody reads them on a phone page. Everything else of About is kept.
     */
    fun slimAbout(aboutJson: String): String {
        val o = JSONObject(aboutJson)
        o.remove("trees")
        return o.toString()
    }

    /** The newest lines that fit, cut at a line boundary, with a first line saying so. */
    fun clampTail(text: String): String {
        if (text.length <= MAX_PAYLOAD) return text
        val tail = text.takeLast(MAX_PAYLOAD - 80)
        val cut = tail.indexOf('\n').let { if (it < 0) 0 else it + 1 }
        return "(older lines dropped: the reply is limited to ${MAX_PAYLOAD} characters)\n" + tail.substring(cut)
    }
}
