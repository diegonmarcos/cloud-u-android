package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.jmap.OAuthClient

/**
 * Hands out a currently-valid OAuth access token for an account, refreshing it (and
 */
class OAuthTokenRefresher(
    private val oauthClient: OAuthClient,
    private val accountStore: AccountStore,
) {
    /** A fresh access token for [credentials], or null when it isn't an OAuth account. */
    suspend fun freshAccessToken(credentials: AccountCredentials, force: Boolean = false): String? {
        val oauth = credentials.oauth ?: return null
        // force: the server answered 401 to a token our clock still thinks valid (revoked, rotated,
        // or refused by the edge gate) — refresh regardless of the stored expiry.
        if (!force && oauth.accessToken.isNotBlank() &&
            oauth.accessExpiresAtMillis - System.currentTimeMillis() > 60_000
        ) {
            return oauth.accessToken
        }
        val tokens = oauthClient.refresh(oauth.tokenEndpoint, oauth.refreshToken, oauth.clientId)
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        accountStore.updateOAuthTokens(
            credentials.id,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
        )
        return tokens.accessToken
    }

    /** Whether [credentials]' OAuth access token is (about to be) expired, so a cached session holding it is stale. */
    fun isExpiring(credentials: AccountCredentials, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val oauth = credentials.oauth ?: return false
        if (oauth.accessExpiresAtMillis <= 0) return false
        return oauth.accessExpiresAtMillis - nowMillis <= 60_000
    }
}
