package com.diegonmarcos.clouddrive.configs

import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.sync.FleetGit
import com.diegonmarcos.cloudlib.auth.AuthDeclaration
import com.diegonmarcos.cloudlib.gitsync.GitAuthChain
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * #646 WHERE THE DECLARED GIT-AUTH CHAIN IS WIRED UP — and the only place in this
 * app that knows what a rung DOES.
 *
 * The RANKING is not here. It comes from `AuthDeclaration.gitChain`, which reads
 * `ab_cloud-libs-shared/build.json::auth.git_chain.order` and hands the rungs back
 * in declared order; [rungs] maps that list straight through with no sort, no
 * filter and no reversal, and [GitAuthChain.resolve] walks it front to back. So
 * reordering the JSON reorders the real attempts and dropping a rung from the JSON
 * stops it being tried — with nothing in this file to edit.
 *
 * THE CREDENTIAL ID IS NOT HERE EITHER. It is
 * `Declarations.authCredentialIds["git"]`, the SAME declared id the vault import
 * writes under (#629's `credential_id`), so whichever rung answers, the token
 * lands in the one store under the one id. A literal id in this file would be the
 * second id that splits the credential in two.
 *
 * THE VAULT IS STILL PRIMARY and is NOT a rung: a credential already on the phone
 * needs no negotiation at all, so [DriveAuthApply.vaultGitToken] is consulted
 * before any of this runs. This chain is what happens when there is nothing there.
 */
object DriveGitChain {

    const val RUNG_FLEET = "fleet_proxy"

    /**
     * #653 THE GITHUB RUNG NO LONGER MINTS ANYTHING. Its kind used to be
     * `gh_device_flow` and it drove an OAuth device grant whose whole user
     * interface was a code to read and a URL to type it at — the UX the owner
     * rejected three times. It is now `gh_on_device`: the rung answers with a
     * GitHub credential that is ALREADY on this phone (the #566 vault import's,
     * under the one declared id) and answers with nothing when there is none.
     *
     * That keeps the owner's ranking intact — "if our servers are down we can do
     * gh, if not we can use our flow" — without the phone ever OBTAINING a GitHub
     * credential interactively. Obtaining one is the fleet's job now, and on the
     * fleet rung the phone does not hold one at all.
     */
    const val RUNG_GITHUB = "gh_on_device"

    /** The declared id every rung writes under. Declared, never typed here. */
    fun credentialId(): String = Declarations.authCredentialIds["git"].orEmpty()

    /**
     * The chain, in DECLARED ORDER, with an implementation for each declared
     * KIND. Dispatch is on `kind` — the same rule the sign-in providers follow —
     * so a second fleet endpoint could be declared under a new id without a new
     * branch here.
     *
     * A declared rung whose kind this build does not implement keeps its declared
     * POSITION and reports itself as unavailable rather than vanishing from the
     * chain: #647 may not be built yet, and "not built" must read as a
     * fall-through, not as a rung that was never declared.
     */
    fun rungs(
        ctx: Context,
        declared: List<AuthDeclaration.GitRung> = AuthDeclaration.gitChain,
        session: String = "",
    ): List<GitAuthChain.Rung> = declared.map { rung ->
        GitAuthChain.Rung(rung.id, rung.label) {
            when (rung.kind) {
                RUNG_FLEET -> fleet(rung.config, session)
                RUNG_GITHUB -> onDevice(ctx)
                else -> GitAuthChain.Answer.NoImplementation("kind '${rung.kind}' is not implemented")
            }
        }
    }

    /**
     * RUNG 1 — our own Authelia-fronted proxy (#647). Ranked first because on this
     * leg the PHONE HOLDS NO GITHUB CREDENTIAL AT ALL: it presents the fleet
     * session the owner's ordinary browser login earned, and git-proxy-api talks to
     * GitHub with a token that never leaves the server. Nothing is minted for the
     * device here, which is why this rung answers [GitAuthChain.Answer.Served]
     * rather than a credential.
     *
     * EVERY WAY THIS CAN FAIL IS A FALL-THROUGH. Not deployed, our servers down,
     * this network unable to see them, no fleet sign-in on this phone yet — a phone
     * cannot tell those apart and they all mean "try the next rung". Only an
     * explicit refusal (401/403) is reported as DECLINED, because that one says
     * something different, and says it about a service that is demonstrably UP: the
     * service validates the credential ITSELF, independent of the edge.
     */
    private fun fleet(config: JSONObject, session: String): GitAuthChain.Answer {
        val url = config.optString("repos_url")
        if (url.isBlank()) return GitAuthChain.Answer.NoImplementation("no repos_url is declared")
        // No session means we cannot even ask. That is indistinguishable from the
        // fleet being unreachable, and must behave identically.
        if (session.isBlank()) return GitAuthChain.Answer.Unreachable("no fleet sign-in on this phone yet")
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = config.optInt("connect_timeout_ms", 4000)
            connection.readTimeout = config.optInt("read_timeout_ms", 8000)
            connection.setRequestProperty("Accept", "application/json")
            // #653 THE HEADER IS DECLARED, and it is NOT Authorization. authelia_web
            // yields a session cookie, not a bearer, so "Bearer $x" would present an
            // empty credential and the refusal would read as a fall-through.
            connection.setRequestProperty(FleetGit.sessionHeader(), session)
            when (val code = connection.responseCode) {
                // #653 ANSWERED, AND THE PHONE HOLDS NOTHING. The body is a repo
                // PROJECTION, never a credential, so this rung is Served and not
                // Credential — nothing is written to the credential store, and the
                // caller lists from the proxy rather than from GitHub.
                in 200..299 -> GitAuthChain.Answer.Served("listing served by the fleet")
                401, 403 -> GitAuthChain.Answer.Declined("the fleet refused this identity (HTTP $code)")
                // #653 A REDIRECT IS NOT REACHABILITY. Caddy's Authelia matcher
                // answers 3xx for ANY path under the proxy's prefix BEFORE it dials
                // the upstream — measured identical for a route that does not exist,
                // with the container stopped. So a 3xx says only "we did not get
                // past the edge", which is a fall-through like any other. Redirects
                // are not followed, precisely so this cannot be mistaken for a 200
                // from the portal's login page.
                in 300..399 -> GitAuthChain.Answer.Unreachable(
                    "the edge redirected (HTTP $code) without reaching the service; this sign-in did not satisfy the gate",
                )
                else -> GitAuthChain.Answer.Unreachable("the fleet answered HTTP $code")
            }
        } catch (t: Throwable) {
            GitAuthChain.Answer.Unreachable(t.message ?: t.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * RUNG 2 — direct to GitHub with a credential that is ALREADY ON THIS PHONE.
     *
     * #653 THERE IS NO GRANT HERE ANY MORE. This rung used to run an OAuth device
     * grant: it printed a short code, printed a URL, and polled. That is the
     * "code to copy, URL to open" ceremony the owner rejected, and it is deleted
     * rather than restyled — no code, no verification URI, no poll loop, and no
     * phase machine to carry them to a screen.
     *
     * What is left is the honest fallback the owner asked for: "if our servers are
     * down we can do gh". gh and gix can only use a credential, never mint one, so
     * this rung reads the ONE store under the ONE declared id — the id the #566
     * vault import writes — and answers with it. NOTHING is obtained here, so this
     * rung can never ask the owner to type anything.
     *
     * NO CREDENTIAL IS A DECLINE, not an error: the fleet rung above it needs no
     * GitHub credential at all, so an empty store is a perfectly normal state and
     * the chain says so in words instead of opening a browser.
     */
    private fun onDevice(ctx: Context): GitAuthChain.Answer {
        if (credentialId().isBlank()) return GitAuthChain.Answer.NoImplementation("no credential id is declared")
        val held = DriveAuthApply.vaultGitToken(ctx)
        return if (held.isNotBlank()) GitAuthChain.Answer.Credential(held)
        else GitAuthChain.Answer.Declined("no GitHub credential is on this device; the vault import delivers one")
    }

    /**
     * Walk the declared chain and, if a rung answers, file the credential in the
     * ONE store under the ONE declared id. Blocking; callers run it on IO.
     *
     * The returned outcome carries every step it took, so the page can say which
     * provider answered and why the ones before it were skipped. It carries the
     * token too, for the caller to use — and its own `toString` is redacted, so
     * holding it in UI state cannot leak the secret into a log.
     */
    fun resolve(
        ctx: Context,
        session: String = "",
    ): GitAuthChain.Outcome = GitAuthChain.resolve(
        chain = rungs(ctx = ctx, session = session),
        store = GitCredentialStore(ctx),
        credentialId = credentialId(),
    )

    /**
     * The declared chain as a sentence, for the page to show BEFORE anything is
     * attempted: "Cloud fleet, then GitHub". Built from the declaration, so the
     * page's own description of the order changes when the order does — a page
     * that describes the chain from a hardcoded string is a page that will
     * eventually describe it wrongly.
     */
    fun declaredOrder(declared: List<AuthDeclaration.GitRung> = AuthDeclaration.gitChain): String =
        declared.joinToString(", then ") { it.label }
}
