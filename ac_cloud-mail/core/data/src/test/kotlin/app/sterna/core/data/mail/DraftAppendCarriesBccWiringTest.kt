package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, and for the usual reason: `ImapMailService`
 */
class DraftAppendCarriesBccWiringTest {

    /** The code lines of the file naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertion compares them, never searches inside them. */
    private fun codeLinesNaming(source: String, needle: String): List<String> =
        source.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test
    fun `only the draft APPEND builds its MIME with the blind copies in it`() {
        assertEquals(
            "the two APPENDs of ImapMailService must build their MIME exactly like this.\n" +
                " · The Drafts one carries BlindCopies.WRITTEN: without it OutgoingMime.build writes " +
                "no Bcc: header, the appended draft holds nothing of the Cci, and reopening it " +
                "from the server's copy shows the To and the Cc with an empty Bcc — a loss the " +
                "user only notices after sending.\n" +
                " · The Sent one must stay bare. Those bytes were already handed to SMTP's DATA " +
                "by the same builder, and a WRITTEN here (or a third call site anywhere in this " +
                "file) puts the hidden recipients into a message other people read.\n" +
                "Found:",
            listOf(
                "runCatching { withSession(credentials) { it.append(sentMailbox, " +
                    "OutgoingMime.build(message), \"\\\\Seen\") } }",
                "it.append(draftsMailbox, OutgoingMime.build(message, BlindCopies.WRITTEN), \"\\\\Draft\")",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("ImapMailService"), "OutgoingMime.build("),
        )
    }

    /**
     * The other side of the same parameter, and the risk that is NOT on this bench at all.
     */
    @Test
    fun `the repository names the blind-copy type nowhere, so its submitted bytes keep the default`() {
        assertEquals(
            "no line of MailRepository may name BlindCopies. Its OutgoingMime.build is the " +
                "pre-built JMAP path, and those bytes are handed to importAndSendEmail and " +
                "submitted as they are: BlindCopies.WRITTEN there writes a Bcc: header into a " +
                "message delivered to the visible recipients, i.e. it hands every hidden " +
                "recipient to everyone. The OMITTED default is the only thing keeping that site " +
                "safe, and there is no test on the wire to catch it — this bench is IMAP, that " +
                "path is JMAP, and no gesture on the bench can make this one show.\n" +
                "⭐ It is the TYPE name that is searched for, not the parameter name: while this " +
                "was a Boolean called includeBcc, a positional `true` armed the header and named " +
                "nothing, and this very assertion stayed green.\nFound:",
            emptyList<String>(),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "BlindCopies"),
        )
    }
}
