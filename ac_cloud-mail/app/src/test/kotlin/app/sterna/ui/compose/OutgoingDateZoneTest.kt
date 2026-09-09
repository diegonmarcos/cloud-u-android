package app.sterna.ui.compose

import app.sterna.util.MailDates
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quoted-dates-in-UTC switch (#120), run rather than described: [outgoingDateZone] is the one
 */
class OutgoingDateZoneTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val pivot = "2026-07-04T23:40:00Z"

    /** The exact pattern MailDates.formatFull uses, with the language pinned to English so the
     *  month name is stable on any machine. */
    private val explicit = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    // --- T-citation: the attribution line's date ------------------------------------------------

    @Test fun switchOffTheQuotedDateSpeaksTheDeviceClock() {
        val date = MailDates.formatWith(pivot, explicit, outgoingDateZone(quotedDatesUtc = false, deviceZone = paris))
        assertEquals("5 Jul 2026, 01:40", date)
    }

    @Test fun switchOnTheQuotedDateSpeaksUtc() {
        val date = MailDates.formatWith(pivot, explicit, outgoingDateZone(quotedDatesUtc = true, deviceZone = paris))
        assertEquals("4 Jul 2026, 23:40", date)
    }

    @Test fun theProductionFormatterFollowsTheSameDecision() {
        // formatFull's month name follows the app language, so the assertion pins the digits: the
        // day of month and the time, which the pattern fixes. Same instant, two zones, two days.
        val off = MailDates.formatFull(pivot, outgoingDateZone(quotedDatesUtc = false, deviceZone = paris))
        val on = MailDates.formatFull(pivot, outgoingDateZone(quotedDatesUtc = true, deviceZone = paris))
        assertTrue("local: $off", off.startsWith("5 ") && off.endsWith("01:40"))
        assertTrue("utc: $on", on.startsWith("4 ") && on.endsWith("23:40"))
    }

    // --- T-transfert: the forwarded header's date -----------------------------------------------

    @Test fun switchOffTheForwardHeaderSpeaksTheDeviceClock() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "Notes",
            date = MailDates.formatWith(pivot, explicit, outgoingDateZone(quotedDatesUtc = false, deviceZone = paris)),
            to = "Bob", originalText = "body", originalHtml = null,
        )
        assertTrue(blocks.text, blocks.text.contains("Date: 5 Jul 2026, 01:40"))
    }

    @Test fun switchOnTheForwardHeaderSpeaksUtc() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "Notes",
            date = MailDates.formatWith(pivot, explicit, outgoingDateZone(quotedDatesUtc = true, deviceZone = paris)),
            to = "Bob", originalText = "body", originalHtml = null,
        )
        assertTrue(blocks.text, blocks.text.contains("Date: 4 Jul 2026, 23:40"))
    }

    // --- T-course: the setting is awaited, never defaulted --------------------------------------

    @Test fun aLateFirstEmissionIsAwaitedNeverReplacedByTheDefault() = runBlocking {
        // DataStore's first emission after a cold start arrives a beat late. A reader that falls
        // back to the default in that beat writes the local time despite the switch being on —
        // the very leak the switch closes. So: a flow whose stored value (true) takes its time,
        // and the resolution MUST come back UTC.
        val stored = flow {
            delay(150)
            emit(true)
        }
        assertEquals(ZoneOffset.UTC, resolveOutgoingDateZone(stored, deviceZone = paris))
    }

    @Test fun aStoredOffStillResolvesToTheDeviceZone() = runBlocking {
        assertEquals(paris, resolveOutgoingDateZone(flowOf(false), deviceZone = paris))
    }
}
