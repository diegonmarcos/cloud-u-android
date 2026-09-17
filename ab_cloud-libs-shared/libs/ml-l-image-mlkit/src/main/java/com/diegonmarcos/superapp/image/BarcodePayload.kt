package com.diegonmarcos.superapp.image.mlkit

/**
 * What a decoded barcode MEANS, not what it literally contains. A QR code that
 * wraps "https://example.com/a" is a [Url] whose action is "open a browser",
 * not a [Plain] string the user has to interpret. This classification is what
 * turns a scan into typed actions (copy, open, join WiFi, add contact, add
 * calendar event) for both consumer apps; neither app re-classifies the raw
 * string, because that would be a second copy of the same logic.
 */
sealed class BarcodePayload {

    /** A web address. Action: open in the browser (or copy). */
    data class Url(val url: String) : BarcodePayload()

    /** A WIFI: scheme credential. Action: join the network. */
    data class Wifi(
        val ssid: String,
        val password: String,
        val security: String,
        val hidden: Boolean
    ) : BarcodePayload()

    /** A vCard (BEGIN:VCARD) or MECARD business card. Action: add contact. */
    data class Contact(
        val name: String?,
        val vcard: String
    ) : BarcodePayload()

    /** A VEVENT calendar entry. Action: add calendar event. */
    data class Calendar(
        val summary: String?,
        val location: String?,
        val startTimeEpochMillis: Long?,
        val endTimeEpochMillis: Long?,
        val vevent: String
    ) : BarcodePayload()

    /** A telephone number. Action: dial or copy. */
    data class Phone(val number: String) : BarcodePayload()

    /** An e-mail address with optional subject and body. Action: compose. */
    data class Email(
        val address: String,
        val subject: String?,
        val body: String?
    ) : BarcodePayload()

    /** A geo: coordinate. Action: open the map. */
    data class Geo(val latitude: Double, val longitude: Double) : BarcodePayload()

    /**
     * Anything the parser does not recognise. Copy is the only honest action;
     * guessing a meaning this code does not model would be IMAP-style
     * over-classification, and a wrong action is worse than no action.
     */
    data class Plain(val text: String) : BarcodePayload()
}

/**
 * Parses a decoded barcode string into a [BarcodePayload]. Pure function with
 * no Android imports so it is unit-testable on the JVM in every consumer app's
 * `unit` phase. All schemes are ANDROID URL-style schemes ZXing hands over
 * verbatim; the formats this recognises follow the published scheme
 * specifications (WiFi QR, MECARD, vCard 3.0, VEVENT, geo:).
 */
object BarcodePayloadParser {

    /** Parses [raw]; never throws — an unreadable value is a [BarcodePayload.Plain]. */
    fun parse(raw: String): BarcodePayload {
        val value = raw.trim()
        if (value.isEmpty()) return BarcodePayload.Plain(value)

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return BarcodePayload.Url(value)
        }

        if (value.startsWith("WIFI:", ignoreCase = true)) {
            parseWifi(value)?.let { return it }
        }

        if (value.startsWith("MECARD:", ignoreCase = true) || value.startsWith("BEGIN:VCARD", ignoreCase = true)) {
            parseContact(value)?.let { return it }
        }

        if (value.startsWith("BEGIN:VEVENT", ignoreCase = true)) {
            parseCalendar(value)?.let { return it }
        }

        if (value.startsWith("tel:", ignoreCase = true)) {
            return BarcodePayload.Phone(value.substringAfter(':').trim())
        }

        if (value.startsWith("mailto:", ignoreCase = true)) {
            parseMailto(value)?.let { return it }
        }

        if (value.startsWith("geo:", ignoreCase = true)) {
            parseGeo(value)?.let { return it }
        }

        return BarcodePayload.Plain(value)
    }

    /** WIFI:T:WPA;S:mynet;P:secret;H:true;; — each field is semicolon-terminated. */
    private fun parseWifi(value: String): BarcodePayload.Wifi? {
        val fields = value.substringAfter("WIFI:", ignoreCase = true)
        var ssid = ""; var password = ""; var security = ""; var hidden = false
        for (field in fields.split(';')) {
            val trimmed = field.trim()
            if (trimmed.length < 2 || trimmed[1] != ':') continue
            when (trimmed[0].uppercaseChar()) {
                'S' -> ssid = unescape(trimmed.substring(2))
                'P' -> password = unescape(trimmed.substring(2))
                'T' -> security = trimmed.substring(2)
                'H' -> hidden = trimmed.substring(2).equals("true", ignoreCase = true)
            }
        }
        // A WiFi code with no network name is not actionable — refuse to guess.
        if (ssid.isEmpty()) return null
        return BarcodePayload.Wifi(ssid, password, security, hidden)
    }

    /** MECARD:N:Last,First;TEL:..;URL:..;; and BEGIN:VCARD blocks. */
    private fun parseContact(value: String): BarcodePayload.Contact? {
        if (value.startsWith("MECARD:", ignoreCase = true)) {
            val name = value.substringAfter("MECARD:", ignoreCase = true)
                .split(';').firstOrNull { it.trim().startsWith("N:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.replace('\\', ',') // MECARD writes "Last,First" and escapes commas
            return BarcodePayload.Contact(name?.trim()?.ifEmpty { null }, value)
        }
        if (value.startsWith("BEGIN:VCARD", ignoreCase = true)) {
            val name = value.lineSequence()
                .firstOrNull { it.trim().startsWith("FN:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
            return BarcodePayload.Contact(name?.ifEmpty { null }, value)
        }
        return null
    }

    /** BEGIN:VEVENT DTSTART/DTEND are UTC yyyyMMdd'T'HHmmss'Z'; SUMMARY/LOCATION are plain. */
    private fun parseCalendar(value: String): BarcodePayload.Calendar? {
        fun field(name: String): String? =
            value.lineSequence().firstOrNull { it.trim().startsWith("$name:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()

        fun epochMillis(dt: String?): Long? {
            if (dt == null) return null
            val normalized = dt.removePrefix("DTSTART:").removePrefix("DTEND:")
            return try {
                val year = normalized.substring(0, 4).toInt()
                val month = normalized.substring(4, 6).toInt()
                val day = normalized.substring(6, 8).toInt()
                val hour = normalized.substring(9, 11).toInt()
                val minute = normalized.substring(11, 13).toInt()
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(year, month - 1, day, hour, minute, 0)
                }.timeInMillis
            } catch (error: Exception) {
                null
            }
        }

        val startLine = value.lineSequence()
            .firstOrNull { it.trim().startsWith("DTSTART", ignoreCase = true) }
        val endLine = value.lineSequence()
            .firstOrNull { it.trim().startsWith("DTEND", ignoreCase = true) }
        return BarcodePayload.Calendar(
            summary = field("SUMMARY"),
            location = field("LOCATION"),
            startTimeEpochMillis = startLine?.let { epochMillis(it) },
            endTimeEpochMillis = endLine?.let { epochMillis(it) },
            vevent = value
        )
    }

    /** mailto:user@example.com?subject=...&body=... */
    private fun parseMailto(value: String): BarcodePayload.Email? {
        val rest = value.substringAfter("mailto:", ignoreCase = true)
        val address = rest.substringBefore('?').trim()
        if (address.isEmpty()) return null
        var subject: String? = null
        var body: String? = null
        if (rest.contains('?')) {
            for (pair in rest.substringAfter('?').split('&')) {
                val key = pair.substringBefore('=').lowercase()
                val valueText = pair.substringAfter('=', "").replace('+', ' ')
                when (key) {
                    "subject" -> subject = urlDecode(valueText)
                    "body" -> body = urlDecode(valueText)
                }
            }
        }
        return BarcodePayload.Email(address, subject, body)
    }

    /** geo:37.422,-122.084 or geo:37.422,-122.084?z=15 */
    private fun parseGeo(value: String): BarcodePayload.Geo? {
        val coordinates = value.substringAfter("geo:", ignoreCase = true).substringBefore('?')
        val parts = coordinates.split(',')
        if (parts.size < 2) return null
        return try {
            BarcodePayload.Geo(parts[0].trim().toDouble(), parts[1].trim().toDouble())
        } catch (error: Exception) {
            null
        }
    }

    /** WiFi QR escapes \; , " and : with a backslash. */
    private fun unescape(text: String): String =
        text.replace("\\;", ";").replace("\\,", ",").replace("\\\"", "\"").replace("\\:", ":")

    private fun urlDecode(text: String): String =
        try {
            java.net.URLDecoder.decode(text, "UTF-8")
        } catch (error: Exception) {
            text
        }
}