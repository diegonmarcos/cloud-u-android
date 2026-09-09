package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * READING the raw header fields of SEVERAL messages in one call — what the body prefetch needs to
 */
class OriginSenderHeadersOnTheWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: JmapClient
    private val auth = BasicAuth("alex@masto.top", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = JmapClient()
    }

    @After fun tearDown() = server.shutdown()

    private fun session() = JmapSession(apiUrl = server.url("/jmap/api/").toString())

    /** A session whose server admits to only [maxObjectsInGet] ids per `Email/get`. */
    private fun sessionCapping(maxObjectsInGet: Int) = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        capabilities = mapOf(
            Jmap.MAIL_CAPABILITY to buildJsonObject { },
            Jmap.CORE_CAPABILITY to buildJsonObject { put("maxObjectsInGet", maxObjectsInGet) },
        ),
    )

    /** One `Email/get` answer carrying [ids], each with the header fields given for it. */
    private fun headersResponse(vararg entries: Pair<String, String>): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[""" +
                entries.joinToString(",") { (id, headers) ->
                    """{"id":"$id","headers":[$headers]}"""
                } +
                """],"notFound":[]},"g0"]]}""",
        )

    private fun field(name: String, value: String) = """{"name":"$name","value":"$value"}"""

    /** The `Email/get` arguments of a recorded request — what the server was actually asked. */
    private fun argumentsOf(request: RecordedRequest) =
        Json.parseToJsonElement(request.body.readUtf8())
            .jsonObject["methodCalls"]!!.jsonArray.single()
            .jsonArray[1].jsonObject

    // -- 1. the request, pinned whole ------------------------------------------------------------

    /**
     * `properties: ["id","headers"]` and NOTHING else — no `bodyValues`, no `header:` property, no
     * subject. The whole arguments object is compared, so an added key fails this test.
     */
    @Test fun `the grouped call asks for the ids and their headers, and for nothing else`() = runBlocking {
        server.enqueue(headersResponse("m1" to field("X-Original-From", "ann@example.org")))

        client.getEmailsHeaders(session(), "acc1", listOf("m1", "m2"), auth)

        assertEquals(
            buildJsonObject {
                put("accountId", "acc1")
                putJsonArray("ids") { add("m1"); add("m2") }
                putJsonArray("properties") { add("id"); add("headers") }
            },
            argumentsOf(server.takeRequest()),
        )
    }

    /** The answer is keyed by id, and an id the server did not return is simply absent. */
    @Test fun `the answer maps each id to its own header fields`() = runBlocking {
        server.enqueue(
            headersResponse(
                "m1" to field("X-Original-From", "ann@example.org"),
                "m2" to (field("From", "alias@alias.example") + "," + field("X-Google-Original-From", "bob@example.org")),
            ),
        )

        val byId = client.getEmailsHeaders(session(), "acc1", listOf("m1", "m2", "m3"), auth)

        assertEquals(setOf("m1", "m2"), byId.keys)
        assertEquals(listOf("X-Original-From"), byId["m1"]!!.map { it.name })
        assertEquals(listOf("ann@example.org"), byId["m1"]!!.map { it.value })
        assertEquals(listOf("From", "X-Google-Original-From"), byId["m2"]!!.map { it.name })
        assertEquals(listOf("alias@alias.example", "bob@example.org"), byId["m2"]!!.map { it.value })
    }

    // -- 2. the split, pinned as a union ---------------------------------------------------------

    /**
     * Five ids on a server that admits to two per get: MORE THAN ONE request, and the union of
     */
    @Test fun `a server with a small get limit is asked in several batches, losing no id`() = runBlocking {
        val ids = listOf("m1", "m2", "m3", "m4", "m5")
        repeat(3) { server.enqueue(headersResponse()) }

        client.getEmailsHeaders(sessionCapping(2), "acc1", ids, auth)

        val sent = generateSequence {
            server.takeRequest(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.map { argumentsOf(it) }.toList()
        val batches = sent.map { args -> args["ids"]!!.jsonArray.map { it.jsonPrimitive.content } }

        assertTrue("five ids past a limit of two must not travel in one request: $batches", batches.size > 1)
        batches.forEach { assertTrue("a batch exceeded the server's limit: $it", it.size <= 2) }
        assertEquals("every id, exactly once", ids, batches.flatten())
        // And every batch asks for the same two properties: a split that widened the request on
        // the second call would be invisible to a check made on the first one alone.
        assertEquals(
            "the properties must not drift between batches",
            List(sent.size) { buildJsonArray { add("id"); add("headers") } },
            sent.map { it["properties"]!!.jsonArray },
        )
    }

    // -- 3. the existing single-id caller, unchanged ---------------------------------------------

    /**
     * The generalisation must be invisible to the reader's "view headers" action (#60), which is
     * the only other caller: same answer, same order, duplicates kept.
     */
    @Test fun `the single-id form still answers the fields in document order`() = runBlocking {
        server.enqueue(
            headersResponse(
                "m1" to (
                    field("Received", "from a.example") + "," +
                        field("Received", "from b.example") + "," +
                        field("X-Original-From", "ann@example.org")
                    ),
            ),
        )

        val fields = client.getEmailHeaders(session(), "acc1", "m1", auth)

        assertEquals(listOf("Received", "Received", "X-Original-From"), fields.map { it.name })
        assertEquals(
            listOf("from a.example", "from b.example", "ann@example.org"),
            fields.map { it.value },
        )
    }

    /** …and it asks for exactly what it always asked for: one id, `["id","headers"]`. */
    @Test fun `the single-id form's request is the grouped one with a single id`() = runBlocking {
        server.enqueue(headersResponse("m1" to field("X-Original-From", "ann@example.org")))

        client.getEmailHeaders(session(), "acc1", "m1", auth)

        assertEquals(
            buildJsonObject {
                put("accountId", "acc1")
                putJsonArray("ids") { add("m1") }
                putJsonArray("properties") { add("id"); add("headers") }
            },
            argumentsOf(server.takeRequest()),
        )
    }
}
