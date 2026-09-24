package com.diegonmarcos.superapp.profile

import android.app.Application
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.ConfigSyncClient
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/**
 * #566/#569 — Configs ▸ Profile ▸ Connect ▸ Vault configs, and the Imported tab.
 *
 * The client runs against a real local HTTP server, so what is asserted is what
 * went over the wire. The paths come from BuildConfig, which is baked from
 * build.json::ui.vault_connect, so a path edited in the data but not in the
 * server contract shows up here. Every expectation about the rendered rows is
 * computed from the fixture by an independent walk, not typed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultConnectTest {

    private lateinit var server: HttpServer
    private val seen = mutableListOf<Triple<String, String, String>>() // method, path, body
    private val seenAuth = mutableListOf<String>()
    private var status = 200
    private var reply = "{}"
    private var location = ""

    @Before fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += Triple(ex.requestMethod, ex.requestURI.path, ex.requestBody.readBytes().decodeToString())
            seenAuth += ex.requestHeaders.getFirst("Authorization").orEmpty()
            if (location.isNotEmpty()) ex.responseHeaders.add("Location", location)
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
    }

    @After fun down() = server.stop(0)

    private fun endpoints() = VaultConnect.Endpoints(
        baseUrl = "http://127.0.0.1:${server.address.port}/pub",
        startPath = BuildConfig.UI_VAULT_CONNECT_START_PATH,
        fetchPath = BuildConfig.UI_VAULT_CONNECT_FETCH_PATH,
        connectTimeoutMs = BuildConfig.UI_VAULT_CONNECT_CONNECT_MS,
        readTimeoutMs = BuildConfig.UI_VAULT_CONNECT_READ_MS,
    )

    @Test fun `build json declares both endpoints`() {
        assertTrue("base_url", BuildConfig.UI_VAULT_CONNECT_BASE_URL.startsWith("https://"))
        assertTrue("start_path", BuildConfig.UI_VAULT_CONNECT_START_PATH.startsWith("/"))
        assertTrue("fetch_path", BuildConfig.UI_VAULT_CONNECT_FETCH_PATH.startsWith("/"))
        assertTrue("distinct", BuildConfig.UI_VAULT_CONNECT_START_PATH != BuildConfig.UI_VAULT_CONNECT_FETCH_PATH)
    }

    @Test fun `start POSTs the bearer to the declared start path`() {
        status = 202; reply = """{"sent_to":"m•@example","expires_in":600}"""
        val o = VaultConnect.start(endpoints(), "tok-A")
        assertTrue(o.toString(), o is ConfigSyncClient.Outcome.Ok)
        val (method, path, _) = seen.single()
        assertEquals("POST", method)
        assertEquals("/pub" + BuildConfig.UI_VAULT_CONNECT_START_PATH, path)
        assertEquals("Bearer tok-A", seenAuth.single())
    }

    @Test fun `fetch sends the same bearer plus the code`() {
        val code = (100000..999999).random().toString()
        reply = fixture().toString()
        val o = VaultConnect.fetch(endpoints(), "tok-B", code)
        assertTrue(o.toString(), o is ConfigSyncClient.Outcome.Ok)
        val (method, path, body) = seen.single()
        assertEquals("POST", method)
        assertEquals("/pub" + BuildConfig.UI_VAULT_CONNECT_FETCH_PATH, path)
        assertEquals(code, JSONObject(body).getString("code"))
        assertEquals("Bearer tok-B", seenAuth.single())
    }

    @Test fun `a refused code and an undeployed server read as themselves`() {
        val cases = mapOf(
            403 to VaultConnect.Hint.CODE_REJECTED,
            404 to VaultConnect.Hint.SERVER_NOT_READY,
            503 to VaultConnect.Hint.SERVER_NOT_READY,
            401 to VaultConnect.Hint.NONE,
        )
        for ((code, want) in cases) {
            status = code; reply = """{"error":"x"}"""
            val o = VaultConnect.fetch(endpoints(), "tok", "1") as ConfigSyncClient.Outcome.Failed
            assertEquals("HTTP $code", want, VaultConnect.hint(o.kind))
        }
    }

    @Test fun `a login redirect is unauthorized, never a fetched bundle`() {
        status = 302; reply = ""; location = "https://auth.example.org/?rd=x"
        val o = VaultConnect.fetch(endpoints(), "tok", "1")
        assertTrue(o.toString(), o is ConfigSyncClient.Outcome.Failed)
        assertEquals(ConfigSyncClient.Kind.UNAUTHORIZED, (o as ConfigSyncClient.Outcome.Failed).kind)
    }

    @Test fun `sections follow the schema order and carry every leaf`() {
        val resp = fixture()
        val got = VaultConnect.sections(resp)
        val schema = resp.getJSONObject("schema").getJSONArray("sections")
        val wantIds = (0 until schema.length()).map { schema.getJSONObject(it).getString("id") }
        val wantLabels = (0 until schema.length()).map { schema.getJSONObject(it).getString("label") }
        assertEquals(wantIds, got.map { it.id })
        assertEquals(wantLabels, got.map { it.label })

        val bundle = resp.getJSONObject("bundle")
        for (s in got) {
            val leaves = mutableListOf<String>()
            if (bundle.has(s.id)) leavesOf(bundle.get(s.id), leaves)
            assertEquals("row count in ${s.id}", leaves.size, s.rows.size)
            for (v in leaves) assertTrue("${s.id} lacks $v", s.rows.any { it.value.contains(v) })
        }
        // The schema names a section the bundle does not have: shown, and empty.
        val missing = wantIds.filterNot { bundle.has(it) }
        assertTrue("fixture must exercise a missing section", missing.isNotEmpty())
        missing.forEach { id -> assertTrue(got.first { it.id == id }.rows.isEmpty()) }
    }

    @Test fun `a pending value is one pending row with its source`() {
        val got = VaultConnect.sections(fixture())
        val pendingRows = got.flatMap { it.rows }.filter { it.pending }
        val bundle = fixture().getJSONObject("bundle")
        val wantSources = mutableListOf<String>()
        pendingSources(bundle, wantSources)
        assertEquals(wantSources.size, pendingRows.size)
        wantSources.forEach { src -> assertTrue(src, pendingRows.any { it.value.contains(src) }) }
    }

    @Test fun `without a schema the bundle's own sections are used, bookkeeping excluded`() {
        val bundle = fixture().getJSONObject("bundle")
        val got = VaultConnect.sections(bundle)
        val want = bundle.keys().asSequence().filter { !it.startsWith("_") && it != "schema_version" }.toList()
        assertEquals(want, got.map { it.id })
        assertTrue(got.none { it.id == "schema_version" || it.id.startsWith("_") })
    }

    // ── independent walkers over the fixture ─────────────────────────────

    private fun leavesOf(v: Any?, out: MutableList<String>) {
        when {
            v is JSONObject && v.optBoolean("pending") -> out += v.getString("source")
            v is JSONObject && v.length() > 0 -> v.keys().forEach { leavesOf(v.get(it), out) }
            v is JSONArray && v.length() > 0 -> (0 until v.length()).forEach { leavesOf(v.get(it), out) }
            else -> out += v.toString()
        }
    }

    private fun pendingSources(v: Any?, out: MutableList<String>) {
        when {
            v is JSONObject && v.optBoolean("pending") -> out += v.getString("source")
            v is JSONObject -> v.keys().forEach { pendingSources(v.get(it), out) }
            v is JSONArray -> (0 until v.length()).forEach { pendingSources(v.get(it), out) }
        }
    }

    /** The server's reply shape (cloud-u-containers c3-public-api, #569). */
    private fun fixture() = JSONObject(
        """
        {"schema": {"sections": [
            {"id": "about", "label": "About"},
            {"id": "mesh", "label": "Mesh"},
            {"id": "autocomplete", "label": "Autocomplete"},
            {"id": "mail", "label": "Mail"}
         ]},
         "bundle": {
            "schema_version": 1,
            "mesh": {"profiles": {"config-v4-full": "[Interface]\nAddress = 10.9.9.9/32"}, "public_key": "PUBK"},
            "mail": {"accounts": {"admin": {"name": "me", "aliases": ["a@x", "b@x"]}}, "flag": true, "none": null},
            "about": {"profile": {"name": "N"}, "phone": {"pending": true, "source": "no-store", "reason": "r1"}},
            "_generated": {"tree_sha256": "abc"}
         }}
        """.trimIndent(),
    )
}
