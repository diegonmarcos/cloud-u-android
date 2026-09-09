package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `replyTo` (RFC 8621 §4.1.2, RFC 5322 §3.6.2), from the request bytes to the decoded model.
 */
class ReplyToOnTheWireTest {

    private val auth = BasicAuth("alex@masto.top", "secret")

    /**
     * A client call whose answer ends up in the `emails` table, with the mock exchange it needs.
     */
    private class CachingPath(
        val name: String,
        val cachedAt: String,
        val enqueue: (MockWebServer) -> Unit,
        val call: suspend (JmapClient, JmapSession) -> Unit,
    )

    private fun emailGetResponse(extraProperties: String = ""): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[
              {"id":"m1","subject":"Weekly digest"$extraProperties}
            ],"notFound":[]},"g0"]]}
            """.trimIndent(),
        )

    private fun queryPageResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"methodResponses":[
              ["Email/query",{"ids":["m1"],"queryState":"q1","total":1},"q0"],
              ["Email/get",{"list":[{"id":"m1"}],"state":"s1"},"g0"]
            ]}
            """.trimIndent(),
        )

    private fun threadGetResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"methodResponses":[["Thread/get",{"list":[{"id":"t1","emailIds":["m1"]}]},"t0"]]}""")

    private fun cachingPaths(): List<CachingPath> = listOf(
        CachingPath(
            name = "getEmail — the reader opens a message",
            cachedAt = "MailRepository.kt:2559, cached by persistBody (:2978) into email_bodies, " +
                "and read live by fetchEmail (:4569) — NOT an `emails` row",
            enqueue = { it.enqueue(emailGetResponse()) },
            call = { client, session -> client.getEmail(session, "acc1", "m1", auth) },
        ),
        CachingPath(
            name = "getEmailsWithBody — the body prefetch",
            cachedAt = "MailRepository.kt:3019, cached by persistBody (:3020) into email_bodies " +
                "— NOT an `emails` row",
            enqueue = { it.enqueue(emailGetResponse()) },
            call = { client, session -> client.getEmailsWithBody(session, "acc1", listOf("m1"), auth) },
        ),
        CachingPath(
            name = "getEmailsByIds — the delta sync and the Undo re-fetch",
            cachedAt = "MailRepository.kt:1201 and :4139 (emailDao.upsertAll)",
            enqueue = { it.enqueue(emailGetResponse()) },
            call = { client, session -> client.getEmailsByIds(session, "acc1", listOf("m1"), auth) },
        ),
        CachingPath(
            name = "queryEmailsPage — a folder page, and every page of the window walk",
            cachedAt = "MailRepository.kt:1716/:1753, and :1289 through queryEmailsWindow",
            enqueue = { it.enqueue(queryPageResponse()) },
            call = { client, session -> client.queryEmailsPage(session, "acc1", "inbox", 50, auth) },
        ),
        CachingPath(
            name = "getThreadEmails — expanding a conversation",
            cachedAt = "MailRepository.kt:4616 (fetchThreadMembers)",
            enqueue = { it.enqueue(threadGetResponse()); it.enqueue(emailGetResponse()) },
            call = { client, session -> client.getThreadEmails(session, "acc1", "t1", auth) },
        ),
    )

    /** The `properties` array of the first `Email/get` this server was actually sent. */
    private fun emailGetProperties(server: MockWebServer): List<String> {
        repeat(server.requestCount) {
            val body = server.takeRequest().body.readUtf8()
            val calls = Json.parseToJsonElement(body).jsonObject["methodCalls"]?.jsonArray.orEmpty()
            calls.forEach { entry ->
                val call = entry.jsonArray
                if (call[0].jsonPrimitive.content == "Email/get") {
                    val properties = call[1].jsonObject["properties"]?.jsonArray
                        ?: error("Email/get sent with no `properties` argument: $body")
                    return properties.map { it.jsonPrimitive.content }
                }
            }
        }
        error("no Email/get reached the server")
    }

    /** Run one path against its own server and hand back the properties it asked for. */
    private fun propertiesAskedBy(path: CachingPath): List<String> {
        val server = MockWebServer()
        server.start()
        try {
            path.enqueue(server)
            runBlocking { path.call(JmapClient(), JmapSession(apiUrl = server.url("/jmap/api/").toString())) }
            return emailGetProperties(server)
        } finally {
            server.shutdown()
        }
    }

    /**
     * THE guard. Every path whose result is kept asks for the property — otherwise the three
     * that write an `emails` row silently wipe, through their `@Upsert`, what the others wrote.
     */
    @Test fun `every fetch that fills the cache asks the server for replyTo`() {
        val missing = cachingPaths().mapNotNull { path ->
            val asked = propertiesAskedBy(path)
            if ("replyTo" in asked) null else "${path.name} (cached at ${path.cachedAt}) asked for $asked"
        }

        assertEquals(
            "these fetch paths keep a message without Reply-To, and the ones writing an `emails` " +
                "row erase the column for every other path:\n" + missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }

    /** The same fact per path, so a failure names ONE call instead of a list. */
    @Test fun `the body fetch asks for replyTo`() {
        val asked = propertiesAskedBy(cachingPaths()[1])
        assertTrue("EMAIL_BODY_PROPERTIES must carry it, got $asked", "replyTo" in asked)
    }

    @Test fun `the delta sync fetch asks for replyTo`() {
        val asked = propertiesAskedBy(cachingPaths()[2])
        assertTrue("getEmailsByIds must carry it, got $asked", "replyTo" in asked)
    }

    @Test fun `a folder page asks for replyTo`() {
        val asked = propertiesAskedBy(cachingPaths()[3])
        assertTrue("queryEmailsPage must carry it, got $asked", "replyTo" in asked)
    }

    @Test fun `expanding a thread asks for replyTo`() {
        val asked = propertiesAskedBy(cachingPaths()[4])
        assertTrue("getThreadEmails must carry it, got $asked", "replyTo" in asked)
    }

    /**
     * Asked for AND decoded, in the server's order, display names included: the reply screen puts
     * these straight into the To field, and an order swapped here is a mail sent to the wrong one.
     */
    @Test fun `the returned addresses reach the model in order, names included`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                emailGetResponse(
                    ""","replyTo":[{"name":"Sterna list","email":"list@lists.example.org"},""" +
                        """{"email":"support@example.org"},{"name":"Ana Ruíz","email":"ana@example.org"}]""",
                ),
            )

            val email = runBlocking {
                JmapClient().getEmail(JmapSession(apiUrl = server.url("/jmap/api/").toString()), "acc1", "m1", auth)
            }

            assertEquals(
                listOf("list@lists.example.org", "support@example.org", "ana@example.org"),
                email.replyTo.map { it.email },
            )
            assertEquals(listOf("Sterna list", null, "Ana Ruíz"), email.replyTo.map { it.name })
        } finally {
            server.shutdown()
        }
    }

    /** A server that answers no `replyTo` at all is the ordinary case, not a failure. */
    @Test fun `a message without the property opens with an empty list`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(emailGetResponse())

            val email = runBlocking {
                JmapClient().getEmail(JmapSession(apiUrl = server.url("/jmap/api/").toString()), "acc1", "m1", auth)
            }

            assertEquals(emptyList<String>(), email.replyTo.map { it.email })
            assertEquals("m1", email.id)
        } finally {
            server.shutdown()
        }
    }

    /** …and an explicit `null`, which RFC 8621 allows for every address field. */
    @Test fun `an explicit null replyTo decodes to an empty list`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(emailGetResponse(""","replyTo":null"""))

            val email = runBlocking {
                JmapClient().getEmail(JmapSession(apiUrl = server.url("/jmap/api/").toString()), "acc1", "m1", auth)
            }

            assertEquals(emptyList<String>(), email.replyTo.map { it.email })
        } finally {
            server.shutdown()
        }
    }
}
