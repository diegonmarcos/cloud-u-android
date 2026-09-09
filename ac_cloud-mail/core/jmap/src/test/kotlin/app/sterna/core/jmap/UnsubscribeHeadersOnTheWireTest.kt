package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two unsubscribe headers, from the request bytes to the decoded model — and, just as
 */
class UnsubscribeHeadersOnTheWireTest {
    private lateinit var server: MockWebServer

    /** A fresh client per test: the "this server refuses the properties" memo is per instance,
     *  and the OS can hand a later mock server the same port a previous one used. */
    private lateinit var client: JmapClient
    private val auth = BasicAuth("alex@masto.top", "secret")

    private fun session() = JmapSession(apiUrl = server.url("/jmap/api/").toString())

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = JmapClient()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun emailResponse(extraProperties: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[
              {"id":"m1","subject":"Weekly digest"$extraProperties}
            ],"notFound":[]},"g0"]]}
            """.trimIndent(),
        )

    @Test fun `opening a message asks the server for both unsubscribe headers`() = runBlocking {
        server.enqueue(emailResponse(""))

        client.getEmail(session(), "acc1", "m1", auth)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"header:List-Unsubscribe:asText\""))
        assertTrue(body, body.contains("\"header:List-Unsubscribe-Post:asText\""))
    }

    @Test fun `the body prefetch asks for them too, so a cached body carries the banner`() = runBlocking {
        server.enqueue(emailResponse(""))

        client.getEmailsWithBody(session(), "acc1", listOf("m1", "m2"), auth)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"header:List-Unsubscribe:asText\""))
        assertTrue(body, body.contains("\"header:List-Unsubscribe-Post:asText\""))
    }

    /**
     * THE witness. A list page fetches dozens of rows every time a folder is scrolled; putting a
     */
    @Test fun `the folder list does NOT ask for them`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"methodResponses":[
                      ["Email/query",{"ids":["m1"],"queryState":"q1"},"q0"],
                      ["Email/get",{"list":[{"id":"m1"}],"state":"s1"},"g0"]
                    ]}
                    """.trimIndent(),
                ),
        )

        client.queryEmailsPage(session(), "acc1", "inbox", 50, auth)

        val body = server.takeRequest().body.readUtf8()
        assertFalse("a list page must not pay for a reader-only header", body.contains("header:List-Unsubscribe"))
    }

    /** Asked for AND decoded: without the `@SerialName` the value is dropped in silence. */
    @Test fun `the returned headers reach the model`() = runBlocking {
        server.enqueue(
            emailResponse(
                ""","header:List-Unsubscribe:asText":"<https://l.example.com/u>",""" +
                    """"header:List-Unsubscribe-Post:asText":"List-Unsubscribe=One-Click"""",
            ),
        )

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals("<https://l.example.com/u>", email.listUnsubscribe)
        assertEquals("List-Unsubscribe=One-Click", email.listUnsubscribePost)
    }

    /** A message with no such header comes back null, and that is not a failure. */
    @Test fun `a message without the headers opens with them null`() = runBlocking {
        server.enqueue(emailResponse(""))

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals(null, email.listUnsubscribe)
        assertEquals(null, email.listUnsubscribePost)
    }

    /**
     * The fallback, and the reason it is not optional. Stalwart accepts these properties; Cyrus,
     */
    @Test fun `a server that rejects the properties still opens the message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"methodResponses":[["error",{"type":"invalidArguments"},"g0"]]}"""),
        )
        server.enqueue(emailResponse(""))

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals("m1", email.id)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue("the first attempt asks", first.contains("header:List-Unsubscribe"))
        assertFalse("the replay must drop them", second.contains("header:List-Unsubscribe"))
        // …and it must still be a real Email/get, not a stripped-down one.
        assertTrue(second, second.contains("\"bodyValues\""))
    }

    /** Learned once: the next message on the same server does not spend a round trip finding out. */
    @Test fun `a refusing server is remembered for the rest of the process`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"methodResponses":[["error",{"type":"invalidArguments"},"g0"]]}"""),
        )
        server.enqueue(emailResponse(""))
        server.enqueue(emailResponse(""))

        client.getEmail(session(), "acc1", "m1", auth)
        client.getEmail(session(), "acc1", "m2", auth)

        assertEquals("the second open must cost ONE request", 3, server.requestCount)
        repeat(2) { server.takeRequest() }
        val third = server.takeRequest().body.readUtf8()
        assertFalse("the properties must not be asked for again", third.contains("header:List-Unsubscribe"))
    }

    /**
     * The same refusal, spoken the other way. A method-level `invalidArguments` is the polite
     */
    @Test fun `a server that refuses the properties with HTTP 400 still opens the message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"type":"urn:ietf:params:jmap:error:notRequest"}"""))
        server.enqueue(emailResponse(""))

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals("m1", email.id)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue("the first attempt asks", first.contains("header:List-Unsubscribe"))
        assertFalse("the replay must drop them", second.contains("header:List-Unsubscribe"))
        assertTrue(second, second.contains("\"bodyValues\""))
    }

    /**
     * And the boundary of THAT, for THIS guard: a 400 is the only status it replays without the
     */
    @Test fun `an authentication or server error is never replayed without these headers`() = runBlocking {
        listOf(401, 403, 500, 503).forEach { code ->
            // A fresh client per code: the "refuses the properties" memo is per instance, and a
            // shared one would let the first code's verdict decide the next code's request.
            val fresh = JmapClient()
            repeat(2) { server.enqueue(MockResponse().setResponseCode(code)) }

            val failure = runCatching { fresh.getEmail(session(), "acc1", "m1", auth) }.exceptionOrNull()

            assertTrue("HTTP $code: $failure", failure is JmapException)
            assertEquals("HTTP $code must reach the caller", code, (failure as JmapException).httpCode)
        }
        assertEquals("one attempt and the outer guard's single replay, per status", 8, server.requestCount)
        repeat(server.requestCount) {
            assertTrue(
                "no attempt may drop the unsubscribe headers over a failure that is not about them",
                server.takeRequest().body.readUtf8().contains("header:List-Unsubscribe"),
            )
        }
    }

    /**
     * Opening a message and prefetching bodies are NOT the same request, and a rate limit is
     */
    @Test fun `opening a message does not retry a rate limit, and keeps its own words`() = runBlocking {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(429)) }

        val failure = runCatching { client.getEmail(session(), "acc1", "m1", auth) }.exceptionOrNull()

        assertEquals("the reader waits for one request, not five", 1, server.requestCount)
        assertEquals(429, (failure as JmapException).httpCode)
        assertTrue(
            "the reader is shown this text verbatim: ${failure.message}",
            failure.message.orEmpty().startsWith("Email/get failed: HTTP 429"),
        )
    }

    /**
     * The witness for the pair above: the background prefetch DOES back off (RFC 8620 §3.6.1),
     */
    @Test fun `the prefetch backs off instead, and never drops the properties`() = runBlocking {
        repeat(8) { server.enqueue(MockResponse().setResponseCode(429)) }

        val failure = runCatching {
            client.getEmailsWithBody(session(), "acc1", listOf("m1"), auth)
        }.exceptionOrNull()

        assertEquals(429, (failure as JmapException).httpCode)
        assertEquals("the limit retry is four attempts on top of the first", 5, server.requestCount)
        repeat(server.requestCount) {
            assertTrue(
                "every attempt must keep the properties",
                server.takeRequest().body.readUtf8().contains("header:List-Unsubscribe"),
            )
        }
    }

    /**
     * RFC 8620 §3.6.1's `urn:ietf:params:jmap:error:limit` is *also* an HTTP 400 — the same status
     */
    @Test fun `the JMAP limit error is a rate limit, not a rejected property`() = runBlocking {
        val limit = MockResponse().setResponseCode(400)
            .setBody("""{"type":"urn:ietf:params:jmap:error:limit","limit":"maxConcurrentRequests"}""")
        repeat(8) { server.enqueue(limit) }

        runCatching { client.getEmailsWithBody(session(), "acc1", listOf("m1"), auth) }

        assertEquals("four backoffs and no replay", 5, server.requestCount)
        repeat(server.requestCount) {
            assertTrue(
                "a busy server must not be read as one that refuses the properties",
                server.takeRequest().body.readUtf8().contains("header:List-Unsubscribe"),
            )
        }
    }

    /**
     * And the limit of the fallback: a real failure is NOT retried without the properties. Doing
     * so would swallow the error and hand the reader an empty message instead of a diagnosis.
     */
    @Test fun `an unrelated method error is not retried without these headers`() = runBlocking {
        val accountNotFound = MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"methodResponses":[["error",{"type":"accountNotFound"},"g0"]]}""")
        repeat(2) { server.enqueue(accountNotFound) }

        val failure = runCatching { client.getEmail(session(), "acc1", "m1", auth) }.exceptionOrNull()

        assertTrue("$failure", failure is JmapException)
        assertEquals("accountNotFound", (failure as JmapException).errorType)
        // Two requests, and the second is the read-receipt guard's replay (see the note above);
        // the error reaches the caller unchanged and these headers are asked for both times.
        assertEquals(2, server.requestCount)
        repeat(2) {
            assertTrue(server.takeRequest().body.readUtf8().contains("header:List-Unsubscribe"))
        }
    }
}
