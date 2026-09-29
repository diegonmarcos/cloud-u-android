package com.diegonmarcos.clouddrive.configs

import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.cloudlib.auth.AuthDeclaration
import com.diegonmarcos.cloudlib.gh.GhDeviceLogin
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
    const val RUNG_GITHUB = "gh_device_flow"

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
        declared: List<AuthDeclaration.GitRung> = AuthDeclaration.gitChain,
        bearer: String = "",
        onPhase: (GhDeviceLogin.Phase) -> Unit = {},
    ): List<GitAuthChain.Rung> = declared.map { rung ->
        GitAuthChain.Rung(rung.id, rung.label) {
            when (rung.kind) {
                RUNG_FLEET -> fleet(rung.config, bearer)
                RUNG_GITHUB -> github(rung.config, onPhase)
                else -> GitAuthChain.Answer.NoImplementation("kind '${rung.kind}' is not implemented")
            }
        }
    }

    /**
     * RUNG 1 — our own Authelia-fronted proxy (#647). Preferred whenever
     * reachable, because on this leg the PHONE HOLDS NO GITHUB CREDENTIAL: the
     * server mints it.
     *
     * EVERY WAY THIS CAN FAIL IS A FALL-THROUGH. Not built yet (#647 is in flight
     * in cloud-infra), our servers down, this network unable to see them, no
     * bearer on this phone to authenticate with — a phone cannot tell those apart
     * and they all mean "try GitHub". Only an explicit refusal (401/403) is
     * reported as DECLINED, because that one says something different: we were
     * reached, and told no.
     */
    private fun fleet(config: JSONObject, bearer: String): GitAuthChain.Answer {
        val url = config.optString("token_url")
        if (url.isBlank()) return GitAuthChain.Answer.NoImplementation("no token_url is declared")
        // No bearer means we cannot even ask. That is indistinguishable from the
        // fleet being unreachable, and must behave identically.
        if (bearer.isBlank()) return GitAuthChain.Answer.Unreachable("no fleet credential on this phone")
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = config.optInt("connect_timeout_ms", 4000)
            connection.readTimeout = config.optInt("read_timeout_ms", 8000)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    val body = connection.inputStream.bufferedReader().readText()
                    val token = JSONObject(body).optString("token").ifBlank { JSONObject(body).optString("github_token") }
                    if (token.isNotBlank()) GitAuthChain.Answer.Credential(token)
                    else GitAuthChain.Answer.Unreachable("the fleet answered without a credential")
                }
                401, 403 -> GitAuthChain.Answer.Declined("the fleet refused this identity (HTTP $code)")
                // 404 is the #647-not-built-yet case, and it is a fall-through.
                else -> GitAuthChain.Answer.Unreachable("the fleet answered HTTP $code")
            }
        } catch (t: Throwable) {
            GitAuthChain.Answer.Unreachable(t.message ?: t.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * RUNG 2 — direct to GitHub, through the device grant against GITHUB'S OWN
     * app. Works when our servers are down, and needs nothing registered on our
     * side. [onPhase] is how the short code reaches the screen; the token never
     * travels that way.
     */
    private fun github(config: JSONObject, onPhase: (GhDeviceLogin.Phase) -> Unit): GitAuthChain.Answer {
        val scope = config.optString("scope").ifBlank { GhDeviceLogin.SCOPE }
        return when (val phase = GhDeviceLogin.login(scope = scope, onPhase = onPhase)) {
            is GhDeviceLogin.Phase.Granted -> GitAuthChain.Answer.Credential(phase.token)
            is GhDeviceLogin.Phase.Failed -> GitAuthChain.Answer.Declined(phase.message)
            // login() only ever returns Granted or Failed; anything else means the
            // flow ended without deciding, which is a non-answer, not a success.
            else -> GitAuthChain.Answer.Declined("the grant ended without a credential")
        }
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
        bearer: String = "",
        onPhase: (GhDeviceLogin.Phase) -> Unit = {},
    ): GitAuthChain.Outcome = GitAuthChain.resolve(
        chain = rungs(bearer = bearer, onPhase = onPhase),
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
