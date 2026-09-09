package app.sterna.core.jmap.model

import app.sterna.core.jmap.JmapClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Mailbox.lostRoleClaim] decoded with the SHIPPING [JmapClient.DefaultJson] — the same trap
 */
class MailboxLostRoleClaimDecodeTest {
    private val json: Json = JmapClient.DefaultJson

    @Test fun aMailboxGetAnswerWithoutTheFieldDecodesAndClaimsNothing() {
        val mailbox = json.decodeFromString(
            Mailbox.serializer(),
            """{"id":"mb2","name":"Projekte","sortOrder":6,"totalEmails":4,"unreadEmails":1}""",
        )

        assertEquals("mb2", mailbox.id)
        assertFalse(
            "no JMAP server sends this field — a throw here would empty the whole drawer, and a " +
                "true would keep folders listed on an account that holds no role election at all",
            mailbox.lostRoleClaim,
        )
    }

    @Test fun aJunkFolderFromAJmapServerClaimsNothingEither() {
        val mailbox = json.decodeFromString(
            Mailbox.serializer(),
            """{"id":"mb5","name":"Junk","role":"junk","isSubscribed":false,"sortOrder":5}""",
        )

        assertEquals("junk", mailbox.role)
        assertFalse(mailbox.lostRoleClaim)
    }

    /** A whole `Mailbox/get` list, decoded the way the client decodes one: not one folder in it
     *  comes back with a claim, so a JMAP account behaves exactly as it did before this fix. */
    @Test fun noFolderOfARealMailboxGetListComesBackWithAClaim() {
        val list = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Mailbox.serializer()),
            """[
              {"id":"a","name":"Inbox","role":"inbox","sortOrder":1,"totalEmails":9,"unreadEmails":2},
              {"id":"b","name":"Junk","role":"junk","sortOrder":5,"isSubscribed":false},
              {"id":"c","name":"Spam","parentId":null,"sortOrder":6,"isSubscribed":false},
              {"id":"d","name":"Trash","role":"trash","sortOrder":7}
            ]""",
        )

        assertEquals(listOf("a", "b", "c", "d"), list.map { it.id })
        assertEquals(
            "a JMAP account must keep main's behaviour exactly: no election there, no lost claim",
            listOf(false, false, false, false),
            list.map { it.lostRoleClaim },
        )
        assertTrue("and the unknown still never hides a folder", list.first().isSubscribed)
    }

}
