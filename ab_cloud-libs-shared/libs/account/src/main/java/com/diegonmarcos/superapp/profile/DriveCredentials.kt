package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract as C
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.settings.AccountVault
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Cloud Drive's credentials, held by Cloud Account in the account vault (the keystore-backed encrypted store, never a
 * repo file): the rclone remotes (Google Drive / Workspace by OAuth token or service account, SFTP, ...), the one ed25519
 * SSH key for git over ssh (GitHub and our Gitea) and the FUSE / sshfs mounts. Paths are [C]'s; the wire is [C.METHOD].
 */
class DriveCredentialStore(private val vault: AccountVault) {
    private fun obj(path: String): JSONObject = vault.connection(path) as? JSONObject ?: JSONObject()

    fun remotes(): List<C.Remote> = obj(C.P_RCLONE).let { o ->
        o.keys().asSequence().sorted().mapNotNull { name ->
            val r = o.optJSONObject(name) ?: return@mapNotNull null
            C.Remote(name, r.optString("type", "drive"), r.keys().asSequence().filter { it != "type" }.associateWith { r.optString(it) })
        }.toList()
    }

    /** Adds or replaces one remote; false when the name or type is not usable. */
    fun putRemote(r: C.Remote): Boolean {
        if (!C.validName(r.name) || r.type !in C.RCLONE_TYPES) return false
        val all = obj(C.P_RCLONE)
        all.put(r.name, JSONObject().put("type", r.type).also { o -> r.fields.forEach { (k, v) -> if (C.validName(k) && v.isNotEmpty()) o.put(k, v) } })
        vault.putConnection(C.P_RCLONE, all)
        return true
    }

    fun removeRemote(name: String) { val all = obj(C.P_RCLONE); all.remove(name); vault.putConnection(C.P_RCLONE, all) }

    fun mounts(): List<C.Mount> = obj(C.P_MOUNTS).let { o ->
        o.keys().asSequence().sorted().mapNotNull { name ->
            val m = o.optJSONObject(name) ?: return@mapNotNull null
            C.Mount(name, m.optString("kind", "rclone"), m.optString("remote"), m.optString("host"), m.optString("user"), m.optInt("port", 22),
                m.optString("path"), m.optString("mountpoint"), m.optString("password"))
        }.toList()
    }

    fun putMount(m: C.Mount): Boolean {
        if (!C.validName(m.name) || m.kind !in listOf("rclone", "sshfs")) return false
        if (m.kind == "rclone" && !C.validName(m.remote)) return false
        if (m.kind == "sshfs" && (m.host.isBlank() || ' ' in m.host || m.port !in 1..65535)) return false
        val all = obj(C.P_MOUNTS)
        all.put(m.name, JSONObject().put("kind", m.kind).put("remote", m.remote).put("host", m.host).put("user", m.user).put("port", m.port)
            .put("path", m.path).put("mountpoint", m.mountpoint).also { if (m.password.isNotEmpty()) it.put("password", m.password) })
        vault.putConnection(C.P_MOUNTS, all)
        return true
    }

    /** #1173 the Unison pair list (JSON text), "" when none is held. */
    fun unison(): String = vault.connection(C.P_UNISON)?.toString().orEmpty()

    fun putUnison(text: String): Boolean {
        if (!C.validUnison(text)) return false
        vault.putConnection(C.P_UNISON, text)
        return true
    }

    fun removeMount(name: String) { val all = obj(C.P_MOUNTS); all.remove(name); vault.putConnection(C.P_MOUNTS, all) }

    // ── the ed25519 key ──────────────────────────────────────────────────

    fun publicKey(): String = vault.connection(C.P_SSH_PUBLIC)?.toString().orEmpty()
    fun fingerprint(): String = vault.connection(C.P_SSH_FINGERPRINT)?.toString().orEmpty()
    fun hasKey(): Boolean = vault.connection(C.P_SSH_PRIVATE)?.toString().isNullOrBlank().not()
    fun privateKey(): String = vault.connection(C.P_SSH_PRIVATE)?.toString().orEmpty()

    /** A fresh ed25519 key (replaces the held one: the old public key stays valid on a host until removed there). */
    fun generate(comment: String = "cloud-account"): Boolean = eddsa {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        try { hold(kp, comment) } finally { kp.dispose() }
    } ?: false

    /** Imports an OpenSSH/PEM ed25519 private key (decrypted with [passphrase] when it is protected); any other key type is refused. */
    fun importKey(pem: String, passphrase: String = "", comment: String = "cloud-account"): ImportResult = eddsa {
        val kp = KeyPair.load(JSch(), pem.toByteArray(), null)
        try {
            if (kp.keyType != KeyPair.ED25519) ImportResult(false, "only ed25519 keys are accepted")
            else if (kp.isEncrypted && !kp.decrypt(passphrase.toByteArray())) ImportResult(false, "wrong passphrase")
            else if (hold(kp, comment)) ImportResult(true, "") else ImportResult(false, "not stored")
        } finally { kp.dispose() }
    } ?: ImportResult(false, "not an SSH private key")

    /** Runs [block] with jsch's EdDSA on BouncyCastle (works on every Android), then, if that fails, on the JDK's; null when both fail. */
    private fun <T> eddsa(block: () -> T): T? {
        for (impl in listOf("bc", "jce")) {
            val cls = "com.jcraft.jsch.$impl.KeyPairGenEdDSA"
            if (runCatching { Class.forName(cls) }.isFailure) continue
            JSch.setConfig("keypairgen.eddsa", cls); JSch.setConfig("keypairgen_fromprivate.eddsa", cls)
            runCatching(block).getOrNull()?.let { return it }
        }
        return null
    }

    data class ImportResult(val ok: Boolean, val why: String)

    private fun hold(kp: KeyPair, comment: String): Boolean {
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString(Charsets.UTF_8.name()).trim()
        val priv = openSshPrivate(kp, comment)
        if (priv.isBlank() || !pub.startsWith("ssh-ed25519 ")) return false
        vault.putConnection(C.P_SSH_PRIVATE, priv); vault.putConnection(C.P_SSH_PUBLIC, pub); vault.putConnection(C.P_SSH_FINGERPRINT, fingerprint(kp.publicKeyBlob))
        return true
    }

    /** `SHA256:<base64>` of the public key blob, the form GitHub and `ssh-keygen -l` show. */
    private fun fingerprint(blob: ByteArray): String =
        "SHA256:" + android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(blob), android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    /**
     * jsch cannot write an ed25519 private key (its writePrivateKey throws for EdDSA), so the unencrypted `openssh-key-v1` file is
     * built here from the key's 32-byte seed and public key (read from the jsch key object; the CI suite pins that the result loads
     * back to the same public key).
     */
    private fun openSshPrivate(kp: KeyPair, comment: String): String {
        val c = Class.forName("com.jcraft.jsch.KeyPairEdDSA")
        fun field(n: String) = c.getDeclaredField(n).also { it.isAccessible = true }.get(kp) as ByteArray
        val seed = field("prv_array"); val pub = field("pub_array")
        if (seed.size != 32 || pub.size != 32) return ""
        fun str(o: ByteArrayOutputStream, b: ByteArray) { o.write(java.nio.ByteBuffer.allocate(4).putInt(b.size).array()); o.write(b) }
        val type = "ssh-ed25519".toByteArray()
        val blob = ByteArrayOutputStream().also { str(it, type); str(it, pub) }.toByteArray()
        val check = java.security.SecureRandom().nextInt()
        val priv = ByteArrayOutputStream().also {
            it.write(java.nio.ByteBuffer.allocate(8).putInt(check).putInt(check).array())
            str(it, type); str(it, pub); str(it, seed + pub); str(it, comment.toByteArray())
            var pad = 1; while (it.size() % 8 != 0) it.write(pad++)
        }.toByteArray()
        val file = ByteArrayOutputStream().also {
            it.write("openssh-key-v1".toByteArray()); it.write(0)
            str(it, "none".toByteArray()); str(it, "none".toByteArray()); str(it, ByteArray(0))
            it.write(java.nio.ByteBuffer.allocate(4).putInt(1).array()); str(it, blob); str(it, priv)
        }.toByteArray()
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            android.util.Base64.encodeToString(file, android.util.Base64.NO_WRAP).chunked(70).joinToString("\n") +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    fun sshHosts(decl: String = AccountHost.fleetEndpointsDecl()): List<C.SshHost> =
        C.parseSshHosts(decl, FleetEndpointsContract.parseDecl(decl), IDENTITY_FILE)

    companion object { const val IDENTITY_FILE = "~/.ssh/id_ed25519" }
}

/**
 * [C.METHOD] on the accountdata provider. The caller was checked for [C.GRANT_READ] before this runs; each secret layer
 * additionally needs its own grant (Cloud Account itself always has all of them).
 */
object DriveCredentialsPublisher {
    fun publish(ctx: Context, caller: String, vault: AccountVault = AccountVault(ctx)): Bundle {
        val store = DriveCredentialStore(vault)
        fun may(grant: String) = caller == ctx.packageName || vault.grants.allow(caller, grant)
        return Bundle().apply {
            putBoolean(C.KEY_OK, true)
            putString(C.KEY_JSON, C.toJson(store.remotes(), store.publicKey(), store.fingerprint(), store.sshHosts(), store.privateKey(), store.mounts(),
                rcloneSecrets = may(C.GRANT_RCLONE), sshPrivate = may(C.GRANT_SSH_PRIVATE), mountSecrets = may(C.GRANT_MOUNTS),
                unison = if (may(C.GRANT_UNISON)) store.unison() else ""))
        }
    }

    /** [C.METHOD_PUT]: the caller was checked for [C.GRANT_WRITE]. Answers ok, or an error in words (never a value). */
    fun put(ctx: Context, body: String?, vault: AccountVault = AccountVault(ctx)): Bundle {
        val store = DriveCredentialStore(vault)
        val ok = when (val op = C.parsePut(body)) {
            is C.Put.RemoteWrite -> store.putRemote(op.remote)
            is C.Put.MountWrite -> store.putMount(op.mount)
            is C.Put.RemoveRemote -> { store.removeRemote(op.name); true }
            is C.Put.RemoveMount -> { store.removeMount(op.name); true }
            is C.Put.UnisonWrite -> store.putUnison(op.text)
            null -> false
        }
        return Bundle().apply { putBoolean(C.KEY_OK, ok); if (!ok) putString(C.KEY_ERROR, "not stored: unusable name, type or fields") }
    }
}
