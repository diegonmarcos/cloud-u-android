package com.diegonmarcos.cloudlib.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * #684 THE AUTH MISSION — a sign-in that rides the FLEET'S OWN BROWSER (ac_cloud-browser)
 * instead of libs:auth's small in-app WebView dialog.
 *
 * A mission is an intent fired at the browser FOR A RESULT: open [Request.url], stay on
 * [Request.allowHosts], and capture the session cookie for [Request.cookieUrl]. The browser
 * finishes with the capture as its activity result, so it reaches the CALLER ONLY — nothing is
 * broadcast, nothing is stored on either side, and the capture is read once by [read].
 *
 * #689 COOKIE ONLY. The redirect-landing capture existed for one caller, the GitHub OAuth-App
 * authorization-code flow, and was deleted with it: the GitHub leg is gh's own sign-in
 * (libs:gh), which needs no landing, no client id and no client secret of the fleet's own.
 *
 * EVERY NAME IS DECLARED. The action, the permission and every extra/result key come from
 * `ab_cloud-libs-shared/build.json::auth.browser_mission`, with `{package}` resolved from the
 * constellation fleet manifest at build time (libs:auth's build.gradle), so no package id, no
 * action and no extra name is a literal here. The gate is a SIGNATURE-level permission the
 * browser declares: fleet apps share the one constellation signing key, so only a fleet app
 * can ask the browser for a cookie.
 *
 * EVERY FAILURE IS NAMED, in the shape TerminalGit.Outcome set (#642): not declared, not
 * installed, permission not held (a signer mismatch), no activity answering the action, and
 * the platform's own refusal are five different facts with five different remedies, and a
 * blind `startActivityForResult` would flatten them into one silence. [decide] is pure so the
 * JVM suite executes every branch.
 */
object AuthMission {

    const val CAPTURE_COOKIE = "cookie"

    const val OUTCOME_CAPTURED = "captured"
    const val OUTCOME_CANCELLED = "cancelled"
    const val OUTCOME_REFUSED = "refused"

    /** The declared contract, `{package}` already resolved. */
    data class Contract(
        val pkg: String,
        val action: String,
        val permission: String,
        val extras: Map<String, String>,
        val results: Map<String, String>,
        val captures: List<String>,
    ) {
        val declared: Boolean get() =
            pkg.isNotBlank() && action.isNotBlank() && permission.isNotBlank() &&
                listOf("url", "capture", "allow_hosts", "cookie_url").all { !extras[it].isNullOrBlank() } &&
                listOf("outcome", "cookie", "why").all { !results[it].isNullOrBlank() }

        fun extra(key: String): String = extras[key].orEmpty()
        fun result(key: String): String = results[key].orEmpty()
    }

    /** What one mission asks the browser to do. */
    data class Request(
        val url: String,
        val title: String,
        val allowHosts: List<String>,
        val capture: String,
        val cookieUrl: String = "",
    )

    /** The declaration's block, as baked (package resolved, `fleet` removed). Null when absent. */
    fun parse(o: JSONObject?): Contract? {
        o ?: return null
        fun map(key: String): Map<String, String> {
            val m = o.optJSONObject(key) ?: return emptyMap()
            return m.keys().asSequence().associateWith { m.optString(it) }
        }
        val caps = o.optJSONArray("captures")
        return Contract(
            pkg = o.optString("package"),
            action = o.optString("action"),
            permission = o.optString("permission"),
            extras = map("extras"),
            results = map("results"),
            captures = (0 until (caps?.length() ?: 0)).map { caps!!.optString(it) },
        )
    }

    /** What firing a mission resolved to. Everything but [Sent] is a reason the caller must show. */
    sealed class Outcome {
        object Sent : Outcome()
        object NotDeclared : Outcome()
        data class NotInstalled(val pkg: String) : Outcome()
        /** Installed, but this app does not hold the signature permission — a signer mismatch. */
        data class NotGranted(val permission: String) : Outcome()
        data class NoActivity(val action: String) : Outcome()
        data class Refused(val why: String) : Outcome()
    }

    /** Ordered deliberately: declaration, installed, permission, an activity that answers. */
    fun decide(contract: Contract?, installed: Boolean, granted: Boolean, resolves: Boolean): Outcome {
        if (contract == null || !contract.declared) return Outcome.NotDeclared
        if (!installed) return Outcome.NotInstalled(contract.pkg)
        if (!granted) return Outcome.NotGranted(contract.permission)
        if (!resolves) return Outcome.NoActivity(contract.action)
        return Outcome.Sent
    }

    fun intent(contract: Contract, req: Request): Intent = Intent(contract.action).apply {
        setPackage(contract.pkg)
        putExtra(contract.extra("url"), req.url)
        putExtra(contract.extra("title"), req.title)
        putExtra(contract.extra("allow_hosts"), req.allowHosts.toTypedArray())
        putExtra(contract.extra("capture"), req.capture)
        putExtra(contract.extra("cookie_url"), req.cookieUrl)
    }

    fun installed(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** The intent to launch for a result, or the reason there is none. */
    fun plan(ctx: Context, contract: Contract?, req: Request): Pair<Intent?, Outcome> {
        val early = decide(
            contract,
            installed = contract != null && installed(ctx, contract.pkg),
            granted = contract != null && ctx.checkSelfPermission(contract.permission) == PackageManager.PERMISSION_GRANTED,
            resolves = true,
        )
        if (early !is Outcome.Sent) return null to early
        val intent = intent(contract!!, req)
        val resolves = ctx.packageManager.resolveActivity(intent, 0) != null
        val outcome = decide(contract, installed = true, granted = true, resolves = resolves)
        return (if (outcome is Outcome.Sent) intent else null) to outcome
    }

    /** What came back. No variant is logged; [Cookie.value] carries the credential. */
    sealed class Capture {
        data class Cookie(val value: String) : Capture() {
            override fun toString(): String = "Cookie(value=<redacted>)"
        }
        object Cancelled : Capture()
        data class Refused(val why: String) : Capture()
        data class Malformed(val why: String) : Capture()
    }

    /** Read the browser's answer off the declared result keys. */
    fun read(contract: Contract, resultCode: Int, data: Intent?): Capture {
        if (resultCode == Activity.RESULT_CANCELED || data == null) return Capture.Cancelled
        return when (val outcome = data.getStringExtra(contract.result("outcome")).orEmpty()) {
            OUTCOME_CANCELLED -> Capture.Cancelled
            OUTCOME_REFUSED -> Capture.Refused(data.getStringExtra(contract.result("why")).orEmpty().ifBlank { "the browser refused the mission" })
            OUTCOME_CAPTURED -> {
                val cookie = data.getStringExtra(contract.result("cookie")).orEmpty()
                if (cookie.isNotBlank()) Capture.Cookie(cookie)
                else Capture.Malformed("the browser reported a capture but carried no cookie")
            }
            else -> Capture.Malformed("the browser answered an undeclared outcome '$outcome'")
        }
    }
}
