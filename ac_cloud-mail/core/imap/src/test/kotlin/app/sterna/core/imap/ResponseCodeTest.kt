package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RFC 5530 response code — the ONE typed thing a failing IMAP server says — as it is read
 */
class ResponseCodeTest {

    // ---- the pure extraction, on lines shaped as ImapParser hands them (atoms, `[` kept) ----

    @Test
    fun `the first bracketed word is the code`() {
        assertEquals(
            "AUTHENTICATIONFAILED",
            responseCodeIn(listOf("a1", "NO", "[AUTHENTICATIONFAILED]", "oops")),
        )
    }

    @Test
    fun `a code with arguments answers only the word`() {
        assertEquals("REFERRAL", responseCodeIn(listOf("a1", "NO", "[REFERRAL", "imap://x]")))
    }

    @Test
    fun `a lower-case code comes back upper-cased`() {
        assertEquals(
            "AUTHENTICATIONFAILED",
            responseCodeIn(listOf("a1", "no", "[authenticationfailed]", "oops")),
        )
    }

    @Test
    fun `a line without brackets has no code`() {
        assertNull(responseCodeIn(listOf("a1", "NO", "LOGIN", "…", "failed")))
    }

    @Test
    fun `an empty line has no code`() {
        assertNull(responseCodeIn(emptyList<Any?>()))
    }

    /** On an OK completion `[CAPABILITY …]` IS a response code (documented, not an accident). */
    @Test
    fun `on an OK line the capability list is itself the response code`() {
        assertEquals(
            "CAPABILITY",
            responseCodeIn(listOf("a1", "OK", "[CAPABILITY", "IMAP4rev1", "UIDPLUS]", "done")),
        )
    }

    // ---- the two throw sites, over a real socket dialogue ----

    @Test
    fun `a tagged NO with a code puts it on the exception thrown by command`() {
        FakeImapServer { tag, line ->
            if (line.startsWith("LOGIN")) "$tag NO [AUTHENTICATIONFAILED] Authentication failed.\r\n"
            else ok(tag)
        }.use { server ->
            val thrown = runCatching { server.session() }.exceptionOrNull()
            val imap = thrown as? ImapException
                ?: throw AssertionError("expected an ImapException, got $thrown")
            assertEquals("AUTHENTICATIONFAILED", imap.responseCode)
            // The MESSAGE is untouched: still the redacted template, body included — that text
            // is for the log, and the redaction is what keeps the password out of it.
            assertTrue(
                "message should keep the redacted template, got <${imap.message}>",
                imap.message.orEmpty().startsWith("LOGIN … failed:"),
            )
        }
    }

    @Test
    fun `a tagged NO without a code leaves the field null`() {
        FakeImapServer { tag, line ->
            if (line.startsWith("LOGIN")) "$tag NO nope\r\n" else ok(tag)
        }.use { server ->
            val thrown = runCatching { server.session() }.exceptionOrNull()
            val imap = thrown as? ImapException
                ?: throw AssertionError("expected an ImapException, got $thrown")
            assertNull(imap.responseCode)
        }
    }

    /** The OTHER throw site — [ImapSession.authenticateXoauth2] — and THE symptom's case:
     *  a server that is DOWN while authenticating must not look like a bad token/password. */
    @Test
    fun `an AUTHENTICATE failure carries the server's code, here UNAVAILABLE`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> ok(tag)
                line.startsWith("AUTHENTICATE") -> "$tag NO [UNAVAILABLE] busy\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val session = server.session()
            val thrown = runCatching { session.authenticateXoauth2("tester", "token") }.exceptionOrNull()
            val imap = thrown as? ImapException
                ?: throw AssertionError("expected an ImapException, got $thrown")
            assertEquals("UNAVAILABLE", imap.responseCode)
        }
    }
}
