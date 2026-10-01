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
 *
 * #689 gh IS THE GITHUB LEG. [login] runs gh's OWN `auth login` (gh's device flow,
 * against the client id GitHub CLI compiles into this binary — the fleet supplies no
 * client id, no secret and no redirect), [status] reads who gh is signed in as,
 * [repoList] lists that account's repositories and [credential] hands the clone the
 * credential gh holds, through gh's own git-credential helper. gh keeps that
 * credential in [configDir], the app's private files.
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
     * goes into the environment, never into [args]. #689 a gh that cannot even be
     * exec'd is an outcome too ([EXEC_FAILED], with the platform's words), never an
     * exception thrown into a caller's coroutine.
     */
    fun run(args: List<String>, token: String? = null): Result = try {
        val process = start(args, token)
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().readText()
        Result(process.waitFor(), text)
    } catch (e: Exception) {
        Result(EXEC_FAILED, "gh could not run: ${e.message ?: e.javaClass.simpleName}")
    }

    /** One gh process: argv as given, stderr folded into stdout, environment built from nothing. */
    private fun start(args: List<String>, token: String?): Process {
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
        return builder.start()
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

    /**
     * #689 who gh ITSELF is signed in as on [host]: `gh auth status --json hosts`. MEASURED on the
     * pinned 2.101.0: the JSON carries state, login, token source, scopes and git protocol and
     * never the token; signed OUT it still exits 0, printing a sentence and then `{"hosts":{}}`,
     * so the caller reads the JSON line and never trusts the exit code alone.
     */
    fun status(host: String): Result = run(listOf("auth", "status", "--hostname", host, "--json", "hosts"))

    /**
     * #689 gh's OWN sign-in, `gh auth login`. With no TTY (how this runs) gh goes straight to its
     * device flow: it prints a one-time code and the page to enter it at, then polls until the code
     * is approved or expires. [onLine] gets every line AS gh prints it, because the code has to
     * reach the screen while gh is still waiting; this returns when gh exits.
     *
     * `--insecure-storage`: Android has no keyring for gh to use, so its credential is written to
     * [configDir], the app's private files. `--clipboard=false`: gh cannot reach Android's
     * clipboard (measured: it warns and names desktop tools); the page copies the code itself.
     * No GH_TOKEN is ever passed here — gh refuses to log in while one is set.
     */
    fun login(host: String, onLine: (String) -> Unit): Result = try {
        val process = start(
            listOf("auth", "login", "--hostname", host, "--git-protocol", "https", "--web",
                "--insecure-storage", "--skip-ssh-key", "--clipboard=false"),
            token = null,
        )
        process.outputStream.close()
        val out = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { out.appendLine(it); onLine(it) }
        Result(process.waitFor(), out.toString())
    } catch (e: Exception) {
        Result(EXEC_FAILED, "gh could not run: ${e.message ?: e.javaClass.simpleName}")
    }

    /** #689 the signed-in account's own repositories, public and private, as gh's JSON [fields]. */
    fun repoList(limit: Int, fields: String): Result =
        run(listOf("repo", "list", "--limit", limit.toString(), "--json", fields))

    /**
     * #689 THE CREDENTIAL gh HOLDS for [host], asked the way git asks it: gh's own git-credential
     * helper, the very protocol `gh repo clone` feeds to git. MEASURED: signed in, gh answers
     * `username=x-access-token` and `password=...`; signed out it answers nothing and exits 1.
     * The answer is read straight into a [Credential] and never into a [Result], so it cannot reach
     * a caller's error line, a log or a screen. Null when gh holds none.
     */
    fun credential(host: String): Credential? {
        if (!isAvailable) return null
        return try {
            val process = start(listOf("auth", "git-credential", "get"), token = null)
            process.outputStream.bufferedWriter().use { it.write("protocol=https\nhost=$host\n\n") }
            val answer = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) GhOutput.credential(answer) else null
        } catch (e: Exception) {
            null
        }
    }

    /** A git credential gh answered with. Its toString never prints the secret. */
    class Credential(val username: String, val secret: String) {
        override fun toString(): String = "Credential(username=$username, secret=<redacted>)"
    }

    companion object {
        /** #689 the exit code of a gh that could not be started at all; no real exit code is negative. */
        const val EXEC_FAILED = -1
    }
}

/**
 * #689 how the PINNED gh speaks, read with the patterns data/gh-binary.json::login_output
 * declares (measured on that version; baked by build.gradle), and the git-credential answer.
 * Pure, so the pin's patterns are the only thing that decides what the page shows.
 */
object GhOutput {
    private val DEVICE_CODE = Regex(BuildConfig.GH_LOGIN_CODE_PATTERN)
    private val VERIFICATION_URL = Regex(BuildConfig.GH_LOGIN_URL_PATTERN)

    /** The one-time code in a line of `gh auth login` output, or null. */
    fun deviceCode(line: String): String? = DEVICE_CODE.find(line)?.value

    /** The page gh says to enter the code at — only ever on [host], so a stray URL is never opened. */
    fun verificationUrl(line: String, host: String): String? =
        VERIFICATION_URL.find(line)?.value?.takeIf { runCatching { java.net.URI(it).host }.getOrNull() == host }

    /** `username=`/`password=` from a git-credential answer; null unless both are there. */
    fun credential(answer: String): GhRunner.Credential? {
        val fields = answer.lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val username = fields["username"].orEmpty()
        val secret = fields["password"].orEmpty()
        return if (username.isBlank() || secret.isBlank()) null else GhRunner.Credential(username, secret)
    }
}
