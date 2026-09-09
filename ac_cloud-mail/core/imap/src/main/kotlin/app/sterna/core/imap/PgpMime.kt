package app.sterna.core.imap

/**
 * PGP/MIME (RFC 3156) wrappers. Pure string/MIME assembly — the actual
 * signing/encryption bytes come from the app's OpenPGP provider.
 */
object PgpMime {

        /**
         * RFC 3156 §5: the signature is computed over the signed entity in canonical form — CRLF line
         */
    fun canonicalize(entity: String): String =
        entity.replace("\r\n", "\n").replace("\n", "\r\n")

        /** The exact bytes to hand the signer for a multipart/signed: canonical CRLF form WITHOUT a
         *  trailing CRLF — per RFC 2046 §5.1.1 the CRLF preceding the boundary belongs to the boundary,
         *  so verifiers extract the part content without it. Pass the SAME string to [wrapSigned]. */
    fun signablePayload(entity: String): String =
        canonicalize(entity).removeSuffix("\r\n")

        /**
         * Wrap a MIME entity + its detached signature as multipart/signed. [innerEntity] must be
         */
    fun wrapSigned(
        innerEntity: String,
        armoredSignature: ByteArray,
        micalg: String,
        boundary: String,
    ): String = buildString {
        append("Content-Type: multipart/signed; micalg=\"${sanitize(micalg)}\";\r\n")
        append(" protocol=\"application/pgp-signature\"; boundary=\"${sanitize(boundary)}\"\r\n")
        append("\r\n")
        append("--$boundary\r\n")
        append(innerEntity)
        if (!innerEntity.endsWith("\r\n")) append("\r\n")
        append("--$boundary\r\n")
        append("Content-Type: application/pgp-signature; name=\"signature.asc\"\r\n")
        append("Content-Description: OpenPGP digital signature\r\n")
        append("\r\n")
        append(canonicalize(armoredSignature.toString(Charsets.US_ASCII)).trimEnd())
        append("\r\n")
        append("--$boundary--\r\n")
    }

        /** Wrap armored ciphertext as multipart/encrypted (protocol="application/pgp-encrypted"): a
         * version part + the payload. [armoredCiphertext] is provider output with bare-LF line
         *  endings, canonicalised here for the same reason as in [wrapSigned]. */
    fun wrapEncrypted(armoredCiphertext: ByteArray, boundary: String): String = buildString {
        append("Content-Type: multipart/encrypted;\r\n")
        append(" protocol=\"application/pgp-encrypted\"; boundary=\"${sanitize(boundary)}\"\r\n")
        append("\r\n")
        append("--$boundary\r\n")
        append("Content-Type: application/pgp-encrypted\r\n")
        append("Content-Description: PGP/MIME version identification\r\n")
        append("\r\n")
        append("Version: 1\r\n")
        append("--$boundary\r\n")
        append("Content-Type: application/octet-stream; name=\"encrypted.asc\"\r\n")
        append("Content-Description: OpenPGP encrypted message\r\n")
        append("Content-Disposition: inline; filename=\"encrypted.asc\"\r\n")
        append("\r\n")
        append(canonicalize(armoredCiphertext.toString(Charsets.US_ASCII)).trimEnd())
        append("\r\n")
        append("--$boundary--\r\n")
    }

    /** Keep header parameter values header-safe (no CR/LF/quotes). */
    private fun sanitize(value: String): String =
        value.filterNot { it == '\r' || it == '\n' || it == '"' || it.code < 32 }
}
