package com.diegonmarcos.cloudlib.gh

import com.diegonmarcos.superapp.core.DataBackendService
import org.json.JSONObject

/**
 * #705 libs:gh AS AN ENGINE. gh runs HERE — inside Cloud-Lib-Gh.apk, in that APK's own
 * process and uid — behind core's IDataBackend, and an app that wants GitHub binds this
 * service instead of compiling gh in. Cloud Drive carried the 39 MB binary itself until
 * #705, so every gh bump republished Cloud Drive; now it republishes this one APK.
 *
 * WHERE gh's STATE LIVES: [GhRunner.configDir] is this APK's own files, so the sign-in gh
 * keeps belongs to the engine, and every fleet app that binds it shares that one GitHub
 * sign-in. The uid is also why gh can be an engine at all: it reads and writes no shared
 * storage. rclone and git-sync do, which is why they are not engines (a0_docs/eng-specs/
 * engine-apk-split.md).
 *
 * THE CONTRACT is [methodNames] plus the JSON each answers, versioned by the CONTRACT
 * meta-data on this service in the manifest. It only GROWS: a method is never removed or
 * re-shaped while a published client may call it — a change that would break an answer
 * ships under a new method name and raises the contract — so an engine update cannot
 * break an older app. A client reads the contract through PackageManager before it binds,
 * so an engine too old to serve it is named as such instead of reading like a missing one.
 *
 * THE CREDENTIAL CROSSES THE BINDER, deliberately: [CREDENTIAL] hands a clone the
 * credential gh holds, as GhRunner.credential did in-process. The service is guarded by
 * CONSTELLATION_DATA (signature-level), so only APKs signed with the fleet key can ask.
 * Nothing here logs an answer.
 *
 * SIGN-IN IS POLLED, NOT STREAMED: `gh auth login` blocks until GitHub approves the code,
 * and a binder call must not. [LOGIN_START] starts it on a thread and returns at once;
 * [LOGIN_POLL] reports the one-time code and the page as gh prints them — read HERE, with
 * the pin's own patterns (GhOutput), so a client never parses gh's output — and gh's exit
 * once it ends.
 *
 * Dispatch is an explicit `when`, not reflection, for the reason DataBackendService gives.
 */
class GhBackendService : DataBackendService() {

    private val runner: GhRunner by lazy { GhRunner(applicationContext) }

    override fun methodNames(): Array<String> = arrayOf(STATUS, REPO_LIST, CREDENTIAL, LOGIN_START, LOGIN_POLL)

    override fun dispatch(method: String, args: Array<String>): String = when (method) {
        STATUS -> result(runner.status(arg(args, 0)))
        REPO_LIST -> result(runner.repoList(arg(args, 0).toInt(), arg(args, 1)))
        CREDENTIAL -> runner.credential(arg(args, 0))
            ?.let { JSONObject().put("username", it.username).put("secret", it.secret).toString() }
            ?: "{}"
        LOGIN_START -> Login.start(runner, arg(args, 0))
        LOGIN_POLL -> Login.poll()
        else -> throw IllegalArgumentException("the gh engine does not answer '$method'")
    }

    private fun result(r: GhRunner.Result): String =
        JSONObject().put("exit", r.exitCode).put("output", r.output).toString()

    private fun arg(args: Array<String>, i: Int): String =
        requireNotNull(args.getOrNull(i)?.takeIf { it.isNotBlank() }) { "argument ${i + 1} is missing" }

    /** One sign-in at a time per engine process: gh keeps ONE config, and two logins would race it. */
    private object Login {
        private var thread: Thread? = null
        @Volatile private var code = ""
        @Volatile private var url = ""
        @Volatile private var ended: GhRunner.Result? = null

        @Synchronized
        fun start(runner: GhRunner, host: String): String {
            val running = thread?.isAlive == true
            if (!running) {
                code = ""; url = ""; ended = null
                thread = Thread({
                    ended = runner.login(host) { line ->
                        if (code.isEmpty()) GhOutput.deviceCode(line)?.let { code = it }
                        if (url.isEmpty()) GhOutput.verificationUrl(line, host)?.let { url = it }
                    }
                }, "gh-login").apply { isDaemon = true; start() }
            }
            return JSONObject().put("started", !running).toString()
        }

        @Synchronized
        fun poll(): String {
            val done = ended
            val o = JSONObject().put("code", code).put("url", url)
            return when {
                done != null -> o.put("running", false).put("exit", done.exitCode).put("output", done.output)
                thread?.isAlive == true -> o.put("running", true)
                else -> o.put("running", false).put("exit", GhRunner.EXEC_FAILED).put("output", "no gh sign-in was started")
            }.toString()
        }
    }

    companion object {
        const val STATUS = "status"
        const val REPO_LIST = "repoList"
        const val CREDENTIAL = "credential"
        const val LOGIN_START = "loginStart"
        const val LOGIN_POLL = "loginPoll"
    }
}
