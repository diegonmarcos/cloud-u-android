package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which messages repeat their subject INSIDE the entity that gets encrypted (RFC 9788 § 3), and
 */
class ProtectedSubjectTest {

    private val subject = "Quarterly report"

    // --- who gets a protected subject ------------------------------------------------------------

    @Test fun encryptRepeatsTheSubjectInsideTheEntity() {
        assertEquals(
            "ENCRYPT is the mode the whole feature is for: the subject sealed in the ciphertext " +
                "is what lets the recipient catch an envelope subject a relay rewrote.",
            "Quarterly report",
            protectedSubject(PgpMode.ENCRYPT, subject),
        )
    }

    @Test fun encryptUnsignedRepeatsItToo() {
        assertEquals(
            "ENCRYPT_UNSIGNED must protect its subject exactly like ENCRYPT. Answering null here " +
                "is what `== PgpMode.ENCRYPT` produces, and it is silent: the message goes out, " +
                "encrypted, simply carrying no inner subject at all.",
            "Quarterly report",
            protectedSubject(PgpMode.ENCRYPT_UNSIGNED, subject),
        )
    }

    @Test fun signOnlyGetsNothing() {
        assertNull(
            "A signed-only entity is not ciphertext — it is what the recipient's client parses " +
                "and shows. An inner Subject: there can be displayed INSTEAD of the envelope's, " +
                "on a message where nothing ever protected it.",
            protectedSubject(PgpMode.SIGN, subject),
        )
    }

    @Test fun plainGetsNothing() {
        assertNull(
            "OFF never reaches the PGP entity builder at all; answering anything but null here " +
                "would mean the decision is not the mode's.",
            protectedSubject(PgpMode.OFF, subject),
        )
    }

    // --- a subject that says nothing is not repeated ---------------------------------------------

    @Test fun anEmptySubjectIsNotRepeated() {
        assertNull(
            "A message sent with no subject carries an EMPTY Subject: header on the envelope " +
                "(`OutgoingMime.build` writes that field unconditionally). Repeating it inside " +
                "the entity protects nothing: there is no subject to compare, and the empty line " +
                "is one more claim to get wrong.",
            protectedSubject(PgpMode.ENCRYPT, ""),
        )
    }

    @Test fun aBlankSubjectIsNotRepeatedEither() {
        assertNull(
            "Whitespace only is the same case, and it is the reason the guard is isNotBlank and " +
                "not isNotEmpty: there is nothing to protect and nothing to compare.",
            protectedSubject(PgpMode.ENCRYPT_UNSIGNED, "   \t "),
        )
    }

    @Test fun theSubjectIsPassedThroughVerbatim() {
        // Not trimmed, not encoded, not rewritten. Encoding is SmtpClient's `encodeHeader`, the
        // same door the envelope subject goes through, and a second one here would drift from it.
        assertEquals(
            "the subject must reach the MIME builder character for character.",
            "  Ümläut — тема  ",
            protectedSubject(PgpMode.ENCRYPT, "  Ümläut — тема  "),
        )
    }
}
