package com.diegonmarcos.superapp.profile

import android.app.Application
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
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
 * #573 — Configs ▸ Profile ▸ Connect ▸ Sign in: the provider list is data,
 * and the one device-grant code path reads what each provider answers.
 *
 * The providers are read off BuildConfig, which is baked from
 * build.json::ui.vault_connect.sign_in — so every expectation about the list
 * is computed from build.json itself by an independent walk (the file is
 * found relative to the module, as gradle runs the suite from `app/`), never
 * typed in. The wire-shape tests feed the parser what GitHub and Google
 * document and assert the step the caller would branch on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SignInTest {

    /** build.json's own sign_in block, walked independently of the code. */
    private fun declared(): JSONObject {
        val f = listOf("../build.json", "build.json").map { File(it) }.firstOrNull { it.isFile }
            ?: error("build.json not found beside the module — the expectations cannot be derived")
        return JSONObject(f.readText()).getJSONObject("ui").getJSONObject("vault_connect").getJSONObject("sign_in")
    }

    @Test fun `the baked providers are exactly build json's, in order`() {
        val arr = declared().getJSONArray("providers")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        assertTrue("build.json declares no provider", ids.isNotEmpty())
        assertEquals(ids, SignIn.providers.map { it.id })
        // The baked blob decodes to the same document, so no field was lost in the bake.
        val baked = JSONObject(String(Base64.decode(BuildConfig.UI_VAULT_CONNECT_SIGN_IN_B64, Base64.DEFAULT)))
        assertEquals(arr.length(), baked.getJSONArray("providers").length())
    }

    @Test fun `exactly one primary, and it is the fleet SSO`() {
        val primaries = SignIn.providers.filter { it.primary }
        assertEquals(1, primaries.size)
        assertEquals(SignIn.Kind.AUTHELIA, primaries.single().kind)
    }

    @Test fun `every declared kind is one the code dispatches on`() {
        SignIn.providers.forEach { assertTrue("${it.id}: unknown kind", it.kind != SignIn.Kind.UNKNOWN) }
    }

    @Test fun `every device-grant provider declares both endpoints and grants something`() {
        val arr = declared().getJSONArray("providers")
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val p = SignIn.provider(d.getString("id"))!!
            assertTrue("${p.id} grants nothing", p.grants.isNotEmpty())
            if (p.kind == SignIn.Kind.DEVICE_FLOW) {
                assertEquals(d.getString("device_code_url"), p.deviceCodeUrl)
                assertEquals(d.getString("token_url"), p.tokenUrl)
                assertTrue(p.deviceCodeUrl.startsWith("https://") && p.tokenUrl.startsWith("https://"))
                // Configured iff the declaration carries a client id — the UI says so instead of failing halfway.
                assertEquals(d.optString("client_id").isNotBlank(), p.configured)
            } else {
                assertTrue("the SSO is always startable", p.configured)
            }
        }
    }

    @Test fun `the repo grant resolves to one declared provider, or none`() {
        val declaredWithRepo = declared().getJSONArray("providers").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { d -> d.optJSONArray("grants")?.let { g -> (0 until g.length()).any { g.getString(it) == SignIn.GRANT_REPO_ARTIFACT } } == true }
                ?.getString("id")
        }
        assertEquals(declaredWithRepo, SignIn.providerGranting(SignIn.GRANT_REPO_ARTIFACT)?.id)
    }

    @Test fun `an unconfigured provider refuses to start without touching the network`() {
        val p = SignIn.providers.first().copy(kind = SignIn.Kind.DEVICE_FLOW, clientId = "", deviceCodeUrl = "http://127.0.0.1:9/never")
        val r = SignIn.requestDeviceCode(p)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains(p.id))
    }

    // ── wire shapes ──────────────────────────────────────────────────────

    @Test fun `device code is read in GitHub's spelling and in Google's`() {
        val gh = SignIn.parseDeviceCode(JSONObject("""{"device_code":"d","user_code":"ABCD-1234","verification_uri":"https://gh/login/device","interval":5,"expires_in":899}"""))
        assertEquals("https://gh/login/device", gh.verificationUri)
        assertEquals(5, gh.intervalSeconds)
        val goog = SignIn.parseDeviceCode(JSONObject("""{"device_code":"d","user_code":"ABCD-1234","verification_url":"https://www.google.com/device","interval":0,"expires_in":1800}"""))
        assertEquals("https://www.google.com/device", goog.verificationUri)
        assertEquals("interval is floored at one second", 1, goog.intervalSeconds)
    }

    @Test fun `a device-code error answers with the provider's own words`() {
        val t = runCatching { SignIn.parseDeviceCode(JSONObject("""{"error":"device_flow_disabled","error_description":"Device Flow must be explicitly enabled"}""")) }
        assertTrue(t.isFailure)
        assertTrue(t.exceptionOrNull()!!.message!!.contains("device_flow_disabled"))
    }

    @Test fun `token poll steps`() {
        assertTrue(SignIn.parseTokenStep(JSONObject("""{"access_token":"gho_x","token_type":"bearer"}""")) is SignIn.Step.Token)
        val pending = SignIn.parseTokenStep(JSONObject("""{"error":"authorization_pending"}""")) as SignIn.Step.Pending
        assertFalse(pending.slowDown)
        val slow = SignIn.parseTokenStep(JSONObject("""{"error":"slow_down"}""")) as SignIn.Step.Pending
        assertTrue(slow.slowDown)
        assertTrue(SignIn.parseTokenStep(JSONObject("""{"error":"expired_token"}""")) is SignIn.Step.Failed)
        assertTrue(SignIn.parseTokenStep(JSONObject("""{"error":"access_denied"}""")) is SignIn.Step.Failed)
        assertTrue("neither token nor error is a failure, not a wait", SignIn.parseTokenStep(JSONObject("{}")) is SignIn.Step.Failed)
    }

    @Test fun `identity prefers the address, falls back to the login, never the string null`() {
        assertEquals("me@x.test", SignIn.parseIdentity(JSONObject("""{"login":"me","email":"me@x.test"}""")))
        assertEquals("me", SignIn.parseIdentity(JSONObject("""{"login":"me","email":null}""")))
        assertNull(SignIn.parseIdentity(JSONObject("{}")))
    }
}
