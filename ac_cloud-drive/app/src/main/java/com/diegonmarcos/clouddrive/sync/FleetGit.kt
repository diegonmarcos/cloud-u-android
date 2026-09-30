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

    /**
     * The declared rung this client serves. #669 there are now TWO rungs of this
     * kind — gitea and git-proxy-api, both Authelia-fronted listings — so a caller
     * that knows WHICH rung answered passes its declared id, and the request is
     * built from THAT rung's declaration. With no id (the pre-chain surfaces:
     * which provider to offer, which health route to probe), the FIRST-ranked
     * rung of the kind speaks for the family, which is what the ranking means.
     */
    private fun rung(id: String = ""): AuthDeclaration.GitRung? =
        AuthDeclaration.gitChain.firstOrNull {
            it.kind == DriveGitChain.RUNG_FLEET && (id.isBlank() || it.id == id)
        }

    /** What the declaration says the fleet serves. Empty when no fleet rung is declared. */
    fun reposUrl(): String = rung()?.config?.optString("repos_url").orEmpty()

    /** The pre-auth liveness route — the ONE probe whose 200 is real evidence. */
    fun healthUrl(): String = rung()?.config?.optString("health_url").orEmpty()

    /**
     * The declared sign-in provider whose session authorises this rung — `authelia_web`,
     * the ordinary browser login. Declared, so the page offers the right way without
     * naming a provider in Kotlin.
     */
    fun sessionProvider(): String = rung()?.config?.optString("session_provider").orEmpty()

    /**
     * Which header carries that session. DECLARED, because it is not `Authorization`:
     * `authelia_web` yields a cookie (libs:auth's WebAuthDialog sets `bearer = ""` and
     * delivers the session through onWebSession), and the edge accepts the interactive
     * session as its non-bearer fallback. Sending `Authorization: Bearer ` here would
     * present an EMPTY credential and read the refusal as a fall-through.
     */
    fun sessionHeader(): String = rung()?.config?.optString("session_header").ifBlankOrNull("Cookie")

    private fun String?.ifBlankOrNull(fallback: String): String =
        if (this.isNullOrBlank()) fallback else this

    /**
     * #669 THE CLONE URL FOR A REPOSITORY THIS RUNG LISTED. Which URL, and in what
     * order candidates are considered, is DECLARED on the rung (`clone_url` +
     * `clone_url_order`), never typed here: `declared` resolves the rung's template
     * against the LISTING ITEM'S owner and name (the public edge, which the same
     * session that listed can satisfy); `listed` is the item's own clone_url
     * (gitea's mesh-internal projection, reachable only on WireGuard). The FIRST
     * resolvable entry wins and there is no silent fall-through past it — a clone
     * that fails says so in words instead of quietly dialing another host.
     */
    fun cloneUrl(rungId: String, owner: String, name: String, listed: String): String {
        val config = rung(rungId)?.config
        val order = config?.optJSONArray("clone_url_order")
            ?.let { array -> (0 until array.length()).map { array.optString(it) } }
            .orEmpty()
        return cloneUrlFrom(config?.optString("clone_url").orEmpty(), order, owner, name, listed)
    }

    /**
     * #684 WHETHER A REPOSITORY THIS RUNG LISTED CLONES ON THE SESSION. Only a rung that
     * DECLARES a clone_url template does: that template is the fleet edge the same session
     * satisfies. A fleet_proxy rung WITHOUT one (git-proxy-api) lists GitHub repositories
     * whose own clone_url points at github.com — dialing that with the Authelia cookie
     * attached would carry the fleet session to a third party, so such a listing clones
     * anonymously when public and says plainly what a private row needs. Declared, so a
     * rung gains the session leg by declaring its edge and never by a Kotlin edit.
     */
    fun clonesOnSession(rungId: String): Boolean =
        rung(rungId)?.config?.optString("clone_url").orEmpty().isNotBlank()

    internal const val CLONE_SOURCE_DECLARED = "declared"
    internal const val CLONE_SOURCE_LISTED = "listed"

    /** The pure rule behind [cloneUrl], exercised on the JVM against both orders. */
    internal fun cloneUrlFrom(template: String, order: List<String>, owner: String, name: String, listed: String): String =
        order.ifEmpty { listOf(CLONE_SOURCE_DECLARED, CLONE_SOURCE_LISTED) }.firstNotNullOfOrNull { source ->
            when (source) {
                CLONE_SOURCE_DECLARED ->
                    if (template.isBlank() || owner.isBlank() || name.isBlank()) null
                    else template.replace("{owner}", owner).replace("{name}", name)
                CLONE_SOURCE_LISTED -> listed.ifBlank { null }
                else -> null
            }
        }.orEmpty()

    /**
     * #669 EVERY CLONE FAILURE ON THE FLEET LEG NAMES ITS NEXT STEP. JGit reports a
     * transport failure as prose, and the two failures that matter here read alike
     * to a person: a REDIRECT means the Authelia gate answered instead of gitea (the
     * session did not satisfy it — sign in), and a 401/403 means gitea itself
     * refused (a different fact, about a service that is UP). Anything else passes
     * through unrewritten: an invented explanation is worse than a raw one.
     */
    fun explainCloneFailure(why: String): String = when {
        listOf("401", "403", "not authorized", "authentication not supported")
            .any { why.contains(it, ignoreCase = true) } ->
            "the fleet refused this clone ($why) — the fleet git itself said no to this identity; it is up and past the gate"
        listOf("302", "303", "307", "redirect", "invalid advertisement", "expected pkt-line")
            .any { why.contains(it, ignoreCase = true) } ->
            "the gate answered instead of the fleet git ($why) — this session did not satisfy it; the Authelia sign-in on this page starts a fresh one"
        else -> why
    }

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
     * #684 THE PAGE SIZE the listing URL asks for, off the declared query (`limit=`). It is a
     * PAGE size, not a cap: a full page means there may be more, so pagination follows until a
     * short one. Absent, one page of unknown size is assumed complete. Pure, JVM-tested.
     */
    internal fun pageLimit(base: String): Int =
        Regex("[?&]limit=(\\d+)").find(base)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE

    /** #684 [base] with `page=[page]` set, replacing any existing one. Pure, JVM-tested. */
    internal fun pagedUrl(base: String, page: Int): String {
        val stripped = base.replace(Regex("([?&])page=\\d+"), "$1").replace(Regex("[?&]$"), "")
        val sep = if (stripped.contains('?')) "&" else "?"
        return stripped + sep + "page=" + page.coerceAtLeast(1)
    }

    /** #684 a page is the LAST when it came back shorter than the page size. Pure, JVM-tested. */
    internal fun isLastPage(count: Int, limit: Int): Boolean = count < limit

    private sealed class Page {
        class Ok(val repos: List<GitHubRepos.Repo>) : Page()
        class Stop(val outcome: Outcome) : Page()
    }

    /**
     * #684 LIST THE ACCOUNT'S REPOSITORIES, EVERY PAGE (Owner Amendment 2, rule 2). Presenting
     * only the session, following pages until a short one — gitea's /repos/search paginates and
     * the declared `limit` is a page size, never a cap. The two ways' lists are independent facts
     * and are NEVER merged: this returns exactly what THIS rung's own API answered.
     *
     * A non-200 on the FIRST page is the leg's verdict (Refused / Blocked / Unreachable); a
     * non-200 on a later page ends the walk and returns what was already gathered rather than
     * discarding a good prefix. Reuses [GitHubRepos.parse] — gitea keeps GitHub's field shape.
     */
    fun repos(session: String, rungId: String = ""): Outcome {
        val config = rung(rungId)?.config
        val base = config?.optString("repos_url").orEmpty()
        if (base.isBlank()) return Outcome.Unreachable("no fleet repos_url is declared")
        if (session.isBlank()) return Outcome.Unreachable("no fleet session on this phone")
        val field = config?.optString("repos_field").orEmpty().ifBlank { "repos" }
        val limit = pageLimit(base)
        val maxPages = config?.optInt("max_pages", 20)?.coerceAtLeast(1) ?: 20
        val all = mutableListOf<GitHubRepos.Repo>()
        var page = 1
        while (page <= maxPages) {
            when (val r = onePage(pagedUrl(base, page), session, config, field)) {
                is Page.Ok -> {
                    all += r.repos
                    if (isLastPage(r.repos.size, limit)) return Outcome.Listed(all)
                    page++
                }
                is Page.Stop -> return if (all.isEmpty()) r.outcome else Outcome.Listed(all)
            }
        }
        return Outcome.Listed(all)
    }

    private fun onePage(url: String, session: String, config: JSONObject?, field: String): Page {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = config?.optInt("connect_timeout_ms", 4000) ?: 4000
            connection.readTimeout = config?.optInt("read_timeout_ms", 8000) ?: 8000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(sessionHeader(), session)
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    val body = connection.inputStream.bufferedReader().readText()
                    // #669 WHICH KEY CARRIES THE ARRAY IS DECLARED (`repos_field`):
                    // git-proxy-api's projection says "repos", gitea's search says
                    // "data". The item fields parse with the one existing parser —
                    // gitea keeps GitHub's field shape deliberately.
                    Page.Ok(GitHubRepos.parse(JSONObject(body).optJSONArray(field)?.toString().orEmpty()))
                }
                401, 403 -> Page.Stop(Outcome.Refused(code, "the fleet refused this identity"))
                in 300..399 -> Page.Stop(Outcome.Blocked(code))
                else -> Page.Stop(Outcome.Unreachable("the fleet answered HTTP $code"))
            }
        } catch (t: Throwable) {
            Page.Stop(Outcome.Unreachable(t.message ?: t.javaClass.simpleName))
        } finally {
            connection.disconnect()
        }
    }
}
