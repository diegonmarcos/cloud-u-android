package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT `importEmail` PUTS ON THE WIRE — the JMAP half of a cross-account move (#189): a blob
 */
class ImportEmailOnTheWireTest {

    private val auth = BasicAuth("alex@masto.top", "secret")

    private fun importedResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"methodResponses":[["Email/import",{"accountId":"acc1","oldState":"s1","newState":"s2",
              "created":{"m0":{"id":"m42","blobId":"b1","threadId":"t9","size":1234}},
              "notCreated":null},"i0"]]}
            """.trimIndent(),
        )

    private fun refusedResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"methodResponses":[["Email/import",{"accountId":"acc1","oldState":"s1","newState":"s1",
              "created":null,
              "notCreated":{"m0":{"type":"blobNotFound","description":"no such blob"}}},"i0"]]}
            """.trimIndent(),
        )

    private class Sent(val request: JsonObject, val result: Result<String>)

    private fun importAgainst(response: MockResponse, call: suspend (JmapClient, JmapSession) -> String): Sent {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(response)
            val result = runCatching {
                runBlocking { call(JmapClient(), JmapSession(apiUrl = server.url("/jmap/api/").toString())) }
            }
            assertEquals("exactly one request must reach the server", 1, server.requestCount)
            val body = server.takeRequest().body.readUtf8()
            return Sent(Json.parseToJsonElement(body).jsonObject, result)
        } finally {
            server.shutdown()
        }
    }

    /** The `Email/import` call of [request] — its args — asserting it is the only method call. */
    private fun importArgs(request: JsonObject): JsonObject {
        val calls = request["methodCalls"]!!.jsonArray
        assertEquals("one Email/import and nothing else", 1, calls.size)
        val call = calls[0].jsonArray
        assertEquals("Email/import", call[0].jsonPrimitive.content)
        return call[1].jsonObject
    }

    @Test fun `the import carries exactly the mailboxes, keywords and date it was given`() {
        val sent = importAgainst(importedResponse()) { client, session ->
            client.importEmail(
                session, "acc1", auth,
                blobId = "b1",
                mailboxIds = setOf("mb-archive", "mb-2026"),
                keywords = setOf("\$seen", "\$flagged"),
                receivedAt = "2026-09-01T21:15:03Z",
            )
        }

        assertEquals(
            listOf(Jmap.CORE_CAPABILITY, Jmap.MAIL_CAPABILITY),
            sent.request["using"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val args = importArgs(sent.request)
        assertEquals("acc1", args["accountId"]!!.jsonPrimitive.content)
        assertEquals(
            buildJsonObject {
                putJsonObject("m0") {
                    put("blobId", "b1")
                    putJsonObject("mailboxIds") { put("mb-archive", true); put("mb-2026", true) }
                    putJsonObject("keywords") { put("\$seen", true); put("\$flagged", true) }
                    put("receivedAt", "2026-09-01T21:15:03Z")
                }
            },
            args["emails"]!!.jsonObject,
        )
        assertEquals("m42", sent.result.getOrThrow())
    }

    @Test fun `no date given means no receivedAt on the wire`() {
        val sent = importAgainst(importedResponse()) { client, session ->
            client.importEmail(
                session, "acc1", auth,
                blobId = "b1",
                mailboxIds = setOf("mb-inbox"),
                keywords = emptySet(),
                receivedAt = null,
            )
        }

        assertEquals(
            buildJsonObject {
                putJsonObject("m0") {
                    put("blobId", "b1")
                    putJsonObject("mailboxIds") { put("mb-inbox", true) }
                    putJsonObject("keywords") { }
                }
            },
            importArgs(sent.request)["emails"]!!.jsonObject,
        )
        assertEquals("m42", sent.result.getOrThrow())
    }

    @Test fun `a notCreated answer is a failure, not an id`() {
        val sent = importAgainst(refusedResponse()) { client, session ->
            client.importEmail(session, "acc1", auth, "b1", setOf("mb-inbox"), emptySet(), null)
        }

        val thrown = sent.result.exceptionOrNull()
        assertTrue("expected a JmapException, got $thrown", thrown is JmapException)
        assertTrue(
            "the refusal must name the server's reason: ${thrown?.message}",
            thrown?.message?.endsWith("""{"type":"blobNotFound","description":"no such blob"}""") == true,
        )
    }
}
