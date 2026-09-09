package app.sterna.pgp

import org.openintents.openpgp.util.OpenPgpApi

/**
 * What Sterna asks an OpenPGP provider for, as a value: an action and the extras that go with it,
 */
internal data class PgpApiRequest(
    val action: String,
    val extras: Map<String, Any>,
)

/**
 * The peer update of `ACTION_UPDATE_AUTOCRYPT_PEER`, kept as plain values here and turned into the
 */
internal data class PeerUpdateFacts(
    val keyData: ByteArray,
    val effectiveDateMillis: Long,
    val isMutual: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is PeerUpdateFacts &&
            keyData.contentEquals(other.keyData) &&
            effectiveDateMillis == other.effectiveDateMillis &&
            isMutual == other.isMutual

    override fun hashCode(): Int =
        (keyData.contentHashCode() * 31 + effectiveDateMillis.hashCode()) * 31 + isMutual.hashCode()
}

/**
 * Ask the provider for the public half of [keyId] — this account's OWN key, on its way into an
 */
internal fun publicKeyRequest(keyId: Long, ownAddress: String): PgpApiRequest = PgpApiRequest(
    action = OpenPgpApi.ACTION_GET_KEY,
    extras = buildMap {
        put(OpenPgpApi.EXTRA_KEY_ID, keyId)
        put(OpenPgpApi.EXTRA_MINIMIZE, true)
        if (ownAddress.isNotBlank()) put(OpenPgpApi.EXTRA_MINIMIZE_USER_ID, ownAddress)
    },
)

/**
 * File a CORRESPONDENT's key in the provider's public keyring under [peerId] (Autocrypt Level 1
 */
internal fun autocryptPeerRequest(
    peerId: String,
    keyData: ByteArray,
    effectiveDateMillis: Long,
): PgpApiRequest = PgpApiRequest(
    action = OpenPgpApi.ACTION_UPDATE_AUTOCRYPT_PEER,
    extras = mapOf(
        OpenPgpApi.EXTRA_AUTOCRYPT_PEER_ID to peerId,
        OpenPgpApi.EXTRA_AUTOCRYPT_PEER_UPDATE to PeerUpdateFacts(
            keyData = keyData,
            effectiveDateMillis = effectiveDateMillis,
            isMutual = false,
        ),
    ),
)
