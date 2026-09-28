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

    data class Declaration(
        val configSource: ConfigSource,
        val vault: VaultConnect.Endpoints,
        val knownSchemaVersions: Set<Int>,
        val signIn: JSONObject,
        val grantRemedies: List<GrantRemedy> = emptyList(),
    )

    val current: Declaration by lazy { parse(decode(BuildConfig.AUTH_B64)) }

    val configSource: ConfigSource get() = current.configSource
    val vault: VaultConnect.Endpoints get() = current.vault
    val knownSchemaVersions: Set<Int> get() = current.knownSchemaVersions
    val grantRemedies: List<GrantRemedy> get() = current.grantRemedies

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
        )
    }
}
