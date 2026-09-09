package app.sterna.core.data.db

import app.sterna.core.jmap.model.EmailBodyPart
import java.io.File

/**
 * Taking a queued message out of the outbox to edit it (#70). The row stays QUEUED-adjacent,
 */
object OutboxEdit {
    /**
     * Stages [attachments] into [stagingDir] as composer parts. THROWS if bytes can't be read,
     * rather than dropping silently — the durable dir is untouched, so nothing is lost.
     */
    fun take(attachments: List<OutboxAttachment>, stagingDir: File): List<EmailBodyPart> =
        attachments.map { a ->
            when (a.kind) {
                OutboxAttachments.KIND_JMAP_BLOB -> EmailBodyPart(
                    blobId = a.blobId, type = a.type, size = a.size, name = a.name,
                    disposition = "attachment",
                )
                OutboxAttachments.KIND_IMAP_FILE -> {
                    val bytes = runCatching { File(a.path!!).readBytes() }.getOrNull()
                        ?: error("Couldn't read the queued attachment ${a.name ?: a.path} to edit it.")
                    val staged = copyInto(stagingDir, a.name, bytes)
                    EmailBodyPart(
                        partId = staged.absolutePath, type = a.type, size = bytes.size.toLong(),
                        name = a.name, disposition = "attachment",
                    )
                }
                else -> error("Unknown outbox attachment kind ${a.kind}")
            }
        }

    /** Write [bytes] under a collision-proof name, so two files sharing a name don't overwrite. */
    private fun copyInto(dir: File, name: String?, bytes: ByteArray): File {
        dir.mkdirs()
        val safe = (name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
    }
}
