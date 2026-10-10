package com.x8bit.bitwarden.data.autofill.cloud

/**
 * Applies [AutofillUriPolicy] to a parsed request: which URI the vault matches logins against.
 */
interface AutofillUriResolver {

    /**
     * The URI a fill request is matched against. May suspend for a Digital Asset Links check
     * (bounded by a short timeout; on timeout or error the app's own URI is used).
     */
    suspend fun resolveForFill(uri: String?, packageName: String?): String?

    /** The URI a save request writes into the new login. Never waits on the network. */
    fun resolveForSave(uri: String?, packageName: String?): String?

    /** No policy: the parser's URI as-is (upstream behaviour; used by tests). */
    object Passthrough : AutofillUriResolver {
        override suspend fun resolveForFill(uri: String?, packageName: String?): String? = uri
        override fun resolveForSave(uri: String?, packageName: String?): String? = uri
    }
}
