package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * "Since 1 June" means 1 June WHERE THE PHONE IS. Material's date picker hands the day back as
 */
class SearchDateBoundsTest {

    private val paris = ZoneId.of("Europe/Paris") // UTC+2 in June
    private val newYork = ZoneId.of("America/New_York") // UTC-4 in June
    private val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14, the extreme case

    /** 1 June 2026, as the picker returns it. */
    private val pickedFirstOfJune = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun `the after bound is local midnight, not UTC midnight`() {
        assertEquals(
            Instant.parse("2026-05-31T22:00:00Z"), // 1 June 00:00 in Paris
            Instant.ofEpochMilli(searchAfterBound(pickedFirstOfJune, paris)),
        )
        assertEquals(
            Instant.parse("2026-06-01T04:00:00Z"), // 1 June 00:00 in New York
            Instant.ofEpochMilli(searchAfterBound(pickedFirstOfJune, newYork)),
        )
    }

    @Test fun `the before bound closes the local day, so the picked day is included`() {
        assertEquals(
            Instant.parse("2026-06-01T21:59:59.999Z"), // 1 June 23:59:59.999 in Paris
            Instant.ofEpochMilli(searchBeforeBound(pickedFirstOfJune, paris)),
        )
        assertEquals(
            Instant.parse("2026-06-02T03:59:59.999Z"), // 1 June 23:59:59.999 in New York
            Instant.ofEpochMilli(searchBeforeBound(pickedFirstOfJune, newYork)),
        )
    }

    @Test fun `in UTC the bounds are the plain day`() {
        assertEquals(
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.ofEpochMilli(searchAfterBound(pickedFirstOfJune, ZoneOffset.UTC)),
        )
        assertEquals(
            Instant.parse("2026-06-01T23:59:59.999Z"),
            Instant.ofEpochMilli(searchBeforeBound(pickedFirstOfJune, ZoneOffset.UTC)),
        )
    }

    @Test fun `a bound displays as the day that was picked, whatever the offset`() {
        val day = LocalDate.of(2026, 6, 1)
        for (zone in listOf(paris, newYork, kiritimati, ZoneOffset.UTC)) {
            assertEquals(day, searchBoundDay(searchAfterBound(pickedFirstOfJune, zone), zone))
            assertEquals(day, searchBoundDay(searchBeforeBound(pickedFirstOfJune, zone), zone))
        }
    }

    @Test fun `reopening the picker lands back on the picked day`() {
        for (zone in listOf(paris, newYork, kiritimati, ZoneOffset.UTC)) {
            assertEquals(pickedFirstOfJune, searchPickerMillis(searchAfterBound(pickedFirstOfJune, zone), zone))
            assertEquals(pickedFirstOfJune, searchPickerMillis(searchBeforeBound(pickedFirstOfJune, zone), zone))
        }
    }

    @Test fun `a whole day sits inside its own bounds`() {
        // The very first and very last local instants of 1 June in Paris.
        val start = Instant.parse("2026-05-31T22:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-06-01T21:59:59.999Z").toEpochMilli()
        val after = searchAfterBound(pickedFirstOfJune, paris)
        val before = searchBeforeBound(pickedFirstOfJune, paris)
        assertEquals(true, start >= after && start <= before)
        assertEquals(true, end >= after && end <= before)
        // And the minute before the day starts is out.
        assertEquals(true, start - 1 < after)
    }
}
