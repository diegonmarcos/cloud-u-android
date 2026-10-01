package com.diegonmarcos.clouddrive.configs

import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.sync.FleetGit
import com.diegonmarcos.clouddrive.sync.GhEngine
import com.diegonmarcos.clouddrive.sync.GitHubRepos
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
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
     * The GitHub rung: gh on this device. #653 took the fleet's OWN device grant
     * out of it (no device_code_url, no client id of ours), and the chain step
     * [onDevice] still answers only with a credential already on the phone (the
     * #566 vault import's, under the one declared id).
     *
     * #689 the GitHub CARD on the page is gh itself: gh's own `auth login` (its
     * device flow, against the client id GitHub CLI compiles into its binary),
     * `gh repo list`, and a clone on the credential gh holds. The rung declares
     * only where gh points ([ghHost]) and how much one listing asks for
     * ([ghListLimit]); it declares no OAuth-App client, and may not.
     */
    const val RUNG_GITHUB = "gh_on_device"

    /** The declared id every rung writes under. Declared, never typed here. */
    fun credentialId(): String = Declarations.authCredentialIds["git"].orEmpty()

    /** #684 the rung of [kind], in declared order — the page resolves a way's rung through this. */
    fun rung(id: String): AuthDeclaration.GitRung? = AuthDeclaration.gitChain.firstOrNull { it.id == id }

    /** #689 the host the gh leg signs in to, lists from and asks a credential for; blank when undeclared. */
    fun ghHost(): String = githubRung()?.config?.optString("host").orEmpty()

    /** #689 how many repositories one `gh repo list` asks for; 0 when undeclared. */
    fun ghListLimit(): Int = githubRung()?.config?.optInt("list_limit", 0) ?: 0

    private fun githubRung(): AuthDeclaration.GitRung? = AuthDeclaration.gitChain.firstOrNull { it.kind == RUNG_GITHUB }

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
        github: () -> GitAuthChain.Answer = { onDevice(ctx) },
    ): List<GitAuthChain.Rung> = declared.map { rung ->
        GitAuthChain.Rung(rung.id, rung.label) {
            when (rung.kind) {
                RUNG_FLEET -> fleet(rung.config, session)
                RUNG_GITHUB -> github()
                else -> GitAuthChain.Answer.NoImplementation("kind '${rung.kind}' is not implemented")
            }
        }
    }

    /**
     * THE FLEET-SHAPED RUNGS — every declared endpoint of kind `fleet_proxy`,
     * each attempted with ITS OWN declared config. #669 there are two: the
     * fleet's own gitea (ranked first — our git server, no third-party involved
     * at all) and git-proxy-api (#647). On BOTH legs the PHONE HOLDS NO GITHUB
     * CREDENTIAL AT ALL: it presents the fleet session the owner's ordinary
     * browser login earned, and the service answers with a listing — gitea from
     * its own store, git-proxy-api with a token that never leaves the server.
     * Nothing is minted for the device here, which is why these rungs answer
     * [GitAuthChain.Answer.Served] rather than a credential.
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
        // fleet being unreachable, and must behave identically. #669 the words name
        // the NEXT STEP, not just the lack: a failure that offers nothing is the
        // dead end the owner spent three days in.
        if (session.isBlank()) return GitAuthChain.Answer.Unreachable("no fleet sign-in on this phone yet — the Authelia sign-in below starts one")
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
     * #653 THERE IS NO GRANT HERE. Nothing is obtained by this rung, so it can never ask
     * the owner to type anything: the code-and-URL ceremony the owner rejected is deleted,
     * not restyled.
     *
     * #735 gh's SIGN-IN NOW REACHES THE CHAIN. Measured on the phone: gh was signed in and
     * listed 34 repositories on the card, while this rung declined "no GitHub credential is
     * on this device" — because it only ever read the store slot the vault import fills,
     * and gh's credential lives in the gh ENGINE (its own hosts.yml), which nothing here
     * asked. So the clone path and the seed, which both take their credential from this
     * chain, had none. The engine already answers git-credential requests for the declared
     * host over its contract (GhBackendService.CREDENTIAL); this rung asks it FIRST, then
     * falls back to the credential filed under the declared id (the vault import, or a PAT
     * the sign-in delivered). Whatever answers is filed by [GitAuthChain.resolve]'s ONE
     * setSecret under the ONE id — this rung writes nothing itself.
     *
     * NO CREDENTIAL IS A DECLINE, not an error: the fleet rungs above need no GitHub
     * credential at all, so an empty store is a normal state and the chain says so in words.
     */
    private fun onDevice(ctx: Context): GitAuthChain.Answer = githubAnswer(
        credentialId = credentialId(),
        fromGh = { ghHost().takeIf { it.isNotBlank() }?.let { host -> runCatching { GhEngine(ctx).credential(host) }.getOrNull()?.secret } },
        held = { DriveAuthApply.vaultGitToken(ctx) },
    )

    /**
     * #735 THE GITHUB RUNG'S RULE, pure so DriveGitChainTest drives it with a fake engine:
     * gh's live credential first — a signed-in gh is the freshest truth, and a filed copy
     * can be one gh has since replaced — then the one filed under the declared id.
     */
    fun githubAnswer(credentialId: String, fromGh: () -> String?, held: () -> String): GitAuthChain.Answer {
        if (credentialId.isBlank()) return GitAuthChain.Answer.NoImplementation("no credential id is declared")
        fromGh()?.takeIf { it.isNotBlank() }?.let { return GitAuthChain.Answer.Credential(it) }
        val filed = held()
        return if (filed.isNotBlank()) GitAuthChain.Answer.Credential(filed)
        else GitAuthChain.Answer.Declined("gh is not signed in and no GitHub credential is on this device; sign in on the GitHub card, or the vault import delivers one")
    }

    /** #735 the credential a GitHub-hosted clone presents: the github rung's own answer, whichever rung ranks first. */
    fun githubCredential(ctx: Context): GitAuthChain.Answer = onDevice(ctx)

    /** How one clone authenticates: (kind, secret). Its toString never prints the secret. */
    data class CloneAuth(val kind: String, val secret: String) {
        override fun toString(): String = "CloneAuth(kind=$kind, secret=<redacted>)"
    }

    /**
     * #735 THE CLONE'S CREDENTIAL, pure. A fleet-listed URL rides the fleet SESSION and never a
     * GitHub token (the session is the only thing that passes that gate, and the token must not
     * travel to a host that did not ask for it); any other URL presents what the github rung
     * answered over https (asked only then), and nothing when it answered nothing.
     */
    fun cloneAuth(viaFleet: Boolean, github: () -> GitAuthChain.Answer): CloneAuth {
        if (viaFleet) return CloneAuth(GitSyncCoordinator.AUTH_SESSION, "")
        val answer = github()
        return if (answer is GitAuthChain.Answer.Credential) CloneAuth(GitSyncCoordinator.AUTH_HTTPS, answer.token)
        else CloneAuth(GitSyncCoordinator.AUTH_NONE, "")
    }

    /**
     * #735 THE LISTING OF RUNG [rungId], dispatched on its declared KIND. The github rung lists
     * through gh's own `gh repo list` in the engine — the page's GitHub card does exactly this —
     * and never through FleetGit, which needs a fleet `repos_url` the github rung does not and
     * may not declare (that was the "no fleet repos_url is declared" the phone answered).
     */
    fun repos(ctx: Context, rungId: String, session: String): FleetGit.Outcome =
        if (rung(rungId)?.kind == RUNG_GITHUB) ghRepos(GhEngine(ctx)) else FleetGit.repos(session, rungId)

    private fun ghRepos(engine: GhEngine): FleetGit.Outcome {
        val host = ghHost()
        val limit = ghListLimit()
        if (host.isBlank() || limit <= 0) return FleetGit.Outcome.Unreachable("the github rung declares no host or list_limit")
        val r = engine.repoList(limit, GitHubRepos.GH_FIELDS)
        if (!r.ok) return FleetGit.Outcome.Unreachable("gh repo list exited ${r.exitCode}: ${GhEngine.why(r.output)}")
        return GitHubRepos.parseGh(r.output)?.let { FleetGit.Outcome.Listed(it) }
            ?: FleetGit.Outcome.Unreachable("gh repo list answered something that is not a repository list")
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
