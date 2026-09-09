package app.sterna.core.data.pgp

import android.app.PendingIntent
import android.content.Intent

/**
 * Per-message crypto mode. [ENCRYPT] signs and encrypts; [ENCRYPT_UNSIGNED] encrypts without a
 * signature. Subject and SMTP envelope are never hidden either way.
 */
enum class PgpMode { OFF, SIGN, ENCRYPT, ENCRYPT_UNSIGNED }

/**
 * "Does this mode turn the body into ciphertext?" — draft-keep, outbox-at-rest blanking and
 */
val PgpMode.encrypts: Boolean get() = this == PgpMode.ENCRYPT || this == PgpMode.ENCRYPT_UNSIGNED

/** Outcome of verifying an OpenPGP signature, mapped for UI badge semantics. */
enum class PgpSignatureState {
    /** No signature present. */
    NONE,

    /** Valid signature from a key the user has confirmed (green). */
    VALID_CONFIRMED,

    /** Valid signature from a known but unconfirmed key (yellow). */
    VALID_UNCONFIRMED,

    /** Signature present but the public key is not available (grey). */
    KEY_MISSING,

    /** Signature did not verify — content altered or corrupt (red). */
    INVALID,

    /** Valid signature but the key is revoked (red). */
    KEY_REVOKED,

    /** Valid signature but the key is expired (red). */
    KEY_EXPIRED,

    /** Valid signature from a cryptographically weak key (red). */
    INSECURE,

    /** Valid signature but the key's user id does not match the sender (yellow). */
    SENDER_MISMATCH,
}

/**
 * Result of a [PgpEngine] call. [UserInteractionRequired] surfaces when the provider needs the
 * user; launch its [PendingIntent], then pass the result Intent back as `interactionResult`.
 */
sealed interface PgpResult<out T> {
    data class Success<T>(val value: T) : PgpResult<T>

    data class UserInteractionRequired(val pendingIntent: PendingIntent) : PgpResult<Nothing>

    data class Error(val message: String) : PgpResult<Nothing>

    /** No OpenPGP provider installed (or the service cannot be bound). */
    object NotAvailable : PgpResult<Nothing>
}

/** Detached-signature output: the armored signature + the micalg for the PGP/MIME wrapper. */
class PgpSignature(
    val armor: ByteArray,
    /** RFC 3156 micalg parameter, e.g. "pgp-sha256". */
    val micalg: String,
)

/** Decrypt/verify output: the plaintext plus what the signature (if any) told us. */
class PgpDecrypted(
    val plaintext: ByteArray,
    val signature: PgpSignatureState,
    val signatureKeyId: Long,
    val signatureUserId: String?,
    val wasEncrypted: Boolean,
    /** Pending intent to show/import the signing key in the provider, when offered. */
    val signaturePendingIntent: PendingIntent? = null,
)

/**
 * Message-level OpenPGP operations, kept behind an interface so core modules stay free of
 */
interface PgpEngine {
    /** True when an OpenPGP provider is installed and its service can be bound. */
    suspend fun isAvailable(): Boolean

    /** Let the user pick (or confirm) their signing key; returns the key id. */
    suspend fun getSignKeyId(
        userIdHint: String? = null,
        interactionResult: Intent? = null,
    ): PgpResult<Long>

    /**
     * This account's OWN public key for [keyId]: minimized (signatures stripped, user ids
     */
    suspend fun getPublicKey(
        keyId: Long,
        ownAddress: String,
        interactionResult: Intent? = null,
    ): PgpResult<ByteArray>

    /**
     * File a CORRESPONDENT's public key under [peerId], from a message's `Autocrypt` header.
     */
    suspend fun updateAutocryptPeer(
        peerId: String,
        keyData: ByteArray,
        effectiveDateMillis: Long,
    ): PgpResult<Unit>

    /** Resolve addresses to public-key ids. Fails when any has none (see [findKeysEach]). */
    suspend fun findKeys(
        emails: List<String>,
        interactionResult: Intent? = null,
    ): PgpResult<LongArray>

    /** Per-address key availability (true = at least one usable key). */
    suspend fun findKeysEach(emails: List<String>): Map<String, Boolean>

    /** Detached-sign [data]; returns the ASCII-armored signature + micalg (RFC 3156). */
    suspend fun detachedSign(
        data: ByteArray,
        signKeyId: Long,
        interactionResult: Intent? = null,
    ): PgpResult<PgpSignature>

    /**
     * Encrypt [data] to [recipientKeyIds] (include the sender's own key to encrypt-to-self),
     * signing with [signKeyId] when non-null.
     */
    suspend fun signAndEncrypt(
        data: ByteArray,
        signKeyId: Long?,
        recipientKeyIds: LongArray,
        interactionResult: Intent? = null,
    ): PgpResult<ByteArray>

    /**
     * Decrypt/verify [data]: for multipart/signed pass the signed bytes plus [detachedSignature];
     * for encrypted messages pass the armored ciphertext. [senderAddress] flags uid mismatches.
     */
    suspend fun decryptVerify(
        data: ByteArray,
        senderAddress: String?,
        detachedSignature: ByteArray? = null,
        interactionResult: Intent? = null,
    ): PgpResult<PgpDecrypted>
}
