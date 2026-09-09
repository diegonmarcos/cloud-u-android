package app.sterna.core.jmap.model

import app.sterna.core.jmap.JmapClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Mailbox.isSubscribed` (RFC 8621 §2) decoded with the SHIPPING [JmapClient.DefaultJson], not a
 */
class MailboxSubscriptionDecodeTest {
    private val json: Json = JmapClient.DefaultJson

    @Test fun aServerThatDoesNotReportSubscriptionStillDecodesAndTheFolderIsSubscribed() {
        val mailbox = json.decodeFromString(
            Mailbox.serializer(),
            """{"id":"mb2","name":"Projekte","sortOrder":6,"totalEmails":4,"unreadEmails":1}""",
        )

        assertEquals("mb2", mailbox.id)
        assertTrue(
            "a JMAP server that omits isSubscribed must leave the folder SUBSCRIBED: the unknown " +
                "never hides a folder, and a throw here empties the whole drawer",
            mailbox.isSubscribed,
        )
    }

    @Test fun aServerThatReportsAnUnsubscribedFolderIsBelieved() {
        val mailbox = json.decodeFromString(
            Mailbox.serializer(),
            """{"id":"mb2","name":"Projekte","isSubscribed":false}""",
        )

        assertEquals(false, mailbox.isSubscribed)
    }

    @Test fun aServerThatReportsASubscribedFolderIsBelieved() {
        val mailbox = json.decodeFromString(
            Mailbox.serializer(),
            """{"id":"mb1","name":"Inbox","role":"inbox","isSubscribed":true}""",
        )

        assertEquals(true, mailbox.isSubscribed)
    }
}
