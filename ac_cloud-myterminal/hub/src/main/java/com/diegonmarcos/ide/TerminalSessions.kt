package com.diegonmarcos.ide

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.diegonmarcos.cloud.terminal.ICloudSession
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Zero-setup sessions into the fleet's terminals (ac_cloud-termux,
 * ac_cloud-nix-on-droid) over their signature-guarded ICloudSession service.
 *
 * Nothing to install, start or authorise: MyTerminal binds the env's
 * CloudSessionService (the system refuses the bind unless both APKs carry the
 * fleet key), the env bootstraps itself headlessly if it never ran, and hands
 * back the master end of a PTY running its own login shell. No sshd, no
 * listening socket, no key on either side.
 *
 * Loopback SSH ([SshBackend]) stays only as the fallback for a terminal build
 * that predates the service ([TerminalRoute.trySshAfter]); every decision of
 * which path and what to tell the owner is [TerminalRoute]'s, which is pure
 * and tested.
 */
object TerminalSessions {

    /** Mirrors com.termux.cloud.SessionGate in both terminals (the tester pins the three). */
    const val ACTION = "com.diegonmarcos.cloud.terminal.SESSION"
    const val PERMISSION = "com.diegonmarcos.cloud.permission.TERMINAL_SESSION"

    private const val BIND_TIMEOUT_MS = 10_000L
    private const val EXEC_TIMEOUT_MS = 30_000

    /** A terminal that cannot be reached, with [TerminalRoute.explain]'s sentence as message. */
    class Unreachable(message: String) : Exception(message)

    /** What [probe] found: [usable] when a shell can be opened by either path. */
    data class Probe(
        val session: TerminalRoute.Reason,
        val ssh: TerminalRoute.Reason,
        val message: String,
    ) {
        val native get() = session == TerminalRoute.Reason.OK
        val usable get() = native || (session == TerminalRoute.Reason.TOO_OLD && ssh == TerminalRoute.Reason.OK)
    }

    fun installed(ctx: Context, pkg: String): Boolean = pkg.isNotEmpty() && try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun hasSessionService(ctx: Context, pkg: String): Boolean = pkg.isNotEmpty() &&
        ctx.packageManager.queryIntentServices(Intent(ACTION).setPackage(pkg), 0).isNotEmpty()

    /**
     * The backend every pty and fs call routes through: the owner's stored
     * pick while it is still declared, else the first installed terminal that
     * has the session service (so a phone with only one terminal just works).
     */
    fun activeBackend(ctx: Context): String {
        val declared = TerminalTargets.all()
        return TerminalRoute.pickBackend(
            declared.map { it.key },
            declared.filter { hasSessionService(ctx, it.pkg) }.map { it.key }.toSet(),
            declared.filter { installed(ctx, it.pkg) }.map { it.key }.toSet(),
            IdePrefs.storedTerminalBackend(ctx),
            BuildConfig.TERMINAL_BACKEND_DEFAULT,
        )
    }

    // ── Binding ──────────────────────────────────────────────────────────────

    private class Link(private val app: Context, private val pkg: String) : ServiceConnection {
        @Volatile var service: ICloudSession? = null
        /** prepare() succeeded on this binding: the env is bootstrapped and its service up. */
        @Volatile var prepared = false
        @Volatile private var latch = CountDownLatch(1)
        @Volatile private var bound = false

        /** (service or null, refusedBySystem). Binds once; rebinds after the env died. */
        @Synchronized
        fun get(): Pair<ICloudSession?, Boolean> {
            service?.let { if (it.asBinder().isBinderAlive) return it to false }
            if (bound) runCatching { app.unbindService(this) }
            bound = false
            service = null
            prepared = false
            latch = CountDownLatch(1)
            val ok = try {
                app.bindService(Intent(ACTION).setPackage(pkg), this,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            } catch (_: SecurityException) {
                return null to true
            }
            if (!ok) {
                runCatching { app.unbindService(this) }
                return null to false
            }
            bound = true
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return service to false
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ICloudSession.Stub.asInterface(binder)
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) { service = null }

        override fun onBindingDied(name: ComponentName?) { service = null }

        override fun onNullBinding(name: ComponentName?) { latch.countDown() }

        @Synchronized
        fun release() {
            if (bound) runCatching { app.unbindService(this) }
            bound = false
            service = null
        }
    }

    private val links = ConcurrentHashMap<String, Link>()

    private fun link(ctx: Context, pkg: String) =
        links.getOrPut(pkg) { Link(ctx.applicationContext, pkg) }

    /** The bound service when the env has a usable one, else the [TerminalRoute.Reason] it has not. */
    private fun connect(ctx: Context, t: TerminalTargets.Target): Pair<ICloudSession?, Pair<TerminalRoute.Reason, String?>> {
        val installed = installed(ctx, t.pkg)
        if (!installed) return null to (TerminalRoute.sessionReason(false, false, false, 0, null) to null)
        val l = link(ctx, t.pkg)
        val (svc, refused) = l.get()
        var version = 0
        var securityRefused = refused
        var error: String? = null
        if (svc != null) {
            try {
                version = svc.version()
                if (version >= 1 && !l.prepared) {
                    error = svc.prepare()?.getString("error")
                    l.prepared = error == null
                }
            } catch (e: SecurityException) {
                securityRefused = true
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
        val reason = TerminalRoute.sessionReason(true, svc != null, securityRefused, version, error)
        return (if (reason == TerminalRoute.Reason.OK) svc else null) to (reason to error)
    }

    /**
     * Can [t] be reached right now, and if not, exactly why. Blocking (binds,
     * may bootstrap a never-opened terminal): call off the UI thread.
     */
    fun probe(ctx: Context, ssh: SshBackend, t: TerminalTargets.Target): Probe {
        val (_, verdict) = connect(ctx, t)
        val (reason, error) = verdict
        var sshReason = TerminalRoute.Reason.OK
        var detail = error
        if (TerminalRoute.trySshAfter(reason)) {
            val sshErr = ssh.testConnection(t)
            sshReason = TerminalRoute.sshReason(sshErr)
            detail = sshErr
        }
        return Probe(reason, sshReason,
            TerminalRoute.explain(reason, sshReason, t.label, t.host, t.port, detail))
    }

    // ── PTYs ─────────────────────────────────────────────────────────────────

    private class Shell(val pkg: String, val pfd: ParcelFileDescriptor, val stdin: OutputStream)

    private val shells = ConcurrentHashMap<String, Shell>()

    /** Ids opened natively, so the bridge routes write/resize/kill to the right path. */
    fun owns(id: String) = shells.containsKey(id)

    fun openCount() = shells.size

    /**
     * Opens a native shell for [id]. Returns null when it did, else the probe
     * that says why not (the caller then tries SSH when [Probe.usable] or
     * prints [Probe.message]).
     */
    fun open(
        ctx: Context, ssh: SshBackend, t: TerminalTargets.Target, id: String, cols: Int, rows: Int,
        onData: (String) -> Unit, onExit: () -> Unit,
    ): Probe? {
        val (svc, verdict) = connect(ctx, t)
        if (svc == null) {
            val (reason, error) = verdict
            if (!TerminalRoute.trySshAfter(reason))
                return Probe(reason, TerminalRoute.Reason.OK,
                    TerminalRoute.explain(reason, TerminalRoute.Reason.OK, t.label, t.host, t.port, error))
            return Probe(reason, TerminalRoute.Reason.OK, "ssh")   // usable → caller uses SSH
        }
        val info = Bundle()
        val pfd = try { svc.openPty(id, cols, rows, "", info) } catch (e: Exception) {
            info.putString("error", e.message ?: e.toString()); null
        }
        if (pfd == null) {
            val r = TerminalRoute.Reason.SESSION_FAILED
            return Probe(r, TerminalRoute.Reason.OK,
                TerminalRoute.explain(r, TerminalRoute.Reason.OK, t.label, t.host, t.port, info.getString("error")))
        }
        val stdin = FileOutputStream(pfd.fileDescriptor)
        shells[id] = Shell(t.pkg, pfd, stdin)
        TerminalWakeLock.syncNative(ctx, shells.size)
        Thread({
            try {
                // A reader, not raw bytes: a UTF-8 sequence split across two reads is
                // still one character.
                InputStreamReader(FileInputStream(pfd.fileDescriptor), Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n <= 0) break
                        onData(String(buf, 0, n))
                    }
                }
            } catch (_: Exception) {
                // EIO: the shell exited and the env closed its end.
            } finally {
                if (shells.remove(id) != null) runCatching { pfd.close() }
                TerminalWakeLock.syncNative(ctx, shells.size)
                onExit()
            }
        }, "pty-reader-$id").also { it.isDaemon = true }.start()
        return null
    }

    fun write(id: String, data: String) {
        val s = shells[id] ?: return
        try {
            s.stdin.write(data.toByteArray(Charsets.UTF_8)); s.stdin.flush()
        } catch (_: Exception) { /* shell gone */ }
    }

    fun resize(id: String, cols: Int, rows: Int) {
        val s = shells[id] ?: return
        runCatching { links[s.pkg]?.service?.resize(id, cols, rows) }
    }

    fun kill(ctx: Context, id: String) {
        val s = shells.remove(id) ?: return
        runCatching { links[s.pkg]?.service?.close(id) }
        runCatching { s.pfd.close() }
        TerminalWakeLock.syncNative(ctx, shells.size)
    }

    /** Runs a POSIX sh [script] in [t]'s env; stdout, or throws naming why not. */
    fun exec(ctx: Context, t: TerminalTargets.Target, script: String): String {
        val (svc, verdict) = connect(ctx, t)
        if (svc == null) throw Unreachable(
            TerminalRoute.explain(verdict.first, TerminalRoute.Reason.OK, t.label, t.host, t.port, verdict.second))
        val b = svc.exec(script, EXEC_TIMEOUT_MS)
        b.getString("error")?.let { throw Unreachable(it) }
        if (b.getInt("exit", -1) != 0)
            throw Unreachable(b.getString("stderr")?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "exit ${b.getInt("exit", -1)}")
        return b.getString("stdout") ?: ""
    }

    /** True when [t] is served natively (so fs calls skip SSH). */
    fun native(ctx: Context, t: TerminalTargets.Target) = connect(ctx, t).first != null

    fun disconnectAll(ctx: Context) {
        shells.keys.toList().forEach { kill(ctx, it) }
        links.values.forEach { it.release() }
        links.clear()
    }
}
