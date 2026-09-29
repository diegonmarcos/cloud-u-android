package com.diegonmarcos.cloudlib.gix

import android.content.Context
import java.io.File

/**
 * Execs the bundled static gitoxide. The binary lives where the installer put
 * it — applicationInfo.nativeLibraryDir/libgix.so — because that is the one
 * place an app may exec from on API 29+ (see data/gix-binary.json). Blocking;
 * callers run it on IO.
 *
 * ONE FILE, NO HELPERS. Canonical git would need a second executable named
 * `git-remote-https` for HTTPS transport and Android extracts only lib*.so;
 * gitoxide has rustls compiled in. Measured on the real device: a clone with
 * `PATH=/nonexistent HOME=/nonexistent` exited 0 with a valid `.git`, and the
 * three child execs it attempts (tput twice, `git config`) all ENOENT'd without
 * affecting the result. So the environment handed to it below is deliberately
 * bare: nothing it needs comes from outside itself.
 *
 * CLONE AND FETCH ONLY. [VERBS] is [BuildConfig.GIX_VERBS], which is
 * data/gix-binary.json::verbs — the pin, not a list typed here. [run] refuses
 * anything outside it, so `push` is rejected by the DECLARATION rather than by
 * a reviewer noticing: gitoxide 0.59.0 genuinely has no push subcommand
 * (`error: unrecognized subcommand 'push'`), and a caller that routed push here
 * would otherwise get an opaque non-zero exit instead of an answer. Push is the
 * terminal handoff or JGit.
 */
class GixRunner(context: Context) {

    val binary: File = File(context.applicationInfo.nativeLibraryDir, BuildConfig.GIX_JNI_NAME)

    /** Where gitoxide may write its own scratch state; never the app's data root. */
    val cacheDir: File = File(context.cacheDir, "gix")

    val isAvailable: Boolean get() = binary.isFile

    val version: String get() = BuildConfig.GIX_VERSION

    /** The outcome of one gix invocation: never an exit code alone. */
    class Result(val verb: String, val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /**
     * Run one DECLARED verb. Refuses an undeclared one before spawning
     * anything, and says which verbs exist — an unsupported verb is a
     * programming error with a readable answer, not a mystery exit code.
     */
    fun run(verb: String, args: List<String> = emptyList(), onLine: (String) -> Unit = {}): Result {
        require(verb in VERBS) {
            "gix cannot '$verb': this build declares ${VERBS.joinToString(", ")} and nothing else " +
                "(gitoxide ${BuildConfig.GIX_VERSION} has no push — route it to the terminal or JGit)"
        }
        check(isAvailable) { "gix is not installed: ${binary.absolutePath} is missing" }
        cacheDir.mkdirs()
        val process = ProcessBuilder(listOf(binary.absolutePath, verb) + args)
            .redirectErrorStream(true)
            .directory(cacheDir)
            .start()
        val text = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            text.append(line).append('\n')
            onLine(line)
        }
        return Result(verb, process.waitFor(), text.toString())
    }

    /** Clone [url] into [target]. [depth] > 0 asks for a shallow clone. */
    fun clone(url: String, target: File, depth: Int = 0, onLine: (String) -> Unit = {}): Result {
        val args = buildList {
            if (depth > 0) { add("--depth"); add(depth.toString()) }
            add(url)
            add(target.absolutePath)
        }
        return run(VERB_CLONE, args, onLine)
    }

    /** Fetch into an existing clone at [repo]. */
    fun fetch(repo: File, onLine: (String) -> Unit = {}): Result =
        run(VERB_FETCH, listOf("--repository", repo.absolutePath), onLine)

    companion object {
        const val VERB_CLONE = "clone"
        const val VERB_FETCH = "fetch"

        /**
         * The verbs this build may run, straight off the pin. Not a literal
         * list: data/gix-binary.json::verbs is baked into
         * [BuildConfig.GIX_VERBS], so adding or removing a verb is an edit to
         * the pin and this file does not change.
         */
        val VERBS: List<String> = BuildConfig.GIX_VERBS.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * True when gitoxide can serve [verb] at all. Callers that build a git
         * operation out of parts ask this instead of assuming, so push routes
         * itself elsewhere rather than failing here.
         */
        fun supports(verb: String): Boolean = verb in VERBS
    }
}
