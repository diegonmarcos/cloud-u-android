package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterisation, NOT a fix. `primaryAccounts` is mandatory (RFC 8620 §2), but the model defaults
 */
class JmapSessionPrimaryFallbackTest {
    private val json = JmapClient.DefaultJson

    /** Two mail-capable accounts, listed in the given order, with NO `primaryAccounts` at all. */
    private fun sessionJson(first: String, second: String) =
        """
        {
          "apiUrl": "https://mail.example.com/jmap/api/",
          "accounts": {
            "$first": { "name": "$first@example.com",
                        "accountCapabilities": { "${Jmap.MAIL_CAPABILITY}": {} } },
            "$second": { "name": "$second@example.com",
                         "accountCapabilities": { "${Jmap.MAIL_CAPABILITY}": {} } }
          }
        }
        """.trimIndent()

    @Test fun withoutPrimaryAccountsTheFirstAccountInTheJsonLeads() {
        val session = json.decodeFromString(JmapSession.serializer(), sessionJson("zulu", "alpha"))

        // "zulu" is alphabetically LAST, yet it leads: only its position in the document explains
        // this head, which rules out an ascending sort. The opposite ordering below, with the same
        // two accounts, rules out a descending one.
        assertEquals("zulu", session.mailAccountId())
        assertEquals(listOf("zulu", "alpha"), session.mailAccountIds())
    }

    @Test fun withoutPrimaryAccountsTheSameAccountsInTheOppositeJsonOrderFlipTheHead() {
        val session = json.decodeFromString(JmapSession.serializer(), sessionJson("alpha", "zulu"))

        // Same two accounts, same capabilities, only the document order changed — and the head
        // changed with it. Any order-independent rule fails one of these two cases.
        assertEquals("alpha", session.mailAccountId())
        assertEquals(listOf("alpha", "zulu"), session.mailAccountIds())
    }
}
