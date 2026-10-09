package com.diegonmarcos.superapp.uikit

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How a page shows a timestamp: never the raw ISO string. `today 12:55`, `yesterday`, `3 Oct`
 * (`3 Oct 2025` in another year), in the phone's zone. Accepts `…Z` with any fraction, an offset
 * (`+02:00`) or a bare date; anything it cannot read comes back as given. Android-only (java.time),
 * so it lives beside the interop shims, not in commonMain.
 */
object KitDates {
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DM = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val DMY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun parse(iso: String, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val s = iso.trim()
        if (s.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(s).atZoneSameInstant(zone) }.getOrNull()
            ?: runCatching { Instant.parse(s).atZone(zone) }.getOrNull()
            ?: runCatching { LocalDate.parse(s).atStartOfDay(zone) }.getOrNull()
    }

    /** [iso] relative to [now]: `today HH:mm`, `yesterday`, `d MMM`, `d MMM yyyy`; unreadable = [iso] unchanged. */
    fun relative(iso: String, now: ZonedDateTime = ZonedDateTime.now()): String {
        val t = parse(iso, now.zone) ?: return iso
        val day = t.toLocalDate(); val today = now.toLocalDate()
        return when {
            day == today -> "today " + t.format(HM)
            day == today.minusDays(1) -> "yesterday"
            day.year == today.year -> t.format(DM)
            else -> t.format(DMY)
        }
    }
}
