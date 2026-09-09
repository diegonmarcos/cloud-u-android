package app.sterna.core.jmap

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a push log line says instead of a URL it could not parse — never the URL itself. */
internal const val UNPARSEABLE_EVENT_SOURCE_URL = "<unparseable url>"

    /**
     * The origin of an EventSource URL: scheme, host, and the port only when it is not the scheme's
     */
internal fun eventSourceOrigin(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return UNPARSEABLE_EVENT_SOURCE_URL
    val port = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
    return "${parsed.scheme}://${parsed.host}$port"
}
