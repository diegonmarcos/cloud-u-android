package com.diegonmarcos.clouddrive.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONObject

/**
 * #705 gh, REACHED, NOT CARRIED. The GitHub card used to exec gh out of this app's own
 * nativeLibraryDir, which made a 39 MB binary part of Cloud Drive and every gh bump a Cloud
 * Drive release. gh now runs in Cloud-Lib-Gh.apk (libs:gh's GhBackendService, installed by
 * the Store) and this is the thin client the page talks to instead: the same calls GhRunner
 * offered, each one a request over core's IDataBackend.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [check] asks PackageManager for the service
 * that answers the declared action in the declared package (build.json::engines.gh, both
 * resolved at build time from the fleet manifest) and reads the CONTRACT it declares. No
 * package → [Check.NotInstalled]; a package with no such service, or one declaring a contract
 * below what this build calls → [Check.TooOld]. Each is a different sentence on the card,
 * because "install it" and "update it" are different next steps. Only a ready engine is bound.
 *
 * WHAT CROSSES: the page's questions and gh's answers as JSON. The engine parses gh's sign-in
 * output with its own pinned patterns and hands back the code and the page, so nothing here
 * knows how a given gh version prints. The credential crosses too — it always reached the
 * clone — and it lands in a [Credential] whose toString never prints it. Nothing here logs.
 *
 * BLOCKING, like GhRunner was: call everything but [check] off the main thread.
 */
class GhEngine(context: Context) {

    private val ctx = context.applicationContext

    @Volatile private var client: DataBackendClient? = null

    /** What stands between this phone and the gh engine. */
    sealed class Check {
        object Ready : Check()
        /** The engine APK is not on the phone. */
        data class NotInstalled(val pkg: String) : Check()
        /** Installed, but its service declares [found] (0 = no engine service at all) and this build needs [needed]. */
        data class TooOld(val pkg: String, val found: Int, val needed: Int) : Check()
    }

    /** The outcome of one gh invocation in the engine: never an exit code alone. */
    class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** A git credential gh answered with. Its toString never prints the secret. */
    class Credential(val username: String, val secret: String) {
        override fun toString(): String = "Credential(username=$username, secret=<redacted>)"
    }

    fun check(): Check {
        val pm = ctx.packageManager
        val pkg = BuildConfig.GH_ENGINE_PACKAGE
        val needed = BuildConfig.GH_ENGINE_MIN_CONTRACT
        val service = pm.resolveService(Intent(BuildConfig.GH_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) Check.TooOld(pkg, 0, needed) else Check.NotInstalled(pkg)
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return Check.TooOld(pkg, found, needed)
        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx, service.packageName, service.name)
        }
        return Check.Ready
    }

    /** `gh auth status` for [host], as gh printed it. */
    fun status(host: String): Result = result(ask(STATUS, host))

    /** `gh repo list` with [fields], as gh printed it. */
    fun repoList(limit: Int, fields: String): Result = result(ask(REPO_LIST, limit.toString(), fields))

    /** The credential gh holds for [host], or null when it holds none or cannot answer. */
    fun credential(host: String): Credential? {
        val o = ask(CREDENTIAL, host)
        val username = o.optString("username")
        val secret = o.optString("secret")
        return if (username.isBlank() || secret.isBlank()) null else Credential(username, secret)
    }

    /**
     * gh's own `auth login`, run by the engine. [onPrompt] gets the one-time code and the page
     * whenever either changes, while gh is still waiting for GitHub; this returns once gh exits
     * (or after the declared ceiling, if the engine stops answering).
     */
    fun login(host: String, onPrompt: (code: String, url: String) -> Unit): Result {
        val started = ask(LOGIN_START, host)
        if (started.has(ERROR)) return Result(EXEC_FAILED, started.optString(ERROR))
        var code = ""
        var url = ""
        val deadline = SystemClock.elapsedRealtime() + BuildConfig.GH_ENGINE_LOGIN_MAX_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(BuildConfig.GH_ENGINE_POLL_MS)
            val p = ask(LOGIN_POLL)
            if (p.has(ERROR)) return Result(EXEC_FAILED, p.optString(ERROR))
            val c = p.optString("code")
            val u = p.optString("url")
            if (c != code || u != url) { code = c; url = u; onPrompt(c, u) }
            if (!p.optBoolean("running")) return Result(p.optInt("exit", EXEC_FAILED), p.optString("output"))
        }
        return Result(EXEC_FAILED, "gh sign-in did not end within ${BuildConfig.GH_ENGINE_LOGIN_MAX_MS / 60_000} minutes")
    }

    private fun ask(method: String, vararg args: String): JSONObject {
        val why = check()
        val c = client
        if (why !is Check.Ready || c == null) return JSONObject().put(ERROR, "the gh engine is not ready: $why")
        val text = c.call(method, *args)
        return runCatching { JSONObject(text) }
            .getOrElse { JSONObject().put(ERROR, "the gh engine answered $method with something that is not JSON") }
    }

    private fun result(o: JSONObject): Result =
        if (o.has(ERROR)) Result(EXEC_FAILED, o.optString(ERROR))
        else Result(o.optInt("exit", EXEC_FAILED), o.optString("output"))

    companion object {
        /** Same exit code GhRunner used for a gh that could not run at all: no real one is negative. */
        const val EXEC_FAILED = -1

        /** The engine CONTRACT meta-data key every engine service declares (libs/gh's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

        // The engine's method names (GhBackendService). Strings, not an import: this app does
        // not compile libs:gh, and that is the point.
        const val STATUS = "status"
        const val REPO_LIST = "repoList"
        const val CREDENTIAL = "credential"
        const val LOGIN_START = "loginStart"
        const val LOGIN_POLL = "loginPoll"

        private const val ERROR = "error"

        /** [url] when it is a page on [host], else null — a page gh names is only ever opened on the declared host. */
        fun pageOnHost(url: String, host: String): String? =
            url.takeIf { it.isNotBlank() && runCatching { java.net.URI(it).host }.getOrNull() == host }
    }
}
