package com.diegonmarcos.cloudlib.auth

import android.app.Application
import com.diegonmarcos.superapp.core.ConfigSyncClient
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
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * #566/#569 — Configs ▸ Profile ▸ Connect ▸ Vault configs, and the Imported tab.
 *
 * The client runs against a real local HTTP server, so what is asserted is what
 * went over the wire. The paths come from AuthDeclaration, which is baked from
 * ab_cloud-libs-shared/build.json::auth.vault_connect (#587), so a path edited
 * in the data but not in the server contract shows up here. Every expectation about the rendered rows is
 * computed from the fixture by an independent walk, not typed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultConnectTest {

    private lateinit var server: ServerSocket
    // Written by the server thread, read by the test: synchronized.
    private val seen = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, String>>()) // method, path, body
    private val seenAuth = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var status = 200
    private var reply = "{}"
    private var location = ""

    /**
     * A one-request-per-connection HTTP/1.1 server on a plain ServerSocket —
     * the JDK's com.sun httpserver is not on the Android unit-test classpath.
     * It records the request line, the Authorization header and the body, and
     * answers with [status] / [location] / [reply].
     */
    @Before fun up() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { c ->
                    val input = c.getInputStream().buffered()
                    fun line(): String {
                        val b = StringBuilder()
                        while (true) {
                            val ch = input.read()
                            if (ch == -1 || ch == '\n'.code) break
                            if (ch != '\r'.code) b.append(ch.toChar())
                        }
                        return b.toString()
                    }
                    val (method, path) = line().split(" ").let { it[0] to it[1] }
                    var length = 0
                    var auth = ""
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        val (k, v) = h.split(":", limit = 2).map { it.trim() }
                        if (k.equals("Content-Length", true)) length = v.toInt()
                        if (k.equals("Authorization", true)) auth = v
                    }
                    val body = ByteArray(length).also {
                        var n = 0
                        while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r }
                    }
                    seen += Triple(method, path, body.decodeToString())
                    seenAuth += auth
                    val bytes = reply.toByteArray()
                    val head = buildString {
                        append("HTTP/1.1 $status X\r\n")
                        if (location.isNotEmpty()) append("Location: $location\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    c.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                }
            }
        }
    }

    @After fun down() = server.close()

    private fun endpoints() = VaultConnect.Endpoints(
        baseUrl = "http://127.0.0.1:${server.localPort}/pub",
        startPath = AuthDeclaration.vault.startPath,
        fetchPath = AuthDeclaration.vault.fetchPath,
        connectTimeoutMs = AuthDeclaration.vault.connectTimeoutMs,
        readTimeoutMs = AuthDeclaration.vault.readTimeoutMs,
    )

    @Test fun `build json declares both endpoints`() {
        assertTrue("base_url", AuthDeclaration.vault.baseUrl.startsWith("https://"))
        assertTrue("start_path", AuthDeclaration.vault.startPath.startsWith("/"))
        assertTrue("fetch_path", AuthDeclaration.vault.fetchPath.startsWith("/"))
        assertTrue("distinct", AuthDeclaration.vault.startPath != AuthDeclaration.vault.fetchPath)
    }

    @Test fun `start POSTs the bearer to the declared start path`() {
        status = 202; reply = """{"sent_to":"m•@example","expires_in":600}"""
        val o = VaultConnect.start(endpoints(), "tok-A")
        assertTrue(o.toString(), o is ConfigSyncClient.Outcome.Ok)
        val (method, path, _) = seen.single()
        assertEquals("POST", method)
        assertEquals("/pub" + AuthDeclaration.vault.startPath, path)
        assertEquals("Bearer tok-A", seenAuth.single())
    }

    @Test fun `fetch sends the same bearer plus the code`() {
        val code = (100000..999999).random().toString()
        reply = fixture().toString()
        val o = VaultConnect.fetch(endpoints(), "tok-B", code)
        assertTrue(o.toString(), o is ConfigSyncClient.Outcome.Ok)
        val (method, path, body) = seen.single()
        assertEquals("POST", method)
        assertEquals("/pub" + AuthDeclaration.vault.fetchPath, path)
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
