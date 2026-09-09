package app.sterna.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The durable attachment descriptors survive a (de)serialisation round-trip. */
class OutboxAttachmentsTest {
    @Test fun emptyAndBlankDecodeToEmpty() {
        assertTrue(OutboxAttachments.decode(null).isEmpty())
        assertTrue(OutboxAttachments.decode("").isEmpty())
        assertTrue(OutboxAttachments.decode("[]").isEmpty())
    }

    @Test fun roundTripsImapAndJmap() {
        val items = listOf(
            OutboxAttachment(
                kind = OutboxAttachments.KIND_IMAP_FILE,
                path = "/data/outbox/7/report.pdf", type = "application/pdf", name = "report.pdf", size = 1234,
            ),
            OutboxAttachment(
                kind = OutboxAttachments.KIND_JMAP_BLOB,
                blobId = "G123", type = "text/calendar", name = "invite.ics", size = 56,
            ),
        )
        val decoded = OutboxAttachments.decode(OutboxAttachments.encode(items))
        assertEquals(items, decoded)
        assertEquals("/data/outbox/7/report.pdf", decoded[0].path)
        assertEquals("G123", decoded[1].blobId)
    }

    @Test fun malformedJsonDecodesToEmpty() {
        assertTrue(OutboxAttachments.decode("not json").isEmpty())
    }
}
