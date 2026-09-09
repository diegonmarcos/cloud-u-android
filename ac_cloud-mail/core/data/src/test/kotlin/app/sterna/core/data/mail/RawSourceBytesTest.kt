package app.sterna.core.data.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import app.sterna.core.data.filter.SourceText
import org.junit.Test

/**
 * Executes [rawSourceBytes], the decision behind "Save as .eml": the String a raw source travels
 */
class RawSourceBytesTest {

    @Test fun `an empty source is refused, not written as zero bytes`() {
        val t = assertThrows(MessageUnavailableException::class.java) { rawSourceBytes("M1", "") }
        assertTrue("message must say the source came back empty: ${t.message}", "empty" in t.message.orEmpty())
    }

    @Test fun `a whitespace-only source is refused too`() {
        val t = assertThrows(MessageUnavailableException::class.java) { rawSourceBytes("M1", "  \r\n") }
        assertTrue("message must say the source came back empty: ${t.message}", "empty" in t.message.orEmpty())
    }

    @Test fun `each char of the source is exactly one byte, 0xE9 stays 0xE9`() {
        val raw = "Subject: Réunion\r\n"
        val bytes = rawSourceBytes("M1", raw)
        // Written by hand: S u b j e c t : SP R é u n i o n CR LF, é being the single octet 0xE9.
        val expected = byteArrayOf(
            0x53, 0x75, 0x62, 0x6A, 0x65, 0x63, 0x74, 0x3A, 0x20,
            0x52, 0xE9.toByte(), 0x75, 0x6E, 0x69, 0x6F, 0x6E, 0x0D, 0x0A,
        )
        assertEquals("one byte per char — UTF-8 would give raw.length + 1", raw.length, bytes.size)
        assertArrayEquals(expected, bytes)
    }

    /**
     * Last resort, and only beside the executing tests above: the public entry point cannot be
     */
    @Test fun `rawSource is wired to rawSourceBytes, and to nothing else`() {
        assertEquals(
            "MailRepository.rawSource must hand fetchRawSource's String to rawSourceBytes — that " +
                "is the ONE place the bytes are made, and where an empty source is refused. Lines found:",
            listOf(
                "internal fun rawSourceBytes(emailId: String, raw: String): ByteArray {",
                "suspend fun rawSource(credentials: AccountCredentials, emailId: String, frozen: FrozenNumbering = FrozenNumbering.NothingFrozen): ByteArray {",
                "return rawSourceBytes(emailId, fetchRawSource(credentials, email, emailId, frozen))",
                "override suspend fun readSource(): ByteArray = rawSource(source, email.id, frozen).also { messageId = messageIdOf(from, it) }",
            ),
            SourceText.codeLines(SourceText.read(REPOSITORY)).filter { "rawSource(" in it || "rawSourceBytes(" in it },
        )
    }

    private companion object {
        const val REPOSITORY = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"
    }
}
