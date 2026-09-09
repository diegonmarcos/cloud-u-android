package app.sterna.core.data.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A single parsed calendar event (the first VEVENT of an iCalendar object), reduced to the
 */
data class ParsedEvent(
    val title: String?,
    val startMillis: Long,
    val endMillis: Long?,
    val allDay: Boolean,
    val location: String?,
    /** Organizer display name (CN) if given, else the bare e-mail (mailto: stripped). */
    val organizer: String?,
    val attendeeCount: Int,
    /** True when the event carries an RRULE (it repeats); we don't expand the rule. */
    val recurs: Boolean,
    val description: String?,
    /** VCALENDAR METHOD (REQUEST, CANCEL, …) in upper case, if present. */
    val method: String?,
    /** VEVENT STATUS (CONFIRMED, CANCELLED, …) in upper case, if present. */
    val status: String?,
    /** VEVENT UID — the invite's stable identity; a REPLY must echo it. */
    val uid: String? = null,
    /** VEVENT SEQUENCE (revision counter); defaults to 0 when absent. */
    val sequence: Int = 0,
    /** Organizer e-mail (mailto: stripped), needed as the REPLY recipient. */
    val organizerEmail: String? = null,
    /** Organizer display name (CN) on its own, if any. */
    val organizerCn: String? = null,
    /** Verbatim (post-unfolding) property lines so a REPLY can echo them exactly. */
    val rawUid: String? = null,
    val rawDtStart: String? = null,
    val rawDtEnd: String? = null,
    val rawSequence: String? = null,
    /** Every ATTENDEE on the event, so we can find "our" entry and reuse its CN. */
    val attendees: List<Attendee> = emptyList(),
) {
    /** A cancellation, whether signalled by the transport METHOD or the event STATUS. */
    val cancelled: Boolean get() = method == "CANCEL" || status == "CANCELLED"
}

/** One ATTENDEE line: its e-mail (mailto: stripped), optional CN, and the verbatim line. */
data class Attendee(val email: String, val cn: String?, val raw: String)

/**
 * A small, dependency-free RFC 5545 reader. It unfolds, finds the first VEVENT, and pulls the
 */
object ICalendar {

    /**
     * Largest .ics we will read (1 MiB). Real invitations are a few kilobytes; a huge one is
     * either broken or hostile, and reading it buys nothing — the card is skipped instead.
     */
    const val MAX_SOURCE_CHARS = 1024 * 1024

    /** Parse [raw] .ics text; returns the first event, or null if there is nothing usable. */
    fun parse(raw: String?): ParsedEvent? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > MAX_SOURCE_CHARS) return null
        return try {
            val lines = unfold(raw).mapNotNull(::parseLine)

            var method: String? = null
            val event = ArrayList<Line>()
            var inEvent = false
            var captured = false
            for (l in lines) {
                when {
                    l.name == "BEGIN" && l.value.trim().equals("VEVENT", true) ->
                        if (!captured) { inEvent = true }
                    l.name == "END" && l.value.trim().equals("VEVENT", true) ->
                        if (inEvent) { inEvent = false; captured = true }
                    inEvent -> event.add(l)
                    l.name == "METHOD" && method == null -> method = l.value.trim().uppercase()
                }
            }
            if (event.isEmpty()) return null

            fun first(name: String) = event.firstOrNull { it.name == name }

            // An event with no usable start can't be placed on a calendar — treat as unusable.
            val startLine = first("DTSTART") ?: return null
            val start = parseDate(startLine.value, startLine.params) ?: return null

            val endLine = first("DTEND")
            val end: Long? = when {
                endLine != null -> parseDate(endLine.value, endLine.params)?.millis
                else -> first("DURATION")?.let { parseDuration(it.value) }?.let { start.millis + it }
            }

            val organizerLine = first("ORGANIZER")
            val seqLine = first("SEQUENCE")
            val uidLine = first("UID")
            val attendees = event.filter { it.name == "ATTENDEE" }.map {
                Attendee(email = emailOf(it.value), cn = cnOf(it), raw = it.raw)
            }

            ParsedEvent(
                title = first("SUMMARY")?.let { unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                startMillis = start.millis,
                endMillis = end,
                allDay = start.allDay,
                location = first("LOCATION")?.let { unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                organizer = organizerLine?.let(::organizerOf),
                attendeeCount = attendees.size,
                recurs = event.any { it.name == "RRULE" },
                description = first("DESCRIPTION")?.let { unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                method = method,
                status = first("STATUS")?.value?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
                uid = uidLine?.value?.trim()?.takeIf { it.isNotBlank() },
                sequence = seqLine?.value?.trim()?.toIntOrNull() ?: 0,
                organizerEmail = organizerLine?.let { emailOf(it.value) }?.takeIf { it.isNotBlank() },
                organizerCn = organizerLine?.let(::cnOf),
                rawUid = uidLine?.raw,
                rawDtStart = startLine.raw,
                rawDtEnd = endLine?.raw,
                rawSequence = seqLine?.raw,
                attendees = attendees,
            )
        } catch (t: Throwable) {
            null
        }
    }

    // ---- Lines -------------------------------------------------------------------------------

    private data class Line(
        val name: String,
        val params: Map<String, String>,
        val value: String,
        /** The verbatim unfolded source line, kept so a REPLY can echo it exactly. */
        val raw: String,
    )

    private data class Dated(val millis: Long, val allDay: Boolean)

    /**
     * RFC 5545 unfolding: join a continuation line (CRLF/LF followed by space/tab) back onto the
     */
    private fun unfold(raw: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var started = false
        var i = 0
        while (i < raw.length) {
            var end = i
            while (end < raw.length && raw[end] != '\n' && raw[end] != '\r') end++
            if (started && end > i && (raw[i] == ' ' || raw[i] == '\t')) {
                current.append(raw, i + 1, end) // continuation: drop the single leading space/tab
            } else {
                if (started) out.add(current.toString())
                current.setLength(0)
                current.append(raw, i, end)
                started = true
            }
            // CRLF is one break, so is a lone CR or LF.
            i = if (end + 1 < raw.length && raw[end] == '\r' && raw[end + 1] == '\n') end + 2 else end + 1
        }
        if (started) out.add(current.toString())
        return out
    }

    /** Split one content line into NAME, params, and VALUE (value starts at the first unquoted colon). */
    private fun parseLine(line: String): Line? {
        if (line.isBlank()) return null
        var colon = -1
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ':' && !inQuote) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val segs = splitUnquoted(head, ';')
        if (segs.isEmpty()) return null
        val name = segs[0].trim().uppercase()
        if (name.isEmpty()) return null
        val params = HashMap<String, String>()
        for (j in 1 until segs.size) {
            val eq = segs[j].indexOf('=')
            if (eq > 0) {
                params[segs[j].substring(0, eq).trim().uppercase()] =
                    segs[j].substring(eq + 1).trim().trim('"')
            }
        }
        return Line(name, params, value, line)
    }

    private fun splitUnquoted(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in s) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == sep && !inQuote -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    // ---- Dates -------------------------------------------------------------------------------

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * Convert a DTSTART/DTEND value to epoch millis. Handles UTC (`…Z`), zoned (`TZID=`),
     */
    private fun parseDate(rawValue: String, params: Map<String, String>): Dated? {
        val v = rawValue.trim()
        if (v.isEmpty()) return null
        val dateOnly = params["VALUE"].equals("DATE", true) || (v.length == 8 && !v.contains('T'))
        return try {
            if (dateOnly) {
                val date = LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE)
                val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                Dated(millis, allDay = true)
            } else {
                val utc = v.endsWith("Z")
                val core = if (utc) v.dropLast(1) else v
                val ldt = LocalDateTime.parse(core, DATE_TIME)
                val zone = when {
                    utc -> ZoneOffset.UTC
                    params["TZID"] != null ->
                        runCatching { ZoneId.of(params["TZID"]) }.getOrDefault(ZoneId.systemDefault())
                    else -> ZoneId.systemDefault()
                }
                Dated(ldt.atZone(zone).toInstant().toEpochMilli(), allDay = false)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private val DURATION =
        Regex("^([+-]?)P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")

    /** Parse an iCalendar DURATION (e.g. PT1H30M, P1D, P1W) to milliseconds. */
    private fun parseDuration(value: String): Long? {
        val m = DURATION.find(value.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        fun n(x: String) = x.toLongOrNull() ?: 0L
        val seconds = n(w) * 7 * 24 * 3600 + n(d) * 24 * 3600 + n(h) * 3600 + n(min) * 60 + n(s)
        if (seconds == 0L) return null
        val ms = seconds * 1000
        return if (sign == "-") -ms else ms
    }

    // ---- Text --------------------------------------------------------------------------------

    /** Unescape an iCalendar TEXT value: \\n → newline, \\, \; \\\\ → the literal character. */
    private fun unescapeText(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    ';' -> sb.append(';')
                    ',' -> sb.append(',')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun organizerOf(line: Line): String? {
        line.params["CN"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return emailOf(line.value).takeIf { it.isNotBlank() }
    }

    /** A CAL-ADDRESS value reduced to its e-mail (drops a leading `mailto:`). */
    private fun emailOf(value: String): String =
        value.trim().replaceFirst(Regex("(?i)^mailto:"), "").trim()

    private fun cnOf(line: Line): String? =
        line.params["CN"]?.trim()?.takeIf { it.isNotBlank() }

    // ---- REPLY (iTIP) generation ------------------------------------------------------------

    private val UTC_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /**
     * Build a minimal iTIP REPLY (RFC 5546) VCALENDAR for [event], echoing UID/ORGANIZER/DTSTART/
     */
    fun buildReply(
        event: ParsedEvent,
        attendeeEmail: String,
        attendeeCn: String?,
        partstat: String,
        nowMillis: Long,
    ): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//Sterna Mail//EN"
        lines += "METHOD:REPLY"
        lines += "BEGIN:VEVENT"
        when {
            event.rawUid != null -> lines += event.rawUid
            event.uid != null -> lines += "UID:${event.uid}"
        }
        lines += "DTSTAMP:${UTC_STAMP.format(Instant.ofEpochMilli(nowMillis))}"
        organizerLine(event)?.let { lines += it }
        event.rawDtStart?.let { lines += it }
        lines += event.rawSequence ?: "SEQUENCE:${event.sequence}"
        lines += attendeeLine(attendeeEmail, attendeeCn, partstat)
        event.title?.let { lines += "SUMMARY:${escapeText(it)}" }
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    private fun organizerLine(event: ParsedEvent): String? {
        val email = event.organizerEmail?.takeIf { it.isNotBlank() } ?: return null
        val cn = event.organizerCn?.let { ";CN=${quoteParam(it)}" } ?: ""
        return "ORGANIZER$cn:mailto:$email"
    }

    private fun attendeeLine(email: String, cn: String?, partstat: String): String {
        val cnParam = cn?.takeIf { it.isNotBlank() }?.let { ";CN=${quoteParam(it)}" } ?: ""
        return "ATTENDEE;PARTSTAT=$partstat$cnParam:mailto:$email"
    }

    /** Quote a parameter value if it contains a character that requires it (RFC 5545 §3.2). */
    private fun quoteParam(value: String): String {
        val v = value.replace("\"", "")
        return if (v.any { it == ',' || it == ';' || it == ':' }) "\"$v\"" else v
    }

    /** Escape an iCalendar TEXT value: backslash, newline, comma and semicolon. */
    private fun escapeText(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(";", "\\;")
        .replace(",", "\\,")

    /** Fold a content line to <=75 octets per RFC 5545, continuations indented with a space. */
    private fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var lineBytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val piece = line.substring(i, i + chars)
            val b = piece.toByteArray(Charsets.UTF_8).size
            if (lineBytes + b > 75) {
                out.append("\r\n ")
                lineBytes = 1 // the leading space counts toward the octet budget
            }
            out.append(piece)
            lineBytes += b
            i += chars
        }
        return out.toString()
    }
}
