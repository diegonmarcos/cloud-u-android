package com.diegonmarcos.cloudlib.auth

import android.util.Base64
import org.json.JSONObject

/**
 * THE ONE reader of the fleet's authentication declaration (#587):
 * `ab_cloud-libs-shared/build.json::auth`, baked whole by this module's
 * build.gradle into [BuildConfig.AUTH_B64] and decoded here exactly once.
 *
 * Nothing else in this module, and nothing in a consuming app, reads the blob:
 * the endpoints, the user slug, the vault route, the schema versions and the
 * provider list all come through these values. The parse functions take the
 * JSON text, not BuildConfig, so the JVM suite exercises the exact parser the
 * phone runs against the repository's own build.json.
 */
object AuthDeclaration {

    /** Where the per-user config artifact is, and the vault repo it is mirrored in. */
    data class ConfigSource(
        val baseUrl: String,
        val pathTemplate: String,
        val user: String,
        val connectTimeoutMs: Int,
        val readTimeoutMs: Int,
        val gitRepo: String,
        val gitPath: String,
        val gitRef: String,
    )

    /**
     * #629 ONE declared error-to-remedy row (`auth.grant_remedies`). [match] is a substring of the
     * provider's OWN error text; [remedy] is what the person must actually DO, because the cause
     * of the error this exists for is a switch in a provider's settings and no code can reach it;
     * [url] is where that switch lives, blank when there is no page to send anyone to.
     */
    data class GrantRemedy(val match: String, val remedy: String, val url: String)

    /**
     * #646 ONE RUNG of the declared git-auth chain (`auth.git_chain.providers`),
     * carrying its declared RANK in [position] — 0 is tried first.
     *
     * [kind] is the only thing a caller dispatches on, the same rule the sign-in
     * providers follow. [config] is the rung's own declaration verbatim, so a
     * rung gaining a field is an edit to the JSON and not to this class.
     */
    data class GitRung(
        val id: String,
        val label: String,
        val kind: String,
        val position: Int,
        val config: JSONObject,
    ) {
        /** Does the phone hold a GitHub credential of its own on this leg? */
        val holdsGithubCredential: Boolean get() = config.optBoolean("holds_github_credential", false)
    }

    data class Declaration(
        val configSource: ConfigSource,
        val vault: VaultConnect.Endpoints,
        val knownSchemaVersions: Set<Int>,
        val signIn: JSONObject,
        val grantRemedies: List<GrantRemedy> = emptyList(),
        val gitChain: List<GitRung> = emptyList(),
        /** #684 `auth.browser_mission`, `{package}` resolved by the bake; null when undeclared. */
        val browserMission: AuthMission.Contract? = null,
    )

    val current: Declaration by lazy { parse(decode(BuildConfig.AUTH_B64)) }

    val configSource: ConfigSource get() = current.configSource
    val vault: VaultConnect.Endpoints get() = current.vault
    val knownSchemaVersions: Set<Int> get() = current.knownSchemaVersions
    val grantRemedies: List<GrantRemedy> get() = current.grantRemedies

    /**
     * #646 The git-auth chain IN DECLARED ORDER — `auth.git_chain.order`, the one
     * ranking. The walker (libs:git-sync's GitAuthChain) tries these front to
     * back and nothing re-sorts them, so reordering the JSON reorders the real
     * attempts and dropping a rung stops it being tried.
     */
    val gitChain: List<GitRung> get() = current.gitChain

    /**
     * #684 The declared auth-mission contract for the fleet's browser — how a sign-in leaves
     * the small in-app dialog for a full-screen browser and comes back as an activity result.
     * Null means no mission is declared, and every caller must then say it fell back.
     */
    val browserMission: AuthMission.Contract? get() = current.browserMission

    /**
     * #629 The declared remedy for [message], or null when nothing is declared for it. First match
     * wins, case-insensitively; an unmatched message is the caller's to show unchanged. Pure, so
     * the JVM suite holds the mapping to the error texts it claims to cover.
     */
    fun remedyFor(message: String, declared: List<GrantRemedy> = grantRemedies): GrantRemedy? =
        if (message.isBlank()) null
        else declared.firstOrNull { it.match.isNotBlank() && message.contains(it.match, ignoreCase = true) }

    /**
     * [message], and the declared remedy after it when there is one. THE single wording rule:
     * an error whose fix is a provider-side setting must carry that setting, not just the code.
     */
    fun explain(message: String, declared: List<GrantRemedy> = grantRemedies): String =
        remedyFor(message, declared)?.let { r ->
            message + "\n\n" + r.remedy + (if (r.url.isBlank()) "" else "\n" + r.url)
        } ?: message

    fun decode(b64: String): String =
        if (b64.isBlank()) "" else runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")

    /** A blank or unparseable text yields an EMPTY declaration — no endpoint, no
     *  provider — so a build with a broken bake says "not configured" instead of
     *  inventing a route. */
    fun parse(text: String): Declaration {
        val root = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val cs = root.optJSONObject("config_source") ?: JSONObject()
        val git = cs.optJSONObject("git") ?: JSONObject()
        val vc = root.optJSONObject("vault_connect") ?: JSONObject()
        val versions = vc.optJSONArray("known_schema_versions")
        return Declaration(
            configSource = ConfigSource(
                baseUrl = cs.optString("base_url"),
                pathTemplate = cs.optString("path_template"),
                user = cs.optString("user"),
                connectTimeoutMs = cs.optInt("connect_timeout_ms", 8000),
                readTimeoutMs = cs.optInt("read_timeout_ms", 20000),
                gitRepo = git.optString("repo"),
                gitPath = git.optString("path"),
                gitRef = git.optString("ref", "main"),
            ),
            vault = VaultConnect.Endpoints(
                baseUrl = vc.optString("base_url"),
                startPath = vc.optString("start_path"),
                fetchPath = vc.optString("fetch_path"),
                connectTimeoutMs = vc.optInt("connect_timeout_ms", 8000),
                readTimeoutMs = vc.optInt("read_timeout_ms", 30000),
            ),
            knownSchemaVersions = (0 until (versions?.length() ?: 0)).mapNotNull { versions!!.optInt(it, -1).takeIf { v -> v >= 0 } }.toSet(),
            signIn = root.optJSONObject("sign_in") ?: JSONObject(),
            grantRemedies = (root.optJSONArray("grant_remedies") ?: org.json.JSONArray()).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                    val match = o.optString("match"); if (match.isBlank()) null else GrantRemedy(match, o.optString("remedy"), o.optString("url"))
                }
            },
            gitChain = gitChain(root.optJSONObject("git_chain")),
            browserMission = AuthMission.parse(root.optJSONObject("browser_mission")),
        )
    }

    /**
     * #646 The declared git-auth chain, in `order`, CLOSED AT BOTH ENDS: a rung
     * ranked in `order` with no `providers` entry would be an attempt with no
     * endpoint, and a `providers` entry missing from `order` would be a rung with
     * no rank that therefore never runs. Either is a declaration that only half
     * exists — the #627 rule libs:appstore's resolver already enforces — so both
     * are dropped rather than guessed at, and the surviving chain is exactly the
     * rungs that are completely declared.
     *
     * `order` alone decides the ranking. There is no sort here and no default
     * ordering to fall back on: an empty or absent block yields an EMPTY chain,
     * which reads as "no git auth is declared" rather than inventing a rung.
     */
    fun gitChain(block: JSONObject?): List<GitRung> {
        val root = block ?: return emptyList()
        val order = root.optJSONArray("order") ?: return emptyList()
        val providers = root.optJSONObject("providers") ?: return emptyList()
        return (0 until order.length()).mapNotNull { i ->
            val id = order.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val declared = providers.optJSONObject(id) ?: return@mapNotNull null
            val kind = declared.optString("kind").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GitRung(
                id = id,
                label = declared.optString("label").ifBlank { id },
                kind = kind,
                position = i,
                config = declared,
            )
        }
    }
}
