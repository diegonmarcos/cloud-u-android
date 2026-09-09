package app.sterna.core.data.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A durable descriptor for one outbox attachment. An IMAP attachment keeps its bytes
 */
@Serializable
data class OutboxAttachment(
    val kind: String,
    /** Persistent file path for [KIND_IMAP_FILE]. */
    val path: String? = null,
    /** Server blob id for [KIND_JMAP_BLOB]. */
    val blobId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val size: Long = 0,
    /** Content-ID (no angle brackets) for an inline part carried by a forward; null for a file. */
    val cid: String? = null,
    /** "inline" for a cid image, else "attachment" (default for older persisted rows). */
    val disposition: String? = null,
)

/** (De)serialises the outbox attachment list stored in [OutboxEntity.attachmentsJson]. */
object OutboxAttachments {
    const val KIND_IMAP_FILE = "imapFile"
    const val KIND_JMAP_BLOB = "jmapBlob"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(items: List<OutboxAttachment>): String = json.encodeToString(items)

    fun decode(raw: String?): List<OutboxAttachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<OutboxAttachment>>(raw) }.getOrDefault(emptyList())
    }
}
