package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.cloudlib.rclone.RcloneConfig
import com.diegonmarcos.cloudlib.rclone.RcloneRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mounts > Rclone: the real `rclone config` verbs, called through the rclone engine ([RcloneRunner.capture], which carries
 * the phone's rclone.conf in and keeps the one rclone rewrote). Blocking: callers run it on IO. Every function returns a
 * [Result] whose failure message is rclone's own last line, never an argument (arguments hold the secrets) and never a
 * value.
 */
class RcloneConfigOps(private val runner: RcloneRunner) {

    private fun run(args: List<String>): Result<String> = runCatching {
        val err = StringBuilder()
        val (rc, out) = runner.capture(args) { err.append(it).append('\n') }
        if (rc != 0) error(err.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" · ").ifBlank { "rclone exited $rc" }.take(300))
        out
    }

    /** The backends and their option schema, from rclone itself. An engine that answers nothing usable is a failure, not an empty list. */
    fun providers(): Result<List<RcloneProviders.Provider>> = run(RcloneProviders.providersArgs()).mapCatching {
        RcloneProviders.parse(it).filterNot { p -> p.hidden }.also { list -> check(list.isNotEmpty()) { "rclone returned no backends" } }
    }

    fun create(name: String, type: String, values: Map<String, String>): Result<Unit> = runCatching { RcloneProviders.createArgs(name, type, values) }.mapCatching { run(it).getOrThrow(); Unit }

    fun update(name: String, values: Map<String, String>): Result<Unit> = runCatching { RcloneProviders.updateArgs(name, values) }.mapCatching { run(it).getOrThrow(); Unit }

    fun delete(name: String): Result<Unit> = run(RcloneProviders.deleteArgs(name)).map { }

    /** `rclone config show <name>`: the type and options as rclone.conf holds them (the caller masks secrets before drawing). */
    fun show(name: String): Result<Pair<String, Map<String, String>>> = run(RcloneProviders.showArgs(name)).mapCatching { out ->
        val remote = RcloneConfig.parse(out).firstOrNull() ?: error("rclone shows no remote named $name")
        remote.type to remote.options
    }

    /** Test: `rclone lsd name:` lists the top-level folders. */
    fun lsd(name: String): Result<List<String>> = run(RcloneProviders.lsdArgs(name)).map { RcloneProviders.lsd(it) }

    fun about(name: String): Result<RcloneProviders.About> = run(RcloneProviders.aboutArgs(name)).mapCatching { RcloneProviders.about(it) ?: error("this backend does not report usage") }

    fun drives(name: String): Result<List<RcloneProviders.SharedDrive>> = run(RcloneProviders.drivesArgs(name)).map { RcloneProviders.drives(it) }

    /**
     * rclone's own browser flow: [onUrl] gets the sign-in link once rclone prints it (open it in this phone's browser; the
     * callback lands on 127.0.0.1 here), [onDone] the token JSON or the failure. Cancel through the returned handle.
     */
    fun authorize(type: String, clientId: String, clientSecret: String, onUrl: (String) -> Unit, onDone: (Result<String>) -> Unit): RcloneRunner.Handle {
        val lines = CopyOnWriteArrayList<String>()
        val announced = java.util.concurrent.atomic.AtomicBoolean(false)
        return runner.stream(
            RcloneProviders.authorizeArgs(type, clientId, clientSecret),
            onLine = { l -> lines += l; if (!announced.get()) RcloneProviders.authUrl(listOf(l))?.let { if (announced.compareAndSet(false, true)) onUrl(it) } },
            onExit = { rc ->
                val token = RcloneProviders.token(lines.joinToString("\n"))
                lines.clear()
                onDone(when {
                    rc == 0 && token != null -> Result.success(token)
                    rc == -2 -> Result.failure(IllegalStateException("sign-in cancelled"))
                    else -> Result.failure(IllegalStateException("rclone ended without a token (exit $rc)"))
                })
            },
        )
    }

    /** `rclone serve webdav name:path` on loopback, until the handle is cancelled; [onLine] gets rclone's own lines. */
    fun serve(source: String, port: Int, cacheMode: String, onLine: (String) -> Unit, onExit: (Int) -> Unit): RcloneRunner.Handle =
        runner.stream(listOf("serve", "webdav", source, "--addr", "127.0.0.1:$port", "--vfs-cache-mode", cacheMode), onLine, onExit)
}
