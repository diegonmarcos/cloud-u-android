package com.diegonmarcos.cloudlib.rclone.engine

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.PublicKey

/** Where and how the SSH leg connects. The secret arrives with the call; this engine stores none of it. */
internal class SshTarget(
    val host: String,
    val port: Int,
    val user: String,
    val keyPath: String,
    val passphrase: String,
    val password: String,
    /** The command the peer runs: the pinned unison in server mode, e.g. "unison -server". */
    val serverCommand: String,
    /** #1173 the private key as TEXT (Cloud Account owns it and hands it over with the call): preferred over [keyPath], never written to disk here. */
    val keyPem: String = "",
)

/**
 * The SSH leg of a Unison pair (#1084). An Android app may not exec an ssh binary, so the engine
 * opens the SSH session itself (sshj, as the mounts engine does), runs `unison -server` on the peer
 * through it, and bridges that channel to a loopback socket that the Unison client reaches as
 * `socket://127.0.0.1:<port>/<path>`. The bridge accepts ONE connection (the client's) on
 * 127.0.0.1 only, and ends with it.
 *
 * Host keys are trusted on first use and refused when they change, the way the mounts engine does
 * (never a promiscuous verifier on a WireGuard mesh hop).
 */
internal object UnisonSshBridge {

    class TofuVerifier(private val knownHosts: File) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val id = "$hostname:$port"
            val fp = SecurityUtils.getFingerprint(key)
            val known = (if (knownHosts.isFile) knownHosts.readLines() else emptyList()).firstOrNull { it.startsWith("$id ") }?.substringAfter(' ')
            if (known == null) {
                knownHosts.parentFile?.mkdirs()
                knownHosts.appendText("$id $fp\n")
                return true
            }
            return known == fp
        }
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    /** Connect, start the peer's unison server, listen on loopback. Throws [IOException] with a sentence on any failure. */
    fun open(t: SshTarget, knownHosts: File): UnisonExec.Bridged {
        val ssh = SSHClient()
        var server: ServerSocket? = null
        try {
            ssh.addHostKeyVerifier(TofuVerifier(knownHosts))
            ssh.connectTimeout = 15_000
            ssh.timeout = 30_000
            ssh.connect(t.host, t.port)
            if (t.keyPem.isNotBlank()) {
                val finder = if (t.passphrase.isEmpty()) null else net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(t.passphrase.toCharArray())
                ssh.authPublickey(t.user, ssh.loadKeys(t.keyPem, null, finder))
            } else if (t.keyPath.isNotBlank()) {
                val kp = if (t.passphrase.isEmpty()) ssh.loadKeys(t.keyPath) else ssh.loadKeys(t.keyPath, t.passphrase)
                ssh.authPublickey(t.user, kp)
            } else ssh.authPassword(t.user, t.password)
            val session = ssh.startSession()
            val cmd = session.exec(t.serverCommand)
            val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            server = ss
            ss.soTimeout = ACCEPT_MS
            val closer = {
                runCatching { ss.close() }; runCatching { cmd.close() }; runCatching { session.close() }; runCatching { ssh.disconnect() }
                Unit
            }
            // The client connects once; both directions are pumped until either side ends.
            Thread {
                try {
                    val sock: Socket = ss.accept()
                    val up = pump("unison-ssh-up", sock.getInputStream(), cmd.outputStream) { runCatching { cmd.outputStream.close() } }
                    val down = pump("unison-ssh-down", cmd.inputStream, sock.getOutputStream()) { runCatching { sock.shutdownOutput() } }
                    up.join(); down.join()
                    runCatching { sock.close() }
                } catch (_: Exception) {
                } finally { closer() }
            }.apply { isDaemon = true; start() }
            return UnisonExec.Bridged(ss.localPort, closer)
        } catch (e: Exception) {
            runCatching { server?.close() }
            runCatching { ssh.disconnect() }
            throw IOException("ssh ${t.user}@${t.host}:${t.port}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun pump(name: String, from: java.io.InputStream, to: java.io.OutputStream, onEnd: () -> Unit): Thread =
        Thread {
            try {
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n); to.flush()
                }
            } catch (_: Exception) {
            } finally { onEnd() }
        }.apply { this.name = name; isDaemon = true; start() }

    private const val ACCEPT_MS = 60_000
}
