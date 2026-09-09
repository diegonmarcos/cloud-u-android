package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT on the TWO ends of the Sent-folder copy, same instrument and same disclaimer as
 */
class SentCopyCallSiteTest {

    private val body = DaoQuerySource.mailFunctionBody("MailRepository", "performDelivery")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `the IMAP delivery routes its Sent folder through the setting`() {
        val at = body.indexOfFirst { it.startsWith("imap.send(") }
        assertTrue(
            "MailRepository.performDelivery no longer calls imap.send( — if the delivery moved, " +
                "this lint moves with it rather than staying green over a path nobody runs:\n" +
                body.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "the IMAP send call site changed. The Sent-folder argument is the whole of the " +
                "'Upload sent messages' setting: passing mailboxDao.idForRole(...) to imap.send( " +
                "directly short-circuits the decision, and the account that asked for one copy " +
                "gets two again — silently, with every other test still green:\n" +
                body.drop(at).take(8).joinToString("\n"),
            listOf(
                "imap.send(",
                "credentials,",
                "message,",
                "sentMailboxToUpload(",
                "accountStore.uploadSentCopy(credentials.id),",
                "mailboxDao.idForRole(credentials.id, \"sent\"),",
                "),",
                ")",
            ),
            body.drop(at).take(8),
        )
    }

    @Test
    fun `the APPEND happens only when a folder was handed over, and cannot fail the delivery`() {
        val send = DaoQuerySource.mailFunctionBody("ImapMailService", "send")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val at = send.indexOfFirst { it.startsWith("if (sentMailbox != null)") }
        assertTrue(
            "ImapMailService.send no longer guards its Sent APPEND with 'if (sentMailbox != null)'. " +
                "That guard IS the setting turned off: with it gone the copy is written whatever " +
                "the account asked for, and it is also the path of an account whose 'sent' role " +
                "has not been synced yet:\n" + send.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "the Sent-folder APPEND changed shape, and both halves of this block are load-bearing:\n" +
                " · the folder must be the one handed over, used ONLY inside this guard. A default " +
                "squeezed in (sentMailbox ?: \"Sent\", the APPEND moved out of the if) makes the " +
                "'Upload sent messages' setting inert — the duplicate comes back for everyone who " +
                "turned it off, and every executable test stays green, because nothing runs send().\n" +
                " · the runCatching must stay. An APPEND refused (quota, a read-only Sent folder) " +
                "would then fail a delivery that HAS already been submitted over SMTP, OutboxWorker " +
                "would replay it, and the RECIPIENT would receive the message twice.\n" +
                "Found:\n" + send.drop(at).take(3).joinToString("\n"),
            listOf(
                "if (sentMailbox != null) {",
                "runCatching { withSession(credentials) { it.append(sentMailbox, " +
                    "OutgoingMime.build(message), \"\\\\Seen\") } }",
                "}",
            ),
            send.drop(at).take(3),
        )
    }
}
