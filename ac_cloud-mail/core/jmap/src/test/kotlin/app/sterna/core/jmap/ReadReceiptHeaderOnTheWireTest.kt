package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * READING `Disposition-Notification-To:` off a JMAP server — the request bytes, which fetches
 */
class ReadReceiptHeaderOnTheWireTest {

    private lateinit var server: MockWebServer

    /** A fresh client per test: the "this server refuses it" memos are per instance. */
    private lateinit var client: JmapClient
    private val auth = BasicAuth("alex@masto.top", "secret")

    private fun session() = JmapSession(apiUrl = server.url("/jmap/api/").toString())

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = JmapClient()
    }

    @After fun tearDown() = server.shutdown()

    /** The literal property name, written out once here and nowhere else in this file. */
    private val receiptProperty = "\"header:Disposition-Notification-To:asText\""

    private fun emailResponse(extraProperties: String = ""): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[
              {"id":"m1","subject":"Six o'clock"$extraProperties}
            ],"notFound":[]},"g0"]]}
            """.trimIndent(),
        )

    // --- 1. the two body-bearing fetches, and only those ---------------------------------------

    @Test fun `opening a message asks the server for the receipt header`() = runBlocking {
        server.enqueue(emailResponse())

        client.getEmail(session(), "acc1", "m1", auth)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains(receiptProperty))
    }

    @Test fun `the body prefetch asks for it too, so a cached body reads the same`() = runBlocking {
        server.enqueue(emailResponse())

        client.getEmailsWithBody(session(), "acc1", listOf("m1", "m2"), auth)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains(receiptProperty))
    }

    /** Asked for AND decoded — without the `@SerialName` the value is dropped in silence. */
    @Test fun `the returned header reaches the model`() = runBlocking {
        server.enqueue(emailResponse(""","header:Disposition-Notification-To:asText":"Ann <ann@example.org>""""))

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals("Ann <ann@example.org>", email.dispositionNotificationTo)
    }

    /** A message nobody asked a receipt for comes back null, and that is not a failure. */
    @Test fun `a message without the header opens with it null`() = runBlocking {
        server.enqueue(emailResponse())

        assertEquals(null, client.getEmail(session(), "acc1", "m1", auth).dispositionNotificationTo)
    }

    // --- 2. THE witnesses: the five fetches that must NOT pay for it ----------------------------

    /**
     * A folder page, a delta sync, a thread and the two search paths fetch dozens of rows every
     */
    @Test fun `the folder page does NOT ask for it`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"methodResponses":[
                  ["Email/query",{"ids":["m1"],"queryState":"q1"},"q0"],
                  ["Email/get",{"list":[{"id":"m1"}],"state":"s1"},"g0"]
                ]}
                """.trimIndent(),
            ),
        )

        client.queryEmailsPage(session(), "acc1", "inbox", 50, auth)

        assertNoReceiptProperty("a list page must not pay for a reader-only header")
    }

    @Test fun `the delta sync does NOT ask for it`() = runBlocking {
        server.enqueue(emailResponse())

        client.getEmailsByIds(session(), "acc1", listOf("m1"), auth)

        assertNoReceiptProperty("the sync writes list rows; it reads no header")
    }

    @Test fun `the thread fetch does NOT ask for it`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"methodResponses":[["Thread/get",{"list":[{"id":"t1","emailIds":["m1"]}]},"t0"]]}""",
            ),
        )
        server.enqueue(emailResponse())

        client.getThreadEmails(session(), "acc1", "t1", auth)

        assertNoReceiptProperty("thread members are list rows too")
    }

    @Test fun `the search does NOT ask for it`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"methodResponses":[
                  ["Email/query",{"ids":["m1"],"queryState":"q1"},"q0"],
                  ["Email/get",{"list":[{"id":"m1"}],"state":"s1"},"g0"]
                ]}
                """.trimIndent(),
            ),
        )

        client.searchEmails(session(), "acc1", SearchQuery(text = "invoice"), 20, auth)

        assertNoReceiptProperty("a search answer is headers only, on purpose")
    }

    @Test fun `the index crawl does NOT ask for it`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"methodResponses":[
                  ["Email/query",{"ids":["m1"],"queryState":"q1"},"q0"],
                  ["Email/get",{"list":[{"id":"m1"}],"state":"s1"},"g0"]
                ]}
                """.trimIndent(),
            ),
        )

        client.crawlHeaders(session(), "acc1", 0, 100, auth)

        assertNoReceiptProperty("the crawl walks years of mail; it must stay tiny")
    }

    private fun assertNoReceiptProperty(why: String) {
        val bodies = requestBodies()
        assertTrue("nothing was sent at all — the check would pass vacuously", bodies.isNotEmpty())
        bodies.forEach { assertFalse("$why — $it", it.contains("Disposition-Notification")) }
    }

    // --- 3. the two fallbacks, INDEPENDENTLY ---------------------------------------------------

    /**
     * A server that answers `invalidArguments` to whichever of the two optional property groups
     */
    private fun serverRefusing(vararg refuses: String, with: MockResponse = invalidArguments()) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // peek, not readUtf8: reading the buffer here EMPTIES it, and every assertion
                // below would then be made against an empty string — vacuously true, which is
                // how this test first passed while proving nothing.
                val body = request.body.peek().readUtf8()
                return if (refuses.any { it in body }) with else emailResponse()
            }
        }
    }

    private fun invalidArguments() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"methodResponses":[["error",{"type":"invalidArguments"},"g0"]]}""")

    /**
     * Every request received since the last drain, in order.
     */
    private fun requestBodies(): List<String> =
        generateSequence { server.takeRequest(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .map { it.body.readUtf8() }
            .toList()

    /**
     * HALF ONE of the invariant. A server that will not hear of the receipt property keeps its
     */
    @Test fun `a server refusing the receipt property keeps its unsubscribe headers`() = runBlocking {
        serverRefusing("Disposition-Notification")

        val email = client.getEmail(session(), "acc1", "m1", auth)
        assertEquals("m1", email.id)

        val bodies = requestBodies()
        val served = bodies.last()
        assertTrue("the message must still be opened by a real Email/get: $served", "bodyValues" in served)
        assertFalse("the refused property must be gone: $served", "Disposition-Notification" in served)
        assertTrue("…and the unsubscribe headers must NOT have been dropped with it: $served", "header:List-Unsubscribe:asText" in served)

        // Learned, and learned about the right property: the next open costs ONE request and
        // still asks for the unsubscribe headers. (The dispatcher above serves it too.)
        client.getEmail(session(), "acc1", "m2", auth)
        val next = requestBodies().single()
        assertFalse("the refusal is remembered: $next", "Disposition-Notification" in next)
        assertTrue("but only for its own property: $next", "header:List-Unsubscribe:asText" in next)
    }

    /**
     * HALF TWO, and the one a nested guard gets wrong by default: a server that refuses the
     */
    @Test fun `a server refusing the unsubscribe headers keeps the receipt property`() = runBlocking {
        serverRefusing("List-Unsubscribe")

        val email = client.getEmail(session(), "acc1", "m1", auth)
        assertEquals("m1", email.id)

        val bodies = requestBodies()
        assertEquals("the measured case must not have grown a probe", 2, bodies.size)
        val served = bodies.last()
        assertFalse("the refused headers must be gone: $served", "List-Unsubscribe" in served)
        assertTrue("…and the receipt property must NOT have been dropped with them: $served", receiptProperty in served)

        client.getEmail(session(), "acc1", "m2", auth)
        val next = requestBodies().single()
        assertFalse("the refusal is remembered: $next", "List-Unsubscribe" in next)
        assertTrue("but only for its own properties: $next", receiptProperty in next)
    }

    /** A server that wants neither still opens the message, and stops probing afterwards. */
    @Test fun `a server refusing both still opens the message, once`() = runBlocking {
        serverRefusing("Disposition-Notification", "List-Unsubscribe")

        assertEquals("m1", client.getEmail(session(), "acc1", "m1", auth).id)
        val served = requestBodies().last()
        assertTrue("still a real Email/get: $served", "bodyValues" in served)

        client.getEmail(session(), "acc1", "m2", auth)
        val bodies = requestBodies()
        assertEquals("both verdicts were learned: the second open costs one request", 1, bodies.size)
        assertFalse(bodies.last(), "Disposition-Notification" in bodies.last())
        assertFalse(bodies.last(), "List-Unsubscribe" in bodies.last())
    }

    /**
     * A server may refuse a property it does not know in words RFC 8620 never wrote down: an
     */
    @Test fun `a property refused in words the RFC never wrote is still recovered from`() = runBlocking {
        serverRefusing(
            "Disposition-Notification",
            with = MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"methodResponses":[["error",{"type":"serverFail"},"g0"]]}"""),
        )

        assertEquals("m1", client.getEmail(session(), "acc1", "m1", auth).id)
        val served = requestBodies().last()
        assertFalse("the refused property is gone: $served", "Disposition-Notification" in served)
        assertTrue("and the unsubscribe headers are not collateral: $served", "header:List-Unsubscribe:asText" in served)
    }

    @Test fun `a property refused with HTTP 500 is still recovered from`() = runBlocking {
        serverRefusing("Disposition-Notification", with = MockResponse().setResponseCode(500))

        assertEquals("m1", client.getEmail(session(), "acc1", "m1", auth).id)
        assertFalse("Disposition-Notification" in requestBodies().last())
    }

    /**
     * And the price of that width, paid where it must be: when the replay fails too, the reader
     */
    @Test fun `a failure that survives the replay reaches the reader unchanged`() = runBlocking {
        // The two attempts fail DIFFERENTLY on purpose: rethrowing the replay's own exception
        // would hand the reader "HTTP 500" for a mailbox whose real problem is a bad password.
        var seen = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(if (seen++ == 0) 401 else 500)
        }

        val failure = runCatching { client.getEmail(session(), "acc1", "m1", auth) }.exceptionOrNull()

        assertEquals("the FIRST failure is the reader's answer", 401, (failure as JmapException).httpCode)
        assertTrue(
            "the reader is shown this text verbatim: ${failure.message}",
            failure.message.orEmpty().startsWith("Email/get failed: HTTP 401"),
        )
        assertEquals("one replay, and one only", 2, server.requestCount)
        assertFalse("and nothing was written down", client.refusesReceiptHeader(session()))
    }

    // --- 4. the prefetch has the same two guards, and they are just as independent -------------

    /**
     * [JmapClient.getEmailsWithBody] carries the same nesting inside its batching, and it is
     */
    @Test fun `the prefetch keeps its unsubscribe headers when the receipt property is refused`() = runBlocking {
        serverRefusing("Disposition-Notification")

        client.getEmailsWithBody(session(), "acc1", listOf("m1"), auth)

        val served = requestBodies().last()
        assertFalse("the refused property is gone: $served", "Disposition-Notification" in served)
        assertTrue("the unsubscribe headers are not collateral: $served", "header:List-Unsubscribe:asText" in served)
    }

    @Test fun `the prefetch keeps the receipt property when the unsubscribe headers are refused`() = runBlocking {
        serverRefusing("List-Unsubscribe")

        client.getEmailsWithBody(session(), "acc1", listOf("m1"), auth)

        val bodies = requestBodies()
        assertEquals("the measured case must not have grown a probe", 2, bodies.size)
        assertFalse("the refused headers are gone: ${bodies.last()}", "List-Unsubscribe" in bodies.last())
        assertTrue("the receipt property is not collateral: ${bodies.last()}", receiptProperty in bodies.last())
    }

    /**
     * The verdict is written down only when dropping the property is what MADE the call work.
     */
    @Test fun `a failure that is nobody's property leaves no verdict behind`() = runBlocking {
        serverRefusing("replyTo")

        val failure = runCatching { client.getEmail(session(), "acc1", "m1", auth) }.exceptionOrNull()

        assertTrue("$failure", failure is JmapException)
        assertFalse("no property may be blamed for this", client.refusesReceiptHeader(session()))
    }

    /**
     * What the draft save reads. A server that refuses the property answers null for EVERY
     */
    @Test fun `a refusing server is reported as such, so a null answer is not read as a no`() = runBlocking {
        assertFalse("nothing is known before the first request", client.refusesReceiptHeader(session()))
        serverRefusing("Disposition-Notification")

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals(null, email.dispositionNotificationTo)
        assertTrue("…and that null must be readable as unknown", client.refusesReceiptHeader(session()))
    }

    /** …and a server that simply has no such header on the message is NOT reported as refusing. */
    @Test fun `a server that answers normally is never marked as refusing`() = runBlocking {
        server.enqueue(emailResponse())

        client.getEmail(session(), "acc1", "m1", auth)

        assertFalse(client.refusesReceiptHeader(session()))
    }
}
