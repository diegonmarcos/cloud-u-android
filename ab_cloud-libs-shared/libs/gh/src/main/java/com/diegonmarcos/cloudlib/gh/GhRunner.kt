package com.diegonmarcos.cloudlib.gh

import android.content.Context
import java.io.File

/**
 * Execs the bundled static gh. The binary lives where the installer put it —
 * applicationInfo.nativeLibraryDir/libgh.so — because that is the one place an
 * app may exec from on API 29+ (see data/gh-binary.json). Blocking; callers run
 * it on IO.
 *
 * SELF-CONTAINED, MEASURED. A real authenticated `gh api /user` round-trip
 * spawned ZERO child processes: no git, no PATH, nothing from `$HOME`. So the
 * environment below is built from nothing rather than inherited — [GH_CONFIG_DIR]
 * points gh's whole config and state tree at the app's own files, and `HOME` is
 * pointed at the same place so a gh that ever consults it cannot reach outside
 * the sandbox.
 *
 * THE TOKEN NEVER APPEARS IN AN ARGUMENT OR IN OUTPUT WE KEEP. It is passed in
 * the environment as `GH_TOKEN`, which keeps it out of argv (and therefore out
 * of /proc/<pid>/cmdline, out of any process listing and out of every log line
 * this class produces). Nothing here calls `gh auth token`, which would print it
 * to stdout; nothing here renders, toasts or logs it, whole or truncated.
 */
class GhRunner(context: Context) {

    val binary: File = File(context.applicationInfo.nativeLibraryDir, BuildConfig.GH_JNI_NAME)

    /**
     * gh's config AND state directory. `GH_CONFIG_DIR` overrides both, measured:
     * with it set, gh needs nothing from `$HOME` at all and writes only here.
     */
    val configDir: File = File(context.filesDir, "gh")

    val isAvailable: Boolean get() = binary.isFile

    val version: String get() = BuildConfig.GH_VERSION

    /** The outcome of one gh invocation: never an exit code alone. */
    class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /**
     * Run gh with [args], authenticating with [token] if one is given. The token
     * goes into the environment, never into [args].
     */
    fun run(args: List<String>, token: String? = null): Result {
        check(isAvailable) { "gh is not installed: ${binary.absolutePath} is missing" }
        configDir.mkdirs()
        val builder = ProcessBuilder(listOf(binary.absolutePath) + args).redirectErrorStream(true)
        builder.environment().apply {
            // Built from nothing: gh needs no inherited environment, and an
            // inherited one is how a stray variable changes behaviour on one
            // phone and not another.
            clear()
            put("GH_CONFIG_DIR", configDir.absolutePath)
            put("HOME", configDir.absolutePath)
            put("GH_NO_UPDATE_NOTIFIER", "1")
            put("GH_PROMPT_DISABLED", "1")
            if (!token.isNullOrBlank()) put("GH_TOKEN", token)
        }
        val process = builder.start()
        val text = process.inputStream.bufferedReader().readText()
        return Result(process.waitFor(), text)
    }

    /**
     * A REST call through gh's own API client — this is the leg #641/#642 left
     * open. Listing a user's repositories is `/user/repos`, which is REST and
     * not the git protocol, so it needs a token no matter which rung of the
     * chain supplied it. Measured to work with zero child execs.
     */
    fun api(path: String, token: String): Result = run(listOf("api", path), token)

    /**
     * Does [token] actually authenticate? Asks GitHub, and returns the answer as
     * a sentence rather than a code. The token is NOT included in the message on
     * either branch.
     */
    fun checkToken(token: String): Pair<Boolean, String> {
        val r = api("/user", token)
        return if (r.ok && r.output.contains("\"login\"")) {
            true to "GitHub accepted the credential"
        } else {
            false to "GitHub refused the credential (gh exited ${r.exitCode})"
        }
    }

    companion object {
        /**
         * The GitHub App client ids compiled into the pinned binary, straight off
         * data/gh-binary.json::client_ids. These are GITHUB'S OWN app's ids, not
         * ours: both were exercised live against the device-code endpoint, which
         * is why this rung needs nothing registered on our side. Not a literal
         * list — the pin is baked into [BuildConfig.GH_CLIENT_IDS].
         */
        val CLIENT_IDS: List<String> =
            BuildConfig.GH_CLIENT_IDS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
