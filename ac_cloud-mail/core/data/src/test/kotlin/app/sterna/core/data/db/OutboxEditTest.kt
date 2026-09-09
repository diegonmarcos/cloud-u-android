package app.sterna.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Taking a queued message out to edit it must never lose an attachment (#70). The row itself stays
 */
class OutboxEditTest {
    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    @Test fun takeStagesReadableCopiesTheComposerCanSendFrom() {
        val durable = tempDir("outbox-durable")
        val staging = tempDir("outbox-staging")
        val file = File(durable, "notes.txt").apply { writeText("hello") }
        val a = OutboxAttachment(
            kind = OutboxAttachments.KIND_IMAP_FILE, path = file.absolutePath,
            type = "text/plain", name = "notes.txt", size = 5,
        )

        val parts = OutboxEdit.take(listOf(a), staging)

        val staged = File(parts.single().partId!!)
        assertEquals(staging.absolutePath, staged.parentFile?.absolutePath)
        assertEquals("hello", staged.readText())
        // The durable original is left untouched: the row keeps it while it is edited.
        assertEquals("hello", file.readText())
    }

    @Test fun takeKeepsAJmapBlobAsItsServerIdNoCopy() {
        val blob = OutboxAttachment(
            kind = OutboxAttachments.KIND_JMAP_BLOB, blobId = "G123",
            type = "text/calendar", name = "invite.ics", size = 56, disposition = "attachment",
        )
        val part = OutboxEdit.take(listOf(blob), tempDir("outbox-staging")).single()
        assertEquals("G123", part.blobId)
        assertEquals("invite.ics", part.name)
    }

    @Test fun takingAnItemWhoseAttachmentBytesAreGoneRefusesRatherThanAmputate() {
        // Reopening an item short of what it held would let the user send an amputated message: the
        // take must throw instead, leaving the queued row and its durable dir exactly as they were.
        val staging = tempDir("outbox-staging")
        val missing = OutboxAttachment(
            kind = OutboxAttachments.KIND_IMAP_FILE, path = "/nonexistent/gone.pdf",
            type = "application/pdf", name = "gone.pdf", size = 10,
        )
        assertThrows(IllegalStateException::class.java) { OutboxEdit.take(listOf(missing), staging) }
    }

    @Test fun twoAttachmentsSharingANameDoNotOverwriteEachOther() {
        val durable = tempDir("outbox-durable")
        val staging = tempDir("outbox-staging")
        val file = File(durable, "report.pdf").apply { writeBytes("one".toByteArray()) }
        val a = OutboxAttachment(
            kind = OutboxAttachments.KIND_IMAP_FILE, path = file.absolutePath,
            type = "application/pdf", name = "report.pdf", size = 3,
        )
        val parts = OutboxEdit.take(listOf(a, a), staging)
        assertNotEquals(parts[0].partId, parts[1].partId)
    }
}
