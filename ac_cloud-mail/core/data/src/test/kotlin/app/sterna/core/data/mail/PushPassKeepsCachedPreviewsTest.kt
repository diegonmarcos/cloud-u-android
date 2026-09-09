package app.sterna.core.data.mail

import app.sterna.core.imap.ImapMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE NOTIFICATION PASS MUST NOT ERASE WHAT THE FOLDER WALK JUST WROTE (#187).
 */
class PushPassKeepsCachedPreviewsTest {

    private fun message(uid: Long) = ImapMessage(
        uid = uid,
        subject = "Subject $uid",
        fromName = "Alex",
        fromEmail = "alex@example.org",
        to = emptyList(),
        dateMillis = 1_700_000_000_000L,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachment = false,
        messageId = "<$uid@example.org>",
        inReplyTo = null,
    )

    /** A row exactly as the push pass builds it: no opening line read for it. */
    private fun pushRow(uid: Long) = message(uid).toEntity("acct-1", "INBOX", 42L, preview = null)

    /**
     * THE WITNESS. The pass hands over a page it read no bodies for; the cache holds the opening
     */
    @Test fun `a row the push pass built blank takes back the opening line the cache holds`() {
        val kept = keepingCachedPreviews(
            listOf(pushRow(7L), pushRow(8L)),
            mapOf(
                "imap:acct-1:INBOX:7" to "Bonjour, l'été arrive",
                "imap:acct-1:INBOX:8" to "Meeting moved to Thursday",
            ),
        )

        assertEquals(
            "the page written to the cache must carry the openings the cache already had",
            listOf("Bonjour, l'été arrive", "Meeting moved to Thursday"),
            kept.map { it.preview },
        )
    }

    /**
     * EACH LINE UNDER ITS OWN ROW'S ID, never by position. The map is keyed by cache id for
     */
    @Test fun `each opening line lands on the row whose id carries it`() {
        val kept = keepingCachedPreviews(
            listOf(pushRow(7L), pushRow(8L), pushRow(9L)),
            mapOf("imap:acct-1:INBOX:9" to "from Carol", "imap:acct-1:INBOX:7" to "from Alice"),
        )

        assertEquals(
            mapOf(
                "imap:acct-1:INBOX:7" to "from Alice",
                "imap:acct-1:INBOX:8" to null,
                "imap:acct-1:INBOX:9" to "from Carol",
            ),
            kept.associate { it.id to it.preview },
        )
    }

    /**
     * A row that ALREADY carries an opening line keeps its OWN. The row is the fresher of the
     */
    @Test fun `a row that already carries an opening line keeps its own`() {
        val row = message(7L).toEntity("acct-1", "INBOX", 42L, preview = "what the row read")

        assertEquals(
            "what the row read",
            keepingCachedPreviews(
                listOf(row),
                mapOf("imap:acct-1:INBOX:7" to "what the cache held"),
            ).single().preview,
        )
    }

    /**
     * And a row the cache knows nothing about stays BLANK. On this path blank means "no opening
     */
    @Test fun `a row the cache knows nothing about stays blank`() {
        val kept = keepingCachedPreviews(listOf(pushRow(7L)), emptyMap())

        assertEquals(1, kept.size)
        assertNull(kept.single().preview)
        assertEquals("nothing else about the row may change", pushRow(7L), kept.single())
    }

    /**
     * SOURCE TEXT, AND A LAST RESORT — `MailRepository` cannot be instantiated on the JVM. The
     */
    @Test fun `the shipped write carries the cached openings over, one folder page at a time`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "refreshAccountFolders")
        val codeLines = { needle: String ->
            body.lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { needle in it }
        }

        assertEquals(
            "every write this function makes must be the REBUILT page, and the read that feeds it " +
                "must be scoped to one load's messages",
            listOf(
                "val cached = emailDao.cachedPreviews(credentials.id, load.messages.map { it.id }, load.uidValidity)",
                "emailDao.upsertAll(keepingCachedPreviews(load.messages, cached.associate { it.id to it.preview }))",
            ),
            codeLines("cachedPreviews(") + codeLines("upsertAll("),
        )
    }

    /**
     * AND THE READ IS BOUNDED BY THE NUMBERING THE PAGE WAS READ UNDER — the half `keepingCachedPreviews`
     */
    @Test fun `the push load carries the numbering its page was read under, and has no default`() {
        val source = DaoQuerySource.mailSource("ImapMailService")

        assertEquals(
            "ImapWatchedLoad must declare the numbering, and declare it without a default",
            listOf("val uidValidity: Long?,"),
            source.substringAfter("data class ImapWatchedLoad(").substringBefore("\n)")
                .lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { it.startsWith("val uidValidity") },
        )
        assertEquals(
            "and it is filled from the SELECT that enumerated the page, through the one shipped " +
                "normalisation (UidValidity.stated): 0 means the server stated nothing, and 0 is " +
                "not a numbering — it must become null, exactly as the row's own column does",
            listOf("uidValidity = UidValidity.stated(status.uidValidity),"),
            DaoQuerySource.mailFunctionBody("ImapMailService", "loadWatchedFolders")
                .lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { it.startsWith("uidValidity =") },
        )
    }
}
