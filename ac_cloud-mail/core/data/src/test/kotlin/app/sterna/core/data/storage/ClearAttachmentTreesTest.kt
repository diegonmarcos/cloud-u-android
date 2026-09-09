package app.sterna.core.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The sweep behind every "clear cache" the app offers, EXECUTED on real directories — the decision
 */
class ClearAttachmentTreesTest {

    @get:Rule val temp = TemporaryFolder()

    private fun fileWith(dir: File, name: String, bytes: String): File =
        File(dir, name).apply { writeBytes(bytes.toByteArray()) }

    @Test fun `both trees are emptied, and the bytes are really gone`() {
        val attachments = temp.newFolder("attachments")
        val outgoing = temp.newFolder("outgoing")
        // The decrypted PDF is written at the size a real one has, several megabytes. A sweep
        // narrowed by a size test — `if (it.length() < 1_000_000)` — passes every assertion below on
        // twenty-byte fixtures while leaving exactly the file of the symptom on the phone. These
        // data have to be able to tell the two apart.
        val decrypted = File(attachments, "invoice.pdf")
            .apply { writeBytes(ByteArray(3 * 1024 * 1024) { 'P'.code.toByte() }) }
        val downloaded = fileWith(attachments, "photo.jpg", "jpeg")
        val staged = fileWith(outgoing, "staged-part.bin", "picked file")
        assertTrue("fixture not written", decrypted.exists() && downloaded.exists() && staged.exists())
        assertTrue("the decrypted fixture must be big enough to survive a size filter", decrypted.length() > 1_000_000)

        clearAttachmentTrees(attachments, outgoing)

        assertFalse(
            "⛔ the decrypted attachment is still on the phone — this is the bug: a cache purge " +
                "that deletes rows and leaves the plaintext PDF behind",
            decrypted.exists(),
        )
        assertFalse("a downloaded attachment survived the sweep", downloaded.exists())
        assertFalse(
            "the outgoing staging copy survived the sweep (#70): once the send is queued it is " +
                "orphaned cache and nothing else ever removes it",
            staged.exists(),
        )
        assertEquals(
            "the attachments tree is not empty afterwards",
            emptyList<String>(),
            attachments.listFiles()!!.map { it.name },
        )
        assertEquals(
            "the outgoing tree is not empty afterwards",
            emptyList<String>(),
            outgoing.listFiles()!!.map { it.name },
        )
        assertTrue("the trees themselves must stay: the app writes into them again", attachments.isDirectory)
        assertTrue("the trees themselves must stay: the app writes into them again", outgoing.isDirectory)
    }

    @Test fun `a tree that was never created does not make it throw`() {
        // listFiles() answers null on a path that does not exist, which is the normal state of both
        // dirs on a fresh install: the first "clear cache" happens before the first download.
        val absentAttachments = File(temp.root, "attachments")
        val absentOutgoing = File(temp.root, "outgoing")
        assertFalse(absentAttachments.exists())
        assertFalse(absentOutgoing.exists())

        clearAttachmentTrees(absentAttachments, absentOutgoing)

        // And it must not CREATE them either: a purge is not a writer.
        assertFalse(absentAttachments.exists())
        assertFalse(absentOutgoing.exists())
    }

    @Test fun `one tree missing still empties the other`() {
        val outgoing = temp.newFolder("outgoing")
        val staged = fileWith(outgoing, "staged-part.bin", "picked file")

        clearAttachmentTrees(File(temp.root, "attachments"), outgoing)

        assertFalse("a null listFiles() on the first tree stopped the sweep of the second", staged.exists())
    }

    @Test fun `a sweep of an empty tree is a no-op, not a failure`() {
        val attachments = temp.newFolder("attachments")
        val outgoing = temp.newFolder("outgoing")

        clearAttachmentTrees(attachments, outgoing)

        assertTrue(attachments.isDirectory && outgoing.isDirectory)
    }
}
