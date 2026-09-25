package com.diegonmarcos.superapp.profile

import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.ConfigSyncClient

/**
 * GitHub half of Configs → Profile → Config import: reading the artifact out
 * of the private vault repo with a token.
 *
 * The token comes from a sign-in through the provider that grants
 * `repo_artifact` (build.json::ui.vault_connect.sign_in — the OAuth device
 * grant itself is [SignIn], one code path for every provider that speaks it;
 * #573) or from the SSH route ([GitSshVault]). Once a token exists, reading the
 * artifact is one authenticated GET of the repo's contents route, which is why
 * this file has no git implementation in it.
 *
 * SECRET HYGIENE: the token is never written to prefs, never logged, and
 * handed to [ConfigSyncClient.request] as the `secret` so it is redacted out
 * of any echoed error body.
 */
object GithubImport {

    private const val TAG = "ConfigSync"

    /**
     * Read the config artifact out of the vault repo with a token.
     *
     * `Accept: application/vnd.github.raw` makes the contents route return the
     * file itself rather than the JSON envelope with a base64 blob in it, so
     * the body handed back is already the artifact and needs no unwrapping.
     */
    fun fetchArtifact(token: String): ConfigSyncClient.Outcome {
        val repo = BuildConfig.UI_CONFIG_GIT_REPO
        val path = BuildConfig.UI_CONFIG_GIT_PATH
        val ref = BuildConfig.UI_CONFIG_GIT_REF
        if (repo.isBlank() || path.isBlank()) {
            return ConfigSyncClient.Outcome.Failed(
                ConfigSyncClient.Kind.MALFORMED,
                "No vault repo configured (build.json::ui.config_source.git.repo / .path are empty)",
            )
        }
        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$ref"
        Log.i(TAG, "github: GET contents $repo/$path@$ref")
        return ConfigSyncClient.request(
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "X-GitHub-Api-Version" to "2022-11-28",
            ),
            secret = token,
            authHint = "The GitHub token is not valid for $repo, or lacks `repo` scope on a private repository.",
            connectTimeoutMs = BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS,
            readTimeoutMs = BuildConfig.UI_CONFIG_SOURCE_READ_MS,
            accept = "application/vnd.github.raw",
        )
    }
}
