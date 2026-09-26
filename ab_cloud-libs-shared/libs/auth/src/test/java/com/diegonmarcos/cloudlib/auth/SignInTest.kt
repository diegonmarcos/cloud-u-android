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
 * #573 — Configs ▸ Profile ▸ Connect ▸ Sign in: the provider list is data,
 * and the one device-grant code path reads what each provider answers.
 *
 * The providers are read off this module's BuildConfig, which is baked from
 * ab_cloud-libs-shared/build.json::auth.sign_in (#587, THE ONE declaration) —
 * so every expectation about the list is computed from that file itself by an
 * independent walk (found relative to the module, as gradle runs the suite
 * from `libs/auth/`), never typed in. The wire-shape tests feed the parser what GitHub and Google
 * document and assert the step the caller would branch on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SignInTest {

    /** The shared build.json's own auth.sign_in block, walked independently of the code. */
    private fun declared(): JSONObject {
        val f = listOf("../../build.json", "../build.json", "build.json").map { File(it) }
            .firstOrNull { it.isFile && JSONObject(it.readText()).has("auth") }
            ?: error("ab_cloud-libs-shared/build.json not found above the module — the expectations cannot be derived")
        return JSONObject(f.readText()).getJSONObject("auth").getJSONObject("sign_in")
    }

    @Test fun `the baked providers are exactly build json's, in order`() {
        val arr = declared().getJSONArray("providers")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        assertTrue("build.json declares no provider", ids.isNotEmpty())
        assertEquals(ids, SignIn.providers.map { it.id })
        // The baked blob decodes to the same document, so no field was lost in the bake.
        val baked = AuthDeclaration.parse(AuthDeclaration.decode(BuildConfig.AUTH_B64)).signIn
        assertEquals(arr.length(), baked.getJSONArray("providers").length())
    }

    @Test fun `exactly one primary, and it is the fleet SSO`() {
        val primaries = SignIn.providers.filter { it.primary }
        assertEquals(1, primaries.size)
        assertEquals(SignIn.Kind.AUTHELIA_BEARER, primaries.single().kind)
    }

    @Test fun `four ways in - the SSO's bearer and web-auth are two providers of distinct kinds (#578)`() {
        assertEquals(4, SignIn.providers.size)
        assertEquals(
            listOf(SignIn.Kind.AUTHELIA_BEARER, SignIn.Kind.AUTHELIA_WEB, SignIn.Kind.DEVICE_FLOW, SignIn.Kind.DEVICE_FLOW),
            SignIn.providers.map { it.kind },
        )
        assertEquals("ids are unique", 4, SignIn.providers.map { it.id }.toSet().size)
        assertEquals("labels are unique - each pill says which way it is", 4, SignIn.providers.map { it.label }.toSet().size)
        // Both SSO ways are startable and reach the same fetches; neither is the device grant.
        val sso = SignIn.providers.filter { it.kind == SignIn.Kind.AUTHELIA_BEARER || it.kind == SignIn.Kind.AUTHELIA_WEB }
        assertEquals(2, sso.size)
        sso.forEach {
            assertTrue(it.configured)
            assertTrue(it.grants(SignIn.GRANT_CONFIG_ARTIFACT) && it.grants(SignIn.GRANT_VAULT_BUNDLE))
        }
    }

    @Test fun `Google stays declared but inert until an owner-minted client id lands`() {
        val google = SignIn.provider("google")!!
        assertEquals(SignIn.Kind.DEVICE_FLOW, google.kind)
        assertEquals("", google.clientId)
        assertFalse("no invented client id - the UI says 'not configured'", google.configured)
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

    @Test fun `the endpoints and the vault route come off the same declaration`() {
        val d = AuthDeclaration.current
        assertTrue("config_source.base_url", d.configSource.baseUrl.startsWith("https://"))
        assertTrue("config_source.user", d.configSource.user.isNotBlank())
        assertTrue("config_source.git.repo", d.configSource.gitRepo.contains('/'))
        assertTrue("vault_connect.base_url", d.vault.baseUrl.startsWith("https://"))
        assertTrue("known_schema_versions", d.knownSchemaVersions.isNotEmpty())
        assertEquals(d.knownSchemaVersions, VaultConnect.knownSchemaVersions)
        assertTrue(ConfigArtifact.endpoint().startsWith(d.configSource.baseUrl))
        assertFalse("{user} is substituted", ConfigArtifact.endpoint().contains("{user}"))
    }

    @Test fun `a blank bake is an empty declaration, never an invented route`() {
        val d = AuthDeclaration.parse("")
        assertEquals("", d.configSource.baseUrl)
        assertTrue(d.knownSchemaVersions.isEmpty())
        assertTrue(SignIn.parseProviders(d.signIn).isEmpty())
    }

    @Test fun `a pasted export yields its bearer, a bare token passes through`() {
        assertEquals("tok", SignIn.extractToken("  tok \n"))
        assertEquals("ey1", SignIn.extractToken("""{"auth":{"authelia_token":"ey1","authelia_email":"a@b.c"}}"""))
        assertEquals("ey2", SignIn.extractToken("""{"token":"ey2"}"""))
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
