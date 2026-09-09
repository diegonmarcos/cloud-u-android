package app.sterna.core.data.db

import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * (De)serialises the attachment parts stored in [EmailEntity.attachmentsJson], the same way
 * [EmailRecipients] stores addresses: kotlinx JSON into one TEXT column rather than a second table.
 *
 * A table would be the textbook shape and is not worth it. These rows are read exactly one way --
 * "give me every part of THIS message", always alongside the message itself -- so a join buys
 * nothing, while a child table buys a foreign key, a cascade, an index and a second thing for the
 * sync's ghost sweep to leave orphans in. The column travels with its row and dies with it.
 *
 * WHAT IS STORED IS METADATA ONLY: partId/blobId, size, type, name, disposition, cid. No bytes.
 * A message's attachment content is never in this database -- it is downloaded on demand and cached
 * as a file under a size and age cap ([StorageRepository]). Mail data in this app has grown badly
 * before; a list page holding 50 messages' worth of file BODIES would be that mistake again.
 */
object EmailAttachments {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(EmailBodyPart.serializer())

    /** Null for "no attachments" rather than `"[]"`: almost every message has none, and a null
     *  column stores nothing. It is also what a row cached before this column existed reads back as,
     *  so the empty case and the not-yet-fetched case decode identically -- neither can show a chip,
     *  which is the honest answer for both. */
    fun encode(parts: List<EmailBodyPart>): String? =
        if (parts.isEmpty()) null else json.encodeToString(serializer, parts)

    /** Undecodable JSON reads back as no attachments, never as a throw: a corrupt column must cost
     *  the chips on one row, not the whole list query that row is in. */
    fun decode(raw: String?): List<EmailBodyPart> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
