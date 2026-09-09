package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A mailbox Sterna creates is a mailbox Sterna subscribes to (#174).
 */
class CreatedMailboxIsSubscribedTest {

    private lateinit var server: MockWebServer
    private val client = JmapClient()
    private val auth = BasicAuth("alex@masto.top", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun created() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"methodResponses":[["Mailbox/set",{"accountId":"acc1","created":{"new":{"id":"mb9"}}},"m0"]]}""")

    /** The `create.new` object of the single `Mailbox/set` the client actually sent. */
    private fun sentCreateNew(): JsonObject {
        val calls = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["methodCalls"]!!.jsonArray
        assertEquals("one Mailbox/set and nothing else", 1, calls.size)
        val call = calls[0].jsonArray
        assertEquals("Mailbox/set", call[0].jsonPrimitive.content)
        val args = call[1].jsonObject
        assertEquals("acc1", args["accountId"]!!.jsonPrimitive.content)
        return args["create"]!!.jsonObject["new"]!!.jsonObject
    }

    /**
     * T4 — the property really leaves, inside `create.new`, and it is the boolean `true`. Read as
     * a JSON boolean, so the string `"true"` — which a server rejects — does not pass either.
     */
    @Test fun `the created mailbox is asked to be subscribed`() = runBlocking {
        server.enqueue(created())
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        assertEquals("mb9", client.createMailbox(session, "acc1", "Projets", role = null, auth))

        val new = sentCreateNew()
        assertTrue("isSubscribed never left the client — $new", new.containsKey("isSubscribed"))
        assertEquals("a mailbox created unsubscribed is invisible at once — $new", true, new["isSubscribed"]!!.jsonPrimitive.boolean)
    }

    /**
     * T5a — a plain folder: adding the property must not have moved `name`, nor invented a `role`
     */
    @Test fun `a folder with no role and no parent still sends name alone`() = runBlocking {
        server.enqueue(created())
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        client.createMailbox(session, "acc1", "Projets", role = null, auth)

        val new = sentCreateNew()
        assertEquals("Projets", new["name"]!!.jsonPrimitive.content)
        assertFalse("no role may be named when the caller gave none — $new", new.containsKey("role"))
        assertFalse("no parentId may be named when the caller gave none — $new", new.containsKey("parentId"))
        assertEquals(setOf("name", "isSubscribed"), new.keys)
    }

    /** T5b — and a nested Archive keeps carrying both of them, alongside the new property. */
    @Test fun `a nested folder with a role still sends role and parentId`() = runBlocking {
        server.enqueue(created())
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        client.createMailbox(session, "acc1", "Archive", role = "archive", auth, parentId = "mb1")

        val new = sentCreateNew()
        assertEquals("Archive", new["name"]!!.jsonPrimitive.content)
        assertEquals("archive", new["role"]!!.jsonPrimitive.content)
        assertEquals("mb1", new["parentId"]!!.jsonPrimitive.content)
        assertEquals(true, new["isSubscribed"]!!.jsonPrimitive.boolean)
    }
}
