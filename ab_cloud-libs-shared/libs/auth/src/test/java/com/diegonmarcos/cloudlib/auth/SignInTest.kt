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

    @Test fun `five ways in over four providers - the SSO's bearer and web-auth are two ways of one family (#578, #1083)`() {
        assertEquals(5, SignIn.providers.size)
        assertEquals(
            listOf(SignIn.Kind.AUTHELIA_BEARER, SignIn.Kind.AUTHELIA_WEB, SignIn.Kind.DEVICE_FLOW, SignIn.Kind.DEVICE_FLOW, SignIn.Kind.CLOUDFLARE_ACCESS),
            SignIn.providers.map { it.kind },
        )
        assertEquals("ids are unique", 5, SignIn.providers.map { it.id }.toSet().size)
        assertEquals("labels are unique - each pill says which way it is", 5, SignIn.providers.map { it.label }.toSet().size)
        assertEquals("four families: Authelia, Google, GitHub, Cloudflare", listOf("authelia", "google", "github", "cloudflare"),
            ProviderStatus.families(SignIn.providers).keys.toList())
        // Both SSO ways are startable and reach the same fetches; neither is the device grant.
        val sso = SignIn.providers.filter { it.kind == SignIn.Kind.AUTHELIA_BEARER || it.kind == SignIn.Kind.AUTHELIA_WEB }
        assertEquals(2, sso.size)
        sso.forEach {
            assertTrue(it.configured)
            assertTrue(it.grants(SignIn.GRANT_CONFIG_ARTIFACT) && it.grants(SignIn.GRANT_VAULT_BUNDLE))
        }
    }

    /**
     * #1083 GitHub is declared again, and this is what keeps it the RIGHT kind of client. #641 deleted an earlier
     * provider whose client id was a GitHub APP (Device Flow off, so the grant never started). The one declared now is
     * the owner's own GitHub OAuth App (`Ov23li1WLY8SoctMF2hp`) with the GitHub CLI's public client as the fallback
     * the device grant retries with. Neither carries a secret: the app's secret lives only in the auth broker's sops.
     */
    @Test fun `github is the owner's OAuth App with the gh public client as fallback, and no secret anywhere (#1083)`() {
        val arr = declared().getJSONArray("providers")
        val d = (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("id") == "github" }
        val gh = SignIn.provider("github")!!
        assertEquals(SignIn.Kind.DEVICE_FLOW, gh.kind)
        assertEquals("Ov23li1WLY8SoctMF2hp", gh.clientId)
        assertEquals("178c6fc778ccc68e1d6a", gh.fallbackClientId)
        assertTrue("startable", gh.configured)
        assertEquals("", gh.clientSecret)
        assertFalse("no secret field in the declaration", d.has("client_secret"))
        assertTrue(gh.deviceCodeUrl.startsWith("https://github.com/") && gh.tokenUrl.startsWith("https://github.com/"))
        val flows = SignIn.providers.filter { it.kind == SignIn.Kind.DEVICE_FLOW }
        assertEquals("exactly two device grants: google and github", listOf("google", "github"), flows.map { it.id })
        // The remedy rows that worded the old App-client dead end stay gone.
        val remedies = AuthDeclaration.current.grantRemedies
        assertTrue("a remedy table is still declared — the mechanism stays", remedies.isNotEmpty())
        assertFalse("a device_flow_disabled remedy is declared", remedies.any { it.match.contains("device_flow_disabled") })
    }

    @Test fun `no declared provider carries a client secret or a token (#1083)`() {
        val text = declared().toString()
        val arr = declared().getJSONArray("providers")
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            assertEquals("${d.getString("id")}: client_secret must stay empty", "", d.optString("client_secret"))
            for (k in d.keys()) assertFalse("${d.getString("id")}: $k looks like a credential", k.contains("secret", true) && d.optString(k).isNotBlank())
        }
        assertFalse("a bearer-looking value in the declaration", Regex("(" + listOf("gh" + "o_", "gh" + "p_", "github" + "_pat_", "GOC" + "SPX-").joinToString("|") + ")").containsMatchIn(text))
    }

    @Test fun `google and cloudflare carry their declared ids and routes (#1083)`() {
        val g = SignIn.provider("google")!!
        assertEquals("514615763870-0p4p464r7cul0jt6nge7deumie5ubdl3.apps.googleusercontent.com", g.serverClientId)
        assertEquals("google_credential", g.native)
        assertTrue(g.startUrl.startsWith("https://auth.diegonmarcos.com/oauth2/google/"))
        val cf = SignIn.provider("cloudflare")!!
        assertEquals(SignIn.Kind.CLOUDFLARE_ACCESS, cf.kind)
        assertTrue(cf.loginUrl.startsWith("https://diegonmarcos.cloudflareaccess.com"))
        assertEquals("https://auth.diegonmarcos.com/oauth2/cloudflare/start", cf.startUrl)
        assertTrue(cf.configured)
        SignIn.providers.forEach { assertTrue("${it.id}: probe_url must be https", it.probeUrl.startsWith("https://")) }
    }

    @Test fun `Google's owner-minted client id has landed, and the secret is never in the public repo (#611)`() {
        val declaredGoogle = declared().getJSONArray("providers").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("id") == "google" }
        }
        val google = SignIn.provider("google")!!
        assertEquals(SignIn.Kind.DEVICE_FLOW, google.kind)
        // client_id is PUBLIC (Google documents it so) and lives in the shared build.json.
        assertTrue("the client id has landed", declaredGoogle.getString("client_id").endsWith(".apps.googleusercontent.com"))
        assertEquals(declaredGoogle.getString("client_id"), google.clientId)
        assertTrue("a device grant with a client id is startable", google.configured)
        // The client_secret is NEVER committed to this public repo — it is baked from the
        // private vault at build time (libs:auth build.gradle), so the declaration is empty.
        assertEquals("", declaredGoogle.optString("client_secret"))
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
