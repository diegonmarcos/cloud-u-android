package com.diegonmarcos.cloudlib.auth

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #684 the browser auth mission, asserted on the RULES the phone runs: every failure is named in
 * order, the declared contract is complete once its package resolves, the result is read off the
 * declared keys, and the intent carries every request field. #689 moved these out of OAuthWebTest
 * when the GitHub OAuth-App web flow they sat beside was deleted; the mission captures the session
 * cookie only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthMissionTest {

    private fun declaredAuth(): JSONObject {
        val f = listOf("../../build.json", "../build.json", "build.json").map { File(it) }
            .firstOrNull { it.isFile && JSONObject(it.readText()).has("auth") }
            ?: error("ab_cloud-libs-shared/build.json not found above the module")
        return JSONObject(f.readText()).getJSONObject("auth")
    }

    private val contract = AuthMission.Contract(
        pkg = "com.example.browser", action = "com.example.browser.AUTH_MISSION",
        permission = "com.example.browser.permission.AUTH_MISSION",
        extras = listOf("url", "title", "allow_hosts", "capture", "cookie_url").associateWith { "x.$it" },
        results = listOf("outcome", "cookie", "why").associateWith { "r.$it" },
        captures = listOf("cookie"),
    )

    @Test fun `decide names every failure in order - declared, installed, granted, answering`() {
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
        assertEquals(listOf("cookie"), c.captures)
        // Unresolved, it is NOT declared: a {package} left in is an intent aimed at nothing.
        assertFalse(AuthMission.parse(raw)!!.declared)
    }

    @Test fun `the result is read off the declared keys and a capture with nothing in it is malformed`() {
        val ok = android.content.Intent().putExtra("r.outcome", AuthMission.OUTCOME_CAPTURED).putExtra("r.cookie", "sid=1")
        assertEquals(AuthMission.Capture.Cookie("sid=1"), AuthMission.read(contract, android.app.Activity.RESULT_OK, ok))
        assertEquals(AuthMission.Capture.Cancelled, AuthMission.read(contract, android.app.Activity.RESULT_CANCELED, null))
        val empty = android.content.Intent().putExtra("r.outcome", AuthMission.OUTCOME_CAPTURED)
        assertTrue(AuthMission.read(contract, android.app.Activity.RESULT_OK, empty) is AuthMission.Capture.Malformed)
        val refused = android.content.Intent().putExtra("r.outcome", AuthMission.OUTCOME_REFUSED).putExtra("r.why", "no")
        assertEquals(AuthMission.Capture.Refused("no"), AuthMission.read(contract, android.app.Activity.RESULT_OK, refused))
        assertEquals("Cookie(value=<redacted>)", AuthMission.Capture.Cookie("sid=1").toString())
    }

    @Test fun `the intent carries every request field under the declared extra names`() {
        val i = AuthMission.intent(contract, AuthMission.Request("https://p/a", "T", listOf("p"), AuthMission.CAPTURE_COOKIE, "https://p/"))
        assertEquals(contract.action, i.action)
        assertEquals(contract.pkg, i.`package`)
        assertEquals("https://p/a", i.getStringExtra("x.url"))
        assertEquals(AuthMission.CAPTURE_COOKIE, i.getStringExtra("x.capture"))
        assertEquals("https://p/", i.getStringExtra("x.cookie_url"))
        assertEquals(listOf("p"), i.getStringArrayExtra("x.allow_hosts")!!.toList())
    }
}
