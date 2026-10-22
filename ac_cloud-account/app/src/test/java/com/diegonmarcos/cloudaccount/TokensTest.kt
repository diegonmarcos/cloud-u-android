package com.diegonmarcos.cloudaccount

import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudaccount.hub.AuditRow
import com.diegonmarcos.cloudaccount.hub.DeviceAuth
import com.diegonmarcos.cloudaccount.hub.LocalDeviceAuth
import com.diegonmarcos.cloudaccount.hub.RevealGate
import com.diegonmarcos.cloudaccount.hub.TokenRow
import com.diegonmarcos.cloudaccount.hub.TokensClient
import com.diegonmarcos.cloudaccount.hub.TokensPage
import com.diegonmarcos.cloudaccount.hub.TokensResult
import com.diegonmarcos.cloudaccount.hub.TokensTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitPartTags
import com.diegonmarcos.superapp.uikit.KitTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** An in-memory stand-in for tokens-api: same routes, same JSON, and a record of every call it saw. */
class FakeTokenEngine(var status: Int = 200) {
    val calls = mutableListOf<String>()
    var lastHeaders: Map<String, String> = emptyMap()
    var lastBody: String? = null
    var createBody: String? = null
    var down = false
    val values = mutableMapOf("tk_a1" to "ft_SECRETVALUE_abcd", "tk_b2" to "eyJ.jwtvalue.zzzz")
    private val revoked = mutableSetOf<String>()

    private fun row(id: String, name: String, kind: String, hint: String) = JSONObject()
        .put("id", id).put("name", name).put("kind", kind).put("audience", "*").put("scopes", JSONArray(listOf("mcp:read")))
        .put("status", if (id in revoked) "revoked" else "active").put("created_at", 1_800_000_000L)
        .put("expires_at", JSONObject.NULL).put("last_used_at", JSONObject.NULL).put("source", "generated").put("hint", hint)

    val transport = TokensClient.Transport { method, url, headers, body ->
        if (down) throw IOException("down")
        lastHeaders = headers; lastBody = body
        val path = url.removePrefix(TokensClient.BASE)
        calls += "$method $path"
        if (status != 200) return@Transport status to """{"error":"SECRETBODY ${values.values.first()}"}"""
        when {
            method == "GET" && path == "/v1/tokens" -> 200 to JSONObject().put("tokens", JSONArray(values.keys.map { id ->
                row(id, if (id == "tk_a1") "ci" else "dagu (adopted)", if (id == "tk_a1") "fleet" else "authelia", values.getValue(id).takeLast(4))
            })).toString()
            method == "GET" && path.startsWith("/v1/audit") -> 200 to JSONObject().put("audit", JSONArray().put(
                JSONObject().put("ts", 1_800_000_100L).put("actor", "client:cloud-admin").put("action", "reveal").put("token_name", "ci"))).toString()
            method == "POST" && path == "/v1/tokens" -> {
                val b = JSONObject(body!!); createBody = body
                values["tk_new"] = "ft_NEWVALUE_wxyz"
                201 to row("tk_new", b.getString("name"), "fleet", "wxyz").put("value", values.getValue("tk_new")).toString()
            }
            method == "POST" && path.endsWith("/reveal") -> 200 to JSONObject().put("value", values.getValue(path.split('/')[3])).toString()
            method == "POST" && path.endsWith("/revoke") -> { revoked += path.split('/')[3]; 200 to row(path.split('/')[3], "ci", "fleet", "abcd").toString() }
            else -> 404 to "{}"
        }
    }
}

class TokensClientTest {
    private fun client(e: FakeTokenEngine, bearer: String? = "owner.jwt") = TokensClient(TokensClient.BASE, { bearer }, e.transport)

    @Test fun listsRowsWithoutAnyValue() {
        val e = FakeTokenEngine()
        val rows = (client(e).list() as TokensResult.Ok).value
        assertEquals(listOf("tk_a1", "tk_b2"), rows.map { it.id })
        assertEquals("••••abcd", rows[0].masked)
        for (r in rows) { assertFalse(r.toString().contains("SECRETVALUE")); assertFalse(r.toString().contains("jwtvalue")) }
    }

    @Test fun everyCallCarriesTheVaultBearerAsAuthorization() {
        val e = FakeTokenEngine()
        client(e, "owner.jwt").list()
        assertEquals("Bearer owner.jwt", e.lastHeaders["Authorization"])
        assertEquals(listOf("GET /v1/tokens"), e.calls)
    }

    @Test fun noBearerStoredMeansNoCall() {
        val e = FakeTokenEngine()
        val r = client(e, bearer = " ").list()
        assertTrue(r is TokensResult.Fail)
        assertTrue(e.calls.isEmpty())
    }

    @Test fun failuresNameTheCauseAndNeverQuoteTheBody() {
        for ((code, word) in listOf(401 to "expired", 403 to "owner", 503 to "unavailable", 500 to "500")) {
            val e = FakeTokenEngine(code)
            val f = client(e).list() as TokensResult.Fail
            assertTrue("$code: ${f.reason}", f.reason.contains(word))
            assertFalse(f.reason.contains("SECRETBODY")); assertFalse(f.reason.contains("ft_"))
        }
        val down = FakeTokenEngine().also { it.down = true }
        assertTrue((client(down).list() as TokensResult.Fail).reason.contains("not reachable"))
    }

    @Test fun generateSendsNameAudienceScopesAndExpiryAndReturnsTheValueOnce() {
        val e = FakeTokenEngine()
        val (row, value) = (client(e).generate(" mcp ", "mcp.diegonmarcos.com", listOf("a", " ", "b"), 30) as TokensResult.Ok).value
        val sent = JSONObject(e.lastBody!!)
        assertEquals("mcp", sent.getString("name")); assertEquals("mcp.diegonmarcos.com", sent.getString("audience"))
        assertEquals(listOf("a", "b"), (0 until sent.getJSONArray("scopes").length()).map { sent.getJSONArray("scopes").getString(it) })
        assertEquals(30, sent.getInt("ttl_days"))
        assertEquals("ft_NEWVALUE_wxyz", value); assertFalse(row.toString().contains("NEWVALUE"))
        client(e).generate("forever", "", emptyList(), null)
        assertFalse(JSONObject(e.lastBody!!).has("ttl_days")); assertEquals("*", JSONObject(e.lastBody!!).getString("audience"))
    }

    @Test fun revealAndRevokeUseThePerTokenRoutes() {
        val e = FakeTokenEngine()
        assertEquals("ft_SECRETVALUE_abcd", (client(e).reveal("tk_a1") as TokensResult.Ok).value)
        assertEquals("revoked", (client(e).revoke("tk_a1") as TokensResult.Ok).value.status)
        assertEquals(listOf("POST /v1/tokens/tk_a1/reveal", "POST /v1/tokens/tk_a1/revoke"), e.calls)
    }

    @Test fun auditRowsCarryNoValue() {
        val rows: List<AuditRow> = (client(FakeTokenEngine()).audit() as TokensResult.Ok).value
        assertEquals("reveal", rows.single().action)
    }
}

/** The one rule of the reveal path: nothing is read from the engine until device auth said yes. */
@RunWith(RobolectricTestRunner::class)
class RevealOnIoTest {
    private fun run(auth: Boolean): Pair<Boolean, String?> {
        var read = false; var shown: String? = null
        val latch = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        RevealGate(DeviceAuth { _, done -> done(auth) }).revealOnIo("test", scope,
            read = { read = true; "v" }, show = { shown = it; latch.countDown() }, denied = { latch.countDown() })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return read to shown
    }

    @Test fun deniedAuthNeverReads() = assertEquals(false to null, run(false))
    @Test fun grantedAuthReadsAndShows() = assertEquals(true to "v", run(true))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TokensPageUiTest {
    private val PALETTE = KitPalette(Color(0xFF101010), Color(0xFF202020), Color.White, Color.Gray, Color.Cyan, Color.DarkGray, Color.Black)
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private fun show(e: FakeTokenEngine, auth: Boolean = true, asked: MutableList<String> = mutableListOf()) {
        val client = TokensClient(TokensClient.BASE, { "owner.jwt" }, e.transport)
        compose.setContent {
            CloudKitTheme(PALETTE) {
                CompositionLocalProvider(LocalDeviceAuth provides DeviceAuth { reason, done -> asked += reason; done(auth) }) { TokensPage(client) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TokensTags.row("tk_a1")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun listsEveryTokenMasked_noValueOnScreen() {
        show(FakeTokenEngine())
        compose.onNodeWithTag(TokensTags.value("tk_a1")).assertTextEquals("••••abcd")
        compose.onNodeWithTag(TokensTags.value("tk_b2")).assertTextEquals("••••zzzz")
        assertTrue(compose.onAllNodesWithTag(TokensTags.row("tk_b2")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun refusedDeviceAuthShowsNothingAndNeverAsksTheEngine() {
        val e = FakeTokenEngine(); val asked = mutableListOf<String>()
        show(e, auth = false, asked = asked)
        compose.onNodeWithTag(TokensTags.reveal("tk_a1")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TokensTags.value("tk_a1")).assertTextEquals("••••abcd")
        assertEquals(1, asked.size)
        assertTrue("no reveal call without device auth", e.calls.none { it.endsWith("/reveal") })
    }

    @Test fun revealShowsTheFullValueThenHidesIt() {
        val e = FakeTokenEngine()
        show(e)
        compose.onNodeWithTag(TokensTags.reveal("tk_a1")).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(TokensTags.value("tk_a1")).assertTextEquals("ft_SECRETVALUE_abcd") }.isSuccess }
        compose.onNodeWithTag(TokensTags.reveal("tk_a1")).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(TokensTags.value("tk_a1")).assertTextEquals("••••abcd") }.isSuccess }
    }

    @Test fun copyPutsTheValueOnTheClipboardBehindDeviceAuth() {
        val e = FakeTokenEngine(); val asked = mutableListOf<String>()
        show(e, asked = asked)
        compose.onNodeWithTag(TokensTags.copy("tk_a1")).performClick()
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        compose.waitUntil(5_000) { cm.primaryClip?.getItemAt(0)?.text?.toString() == "ft_SECRETVALUE_abcd" }
        assertTrue(asked.single().startsWith("Copy"))
    }

    @Test fun revokeAsksToConfirmThenTheTokenReadsRevokedAndCannotBeRevokedAgain() {
        val e = FakeTokenEngine()
        show(e)
        compose.onNodeWithTag(TokensTags.revoke("tk_a1")).performClick()
        assertTrue("nothing revoked before the confirmation", e.calls.none { it.endsWith("/revoke") })
        compose.onNodeWithTag(KitTags.DIALOG_CONFIRM).performClick()
        compose.waitUntil(5_000) { e.calls.any { it == "POST /v1/tokens/tk_a1/revoke" } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(TokensTags.revoke("tk_a1")).assertIsNotEnabled() }.isSuccess }
    }

    @Test fun dismissingTheRevokeDialogRevokesNothing() {
        val e = FakeTokenEngine()
        show(e)
        compose.onNodeWithTag(TokensTags.revoke("tk_a1")).performClick()
        compose.onNodeWithTag(KitTags.DIALOG_DISMISS).performClick()
        compose.waitForIdle()
        assertTrue(e.calls.none { it.endsWith("/revoke") })
    }

    @Test fun generateAsksDeviceAuthSendsTheFormAndShowsTheNewValue() {
        val e = FakeTokenEngine(); val asked = mutableListOf<String>()
        show(e, asked = asked)
        compose.onNodeWithTag(TokensTags.NEW_TOGGLE).performClick()
        compose.onNodeWithTag(TokensTags.NEW_NAME).performTextInput("ci bot")
        compose.onNodeWithTag(TokensTags.NEW_AUDIENCE).performTextReplacement("*.diegonmarcos.com")
        compose.onNodeWithTag(TokensTags.NEW_SCOPES).performTextInput("mcp:read mcp:write")
        compose.onNodeWithTag(TokensTags.NEW_DAYS).performTextReplacement("7")
        compose.onNodeWithTag(TokensTags.NEW_CREATE).performClick()
        waitFor(TokensTags.value("tk_new"))
        val sent = JSONObject(e.createBody!!)
        assertEquals("ci bot", sent.getString("name")); assertEquals(7, sent.getInt("ttl_days")); assertEquals("*.diegonmarcos.com", sent.getString("audience"))
        assertTrue(asked.single().startsWith("Generate"))
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(TokensTags.value("tk_new")).assertTextEquals("ft_NEWVALUE_wxyz") }.isSuccess }
    }

    @Test fun anEngineErrorIsShownAsOneLineWithoutItsBody() {
        val e = FakeTokenEngine(403)
        val client = TokensClient(TokensClient.BASE, { "agent.jwt" }, e.transport)
        compose.setContent { CloudKitTheme(PALETTE) { TokensPage(client) } }
        waitFor(KitPartTags.banner(TokensTags.ERROR))
        assertTrue(compose.onAllNodesWithTag(TokensTags.row("tk_a1")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun auditLogListsEventsWithoutValues() {
        val e = FakeTokenEngine()
        show(e)
        compose.onNodeWithTag(TokensTags.AUDIT_TOGGLE).performClick()
        waitFor(KitPartTags.listRow("tokens:audit:1800000100:reveal"))
    }
}

