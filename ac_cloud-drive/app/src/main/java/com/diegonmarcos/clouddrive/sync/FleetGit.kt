package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.auth.AuthDeclaration
import com.diegonmarcos.clouddrive.configs.DriveGitChain
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * #653 THE FLEET'S OWN GIT READ PATH — how this app lists an account's repositories
 * WITHOUT EVER HOLDING A GITHUB CREDENTIAL.
 *
 * The owner's complaint that produced this file: the Git page offered a GitHub device
 * grant (a code to read off the phone, a URL to retype it at) which (a) was the UX he
 * had rejected three times and (b) could never complete, because our client shipped
 * with `device_flow_disabled`. Meanwhile #647 had already deployed an Authelia-fronted
 * proxy that does the GitHub work server-side — and nothing in this app called it. A
 * server with no caller is not a feature; this is the caller.
 *
 * WHAT IS PRESENTED, AND WHAT IS NOT. Outbound, the ONLY credential is the Authelia
 * bearer the fleet's own browser login (`authelia_web`) produced. There is no GitHub
 * token in this file, none is accepted as a parameter, and none can be returned: the
 * response is a PROJECTION the service builds (full_name/owner/name/private/
 * default_branch/updated_at/size_kb) precisely so no upstream field can carry a
 * credential through. That is the property that lets the Git page work on a phone with
 * ZERO GitHub credential present.
 *
 * NO URL IS TYPED HERE. Every endpoint comes from
 * `ab_cloud-libs-shared/build.json::auth.git_chain.providers.fleet`, which was read off
 * the service's own declared contract (cloud-u-containers/infra-api_git-proxy-api/
 * build.json::runtime). When that contract moves, this file does not.
 *
 * READING A STATUS CODE HONESTLY — the distinction #647's own verification got wrong.
 * Three outcomes look alike from a phone and mean completely different things:
 *
 *  · **401** from the service — it is UP and correctly refusing. The service validates
 *    the bearer itself, independent of Caddy (measured direct-to-container: GET /repos
 *    with no Authorization → 401 `missing_authorization`).
 *  · **3xx** — Caddy's Authelia matcher, which fires on the proxy's path prefix BEFORE
 *    any upstream dial. A control probe of a route that does not exist returns an
 *    IDENTICAL 3xx while the container is stopped. So a redirect is evidence about the
 *    EDGE and none whatsoever about the service, and [Outcome.Blocked] keeps it apart
 *    from both success and failure instead of flattening it into either.
 *  · **502** — the service is down. It has happened: git-proxy-api was missing from
 *    oci-analytics `tier1_services`, so the load-shedder stopped it as expendable.
 *
 * Redirects are therefore NOT followed. Following one would hand back the portal's
 * login page with a 200, and a 200 carrying HTML is exactly how a dead path passes for
 * a live one.
 */
object FleetGit {

    /** The declared fleet rung, or null when the declaration does not rank one. */
    private fun rung(): AuthDeclaration.GitRung? =
        AuthDeclaration.gitChain.firstOrNull { it.kind == DriveGitChain.RUNG_FLEET }

    /** What the declaration says the fleet serves. Empty when no fleet rung is declared. */
    fun reposUrl(): String = rung()?.config?.optString("repos_url").orEmpty()

    /** The pre-auth liveness route — the ONE probe whose 200 is real evidence. */
    fun healthUrl(): String = rung()?.config?.optString("health_url").orEmpty()

    /**
     * The declared sign-in provider whose bearer authorises this rung. Declared, so the
     * page asks for the right login without naming a provider in Kotlin.
     */
    fun bearerProvider(): String = rung()?.config?.optString("bearer_provider").orEmpty()

    /** What a call to the fleet produced. No variant carries a credential. */
    sealed class Outcome {
        /** The proxy listed the account. [repos] came from ITS projection, not GitHub's body. */
        class Listed(val repos: List<GitHubRepos.Repo>) : Outcome()

        /** The service was reached and refused this identity — it is UP. */
        class Refused(val code: Int, val why: String) : Outcome()

        /**
         * The EDGE answered instead of the service: a redirect to the portal. Says
         * nothing about whether the service is running, and must never be reported as
         * either success or a service failure.
         */
        class Blocked(val code: Int) : Outcome()

        /** Could not be reached, or answered something unusable. A fall-through. */
        class Unreachable(val why: String) : Outcome()
    }

    /**
     * List the account's repositories through the proxy, presenting only [bearer].
     *
     * Reuses [GitHubRepos.parse] for the array: the proxy's projection keeps the
     * upstream field names, so a second parser would be a second thing to keep in step.
     */
    fun repos(bearer: String): Outcome {
        val url = reposUrl()
        if (url.isBlank()) return Outcome.Unreachable("no fleet repos_url is declared")
        if (bearer.isBlank()) return Outcome.Unreachable("no fleet bearer on this phone")
        val config = rung()?.config
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = config?.optInt("connect_timeout_ms", 4000) ?: 4000
            connection.readTimeout = config?.optInt("read_timeout_ms", 8000) ?: 8000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    val body = connection.inputStream.bufferedReader().readText()
                    val repos = GitHubRepos.parse(JSONObject(body).optJSONArray("repos")?.toString().orEmpty())
                    Outcome.Listed(repos)
                }
                401, 403 -> Outcome.Refused(code, "the fleet refused this identity")
                in 300..399 -> Outcome.Blocked(code)
                else -> Outcome.Unreachable("the fleet answered HTTP $code")
            }
        } catch (t: Throwable) {
            Outcome.Unreachable(t.message ?: t.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }
}
