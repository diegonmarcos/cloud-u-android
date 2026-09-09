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
 * `cc` and `bcc` (RFC 8621 §4.1.2) must be ASKED FOR by every fetch whose rows are cached.
 */
class CopiesOnTheWireTest {

    private val auth = BasicAuth("alex@masto.top", "secret")

    /** A client call whose answer is cached; [cachedAt] is the upsert that makes it one. */
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
              {"id":"m1","subject":"Six o'clock"$extraProperties}
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
            name = "queryEmailsPage — a folder page, and every page of the window walk",
            cachedAt = "MailRepository.kt:1761/:1798 (folderMediator), and :1334 (syncMailbox) through queryEmailsWindow",
            enqueue = { it.enqueue(queryPageResponse()) },
            call = { client, session -> client.queryEmailsPage(session, "acc1", "inbox", 50, auth) },
        ),
        CachingPath(
            name = "getEmailsByIds — the delta sync and the Undo re-fetch",
            cachedAt = "MailRepository.kt:1246 (syncMailbox) and :4184 (restoreAll), both emailDao.upsertAll",
            enqueue = { it.enqueue(emailGetResponse()) },
            call = { client, session -> client.getEmailsByIds(session, "acc1", listOf("m1"), auth) },
        ),
        CachingPath(
            name = "getThreadEmails — expanding a conversation",
            cachedAt = "MailRepository.kt:4661 (fetchThreadMembers)",
            enqueue = { it.enqueue(threadGetResponse()); it.enqueue(emailGetResponse()) },
            call = { client, session -> client.getThreadEmails(session, "acc1", "t1", auth) },
        ),
        CachingPath(
            name = "getEmailsWithBody — the body prefetch (EMAIL_BODY_PROPERTIES)",
            cachedAt = "MailRepository.kt:3064 (prefetchInboxBodies), cached by persistBody (:3065) into email_bodies",
            enqueue = { it.enqueue(emailGetResponse()) },
            call = { client, session -> client.getEmailsWithBody(session, "acc1", listOf("m1"), auth) },
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

    /** Run one path against its own server and hand back the properties it really asked for. */
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

    private fun assertAsksForCopies(path: CachingPath) {
        val asked = propertiesAskedBy(path)
        assertTrue(
            "${path.name} does not ask for `cc`; its rows are cached at ${path.cachedAt}, and that " +
                "@Upsert then wipes the Cc another fetch stored. Asked for: $asked",
            "cc" in asked,
        )
        assertTrue(
            "${path.name} does not ask for `bcc` (cached at ${path.cachedAt}). Asked for: $asked",
            "bcc" in asked,
        )
    }

    // One test per path: whichever single property set is amputated, exactly that path names itself.

    @Test fun `a folder page asks for cc and bcc`() = assertAsksForCopies(cachingPaths()[0])

    @Test fun `the delta sync fetch asks for cc and bcc`() = assertAsksForCopies(cachingPaths()[1])

    @Test fun `expanding a thread asks for cc and bcc`() = assertAsksForCopies(cachingPaths()[2])

    @Test fun `the body fetch asks for cc and bcc`() = assertAsksForCopies(cachingPaths()[3])

    /** The guard as one statement, so a review reads the whole set of cached paths at once. */
    @Test fun `every fetch that fills the cache asks the server for cc and bcc`() {
        val missing = cachingPaths().mapNotNull { path ->
            val asked = propertiesAskedBy(path)
            val absent = listOf("cc", "bcc").filterNot { it in asked }
            if (absent.isEmpty()) null else "${path.name} (cached at ${path.cachedAt}) omits $absent, asked for $asked"
        }

        assertEquals(
            "these fetch paths cache a message without its copies, and their @Upsert erases the " +
                "columns every other path filled:\n" + missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }

    /**
     * Asked for AND decoded, order and display names included: these lists are what a reopened
     */
    @Test fun `the returned copies reach the model in their own fields`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                emailGetResponse(
                    ""","to":[{"email":"bob@example.org"}],""" +
                        """"cc":[{"name":"Carol","email":"carol@example.org"},{"email":"support@example.org"}],""" +
                        """"bcc":[{"name":"Dave","email":"dave@example.org"}]""",
                ),
            )

            val email = runBlocking {
                JmapClient().getEmail(JmapSession(apiUrl = server.url("/jmap/api/").toString()), "acc1", "m1", auth)
            }

            assertEquals(listOf("bob@example.org"), email.to.map { it.email })
            assertEquals(listOf("carol@example.org", "support@example.org"), email.cc.map { it.email })
            assertEquals(listOf("Carol", null), email.cc.map { it.name })
            assertEquals(listOf("dave@example.org"), email.bcc.map { it.email })
        } finally {
            server.shutdown()
        }
    }
}
