package app.sterna.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply's attribution line and a forward's header used to paste the raw ISO timestamp
 */
class MailDatesTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val explicit = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    @Test fun isoInstantIsFormattedInTheReadersZone() {
        // 09:12 UTC is 11:12 in Paris (CEST): the reader's clock, not the sender's.
        assertEquals("4 Jul 2026, 11:12", MailDates.formatWith("2026-07-04T09:12:33Z", explicit, paris))
    }

    @Test fun anOffsetTimestampIsAlsoAccepted() {
        assertEquals(
            "4 Jul 2026, 11:12",
            MailDates.formatWith("2026-07-04T10:12:33+01:00", explicit, paris),
        )
    }

    @Test fun missingOrUnparseableDatesRenderEmptyRatherThanThrowing() {
        assertEquals("", MailDates.formatFull(null, paris))
        assertEquals("", MailDates.formatFull("", paris))
        assertEquals("", MailDates.formatFull("not a date", paris))
        assertEquals("", MailDates.formatWith("2026-13-45T99:99:99Z", explicit, paris))
    }

    @Test fun theRawIsoStringNeverReachesTheOutput() {
        val formatted = MailDates.formatFull("2026-07-04T09:12:33Z", paris)
        assertFalse("no ISO 'T' separator: $formatted", formatted.contains("T"))
        assertFalse("no trailing Z: $formatted", formatted.endsWith("Z"))
        assertTrue("year present: $formatted", formatted.contains("2026"))
        assertTrue("local time present: $formatted", formatted.contains("11:12"))
    }

    // --- List rows: today / this year / another year -----------------------------------------
    //
    // Three steps: today shows the time, an earlier day of the current year "d MMM", any other
    // year a short numeric date in the language's own order with a two-digit year. The month NAME

    @Test fun aMessageFromTodayIsPlacedByItsTime() {
        assertEquals(
            "09:12",
            MailDates.formatListDate("2026-08-24T07:12:33Z", paris, LocalDate.of(2026, 8, 24)),
        )
    }

    @Test fun anEarlierDayOfTheCurrentYearOmitsTheYear() {
        val label = MailDates.formatListDate("2026-08-24T07:12:33Z", paris, LocalDate.of(2026, 11, 2))
        assertFalse("no year while it is the current one: $label", label.contains("2026"))
        assertFalse("a past day is a date, not a clock: $label", label.contains(":"))
        assertTrue("day of month present: $label", label.startsWith("24 "))
    }

    @Test fun anotherYearIsNumericInTheLanguagesOwnOrder() {
        // 19 December 1989, read in two languages. English-US puts the month first, French the
        // day: same instant, two orders, and nothing hand-written decides which — the locale does.
        val reference = LocalDate.of(2026, 11, 2)
        val us = MailDates.formatListDate("1989-12-19T08:12:33Z", paris, reference, Locale.US)
        val fr = MailDates.formatListDate("1989-12-19T08:12:33Z", paris, reference, Locale.FRENCH)
        assertEquals("12/19/89", us)
        assertEquals("19/12/89", fr)
    }

    @Test fun theYearIsTwoDigitsEvenWhereTheLanguageWritesItInFull() {
        // French, Polish, Portuguese and Russian have a four-digit year in their short date
        // ("19/12/1989"), which nearly doubles the stamp and takes the width straight back out of
        // the sender's name. Only the year field is narrowed: the order and the separator are
        // still the language's own, which is what the two different shapes below show.
        val reference = LocalDate.of(2026, 11, 2)
        val fr = MailDates.formatListDate("1989-12-19T08:12:33Z", paris, reference, Locale.FRENCH)
        val ru = MailDates.formatListDate(
            "1989-12-19T08:12:33Z", paris, reference, Locale.forLanguageTag("ru"),
        )
        assertEquals("19/12/89", fr)
        assertEquals("19.12.89", ru)
        assertFalse("no four-digit year: $fr", fr.contains("1989"))
        assertFalse("no four-digit year: $ru", ru.contains("1989"))
    }

    @Test fun anotherYearCarriesNoMonthNameSoTheStampStaysNarrow() {
        // The whole point of the numeric step: it shares its line with the sender's name, which
        // must not be the field that yields. Digits and separators only, eight characters, and the
        // same width in a language that spells its year in full and in one that does not.
        val reference = LocalDate.of(2026, 11, 2)
        val de = MailDates.formatListDate("1989-12-19T08:12:33Z", paris, reference, Locale.GERMAN)
        val pt = MailDates.formatListDate(
            "1989-12-19T08:12:33Z", paris, reference, Locale.forLanguageTag("pt"),
        )
        assertEquals("19.12.89", de)
        assertEquals("19/12/89", pt)
        for (label in listOf(de, pt)) {
            assertFalse("no month name: $label", label.any { it.isLetter() })
            assertEquals("eight characters: $label", 8, label.length)
        }
    }

    @Test fun twoDecember19thsAYearApartCannotReadAlike() {
        val reference = LocalDate.of(2026, 11, 2)
        val thisYear = MailDates.formatListDate("2026-12-19T08:12:33Z", paris, reference, Locale.US)
        val longAgo = MailDates.formatListDate("1989-12-19T08:12:33Z", paris, reference, Locale.US)
        assertEquals("19 Dec", thisYear)
        assertEquals("12/19/89", longAgo)
    }

    @Test fun newYearsEveSeenTheNextMorningShowsItsYear() {
        // 1 Jan is "this year"; the message from the day before is not, however close it is.
        val label = MailDates.formatListDate(
            "2025-12-31T22:30:00Z", paris, LocalDate.of(2026, 1, 1), Locale.US,
        )
        assertEquals("12/31/25", label)
    }

    @Test fun newYearsDaySeenOnNewYearsEveOfTheSameYearDoesNot() {
        val label = MailDates.formatListDate("2026-01-01T09:00:00Z", paris, LocalDate.of(2026, 12, 31))
        assertFalse("same year, no year shown: $label", label.contains("2026"))
        assertFalse("still the month-name step, not the numeric one: $label", label.contains("/"))
    }

    @Test fun midnightIsTheReadersMidnightNotUtcs() {
        // 23:30 UTC is already 01:30 the next day in Paris: for a reader whose calendar says the
        // 25th, this is today's mail. Comparing dates in UTC would render "24 Aug" instead.
        assertEquals(
            "01:30",
            MailDates.formatListDate("2026-08-24T23:30:00Z", paris, LocalDate.of(2026, 8, 25)),
        )
    }

    @Test fun aWesternZoneCanStillBeInThePreviousYear() {
        // 02:00 UTC on 1 Jan 2026 is 21:00 on 31 Dec 2025 in New York. In UTC the row would jump a
        // year and fall to the numeric step; in the reader's zone it is simply today, at 21:00.
        val newYork = ZoneId.of("America/New_York")
        assertEquals(
            "21:00",
            MailDates.formatListDate("2026-01-01T02:00:00Z", newYork, LocalDate.of(2025, 12, 31)),
        )
    }

    @Test fun aListRowWithNoUsableDateStaysEmpty() {
        val reference = LocalDate.of(2026, 11, 2)
        assertEquals("", MailDates.formatListDate(null, paris, reference))
        assertEquals("", MailDates.formatListDate("", paris, reference))
        assertEquals("", MailDates.formatListDate("not a date", paris, reference))
        assertEquals("", MailDates.formatListDate("2026-13-45T99:99:99Z", paris, reference))
    }

    @Test fun theCallTheListRowActuallyMakesTakesEveryDefault() {
        // The one production call is formatListDate(email.receivedAt): no zone, no reference day,
        // no language. Every other test here passes them, so the default expressions themselves
        // are never run and a regression inside one of them would leave this suite green.
        //
        // The island sits at UTC+14, so for two hours a day its calendar is one day ahead. The
        // instant below is 01:00 today there and yesterday in UTC: were the default day taken in
        // UTC rather than in the device's zone, the row would show a date instead of a clock.
        val island = ZoneId.of("Pacific/Kiritimati")
        val restore = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(island))
            val earlyToday = ZonedDateTime.now(island).toLocalDate().atStartOfDay(island).plusHours(1)
            assertEquals("01:00", MailDates.formatListDate(earlyToday.toInstant().toString()))

            val longAgo = earlyToday.minusYears(3)
            val label = MailDates.formatListDate(longAgo.toInstant().toString())
            assertFalse("an old row is a date, not a clock: $label", label.contains(":"))
            assertFalse("numeric, whatever the device language: $label", label.any { it.isLetter() })
            val twoDigitYear = "%02d".format(longAgo.year % 100)
            assertTrue("year $twoDigitYear in $label", label.contains(twoDigitYear))
        } finally {
            TimeZone.setDefault(restore)
        }
    }

    @Test fun anOffsetTimestampIsAcceptedOnAListRowToo() {
        assertEquals(
            "09:12",
            MailDates.formatListDate("2026-08-24T08:12:33+01:00", paris, LocalDate.of(2026, 8, 24)),
        )
    }
}
