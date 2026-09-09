package app.sterna.util

import app.sterna.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Formatting of the ISO timestamps mail carries, in the device's language and time zone. Shared, so
 */
object MailDates {

    /** "4 Jul 2026, 09:12" in the app's language. */
    private val fullFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", appLocale)

    /** "09:12" — a message from today is placed by its clock time. */
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", appLocale)

    /** "4 Jul" — the year is implicit while it is the current one. */
    private val dayMonthFormatter = DateTimeFormatter.ofPattern("d MMM", appLocale)

    /**
     * "12/19/89" in en-US, "19/12/89" in fr: the language's own short date with the year cut to two
     * digits. Built once per language and kept — a list asks for one per visible row.
     */
    private val shortDateFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()

    private fun shortDateFormatter(locale: Locale): DateTimeFormatter =
        shortDateFormatters.getOrPut(locale) {
            val localized = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale,
            )
            DateTimeFormatter.ofPattern(withTwoDigitYear(localized), locale)
        }

    /**
     * [pattern] with its year field narrowed to two digits, everything else untouched. Four of the
     */
    private fun withTwoDigitYear(pattern: String): String = buildString {
        var i = 0
        var quoted = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> { quoted = !quoted; append(c); i++ }
                !quoted && (c == 'y' || c == 'u') -> {
                    while (i < pattern.length && pattern[i] == c) i++
                    append("yy")
                }
                else -> { append(c); i++ }
            }
        }
    }

    /** [iso] as a full local date + time, or "" when it is missing or unparseable. */
    fun formatFull(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        formatWith(iso, fullFormatter, zone)

    /**
     * The stamp a list row carries, in three steps: today shows the time, an earlier day of the
     */
    fun formatListDate(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): String {
        val zoned = zoned(iso, zone) ?: return ""
        val date = zoned.toLocalDate()
        val formatter = when {
            date == today -> timeFormatter.withLocale(locale)
            date.year == today.year -> dayMonthFormatter.withLocale(locale)
            else -> shortDateFormatter(locale)
        }
        return zoned.format(formatter)
    }

    /** [iso] rendered with [formatter] in [zone]; "" when missing or unparseable (never throws). */
    fun formatWith(
        iso: String?,
        formatter: DateTimeFormatter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = zoned(iso, zone)?.format(formatter) ?: ""

    /**
     * [iso] in [zone], or null when it is missing or unreadable. Mail carries two timestamp shapes:
     * a plain instant, or one with an explicit offset.
     */
    private fun zoned(iso: String?, zone: ZoneId): ZonedDateTime? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrNull()
            ?.atZone(zone)
    }
}
