package com.diegonmarcos.cloudlib.auth

import android.util.Log
import com.diegonmarcos.superapp.core.ConfigSyncClient

/**
 * The per-user config artifact, fetched three ways (#587, lifted from the
 * superapp's fetchWithBearer / fetchWithCookie / GithubImport): a pasted or
 * stored Authelia bearer, the cookie a browser login left behind, or a token
 * that reads the SAME BYTES out of the private vault repo. Endpoint, user slug,
 * timeouts and repo coordinates are [AuthDeclaration.configSource] — the ONE
 * declaration — so no host names a URL.
 *
 * Every route lands in one [ConfigSyncClient.Outcome]: four ways to
 * authenticate for one artifact contract, so the host's landing code is
 * reached identically no matter which way was taken.
 *
 * SECRET HYGIENE: the credential is never written to prefs here, never logged,
 * and handed to [ConfigSyncClient] as the `secret` so it is redacted out of
 * any echoed error body.
 */
object ConfigArtifact {

    private const val TAG = ConfigSyncClient.TAG

    /** The artifact's URL, `{user}` substituted. */
    fun endpoint(): String = AuthDeclaration.configSource.let { ConfigSyncClient.endpoint(it.baseUrl, it.pathTemplate, it.user) }

    /** The config route, authenticated by a bearer. Blocking. */
    fun fetchWithBearer(token: String): ConfigSyncClient.Outcome = AuthDeclaration.configSource.let {
        ConfigSyncClient.fetch(
            baseUrl = it.baseUrl, pathTemplate = it.pathTemplate, user = it.user, bearer = token,
            connectTimeoutMs = it.connectTimeoutMs, readTimeoutMs = it.readTimeoutMs,
        )
    }

    /** The same route, authenticated by the cookie a browser login left behind. Blocking. */
    fun fetchWithCookie(cookie: String): ConfigSyncClient.Outcome = AuthDeclaration.configSource.let {
        ConfigSyncClient.fetchWithCookie(
            baseUrl = it.baseUrl, pathTemplate = it.pathTemplate, user = it.user, cookie = cookie,
            connectTimeoutMs = it.connectTimeoutMs, readTimeoutMs = it.readTimeoutMs,
        )
    }

    /**
     * Read the artifact out of the vault repo with a token (the provider that
     * grants `repo_artifact`). `Accept: application/vnd.github.raw` makes the
     * contents route return the file itself rather than the JSON envelope with
     * a base64 blob in it, so the body handed back is already the artifact.
     */
    fun fetchFromRepo(token: String): ConfigSyncClient.Outcome {
        val cs = AuthDeclaration.configSource
        if (cs.gitRepo.isBlank() || cs.gitPath.isBlank()) {
            return ConfigSyncClient.Outcome.Failed(
                ConfigSyncClient.Kind.MALFORMED,
                "No vault repo configured (ab_cloud-libs-shared/build.json::auth.config_source.git.repo / .path are empty)",
            )
        }
        val url = "https://api.github.com/repos/${cs.gitRepo}/contents/${cs.gitPath}?ref=${cs.gitRef}"
        Log.i(TAG, "github: GET contents ${cs.gitRepo}/${cs.gitPath}@${cs.gitRef}")
        return ConfigSyncClient.request(
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "X-GitHub-Api-Version" to "2022-11-28",
            ),
            secret = token,
            authHint = "The GitHub token is not valid for ${cs.gitRepo}, or lacks `repo` scope on a private repository.",
            connectTimeoutMs = cs.connectTimeoutMs,
            readTimeoutMs = cs.readTimeoutMs,
            accept = "application/vnd.github.raw",
        )
    }
}
