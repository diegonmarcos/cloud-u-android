package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

class ImapParserTest {
    private fun parse(raw: String): List<Any?> =
        ImapParser(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))).readResponse()

    /**
     * The same entry point taking the WIRE BYTES. The `String` overload cannot express the case
     */
    private fun parseBytes(raw: ByteArray): List<Any?> =
        ImapParser(ByteArrayInputStream(raw)).readResponse()

    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) when (p) {
            is String -> out.write(p.toByteArray(Charsets.US_ASCII))
            is Int -> out.write(p)
            is ByteArray -> out.write(p)
            else -> error("unsupported piece: $p")
        }
        return out.toByteArray()
    }

    @Test
    fun parsesAtoms() {
        assertEquals(listOf("*", "OK", "hello"), parse("* OK hello\r\n"))
    }

    @Test
    fun parsesQuotedAndEmptyList() {
        val r = parse("* LIST () \"/\" \"INBOX\"\r\n")
        assertEquals(listOf("*", "LIST", emptyList<Any?>(), "/", "INBOX"), r)
    }

    @Test
    fun parsesNilAsNull() {
        val r = parse("* 1 FETCH (ENVELOPE (NIL \"Subject\" NIL))\r\n")
        @Suppress("UNCHECKED_CAST")
        val fetchArgs = r[3] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val envelope = fetchArgs[1] as List<Any?>
        assertNull(envelope[0])
        assertEquals("Subject", envelope[1])
        assertNull(envelope[2])
    }

    @Test
    fun parsesLiteral() {
        val r = parse("* 1 FETCH (BODY[] {11}\r\nhello world)\r\n")
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals("BODY[]", args[0])
        assertEquals("hello world", args[1])
    }

    /**
     * A literal is a byte container: every octet comes back as the char of the same value, so
     */
    @Test
    fun aLiteralKeepsEveryByteVerbatim() {
        val r = parseBytes(bytes("* 1 FETCH (BODY[] {4}\r\ncaf", 0xE9, ")\r\n"))
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        val literal = args[1] as String

        assertEquals(4, literal.length)
        assertEquals(0xE9, literal[3].code)
        assertEquals(
            listOf<Byte>(0x63, 0x61, 0x66, 0xE9.toByte()),
            literal.toByteArray(Charsets.ISO_8859_1).toList(),
        )
    }

    /** And the bytes of a multi-byte character survive as their own chars, to be reassembled
     *  later by whoever knows the charset — never decoded, never merged, never lost. */
    @Test
    fun aLiteralDoesNotDecodeUtf8ByItself() {
        // "é" in UTF-8 is C3 A9; "日" is E6 97 A5.
        val r = parseBytes(bytes("* 1 FETCH (BODY[] {5}\r\n", 0xC3, 0xA9, 0xE6, 0x97, 0xA5, ")\r\n"))
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        val literal = args[1] as String

        assertEquals(5, literal.length)
        assertEquals("é日", String(literal.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
    }

    /**
     * A hostile server announces a literal past [MAX_LITERAL]. The parser must REFUSE IT WITHOUT
     */
    @Test
    fun oversizeLiteralIsRefusedNotAllocated() {
        // One byte past the parser's 32 MiB cap. The cap is a private const, so the figure is
        // written out here: raising it must break this test, which is the point.
        val declared = 32 * 1024 * 1024 + 1
        val parser = ImapParser(
            streamOf(
                "* 1 FETCH (BODY[] {$declared}\r\n",
                generated(declared),
                ")\r\n* 2 OK still in sync\r\n",
            ),
        )

        val r = parser.readResponse()

        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals("BODY[]", args[0])
        assertEquals("", args[1])
        // The half that was worth as much as the refusal: the drain stopped exactly at the end of
        // the announced data, so the response after it is still there.
        assertEquals(listOf("*", "2", "OK", "still", "in", "sync"), parser.readResponse())
    }

    /**
     * The other half of [MAX_LITERAL]: what is UNDER the cap comes back WHOLE. 100 000 bytes is a
     */
    @Test
    fun literalUnderTheCapComesBackWhole() {
        val size = 100_000
        val parser = ImapParser(streamOf("* 1 FETCH (BODY[] {$size}\r\n", generated(size), ")\r\n"))

        @Suppress("UNCHECKED_CAST")
        val args = parser.readResponse()[3] as List<Any?>

        assertEquals(size, (args[1] as String).length)
    }

    /** And an oversize literal the peer never finishes sending is the #156 case: it throws. */
    @Test
    fun oversizeLiteralFollowedByAClosedPeerThrows() {
        val raw = "* 1 FETCH (BODY[] {2000000000}\r\nignored)\r\n".toByteArray(Charsets.UTF_8)

        val error = assertThrows(ImapException::class.java) {
            ImapParser(ByteArrayInputStream(raw)).readResponse()
        }

        assertEquals("Connection closed", error.message)
    }

    /** `size` bytes of `x`, made up as they are read — never held anywhere. */
    private fun generated(size: Int): InputStream = object : InputStream() {
        private var left = size
        override fun read(): Int = if (left-- > 0) 'x'.code else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len, left)
            java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
            left -= n
            return n
        }
    }

    /** Head, body, tail as one stream, the head and tail written as text. */
    private fun streamOf(head: String, body: InputStream, tail: String): InputStream =
        SequenceInputStream(
            java.util.Collections.enumeration(
                listOf(
                    ByteArrayInputStream(head.toByteArray(Charsets.ISO_8859_1)),
                    body,
                    ByteArrayInputStream(tail.toByteArray(Charsets.ISO_8859_1)),
                ),
            ),
        )

    @Test
    fun parsesNestedEnvelopeAddresses() {
        val raw = "* 1 FETCH (UID 42 FLAGS (\\Seen) ENVELOPE " +
            "(\"Wed, 17 Jul 2024 12:00:00 +0000\" \"Hi\" ((\"Jane Doe\" NIL \"jane\" \"example.com\")) " +
            "NIL NIL NIL NIL NIL NIL \"<msg-1@example.com>\"))\r\n"
        val r = parse(raw)
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals("UID", args[0])
        assertEquals("42", args[1])
        @Suppress("UNCHECKED_CAST")
        val flags = args[3] as List<Any?>
        assertTrue(flags.contains("\\Seen"))
        @Suppress("UNCHECKED_CAST")
        val envelope = args[5] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val fromList = envelope[2] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val firstFrom = fromList[0] as List<Any?>
        assertEquals("Jane Doe", firstFrom[0])
        assertEquals("jane", firstFrom[2])
        assertEquals("example.com", firstFrom[3])
    }

    /**
     * An ENVELOPE address part sent as a literal (`{n}`) keeps CR/LF verbatim: literals are
     */
    @Test
    fun envelopeAddressLiteralKeepsCrlf() {
        val hostile = "bob\r\nRCPT TO:<victim@evil.com>"
        val raw = "* 1 FETCH (UID 42 ENVELOPE (NIL \"Hi\" " +
            "((NIL NIL {${hostile.toByteArray(Charsets.UTF_8).size}}\r\n$hostile \"example.com\")) " +
            "NIL NIL NIL NIL NIL NIL NIL))\r\n"

        val r = parse(raw)
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val envelope = args[3] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val fromList = envelope[2] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val firstFrom = fromList[0] as List<Any?>

        assertEquals(hostile, firstFrom[2])
        assertEquals("example.com", firstFrom[3])
    }

    /**
     * A section item name is ONE atom, brackets and everything inside them included. Cut on the
     */
    @Test
    fun aHeaderFieldsSectionIsOneAtom() {
        val r = parse("* 1 FETCH (UID 1 BODY[HEADER.FIELDS (REFERENCES)] {5}\r\nhello)\r\n")
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals(listOf("UID", "1", "BODY[HEADER.FIELDS (REFERENCES)]", "hello"), args)
    }

    /** The field name may come back quoted; the quotes are part of the atom, the atom is whole. */
    @Test
    fun aQuotedHeaderFieldsSectionIsOneAtom() {
        val r = parse("* 1 FETCH (UID 1 BODY[HEADER.FIELDS (\"REFERENCES\")] NIL)\r\n")
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals(listOf("UID", "1", "BODY[HEADER.FIELDS (\"REFERENCES\")]", null), args)
    }

    /** The partial-fetch form, one atom as it always was — the suffix rides with the brackets. */
    @Test
    fun aPartialBodySectionIsOneAtom() {
        val r = parse("* 1 FETCH (UID 1 BODY[1]<0> {5}\r\nhello FLAGS (\\Seen))\r\n")
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals(listOf("UID", "1", "BODY[1]<0>", "hello", "FLAGS", listOf("\\Seen")), args)
    }

    /**
     * A `[` never closed does not run past the line: the atom stops at the CRLF, and the next
     */
    @Test
    fun anUnclosedBracketStopsAtTheEndOfTheLine() {
        val parser = ImapParser(ByteArrayInputStream(
            "* 1 BODY[HEADER.FIELDS (REFERENCES\r\n* 2 EXISTS\r\n".toByteArray(Charsets.UTF_8),
        ))
        assertEquals(listOf("*", "1", "BODY[HEADER.FIELDS (REFERENCES"), parser.readResponse())
        assertEquals(listOf("*", "2", "EXISTS"), parser.readResponse())
    }

    /**
     * A `[` never closed inside a list leaves the list its `)`: the parenthesis that answers no
     */
    @Test
    fun anUnclosedBracketLeavesTheListItsClosingParenthesis() {
        val r = parse("* 1 FETCH (UID 1 BODY[HEADER.FIELDS (REFERENCES))\r\n")
        assertEquals(listOf("*", "1", "FETCH", listOf("UID", "1", "BODY[HEADER.FIELDS (REFERENCES)")), r)
    }

    /** A response code is not a section: a leading `[` keeps the shape it always had. */
    @Test
    fun aResponseCodeIsNotASectionAtom() {
        assertEquals(listOf("*", "OK", "[UIDVALIDITY", "7]", "ok"), parse("* OK [UIDVALIDITY 7] ok\r\n"))
    }
}
