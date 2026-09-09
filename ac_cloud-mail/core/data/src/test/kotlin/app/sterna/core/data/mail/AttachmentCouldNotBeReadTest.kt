package app.sterna.core.data.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [attachmentCouldNotBeRead] and [requireAttachmentBytes], EXECUTED — the verdict standing between
 */
class AttachmentCouldNotBeReadTest {

    /** THE case, measured on the test server: the UID vanished between opening the message and
     *  the Save tap, `UID FETCH <uid> (BODY.PEEK[1])` answered OK with no untagged FETCH at all,
     *  `ImapClient.bodyItem()` made that "" and `MimeParser.decodeBytes("", …)` made it zero bytes
     *  without throwing. */
    @Test fun zeroBytesCouldNotBeRead() {
        assertTrue(
            "zero octets are a part the server never handed over, never a saved attachment",
            attachmentCouldNotBeRead(ByteArray(0)),
        )
    }

    /** One octet is a file. Even 0x00: a byte that decodes to nothing readable is still a byte the
     *  server sent, and refusing it would refuse legitimate files. */
    @Test fun oneByteCanBeRead() {
        assertFalse("a single 0x00 is one octet the server sent", attachmentCouldNotBeRead(byteArrayOf(0)))
        assertFalse(attachmentCouldNotBeRead(byteArrayOf(0x25)))
    }

    /**
     * THE case that says this is not an `isBlank` in disguise. An attachment made of nothing but
     */
    @Test fun anAttachmentOfNothingButWhitespaceCanBeRead() {
        for (bytes in listOf(" ", "   ", "\r\n", "\t", " \r\n\t ")) {
            assertFalse(
                "bytes = \"$bytes\" is a real file: an attachment is octets, not prose to judge",
                attachmentCouldNotBeRead(bytes.toByteArray(Charsets.ISO_8859_1)),
            )
        }
    }

    /** A real attachment: a PDF header. Nothing here may be refused. */
    @Test fun aRealAttachmentCanBeRead() {
        assertFalse(attachmentCouldNotBeRead("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1)))
    }

    /** Not a test that only ever refuses: the two sides, side by side. */
    @Test fun theVerdictSeparatesTheTwoSides() {
        assertTrue(attachmentCouldNotBeRead(ByteArray(0)))
        assertFalse(attachmentCouldNotBeRead(byteArrayOf(1)))
    }

    /**
     * The twin, EXECUTED — and this is the assertion that kills "the twin was emptied of its
     */
    @Test fun requiringTheBytesThrowsTheUnavailableMessageOnZeroBytes() {
        assertThrows(MessageUnavailableException::class.java) {
            requireAttachmentBytes("email-7", "2", ByteArray(0))
        }
    }

    /** And the message names BOTH keys: a bug report has to be able to quote which part of which
     *  message the server dropped, and the reader appends this text to its failure sentence. */
    @Test fun theRefusalNamesTheMessageAndThePart() {
        val thrown = assertThrows(MessageUnavailableException::class.java) {
            requireAttachmentBytes("email-7", "2.1", ByteArray(0))
        }
        val message = thrown.message.orEmpty()
        assertTrue("the message must name the emailId, was \"$message\"", "email-7" in message)
        assertTrue("the message must name the partKey, was \"$message\"", "2.1" in message)
        // And in THAT order, which is what says which key is which. Presence alone is blind to
        // the parameters being swapped — `(partKey, emailId, bytes)` is two Strings, it compiles,
        assertTrue(
            "the PART must be named before the MESSAGE it belongs to — \"Attachment <part> of " +
                "<message>\". Swapped, the sentence lies about which key is which. Was: \"$message\"",
            message.indexOf("2.1") < message.indexOf("email-7"),
        )
    }

    /**
     * The control that forbids refusing everything: one octet passes, and whitespace passes. A
     */
    @Test fun requiringTheBytesLetsARealAttachmentThrough() {
        requireAttachmentBytes("email-7", "2", byteArrayOf(0))
        requireAttachmentBytes("email-7", "2", "   ".toByteArray(Charsets.ISO_8859_1))
        requireAttachmentBytes("email-7", "2", "%PDF-1.4".toByteArray(Charsets.ISO_8859_1))
    }
}
