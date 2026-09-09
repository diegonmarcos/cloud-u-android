package app.sterna.core.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [eventSourceOrigin] is the redaction that lets the push connection be logged at all: a reporter
 */
class EventSourceOriginTest {

    @Test fun `an ordinary url keeps its origin and nothing else`() {
        assertEquals(
            "https://mail.example.com",
            eventSourceOrigin("https://mail.example.com/jmap/eventsource/?types=Email,Mailbox&closeafter=no&ping=90"),
        )
    }

    @Test fun `a token carried in the query does not survive`() {
        val origin = eventSourceOrigin("https://mail.example.com/jmap/eventsource/?access_token=hunter2-secret")
        assertEquals("https://mail.example.com", origin)
        assertFalse("the query token leaked into $origin", origin.contains("hunter2-secret"))
    }

    @Test fun `userinfo does not survive`() {
        val origin = eventSourceOrigin("https://alex:hunter2-secret@mail.example.com/jmap/eventsource/")
        assertEquals("https://mail.example.com", origin)
        assertFalse("userinfo leaked into $origin", origin.contains("hunter2-secret"))
        assertFalse("userinfo leaked into $origin", origin.contains("alex"))
    }

    @Test fun `a fragment does not survive`() {
        assertEquals(
            "https://mail.example.com",
            eventSourceOrigin("https://mail.example.com/jmap/eventsource/#hunter2-secret"),
        )
    }

    @Test fun `an explicit non-default port is kept`() {
        assertEquals(
            "https://mail.example.com:8443",
            eventSourceOrigin("https://mail.example.com:8443/jmap/eventsource/"),
        )
    }

    @Test fun `an implicit port is not invented`() {
        assertEquals("https://mail.example.com", eventSourceOrigin("https://mail.example.com/jmap/eventsource/"))
        assertEquals("http://mail.example.com", eventSourceOrigin("http://mail.example.com/jmap/eventsource/"))
    }

    @Test fun `an unparseable url yields the marker, never the input`() {
        val input = "mail.example.com/jmap/eventsource/?access_token=hunter2-secret"
        val origin = eventSourceOrigin(input)
        assertEquals("<unparseable url>", origin)
        assertNotEquals(input, origin)
        assertFalse("the unparseable input leaked into $origin", origin.contains("hunter2-secret"))
    }

    @Test fun `an empty url yields the marker`() {
        assertEquals("<unparseable url>", eventSourceOrigin(""))
    }
}
