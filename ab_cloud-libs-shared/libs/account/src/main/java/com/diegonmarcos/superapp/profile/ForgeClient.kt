package com.diegonmarcos.superapp.profile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cloud Account redesign (spec sections 2, 5.3, 6): one file in the vault repo, read and written
 * through a forge's contents API. GitHub and Gitea answer the same shape at the same path
 * (`<api>/repos/<owner>/<repo>/contents/<path>`), so the only per-forge difference is the auth
 * header: GitHub `Authorization: Bearer <t>`, Gitea `Authorization: token <t>` — the scheme is
 * DECLARED per forge (ac_cloud-account build.json::ui.account.forges[].auth), never guessed.
 *
 * [put] carries the blob sha it read; a 409/422 (the file moved on the server) refetches the sha
 * ONCE and retries ONCE, then fails with a named reason. The token travels in a header only —
 * never in a URL, a result, an exception message or a log line.
 */
class ForgeClient(val forge: Forge, private val token: String, private val http: Http = UrlHttp) {

    enum class Auth(val scheme: String) {
        BEARER("Bearer"), TOKEN("token");
        companion object {
            fun of(s: String): Auth? = values().firstOrNull { it.name.equals(s, ignoreCase = true) }
        }
    }

    /** One declared forge. [repo] null = declared without a repo path (refused, never invented). */
    data class Forge(val id: String, val api: String, val repo: String?, val auth: Auth, val ways: List<String> = emptyList()) {
        fun contents(path: String): String {
            val r = repo ?: throw IllegalStateException("forge '$id' declares no repo")
            return api.trimEnd('/') + "/repos/" + r + "/contents/" + path.trimStart('/')
        }
    }

    /** The parsed `ui.account` declaration: the forges, in order, and the vault layout. */
    data class Decl(val forges: List<Forge>, val branch: String, val devicesDir: String, val secretsFile: String) {
        fun forge(id: String?): Forge? = forges.firstOrNull { it.id == id }
        /** The first forge that can actually be written (has a repo). */
        fun firstUsable(): Forge? = forges.firstOrNull { it.repo != null }
        fun devicePath(deviceId: String) = devicesDir.trimEnd('/') + "/" + deviceId + ".json"

        companion object {
            fun parse(o: JSONObject): Decl {
                val arr = o.optJSONArray("forges") ?: JSONArray()
                val forges = (0 until arr.length()).mapNotNull { i ->
                    val f = arr.optJSONObject(i) ?: return@mapNotNull null
                    val auth = Auth.of(f.optString("auth")) ?: return@mapNotNull null
                    val ways = f.optJSONArray("ways")?.let { w -> (0 until w.length()).map { w.optString(it) } }.orEmpty()
                    Forge(f.optString("id"), f.optString("api"), if (f.isNull("repo")) null else f.optString("repo").ifBlank { null }, auth, ways)
                }
                val v = o.optJSONObject("vault") ?: JSONObject()
                return Decl(forges, v.optString("branch", "main"), v.optString("devices_dir", "C_A1-configs/devices"),
                    v.optString("secrets_file", "C_A1-configs/profile-secrets.json"))
            }

            /** The host app's declaration (BuildConfig.UI_ACCOUNT_B64); empty when the host declares none. */
            fun fromBuildConfig(b64: String): Decl = parse(runCatching {
                JSONObject(String(java.util.Base64.getDecoder().decode(b64)).ifBlank { "{}" })
            }.getOrDefault(JSONObject()))
        }
    }

    data class File(val text: String, val sha: String)
    data class Entry(val name: String, val path: String, val sha: String)

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Failed(val status: Int, val reason: String) : Result<Nothing>()
    }

    /** One HTTP exchange: status and body. Tests hand in a fake remote. */
    fun interface Http {
        fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
    }

    /** The headers every request sends; the auth line is the forge's declared scheme. */
    fun headers(): Map<String, String> = buildMap {
        put("Authorization", forge.auth.scheme + " " + token)
        put("Accept", "application/json")
        if (forge.auth == Auth.BEARER) {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    private fun refQuery(ref: String) = "?ref=" + URLEncoder.encode(ref, "UTF-8")

    /** GET one file: its decoded text and blob sha; [Result.Failed] with status 404 when absent. */
    fun get(path: String, ref: String): Result<File> {
        if (token.isBlank()) return Result.Failed(0, "no credential for forge '${forge.id}'")
        if (forge.repo == null) return Result.Failed(0, "forge '${forge.id}' declares no repo")
        val (status, body) = exchange("GET", forge.contents(path) + refQuery(ref), null) ?: return Result.Failed(0, "network error")
        if (status != 200) return Result.Failed(status, reason(status, body, path))
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return Result.Failed(status, "not a file (no JSON object)")
        val sha = o.optString("sha")
        val enc = o.optString("content")
        if (sha.isBlank()) return Result.Failed(status, "not a file (no sha)")
        val size = o.optLong("size", 0L)
        // Over 1 MB the contents API sends no content (GitHub: "" with encoding "none"; Gitea caps
        // it too): fetch the bytes by the raw route, keeping the sha from this call.
        if (enc.isBlank() && size > 0) return raw(path, ref, sha, size)
        val text = runCatching { String(java.util.Base64.getMimeDecoder().decode(enc)) }.getOrElse { return Result.Failed(status, "undecodable content") }
        return Result.Ok(File(text, sha))
    }

    /** The large-file route: GitHub = the contents URL with the raw media type; Gitea = `/raw/<path>`. */
    private fun raw(path: String, ref: String, sha: String, size: Long): Result<File> {
        val (url, headers) = if (forge.auth == Auth.BEARER)
            (forge.contents(path) + refQuery(ref)) to (headers() + ("Accept" to "application/vnd.github.raw+json"))
        else
            (forge.api.trimEnd('/') + "/repos/" + forge.repo + "/raw/" + path.trimStart('/') + refQuery(ref)) to headers()
        val (status, body) = runCatching { http.call("GET", url, headers, null) }.getOrNull() ?: return Result.Failed(0, "network error (raw)")
        if (status != 200) return Result.Failed(status, "raw fetch of a $size-byte file: " + reason(status, body, path))
        return Result.Ok(File(body, sha))
    }

    /** GET a folder listing: name, path, sha per entry; an absent folder is an empty list. */
    fun list(dir: String, ref: String): Result<List<Entry>> {
        if (token.isBlank()) return Result.Failed(0, "no credential for forge '${forge.id}'")
        if (forge.repo == null) return Result.Failed(0, "forge '${forge.id}' declares no repo")
        val (status, body) = exchange("GET", forge.contents(dir) + refQuery(ref), null) ?: return Result.Failed(0, "network error")
        if (status == 404) return Result.Ok(emptyList())
        if (status != 200) return Result.Failed(status, reason(status, body, dir))
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return Result.Failed(status, "not a folder")
        return Result.Ok((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("type", "file") == "file" }
            .map { Entry(it.optString("name"), it.optString("path"), it.optString("sha")) })
    }

    /** The request [put] would send, without the content: target, method, body keys. For dry runs. */
    fun putShape(path: String, sha: String?, message: String, branch: String): JSONObject = JSONObject()
        .put("forge", forge.id).put("method", if (sha == null && forge.auth == Auth.TOKEN) "POST" else "PUT")
        .put("url", forge.contents(path)).put("auth_scheme", forge.auth.scheme)
        .put("body_keys", JSONArray(listOfNotNull("message", "content", "branch", sha?.let { "sha" })))
        .put("message", message).put("branch", branch)

    /**
     * Commit [text] at [path] on [branch]. [sha] is the blob the caller read (null = a new file).
     * Returns the new blob sha of the file. A stale sha (409/422) refetches and retries once.
     */
    fun put(path: String, text: String, sha: String?, message: String, branch: String): Result<String> {
        if (token.isBlank()) return Result.Failed(0, "no credential for forge '${forge.id}'")
        if (forge.repo == null) return Result.Failed(0, "forge '${forge.id}' declares no repo")
        val first = putOnce(path, text, sha, message, branch)
        if (first !is Result.Failed || (first.status != 409 && first.status != 422)) return first
        val fresh = when (val g = get(path, branch)) {
            is Result.Ok -> g.value.sha
            is Result.Failed -> if (g.status == 404) null else return Result.Failed(first.status, "stale sha, and the refetch failed: ${g.reason}")
        }
        return when (val again = putOnce(path, text, fresh, message, branch)) {
            is Result.Failed -> Result.Failed(again.status, "the file changed on the server twice in a row (${again.status}) — fetch again: ${again.reason}")
            else -> again
        }
    }

    /**
     * DELETE [path] on [branch] (the blob [sha] the caller read must still be current, same as
     * [put]). Profiles ▸ devices (spec 4.3) Delete, behind a confirm sheet on the caller's side —
     * this call itself never asks, so the gate is the UI's alone.
     */
    fun delete(path: String, sha: String, message: String, branch: String): Result<Unit> {
        if (token.isBlank()) return Result.Failed(0, "no credential for forge '${forge.id}'")
        if (forge.repo == null) return Result.Failed(0, "forge '${forge.id}' declares no repo")
        val body = JSONObject().put("message", message).put("sha", sha).put("branch", branch)
        val (status, resp) = exchange("DELETE", forge.contents(path), body.toString()) ?: return Result.Failed(0, "network error")
        if (status != 200) return Result.Failed(status, reason(status, resp, path))
        return Result.Ok(Unit)
    }

    private fun putOnce(path: String, text: String, sha: String?, message: String, branch: String): Result<String> {
        val body = JSONObject()
            .put("message", message)
            .put("content", java.util.Base64.getEncoder().encodeToString(text.toByteArray()))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        // Gitea creates with POST and updates with PUT; GitHub does both with PUT.
        val method = if (sha == null && forge.auth == Auth.TOKEN) "POST" else "PUT"
        val (status, resp) = exchange(method, forge.contents(path), body.toString()) ?: return Result.Failed(0, "network error")
        if (status != 200 && status != 201) return Result.Failed(status, reason(status, resp, path))
        val newSha = runCatching { JSONObject(resp).optJSONObject("content")?.optString("sha") }.getOrNull().orEmpty()
        return Result.Ok(newSha)
    }

    /** Never lets an exception message out: it could carry the request. */
    private fun exchange(method: String, url: String, body: String?): Pair<Int, String>? =
        runCatching { http.call(method, url, headers(), body) }.getOrNull()

    /** The server's own message, never the request (which held the token). */
    private fun reason(status: Int, body: String, path: String = ""): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("").take(200)
        return when (status) {
            401, 403 -> "the credential was refused by '${forge.id}' ($status${if (msg.isBlank()) "" else ": $msg"})"
            404 -> if (path.isBlank()) "not found on '${forge.id}'" else "$path not found on '${forge.id}'"
            409, 422 -> "the file changed on the server since it was read ($status)"
            else -> "HTTP $status${if (msg.isBlank()) "" else ": $msg"}"
        }
    }

    /** The phone's [Http]: HttpURLConnection, bounded. Blocking — call on IO. */
    object UrlHttp : Http {
        override fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = c.responseCode
                val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                return status to text
            } finally {
                c.disconnect()
            }
        }
    }
}
