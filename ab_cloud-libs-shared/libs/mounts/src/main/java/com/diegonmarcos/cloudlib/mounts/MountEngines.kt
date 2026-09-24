package com.diegonmarcos.cloudlib.mounts

import java.io.File
import java.io.IOException
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient

/**
 * Trust-on-first-use host keys, the way ssh itself behaves with
 * StrictHostKeyChecking=accept-new: a host never seen is recorded into the
 * app's own known_hosts (`host:port fingerprint`), and a host whose key
 * changed is REFUSED. Never PromiscuousVerifier — that accepts anything and
 * turns a WireGuard mesh hop into a silent MITM surface.
 */
class TofuHostKeyVerifier(private val knownHosts: File) : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val fp = SecurityUtils.getFingerprint(key)
        val lines = if (knownHosts.isFile) knownHosts.readLines() else emptyList()
        val known = lines.firstOrNull { it.startsWith("$id ") }?.substringAfter(' ')
        if (known == null) {
            knownHosts.parentFile?.mkdirs()
            knownHosts.appendText("$id $fp\n")
            return true
        }
        return known == fp
    }
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/** SFTP, and for [MountType.SSH] also remote commands, over sshj. */
class SftpFs(private val spec: MountSpec, private val secret: MountSecret, private val knownHosts: File) : RemoteFs {
    private val ssh = SSHClient()
    private val sftp: SFTPClient

    init {
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(knownHosts))
        ssh.connectTimeout = 15_000
        ssh.timeout = 30_000
        ssh.connect(spec.host, spec.port)
        try {
            if (spec.keyPath.isNotBlank()) {
                val kp = if (secret.passphrase.isNullOrEmpty()) ssh.loadKeys(spec.keyPath) else ssh.loadKeys(spec.keyPath, secret.passphrase)
                ssh.authPublickey(spec.username, kp)
            } else {
                ssh.authPassword(spec.username, secret.password ?: "")
            }
            sftp = ssh.newSFTPClient()
        } catch (e: Exception) { ssh.close(); throw e }
    }

    override fun list(path: String): List<RemoteEntry> = sftp.ls(path).map { r ->
        RemoteEntry(r.name, r.path, r.isDirectory, if (r.isDirectory) -1L else r.attributes.size, r.attributes.mtime * 1000)
    }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))

    override fun download(path: String, into: File, onProgress: (Long) -> Unit) { into.parentFile?.mkdirs(); sftp.get(path, into.absolutePath); onProgress(into.length()) }
    override fun upload(local: File, path: String, onProgress: (Long) -> Unit) { sftp.put(local.absolutePath, path); onProgress(local.length()) }
    override fun mkdir(path: String) = sftp.mkdirs(path)
    override fun delete(path: String, isDir: Boolean) = if (isDir) sftp.rmdir(path) else sftp.rm(path)
    override fun rename(from: String, to: String) = sftp.rename(from, to)

    override fun exec(command: String): String? {
        if (spec.type != MountType.SSH) return null
        val session = ssh.startSession()
        try {
            val cmd = session.exec(command)
            val out = cmd.inputStream.bufferedReader().readText()
            val err = cmd.errorStream.bufferedReader().readText()
            cmd.join(60, TimeUnit.SECONDS)
            return out + (if (err.isNotBlank()) "\n[stderr]\n$err" else "") + "\n[exit ${cmd.exitStatus ?: "?"}]"
        } finally { session.close() }
    }

    override fun close() { runCatching { sftp.close() }; runCatching { ssh.disconnect() } }
}

/** FTP and FTPS (explicit TLS) over commons-net, passive mode, binary transfers. */
class FtpFs(private val spec: MountSpec, secret: MountSecret) : RemoteFs {
    private val ftp: FTPClient = if (spec.type == MountType.FTPS) FTPSClient() else FTPClient()

    init {
        ftp.connectTimeout = 15_000
        ftp.connect(spec.host, spec.port)
        try {
            if (!ftp.login(spec.username.ifBlank { "anonymous" }, secret.password ?: "")) throw IOException("FTP login refused: ${ftp.replyString.trim()}")
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            if (ftp is FTPSClient) { ftp.execPBSZ(0); ftp.execPROT("P") }
        } catch (e: Exception) { runCatching { ftp.disconnect() }; throw e }
    }

    override fun list(path: String): List<RemoteEntry> = ftp.listFiles(path).filter { it.name != "." && it.name != ".." }.map { f ->
        RemoteEntry(f.name, RemotePaths.join(path, f.name), f.isDirectory, if (f.isDirectory) -1L else f.size, f.timestamp?.timeInMillis ?: 0L)
    }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))

    override fun download(path: String, into: File, onProgress: (Long) -> Unit) {
        into.parentFile?.mkdirs()
        into.outputStream().use { if (!ftp.retrieveFile(path, it)) throw IOException("FTP retrieve failed: ${ftp.replyString.trim()}") }
        onProgress(into.length())
    }
    override fun upload(local: File, path: String, onProgress: (Long) -> Unit) {
        local.inputStream().use { if (!ftp.storeFile(path, it)) throw IOException("FTP store failed: ${ftp.replyString.trim()}") }
        onProgress(local.length())
    }
    override fun mkdir(path: String) { if (!ftp.makeDirectory(path)) throw IOException("FTP mkdir failed: ${ftp.replyString.trim()}") }
    override fun delete(path: String, isDir: Boolean) { if (!(if (isDir) ftp.removeDirectory(path) else ftp.deleteFile(path))) throw IOException("FTP delete failed: ${ftp.replyString.trim()}") }
    override fun rename(from: String, to: String) { if (!ftp.rename(from, to)) throw IOException("FTP rename failed: ${ftp.replyString.trim()}") }
    override fun close() { runCatching { ftp.logout() }; runCatching { ftp.disconnect() } }
}

/** WebDAV over OkHttp: PROPFIND / GET / PUT / MKCOL / DELETE / MOVE with basic auth. */
class WebDavFs(private val spec: MountSpec, secret: MountSecret) : RemoteFs {
    private val base = (if (spec.type == MountType.WEBDAVS) "https" else "http") + "://" + spec.host + (if (spec.port != spec.type.defaultPort) ":${spec.port}" else "")
    private val auth: String? = if (spec.username.isNotBlank()) Credentials.basic(spec.username, secret.password ?: "") else null
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun url(path: String): String = base + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun req(path: String): Request.Builder = Request.Builder().url(url(path)).apply { if (auth != null) header("Authorization", auth) }
    private fun check(code: Int, what: String) { if (code !in 200..299) throw IOException("WebDAV $what: HTTP $code") }

    override fun list(path: String): List<RemoteEntry> {
        val r = req(path).method("PROPFIND", WebDavParser.PROPFIND_BODY.toRequestBody("application/xml".toMediaType())).header("Depth", "1").build()
        client.newCall(r).execute().use { resp ->
            if (resp.code != 207) throw IOException("WebDAV PROPFIND: HTTP ${resp.code}")
            return WebDavParser.parseMultistatus(resp.body?.string() ?: "", path)
        }
    }
    override fun download(path: String, into: File, onProgress: (Long) -> Unit) {
        into.parentFile?.mkdirs()
        client.newCall(req(path).get().build()).execute().use { resp ->
            check(resp.code, "GET")
            into.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
        }
        onProgress(into.length())
    }
    override fun upload(local: File, path: String, onProgress: (Long) -> Unit) {
        client.newCall(req(path).put(local.asRequestBody("application/octet-stream".toMediaType())).build()).execute().use { check(it.code, "PUT") }
        onProgress(local.length())
    }
    override fun mkdir(path: String) { client.newCall(req(path).method("MKCOL", null).build()).execute().use { check(it.code, "MKCOL") } }
    override fun delete(path: String, isDir: Boolean) { client.newCall(req(path).delete().build()).execute().use { check(it.code, "DELETE") } }
    override fun rename(from: String, to: String) { client.newCall(req(from).method("MOVE", null).header("Destination", url(to)).header("Overwrite", "F").build()).execute().use { check(it.code, "MOVE") } }
    override fun close() {}
}

object MountFsFactory {
    fun open(spec: MountSpec, secret: MountSecret, knownHosts: File): RemoteFs = when (spec.type) {
        MountType.SFTP, MountType.SSH -> SftpFs(spec, secret, knownHosts)
        MountType.FTP, MountType.FTPS -> FtpFs(spec, secret)
        MountType.WEBDAV, MountType.WEBDAVS -> WebDavFs(spec, secret)
    }
}
