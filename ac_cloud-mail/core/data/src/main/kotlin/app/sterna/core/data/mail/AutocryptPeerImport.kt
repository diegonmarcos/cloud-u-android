package app.sterna.core.data.mail

import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.pgp.PgpResult
import java.util.Base64

internal class AutocryptPeerImport(
    val peerId: String,
    val keyData: ByteArray,
    val effectiveDateMillis: Long,
)

/** Autocrypt Level 1 §2.1: everything else that is not prefixed `_` makes the message worthless. */
private val KNOWN_ATTRIBUTES = setOf("addr", "keydata", "prefer-encrypt")

/**
 * Autocrypt Level 1 §2.1, or null meaning "ignore, in silence". [headers] must come from
 */
internal fun autocryptPeerImportOf(
    headers: List<Pair<String, String>>,
    fromAddress: String?,
    receivedAt: String?,
    nowMillis: Long,
): AutocryptPeerImport? {
    val from = fromAddress?.trim()?.lowercase().orEmpty()
    if (from.isEmpty()) return null
    val effectiveDate = internalDateOf(receivedAt) ?: return null
    if (effectiveDate > nowMillis + FUTURE_TOLERANCE_MILLIS) return null
    // singleOrNull is the guard, not a convenience: zero and two both answer null.
    val value = headers
        .filter { (name, _) -> name.trim().equals(FIELD_NAME, ignoreCase = true) }
        .singleOrNull()
        ?.second
        ?: return null
    val attributes = mutableMapOf<String, String>()
    // Neither base64 nor a deliverable addr-spec holds a ';', so the split cannot fall in a value.
    for (part in value.split(';')) {
        val text = part.trim()
        val equals = text.indexOf('=')
        // Malformed is treated as unknown-critical: ignore the message.
        if (equals <= 0) return null
        val name = text.substring(0, equals).trim().lowercase()
        if (name.startsWith("_")) continue
        if (name !in KNOWN_ATTRIBUTES) return null
        // A second `addr` or `keydata` is a message trying to have it both ways.
        if (attributes.put(name, text.substring(equals + 1).trim()) != null) return null
    }
    if (attributes["addr"]?.trim()?.lowercase() != from) return null
    val keydata = attributes["keydata"]?.filterNot { it.isWhitespace() }
    if (keydata.isNullOrEmpty()) return null
    // Strict: the MIME decoder skips characters it dislikes, making a corrupt header plausible.
    val bytes = runCatching { Base64.getDecoder().decode(keydata) }.getOrNull()
    if (bytes == null || bytes.isEmpty()) return null
    return AutocryptPeerImport(peerId = from, keyData = bytes, effectiveDateMillis = effectiveDate)
}

private const val FIELD_NAME = "Autocrypt"

/** How far ahead of the reader's own clock a server's date may still be: 24 h. */
private const val FUTURE_TOLERANCE_MILLIS = 24L * 60 * 60 * 1000

/**
 * Every answer that is not [PgpResult.Success] is dropped in silence,
 * [PgpResult.UserInteractionRequired] included: the open path has no launcher for a `PendingIntent`.
 */
internal suspend fun importAutocryptPeer(
    engine: PgpEngine?,
    headers: List<Pair<String, String>>,
    fromAddress: String?,
    receivedAt: String?,
    nowMillis: Long,
): Boolean {
    if (engine == null) return false
    val import = autocryptPeerImportOf(headers, fromAddress, receivedAt, nowMillis) ?: return false
    return engine.updateAutocryptPeer(import.peerId, import.keyData, import.effectiveDateMillis) is
        PgpResult.Success
}
