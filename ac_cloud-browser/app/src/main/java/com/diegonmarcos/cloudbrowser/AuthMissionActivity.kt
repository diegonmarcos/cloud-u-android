package com.diegonmarcos.cloudbrowser

import android.app.Activity
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.URI

/**
 * #684 THE FLEET BROWSER'S AUTH-MISSION SURFACE. cloud-browser IS the fleet's browser, so it
 * ANSWERS the mission a fleet app (cloud-drive's Sync ▸ Git) fires: open a sign-in page
 * full-screen, stay on the declared hosts, and capture the session cookie — then finish with the
 * capture as this activity's RESULT, reaching the caller ONLY. Nothing is broadcast, nothing is
 * stored, nothing is logged.
 *
 * #689 COOKIE ONLY. The OAuth redirect-landing capture served one caller, the GitHub OAuth-App
 * flow, and was deleted with it (the GitHub leg is gh's own sign-in now). A mission asking for
 * any other capture is REFUSED in words rather than opened with no way to finish.
 *
 * DATA-DRIVEN AND DECLARED. Every intent extra and result key is read off the contract baked
 * into [BuildConfig.AUTH_MISSION_B64] from `ab_cloud-libs-shared/build.json::auth.browser_mission`
 * (libs:auth's own declaration, with `{package}` resolved to this app). This file names no key
 * literal, so a rename in the declaration moves both the caller (libs:auth) and this answerer
 * together. The activity is guarded by a SIGNATURE-level permission in the manifest: the fleet
 * shares one signing key, so only a fleet app can start a mission — no third-party app can ask
 * this browser for a cookie.
 */
class AuthMissionActivity : AppCompatActivity() {

    private val contract by lazy {
        JSONObject(String(Base64.decode(BuildConfig.AUTH_MISSION_B64, Base64.DEFAULT), Charsets.UTF_8))
    }
    private fun extras() = contract.optJSONObject("extras") ?: JSONObject()
    private fun results() = contract.optJSONObject("results") ?: JSONObject()

    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ex = extras()
        val url = intent.getStringExtra(ex.optString("url")).orEmpty()
        val capture = intent.getStringExtra(ex.optString("capture")).orEmpty()
        val cookieUrl = intent.getStringExtra(ex.optString("cookie_url")).orEmpty()
        val allow = intent.getStringArrayExtra(ex.optString("allow_hosts"))?.toList().orEmpty()
        if (url.isBlank()) { refuse("no url in the mission"); return }
        if (capture != CAPTURE_COOKIE) { refuse("this browser captures a session cookie only; '$capture' is not a declared capture"); return }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val web = WebView(this)
        column.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // "Done" reads the session cookie for the declared cookie_url and returns it.
        val done = Button(this).apply {
            text = getString(R.string.auth_mission_done)
            setOnClickListener {
                val cookie = CookieManager.getInstance().getCookie(cookieUrl).orEmpty()
                if (cookie.isBlank()) text = getString(R.string.auth_mission_no_cookie)
                else returnCookie(cookie)
            }
        }
        column.addView(done, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString().orEmpty()
                // Confine navigation to the declared hosts.
                if (!allowed(allow, target)) return true
                return false
            }
        }
        setContentView(column)
        web.loadUrl(url)
    }

    override fun onBackPressed() {
        if (!captured) cancel()
        super.onBackPressed()
    }

    private fun allowed(allow: List<String>, url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().lowercase()
        if (host.isBlank()) return false
        return allow.any { val a = it.lowercase(); host == a || host.endsWith(".$a") }
    }

    private fun returnCookie(cookie: String) {
        captured = true
        val r = results()
        setResult(Activity.RESULT_OK, android.content.Intent()
            .putExtra(r.optString("outcome"), OUTCOME_CAPTURED)
            .putExtra(r.optString("cookie"), cookie))
        finish()
    }

    private fun cancel() {
        val r = results()
        setResult(Activity.RESULT_OK, android.content.Intent()
            .putExtra(r.optString("outcome"), OUTCOME_CANCELLED))
    }

    private fun refuse(why: String) {
        val r = results()
        setResult(Activity.RESULT_OK, android.content.Intent()
            .putExtra(r.optString("outcome"), OUTCOME_REFUSED)
            .putExtra(r.optString("why"), why))
        finish()
    }

    private companion object {
        const val CAPTURE_COOKIE = "cookie"
        const val OUTCOME_CAPTURED = "captured"
        const val OUTCOME_CANCELLED = "cancelled"
        const val OUTCOME_REFUSED = "refused"
    }
}
