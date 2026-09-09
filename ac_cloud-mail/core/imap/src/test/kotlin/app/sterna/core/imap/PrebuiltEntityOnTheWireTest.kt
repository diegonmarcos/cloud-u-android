package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The octets a PGP/MIME submission actually puts on the socket.**
 */
class PrebuiltEntityOnTheWireTest {

    /** An entity as the outbox hands it over: provider armor, bare LFs and all. */
    private fun queuedPgpEntity(): String =
        "Content-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=\"B\"\r\n" +
            "\r\n" +
            "--B\n" +
            "Content-Type: application/octet-stream\n" +
            "\n" +
            String(bareLfArmor("MESSAGE"), Charsets.US_ASCII) +
            "--B--\n"

    @Test
    fun `a pre-built entity reaches the socket as conforming SMTP lines`() {
        val server = FakeSmtpServer()
        server.use {
            runBlocking {
                SmtpClient().send(server.config, outgoingFixture().copy(prebuiltEntity = queuedPgpEntity()))
            }
            server.awaitDone()
        }

        val wire = String(server.wire, Charsets.ISO_8859_1)

        // --- What must NOT be on the wire ---

        // RFC 5321 § 2.3.8 — CR and LF appear only as the CRLF that ends a line. The bare LF is
        // what a Postfix with smtpd_forbid_bare_newline refuses outright.
        assertNoBareLf("what the client wrote", wire)
        val bareCr = wire.indices.filter { wire[it] == '\r' && (it == wire.length - 1 || wire[it + 1] != '\n') }
        assertEquals("the client wrote bare CR(s) at offsets ${bareCr.take(5)}", emptyList<Int>(), bareCr)

        // RFC 5321 § 4.5.3.1 — 1000 octets a line, CRLF included.
        val tooLong = wire.split("\r\n").filter { it.length > 998 }
        assertTrue(
            "the client wrote ${tooLong.size} line(s) over 998 octets (longest: ${tooLong.maxOfOrNull { it.length }})",
            tooLong.isEmpty(),
        )

        // Dot-stuffing (§ 4.5.2) applies to EVERY line of the body, not just the first one of a
        // spliced block — see the fixture's deliberate ".abcdef".
        assertTrue("the '.abcdef' line was not dot-stuffed on the wire", "\r\n..abcdef\r\n" in wire)

        // --- What must BE on the wire ---
        //
        // Without these, the three assertions above are satisfied by an EMPTY or truncated wire:
        // a client that dropped the entity, or stopped writing halfway through it, would pass a
        // conformance check made only of absences.
        assertNotNull("the server accepted no message at all", server.delivered)
        assertTrue(
            "the closing boundary never reached the socket: the entity was truncated",
            "\r\n--B--\r\n" in wire,
        )
        assertTrue(
            "the armor did not survive the trip intact. Expected the whole block:\n" +
                expectedCrlfArmor("MESSAGE") + "\ninside the transmitted body:\n" + unstuff(wire),
            expectedCrlfArmor("MESSAGE") in unstuff(wire),
        )
    }

    /**
     * Undo SMTP's dot-stuffing, the way a receiving MTA does, so the anchor above compares the
     */
    private fun unstuff(wire: String): String =
        wire.split("\r\n").joinToString("\r\n") { if (it.startsWith("..")) it.substring(1) else it }
}
