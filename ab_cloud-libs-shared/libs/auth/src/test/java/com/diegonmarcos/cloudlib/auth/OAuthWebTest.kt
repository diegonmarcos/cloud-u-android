package com.diegonmarcos.cloudlib.auth

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #684 the authorization-code web flow and the browser auth mission, asserted on the RULES the
 * phone runs: a landing with another attempt's state is refused, a landing off the redirect is
 * not a landing, navigation is confined to the declared hosts, and an unconfigured client says
 * so instead of starting. The declared client is read off the repository's own build.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OAuthWebTest {

    private fun declaredAuth(): JSONObject {
        val f = listOf("../../build.json", "../build.json", "build.json").map { File(it) }
            .firstOrNull { it.isFile && JSONObject(it.readText()).has("auth") }
            ?: error("ab_cloud-libs-shared/build.json not found above the module")
        return JSONObject(f.readText()).getJSONObject("auth")
    }

    private val client = OAuthWeb.Client(
        authorizeUrl = "https://provider.example/login/oauth/authorize",
        tokenUrl = "https://provider.example/login/oauth/access_token",
        userinfoUrl = "https://api.provider.example/user",
        clientId = "id-1",
        clientSecret = "secret-1",
        scope = "repo",
        redirectUri = "https://fleet.example/git/oauth/landed",
        allowHosts = listOf("provider.example"),
    )

    @Test fun `the declared github web client is complete, secretless, and therefore NOT configured in the tree`() {
        val gh = declaredAuth().getJSONObject("git_chain").getJSONObject("providers").getJSONObject("github")
        val c = OAuthWeb.parse(gh.optJSONObject("web_client"))!!
        assertTrue("the flow must be describable from the declaration", c.declared)
        assertEquals("", c.clientSecret)
        assertFalse("a committed secret would be a leak; an empty one is a declared absence", c.configured)
        assertTrue(c.allowHosts.isNotEmpty())
        assertTrue(c.redirectUri.startsWith("https://"))
        // The rung reader exposes the same client.
        val rung = AuthDeclaration.gitChain(declaredAuth().getJSONObject("git_chain")).first { it.id == "github" }
        assertEquals(c, rung.webClient)
    }

    @Test fun `authorize url carries client, redirect, scope and state, each encoded once`() {
        val url = client.authorizeUrl("abc123")
        assertTrue(url.startsWith(client.authorizeUrl + "?"))
        assertTrue(url.contains("client_id=id-1"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Ffleet.example%2Fgit%2Foauth%2Flanded"))
        assertTrue(url.contains("scope=repo"))
        assertTrue(url.contains("state=abc123"))
    }

    @Test fun `a landing with the right state yields the code, another state is refused, off-redirect is not a landing`() {
        val ok = OAuthWeb.landing(client, client.redirectUri + "?code=c0de&state=s1", "s1")
        assertEquals(OAuthWeb.Landing.Code("c0de"), ok)
        assertEquals(OAuthWeb.Landing.StateMismatch, OAuthWeb.landing(client, client.redirectUri + "?code=c0de&state=OTHER", "s1"))
        assertEquals(OAuthWeb.Landing.StateMismatch, OAuthWeb.landing(client, client.redirectUri + "?code=c0de", "s1"))
        assertEquals(OAuthWeb.Landing.NotALanding, OAuthWeb.landing(client, "https://provider.example/login?x=1", "s1"))
        val denied = OAuthWeb.landing(client, client.redirectUri + "?error=access_denied&error_description=no&state=s1", "s1")
        assertTrue(denied is OAuthWeb.Landing.Denied && denied.why.startsWith("access_denied"))
        // No code, no state: nothing to exchange, and the toString never prints one.
        assertEquals("Code(<redacted>)", ok.toString())
    }

    @Test fun `navigation is confined to the declared hosts and the landing`() {
        assertTrue(OAuthWeb.allowed(client.allowHosts, client.redirectUri, "https://provider.example/login"))
        assertTrue(OAuthWeb.allowed(client.allowHosts, client.redirectUri, "https://sub.provider.example/x"))
        assertTrue(OAuthWeb.allowed(client.allowHosts, client.redirectUri, client.redirectUri + "?code=1&state=2"))
        assertFalse(OAuthWeb.allowed(client.allowHosts, client.redirectUri, "https://evil.example/provider.example"))
        assertFalse(OAuthWeb.allowed(client.allowHosts, client.redirectUri, "https://fleet.example/other"))
    }

    @Test fun `an unconfigured client refuses to exchange instead of dialing`() {
        val r = OAuthWeb.exchange(client.copy(clientSecret = ""), "c0de")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("not configured"))
        assertNull(OAuthWeb.identity(client.copy(userinfoUrl = ""), "t"))
    }

    @Test fun `states are fresh and never blank`() {
        val a = OAuthWeb.newState(); val b = OAuthWeb.newState()
        assertEquals(32, a.length)
        assertTrue(a != b)
    }

    // ── the browser mission ─────────────────────────────────────────────

    private val contract = AuthMission.Contract(
        pkg = "com.example.browser", action = "com.example.browser.AUTH_MISSION",
        permission = "com.example.browser.permission.AUTH_MISSION",
        extras = listOf("url", "title", "allow_hosts", "capture", "cookie_url", "redirect_prefix").associateWith { "x.$it" },
        results = listOf("outcome", "cookie", "redirect_url", "why").associateWith { "r.$it" },
        captures = listOf("cookie", "redirect"),
    )

    @Test fun `decide names every failure in order: declared, installed, granted, answering`() {
        assertEquals(AuthMission.Outcome.NotDeclared, AuthMission.decide(null, true, true, true))
        assertEquals(AuthMission.Outcome.NotDeclared, AuthMission.decide(contract.copy(action = ""), true, true, true))
        assertEquals(AuthMission.Outcome.NotInstalled(contract.pkg), AuthMission.decide(contract, false, false, false))
        assertEquals(AuthMission.Outcome.NotGranted(contract.permission), AuthMission.decide(contract, true, false, false))
        assertEquals(AuthMission.Outcome.NoActivity(contract.action), AuthMission.decide(contract, true, true, false))
        assertEquals(AuthMission.Outcome.Sent, AuthMission.decide(contract, true, true, true))
    }

    @Test fun `the declared mission contract resolves to a complete contract once the package is set`() {
        val raw = declaredAuth().getJSONObject("browser_mission")
        assertEquals("browser", raw.getString("fleet"))
        // The bake substitutes {package} and sets `package`; mirror all of it.
        val baked = JSONObject(raw.toString().replace("{package}", "com.example.browser")).put("package", "com.example.browser")
        val c = AuthMission.parse(baked)!!
        assertTrue("contract incomplete: $c", c.declared)
        assertEquals("com.example.browser.AUTH_MISSION", c.action)
        assertEquals("com.example.browser.permission.AUTH_MISSION", c.permission)
        assertEquals(listOf("cookie", "redirect"), c.captures)
        // Unresolved, it is NOT declared: a {package} left in is an intent aimed at nothing.
        assertFalse(AuthMission.parse(raw)!!.declared)
    }

    @Test fun `the result is read off the declared keys and a capture with nothing in it is malformed`() {
        val ok = android.content.Intent().putExtra("r.outcome", AuthMission.OUTCOME_CAPTURED).putExtra("r.cookie", "sid=1")
        assertEquals(AuthMission.Capture.Cookie("sid=1"), AuthMission.read(contract, android.app.Activity.RESULT_OK, ok))
        val land = android.content.Intent().putExtra("r.outcome", AuthMission.OUTCOME_CAPTURED).putExtra("r.redirect_url", "https://f/landed?code=1")
        assertEquals(AuthMission.Capture.Redirect("https://f/landed?code=1"), AuthMission.read(contract, android.app.Activity.RESULT_OK, land))
        assertEquals(AuthMission.Capture.Cancelled, AuthMission.read(contract, android.app.Activity.RESULT_CANCELED, null))
        val empty = android.content.Intent().putExtra("r.outcome", AuthMission.OUTCOME_CAPTURED)
        assertTrue(AuthMission.read(contract, android.app.Activity.RESULT_OK, empty) is AuthMission.Capture.Malformed)
        val refused = android.content.Intent().putExtra("r.outcome", AuthMission.OUTCOME_REFUSED).putExtra("r.why", "no")
        assertEquals(AuthMission.Capture.Refused("no"), AuthMission.read(contract, android.app.Activity.RESULT_OK, refused))
        assertEquals("Cookie(value=<redacted>)", AuthMission.Capture.Cookie("sid=1").toString())
    }

    @Test fun `the intent carries every request field under the declared extra names`() {
        val i = AuthMission.intent(contract, AuthMission.Request("https://p/a", "T", listOf("p"), "redirect", "", "https://f/landed"))
        assertEquals(contract.action, i.action)
        assertEquals(contract.pkg, i.`package`)
        assertEquals("https://p/a", i.getStringExtra("x.url"))
        assertEquals("redirect", i.getStringExtra("x.capture"))
        assertEquals("https://f/landed", i.getStringExtra("x.redirect_prefix"))
        assertEquals(listOf("p"), i.getStringArrayExtra("x.allow_hosts")!!.toList())
    }
}
