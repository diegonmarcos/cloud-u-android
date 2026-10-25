package com.diegonmarcos.superapp.fleetendpoints

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE DRIVE / GIT CREDENTIAL CONTRACT: what Cloud Account (the owner of every credential) hands to Cloud Drive and the other
 * apps over its accountdata provider, method [METHOD], JSON under [KEY_JSON]:
 *
 *   {"v":1,
 *    "rclone":[{"name","type","auth":"oauth|service_account","scope"}],  "rclone_config":"<rclone.conf>"?,
 *    "ssh":{"public","fingerprint","hosts":[{"host","hostname","user","port","identity_file"}],"config":"<ssh_config>","private":"<key>"?},
 *    "mounts":[{"name","kind":"rclone|sshfs","remote","host","user","port","path","mountpoint"}],  "mount_secrets":{"<name>":{…}}?}
 *
 * Default deny, per layer: [GRANT_READ] (`drive.credentials`) shows the public layer only (remote names and types, the
 * PUBLIC key, host entries, mount definitions); a secret layer needs its own grant, so a package sees exactly what the
 * owner granted in Secrets: [GRANT_RCLONE] the generated rclone.conf (tokens, service accounts), [GRANT_SSH_PRIVATE] the
 * private key, [GRANT_MOUNTS] the sshfs/FUSE passwords. Nothing here is written by a caller.
 */
object DriveCredentialsContract {
    const val VERSION = 1
    const val METHOD = "drive_credentials"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_JSON = "drive_json"
    const val GRANT_READ = "drive.credentials"
    const val GRANT_RCLONE = "drive.rclone"
    const val GRANT_SSH_PRIVATE = "ssh.private"
    const val GRANT_MOUNTS = "drive.mounts"
    /** The one write door: a granted package (Cloud Drive's `rclone config` flow) stores or removes a remote or a mount; the ssh key is never written by a caller. */
    const val GRANT_WRITE = "drive.write"
    val GRANTS = listOf(GRANT_READ, GRANT_RCLONE, GRANT_SSH_PRIVATE, GRANT_MOUNTS, GRANT_WRITE)

    /**
     * [METHOD_PUT], `arg` = the JSON body: `{"rclone":{"name","type","fields":{…}}}`, `{"mount":{"name","kind","remote","host","user","port","path","mountpoint","password"}}`,
     * `{"remove_rclone":"name"}` or `{"remove_mount":"name"}`. Answers `ok` (and `error` in words, never an echo of a value).
     */
    const val METHOD_PUT = "drive_credentials_put"

    /** Vault paths (`section.key`) the page writes and the publisher reads. */
    const val P_RCLONE = "drive.rclone"
    const val P_MOUNTS = "drive.mounts"
    const val P_SSH_PRIVATE = "ssh.ed25519.private"
    const val P_SSH_PUBLIC = "ssh.ed25519.public"
    const val P_SSH_FINGERPRINT = "ssh.ed25519.fingerprint"

    /** rclone remote fields that are secrets (kept out of the public layer). */
    val RCLONE_SECRET_FIELDS = setOf("token", "client_secret", "service_account_credentials", "pass", "password", "key_pem")
    val RCLONE_TYPES = listOf("drive", "sftp", "webdav", "s3", "b2", "onedrive", "dropbox")

    const val AUTH_OAUTH = "oauth"
    const val AUTH_SERVICE_ACCOUNT = "service_account"

    data class Remote(val name: String, val type: String, val fields: Map<String, String>) {
        val auth: String get() = if (fields["service_account_credentials"].isNullOrEmpty()) AUTH_OAUTH else AUTH_SERVICE_ACCOUNT
        val scope: String get() = fields["scope"].orEmpty()
        val hasSecret: Boolean get() = fields.any { (k, v) -> k in RCLONE_SECRET_FIELDS && v.isNotEmpty() }
    }

    data class Mount(val name: String, val kind: String, val remote: String, val host: String, val user: String, val port: Int, val path: String, val mountpoint: String, val password: String = "")

    /** `[drive]\ntype = drive\n…` in the order given; remote names and keys are restricted to `[A-Za-z0-9_.-]` (anything else is dropped). */
    fun rcloneConfig(remotes: List<Remote>): String = buildString {
        for (r in remotes.filter { validName(it.name) }) {
            append('[').append(r.name).append("]\ntype = ").append(r.type).append('\n')
            for ((k, v) in r.fields) if (validName(k) && v.isNotEmpty() && '\n' !in v) append(k).append(" = ").append(v).append('\n')
            append('\n')
        }
    }.trimEnd() + "\n"

    fun validName(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "_.-" }

    /** One `Host` block per entry, deterministic; [identityFile] is where the consumer stores the private key. */
    data class SshHost(val host: String, val hostname: String, val user: String, val port: Int, val identityFile: String)

    fun sshConfig(hosts: List<SshHost>): String = hosts.joinToString("\n\n") { h ->
        "Host ${h.host}\n  HostName ${h.hostname}\n  User ${h.user}\n  Port ${h.port}\n  IdentityFile ${h.identityFile}\n  IdentitiesOnly yes"
    } + "\n"

    /**
     * The ssh hosts the declaration lists (`fleet_endpoints.ssh_hosts`): `{host, hostname | service, user, port, label}`. A `service`
     * names a declared fleet service, whose mesh host is the HostName (our Gitea is reached over the mesh); an entry that names
     * neither, or an unknown service, or has a bad port, is dropped, never guessed at.
     */
    fun parseSshHosts(decl: String, services: List<FleetEndpointsContract.Service>, identityFile: String): List<SshHost> {
        val arr = runCatching { JSONObject(decl).optJSONArray("ssh_hosts") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host").trim()
            val hostname = o.optString("hostname").trim().ifEmpty { services.firstOrNull { it.id == o.optString("service") }?.meshDns.orEmpty() }
            val port = o.optInt("port", 22)
            if (!validName(host) || hostname.isEmpty() || ' ' in hostname || port !in 1..65535) return@mapNotNull null
            SshHost(host, hostname, o.optString("user", "git").ifBlank { "git" }, port, identityFile)
        }.distinctBy { it.host }
    }

    /**
     * The published document. [rcloneSecrets], [sshPrivate] and [mountSecrets] each say whether the caller holds that secret
     * layer's grant; with the flag off the corresponding key is ABSENT, not empty.
     */
    fun toJson(
        remotes: List<Remote>, publicKey: String, fingerprint: String, hosts: List<SshHost>, privateKey: String, mounts: List<Mount>,
        rcloneSecrets: Boolean, sshPrivate: Boolean, mountSecrets: Boolean,
    ): String = JSONObject().also { o ->
        o.put("v", VERSION)
        o.put("rclone", JSONArray().also { a -> for (r in remotes) a.put(JSONObject().put("name", r.name).put("type", r.type).put("auth", r.auth).put("scope", r.scope).put("has_secret", r.hasSecret)) })
        if (rcloneSecrets) o.put("rclone_config", rcloneConfig(remotes))
        o.put("ssh", JSONObject().put("public", publicKey).put("fingerprint", fingerprint)
            .put("hosts", JSONArray().also { a -> for (h in hosts) a.put(JSONObject().put("host", h.host).put("hostname", h.hostname).put("user", h.user).put("port", h.port).put("identity_file", h.identityFile)) })
            .put("config", sshConfig(hosts))
            .also { if (sshPrivate && privateKey.isNotEmpty()) it.put("private", privateKey) })
        o.put("mounts", JSONArray().also { a -> for (m in mounts) a.put(JSONObject().put("name", m.name).put("kind", m.kind).put("remote", m.remote).put("host", m.host).put("user", m.user).put("port", m.port).put("path", m.path).put("mountpoint", m.mountpoint)) })
        if (mountSecrets) o.put("mount_secrets", JSONObject().also { s -> for (m in mounts) if (m.password.isNotEmpty()) s.put(m.name, JSONObject().put("password", m.password)) })
    }.toString()

    sealed class Put {
        data class RemoteWrite(val remote: Remote) : Put()
        data class MountWrite(val mount: Mount) : Put()
        data class RemoveRemote(val name: String) : Put()
        data class RemoveMount(val name: String) : Put()
    }

    /** The body of [METHOD_PUT] as one operation, or null when it is none of the four (or has no usable name). */
    fun parsePut(text: String?): Put? {
        val o = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return null
        o.optJSONObject("rclone")?.let { r ->
            val f = r.optJSONObject("fields") ?: JSONObject()
            val fields = f.keys().asSequence().associateWith { f.optString(it) }
            return r.optString("name").takeIf { validName(it) }?.let { Put.RemoteWrite(Remote(it, r.optString("type", "drive"), fields)) }
        }
        o.optJSONObject("mount")?.let { m ->
            return m.optString("name").takeIf { validName(it) }?.let {
                Put.MountWrite(Mount(it, m.optString("kind", "rclone"), m.optString("remote"), m.optString("host"), m.optString("user"), m.optInt("port", 22),
                    m.optString("path"), m.optString("mountpoint"), m.optString("password")))
            }
        }
        o.optString("remove_rclone").takeIf { validName(it) }?.let { return Put.RemoveRemote(it) }
        o.optString("remove_mount").takeIf { validName(it) }?.let { return Put.RemoveMount(it) }
        return null
    }

    /** What a reader receives; [toString] never prints a secret layer. */
    data class Published(val json: JSONObject) {
        val remotes: List<String> get() = json.optJSONArray("rclone")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }.orEmpty()
        val publicKey: String get() = json.optJSONObject("ssh")?.optString("public").orEmpty()
        val sshConfig: String get() = json.optJSONObject("ssh")?.optString("config").orEmpty()
        val rcloneConfig: String? get() = json.optString("rclone_config").takeIf { json.has("rclone_config") }
        val privateKey: String? get() = json.optJSONObject("ssh")?.optString("private")?.takeIf { it.isNotEmpty() }
        override fun toString() = "DrivePublished(remotes=$remotes, ssh=${publicKey.isNotEmpty()}, secrets=${rcloneConfig != null || privateKey != null})"
    }

    fun parse(text: String): Published? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return if (o.optInt("v", -1) == VERSION) Published(o) else null
    }
}
