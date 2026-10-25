package com.diegonmarcos.clouddrive.storage

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.diegonmarcos.cloudlib.rclone.RcloneConfig
import com.diegonmarcos.cloudlib.rclone.RcloneRemote
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drive's side of the credential contract. Cloud Account OWNS the rclone remotes (their OAuth tokens and service accounts),
 * the SSH key, the FUSE mount definitions and the Unison pairs; its `accountdata` provider publishes them (`drive_credentials`,
 * a layer per grant) and takes writes (`drive_credentials_put`, grant `drive.write`). The contract is Account's
 * (libs:fleet-endpoints DriveCredentialsContract); [Wire] mirrors its names and DriveCredentialsGuardTest holds every one of them
 * equal to the source, so Drive can neither drift from nor invent a name. No value is logged or put in a sentence here.
 */
object Wire {
    const val ACCOUNT_PACKAGE = "com.diegonmarcos.cloudaccount"
    const val AUTHORITY = "$ACCOUNT_PACKAGE.accountdata"
    const val METHOD = "drive_credentials"
    const val METHOD_PUT = "drive_credentials_put"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_JSON = "drive_json"
    const val VERSION = 1
    /** The backends Account stores; any other stays on this phone only, and the page says so. */
    val RCLONE_TYPES = listOf("drive", "sftp", "webdav", "s3", "b2", "onedrive", "dropbox")
    const val MAX_UNISON_BYTES = 64 * 1024
    /** The grants Drive names in a sentence when a layer is closed (the owner opens them in Account > Secrets). */
    const val GRANT_RCLONE = "drive.rclone"
    const val GRANT_SSH_PRIVATE = "ssh.private"
}

class DriveCredentialsClient(private val transport: Transport) {

    /** One provider call, already unpacked from the Bundle (so the JVM suite needs no Android). */
    data class Reply(val ok: Boolean, val json: String?, val error: String?)

    interface Transport { fun call(method: String, arg: String?): Reply }

    data class AccountMount(val name: String, val kind: String, val remote: String, val host: String, val user: String, val port: Int, val path: String, val mountpoint: String)

    /** What Account published to this app. A layer the owner did not grant is null/empty, never a guess. */
    data class Snapshot(
        val remotes: List<Pair<String, String>>,
        val rcloneConfig: String?,
        val sshPublic: String,
        val sshPrivate: String?,
        val mounts: List<AccountMount>,
        val unison: String?,
    ) {
        val hasSshKey: Boolean get() = sshPublic.isNotBlank()
        /** A grant Account has not given us, as a sentence for the page; null when everything this app needs is open. */
        val missingGrants: String? get() = listOfNotNull(Wire.GRANT_RCLONE.takeIf { rcloneConfig == null }, Wire.GRANT_SSH_PRIVATE.takeIf { sshPrivate == null && hasSshKey })
            .takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    sealed class Read {
        data class Ok(val snapshot: Snapshot) : Read()
        data class Unavailable(val why: String) : Read()
    }

    fun read(): Read {
        val r = try { transport.call(Wire.METHOD, null) } catch (t: Throwable) { return Read.Unavailable("Cloud Account is not installed or does not answer (${t.javaClass.simpleName})") }
        if (!r.ok) return Read.Unavailable(refusal(r.error))
        val o = runCatching { JSONObject(r.json.orEmpty()) }.getOrNull() ?: return Read.Unavailable("Cloud Account answered something unreadable")
        if (o.optInt("v", -1) != Wire.VERSION) return Read.Unavailable("Cloud Account speaks another version of the credential contract: update it")
        val remotes = o.optJSONArray("rclone")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("name") to it.optString("type") } }.orEmpty()
        val ssh = o.optJSONObject("ssh")
        val mounts = o.optJSONArray("mounts")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            AccountMount(it.optString("name"), it.optString("kind"), it.optString("remote"), it.optString("host"), it.optString("user"), it.optInt("port", 22), it.optString("path"), it.optString("mountpoint"))
        } }.orEmpty()
        return Read.Ok(Snapshot(
            remotes = remotes,
            rcloneConfig = o.optString("rclone_config").takeIf { o.has("rclone_config") },
            sshPublic = ssh?.optString("public").orEmpty(),
            sshPrivate = ssh?.optString("private")?.takeIf { it.isNotEmpty() },
            mounts = mounts,
            unison = o.optString("unison").takeIf { o.has("unison") && it.isNotBlank() },
        ))
    }

    private fun refusal(error: String?): String {
        val e = error.orEmpty()
        return if (e.startsWith("no grant")) "Cloud Account has not granted Cloud Drive access ($e): Account > Secrets" else e.ifBlank { "Cloud Account refused" }
    }

    private fun put(body: JSONObject): String? {
        val r = try { transport.call(Wire.METHOD_PUT, body.toString()) } catch (t: Throwable) { return "Cloud Account is not installed or does not answer (${t.javaClass.simpleName})" }
        return if (r.ok) null else refusal(r.error)
    }

    /** Hand one remote (as `rclone config show` printed it) to Account. Null on success, else the sentence why not. */
    fun putRemote(name: String, type: String, fields: Map<String, String>): String? {
        if (type !in Wire.RCLONE_TYPES) return "kept on this phone only: Cloud Account does not store $type remotes yet"
        return put(JSONObject().put("rclone", JSONObject().put("name", name).put("type", type)
            .put("fields", JSONObject().also { f -> fields.forEach { (k, v) -> if (v.isNotBlank() && '\n' !in v) f.put(k, v) } })))
    }

    fun removeRemote(name: String): String? = put(JSONObject().put("remove_rclone", name))

    fun putMount(m: AccountMount): String? = put(JSONObject().put("mount", JSONObject().put("name", m.name).put("kind", m.kind).put("remote", m.remote)
        .put("host", m.host).put("user", m.user).put("port", m.port).put("path", m.path).put("mountpoint", m.mountpoint)))

    fun removeMount(name: String): String? = put(JSONObject().put("remove_mount", name))

    fun putUnison(text: String): String? {
        if (text.toByteArray().size > Wire.MAX_UNISON_BYTES) return "the pair list is too large for Cloud Account"
        return put(JSONObject().put("unison", text))
    }

    companion object {
        /** The phone's Android IPC to Account's provider. Blocking: call off the main thread. */
        fun of(ctx: Context): DriveCredentialsClient = DriveCredentialsClient(object : Transport {
            private val app = ctx.applicationContext
            override fun call(method: String, arg: String?): Reply {
                val b: Bundle? = app.contentResolver.call(Uri.parse("content://${Wire.AUTHORITY}"), method, arg, null)
                return when {
                    b == null -> Reply(false, null, "Cloud Account returned nothing")
                    b.getBoolean(Wire.KEY_OK) -> Reply(true, b.getString(Wire.KEY_JSON), null)
                    else -> Reply(false, null, b.getString(Wire.KEY_ERROR))
                }
            }
        })
    }
}

/**
 * Which side wins, as pure rules. Account owns every value; this phone keeps a working copy (rclone rewrites its OAuth token on
 * refresh, a mount is defined on the phone). A thing only Account holds is RESTORED here (a new phone), a thing only this phone
 * holds is HANDED to Account, and where both hold it the phone's copy wins and is handed over when it differs (the refreshed token).
 */
object CredentialMerge {

    private fun clean(o: Map<String, String>) = o.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }

    /** Remotes in Account's generated rclone.conf that this phone lacks. */
    fun remotesToRestore(local: List<RcloneRemote>, accountConfig: String?): List<RcloneRemote> {
        if (accountConfig.isNullOrBlank()) return emptyList()
        val have = local.map { it.name }.toSet()
        return RcloneConfig.parse(accountConfig).filter { it.name !in have && it.type.isNotBlank() }
    }

    /** Local remotes Account does not hold (or holds with other fields), of a type it stores. With no secret layer, only the missing ones. */
    fun remotesToPush(local: List<RcloneRemote>, held: List<Pair<String, String>>, accountConfig: String?): List<RcloneRemote> {
        val byName = accountConfig?.let { c -> RcloneConfig.parse(c).associateBy { it.name } }
        val names = held.map { it.first }.toSet()
        return local.filter { it.type in Wire.RCLONE_TYPES }.filter { r ->
            when {
                r.name !in names -> true
                byName == null -> false
                else -> byName[r.name]?.let { clean(it.options) != clean(r.options) || it.type != r.type } ?: true
            }
        }
    }

    fun mountToAccount(s: FuseWire.Spec): DriveCredentialsClient.AccountMount = when (s.kind) {
        FuseWire.Kind.RCLONE -> DriveCredentialsClient.AccountMount(s.id, "rclone", s.source.substringBefore(':'), "", "", 22, s.source.substringAfter(':'), s.mountpoint)
        FuseWire.Kind.SSHFS -> DriveCredentialsClient.AccountMount(s.id, "sshfs", "", s.source.substringAfter('@').substringBefore(':'), s.source.substringBefore('@'), 22, s.source.substringAfter(':'), s.mountpoint)
    }

    /** A mount Account holds, as a definition this phone can run; null when it would not pass [FuseWire.refusal]. */
    fun mountFromAccount(m: DriveCredentialsClient.AccountMount): FuseWire.Spec? {
        val id = m.name.lowercase()
        val spec = when (m.kind) {
            "rclone" -> FuseWire.Spec(id, FuseWire.Kind.RCLONE, "${m.remote}:${m.path}", m.mountpoint.ifBlank { "/root/mnt/$id" })
            "sshfs" -> FuseWire.Spec(id, FuseWire.Kind.SSHFS, "${m.user}@${m.host}:${m.path}", m.mountpoint.ifBlank { "/root/mnt/$id" })
            else -> return null
        }
        return spec.takeIf { FuseWire.refusal(it) == null }
    }

    fun mountsToRestore(local: List<FuseWire.Spec>, held: List<DriveCredentialsClient.AccountMount>): List<FuseWire.Spec> {
        val have = local.map { it.id }.toSet()
        return held.mapNotNull(::mountFromAccount).filter { it.id !in have }
    }

    fun mountsToPush(local: List<FuseWire.Spec>, held: List<DriveCredentialsClient.AccountMount>): List<FuseWire.Spec> {
        val byName = held.associateBy { it.name }
        return local.filter { byName[it.id] != mountToAccount(it) }
    }

    /** The Unison pair list: Account's copy fills an empty phone, the phone's copy fills an empty Account; both held, the phone's wins. */
    enum class PairAction { NONE, RESTORE, PUSH }
    fun pairs(local: String, accountText: String?, accountReadable: Boolean): PairAction = when {
        !accountReadable -> PairAction.NONE
        local.isBlank() && !accountText.isNullOrBlank() -> PairAction.RESTORE
        local.isNotBlank() && local != accountText -> PairAction.PUSH
        else -> PairAction.NONE
    }

    fun pairListJson(pairs: JSONArray): String = JSONObject().put("pairs", pairs).toString()
}
