package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The push connection is the one thing a reporter is asked to capture (`adb logcat -s
 */
class EventSourceLogTest {
    private lateinit var server: MockWebServer
    private val captured = CopyOnWriteArrayList<Pair<String, Throwable?>>()
    private val client = JmapClient { message, error -> captured += message to error }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** The URL the client actually opens: query template included, token only in the header. */
    private fun pushSession() = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        eventSourceUrl = server.url("/jmap/eventsource/").toString() +
            "?types={types}&closeafter={closeafter}&ping={ping}&access_token=$QUERY_SECRET",
    )

    /** What [eventSourceOrigin] must reduce the mock server's URL to — spelled out, not derived. */
    private fun origin() = "http://${server.hostName}:${server.port}"

    /**
     * The two lines of a connection that opens and ends cleanly, pinned as WHOLE sentences. The
     */
    @Test fun `opening and closing the event source are logged, with the host and nothing else`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: state\ndata: {\"changed\":{}}\n\n"),
        )
        val closed = CountDownLatch(1)

        val source = client.openEventSource(
            pushSession(),
            BearerAuth(HEADER_SECRET),
            onStateChange = {},
            onClosed = { closed.countDown() },
        )
        source.use {
            assertTrue("the connection never ended: $captured", closed.await(20, TimeUnit.SECONDS))
        }

        assertEquals(
            listOf(
                "event source: opening to ${origin()}",
                "event source: closed by ${origin()}",
            ),
            captured.take(2).map { it.first },
        )
        assertNull("a clean open and close carried a throwable", captured.take(2).firstOrNull { it.second != null })
        assertNoSecretsLeaked()
    }

    @Test fun `a failed event source is logged with its cause`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val closed = CountDownLatch(1)

        val source = client.openEventSource(
            pushSession(),
            BearerAuth(HEADER_SECRET),
            onStateChange = {},
            onClosed = { closed.countDown() },
        )
        source.use {
            assertTrue("the connection never failed: $captured", closed.await(20, TimeUnit.SECONDS))
        }

        val failure = captured.drop(1).firstOrNull { it.second != null }
        assertNotNull(
            "no line carried the failure cause: ${captured.map { it.first to it.second?.javaClass?.name }}",
            failure,
        )
        assertEquals("event source: failed for ${origin()}", failure!!.first)
        assertNoSecretsLeaked()
    }

    /**
     * The likeliest field failure of all — an expired credential answering 401 on the SSE endpoint —
     */
    @Test fun `a refused event source names the status it was refused with`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("token expired for alex@example.com"))
        val closed = CountDownLatch(1)

        val source = client.openEventSource(
            pushSession(),
            BearerAuth(HEADER_SECRET),
            onStateChange = {},
            onClosed = { closed.countDown() },
        )
        source.use {
            assertTrue("the refusal never ended the connection: $captured", closed.await(20, TimeUnit.SECONDS))
        }

        assertEquals(
            listOf(
                "event source: opening to ${origin()}",
                "event source: failed for ${origin()} (HTTP 401)",
            ),
            captured.take(2).map { it.first },
        )
        assertFalse(
            "the response body reached the log: ${captured.map { it.first }}",
            captured.any { it.first.contains("alex@example.com") },
        )
        assertNoSecretsLeaked()
    }

    /**
     * A sink that throws must not cost the caller its reconnect: [onClosed] is how the service
     */
    @Test fun `a sink that throws does not swallow the callback`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val closed = CountDownLatch(1)
        val hostile = JmapClient { _, _ -> throw IllegalStateException("sink is on fire") }

        val source = hostile.openEventSource(
            pushSession(),
            BearerAuth(HEADER_SECRET),
            onStateChange = {},
            onClosed = { closed.countDown() },
        )
        source.use {
            assertTrue("the callback was swallowed by the sink", closed.await(20, TimeUnit.SECONDS))
        }
    }

    /**
     * Nothing logged may carry the credential, the query, or the path of the connection — and that
     */
    private fun assertNoSecretsLeaked() {
        captured.forEach { (message, error) ->
            assertFalse("the bearer token leaked: $message", message.contains(HEADER_SECRET))
            assertFalse("a query token leaked: $message", message.contains(QUERY_SECRET))
            assertFalse("the query leaked: $message", message.contains("?"))
            assertFalse("the path leaked: $message", message.contains("/jmap/"))
            val trace = error?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() }
            if (trace != null) {
                assertFalse("the bearer token leaked through the cause: $trace", trace.contains(HEADER_SECRET))
                assertFalse("a query token leaked through the cause: $trace", trace.contains(QUERY_SECRET))
                assertFalse("the path leaked through the cause: $trace", trace.contains("/jmap/"))
            }
        }
    }

    private companion object {
        const val HEADER_SECRET = "hunter2-header-secret"
        const val QUERY_SECRET = "hunter2-query-secret"
    }
}
