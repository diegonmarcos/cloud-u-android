package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.PushKeys
import app.sterna.core.jmap.model.PushSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.Json

class PushSubscriptionTest {
    private lateinit var server: MockWebServer
    private val client = JmapClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun session() = JmapSession(apiUrl = server.url("/jmap/api/").toString())

    private fun lastRequestJson() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun create_sendsNoAccountId_usesCoreOnly_omitsServerSetFields() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["PushSubscription/set",
                   {"created":{"sub":{"id":"ps1","expires":"2026-07-20T00:00:00Z"}}},"c0"]]}""",
            ),
        )

        val created = client.createPushSubscription(
            session(),
            BasicAuth("u", "p"),
            PushSubscription(
                deviceClientId = "dev-1",
                url = "https://ntfy.example/upABC",
                keys = PushKeys(p256dh = "PK", auth = "AU"),
                expires = "2026-07-24T00:00:00Z",
                types = listOf("Email"),
            ),
        )

        // Server-assigned id + server-capped expires are reflected back.
        assertEquals("ps1", created.id)
        assertEquals("2026-07-20T00:00:00Z", created.expires)

        val request = lastRequestJson()
        val using = request["using"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(Jmap.CORE_CAPABILITY), using)
        val call = request["methodCalls"]!!.jsonArray[0].jsonArray
        assertEquals("PushSubscription/set", call[0].jsonPrimitive.content)
        val args = call[1].jsonObject
        assertFalse("PushSubscription is session-level", args.containsKey("accountId"))
        val sub = args["create"]!!.jsonObject["sub"]!!.jsonObject
        assertEquals("dev-1", sub["deviceClientId"]!!.jsonPrimitive.content)
        assertEquals("https://ntfy.example/upABC", sub["url"]!!.jsonPrimitive.content)
        assertEquals("PK", sub["keys"]!!.jsonObject["p256dh"]!!.jsonPrimitive.content)
        assertEquals("Email", sub["types"]!!.jsonArray[0].jsonPrimitive.content)
        // Server-set fields must be absent (not null) or the create is rejected.
        assertFalse(sub.containsKey("id"))
        assertFalse(sub.containsKey("verificationCode"))
    }

    @Test fun verify_sendsUpdateWithCode() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["PushSubscription/set",{"updated":{"ps1":null}},"c0"]]}""",
            ),
        )

        client.verifyPushSubscription(session(), BasicAuth("u", "p"), "ps1", "code42")

        val update = lastRequestJson()["methodCalls"]!!.jsonArray[0].jsonArray[1]
            .jsonObject["update"]!!.jsonObject["ps1"]!!.jsonObject
        assertEquals("code42", update["verificationCode"]!!.jsonPrimitive.content)
    }

    @Test fun renew_returnsServerCappedExpires() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["PushSubscription/set",
                   {"updated":{"ps1":{"expires":"2026-07-19T00:00:00Z"}}},"c0"]]}""",
            ),
        )

        val applied = client.updatePushSubscriptionExpires(
            session(), BasicAuth("u", "p"), "ps1", "2026-07-24T00:00:00Z",
        )

        assertEquals("2026-07-19T00:00:00Z", applied)
    }

    @Test fun destroy_toleratesNotFound() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["PushSubscription/set",
                   {"notDestroyed":{"ps1":{"type":"notFound"}}},"c0"]]}""",
            ),
        )

        client.destroyPushSubscription(session(), BasicAuth("u", "p"), "ps1") // must not throw
    }

    @Test fun get_parsesList() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["PushSubscription/get",{"list":[
                   {"id":"ps1","deviceClientId":"dev-1","url":"https://e/1","expires":"2026-07-20T00:00:00Z"}
                   ]},"c0"]]}""",
            ),
        )

        val subs = client.getPushSubscriptions(session(), BasicAuth("u", "p"))

        assertEquals(1, subs.size)
        assertEquals("ps1", subs[0].id)
        assertEquals("dev-1", subs[0].deviceClientId)
        assertNull(subs[0].keys)
    }

    @Test fun vapidPublicKey_readsCapability_absentIsNull() {
        val with = JmapSession(
            apiUrl = "https://x/api",
            capabilities = mapOf(
                Jmap.WEBPUSH_VAPID_CAPABILITY to buildJsonObject { put("applicationServerKey", "VAPIDKEY") },
            ),
        )
        assertEquals("VAPIDKEY", with.vapidPublicKey())
        assertNull(JmapSession(apiUrl = "https://x/api").vapidPublicKey())
    }

    @Test fun create_missingCreated_throws() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["PushSubscription/set",
                   {"notCreated":{"sub":{"type":"forbidden"}}},"c0"]]}""",
            ),
        )
        val thrown = runCatching {
            client.createPushSubscription(
                session(), BasicAuth("u", "p"),
                PushSubscription(deviceClientId = "d", url = "https://e/1"),
            )
        }.exceptionOrNull()
        assertTrue(thrown is JmapException)
    }
}
