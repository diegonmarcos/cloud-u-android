package app.sterna.core.jmap

import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.SubmissionEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * The EmailSubmission `envelope` (RFC 8621 §7.5) on the wire — the OCTETS that leave, read back
 */
class EnvelopeOnTheWireTest {

    private lateinit var server: MockWebServer
    private val client = JmapClient()
    private val auth = BasicAuth("alex@masto.top", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun session() = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        // Built by hand: OkHttp percent-encodes the braces of a template put through HttpUrl.
        uploadUrl = server.url("/jmap/upload/").toString() + "{accountId}",
    )

    private val sendResponse =
        """{"methodResponses":[
             ["Email/set",{"accountId":"acc1","created":{"draft":{"id":"e123"}}},"e0"],
             ["EmailSubmission/set",{"accountId":"acc1","created":{"sub":{"id":"s1"}}},"s0"]
           ]}"""

    private val importResponse =
        """{"methodResponses":[
             ["Email/import",{"accountId":"acc1","created":{"draft":{"id":"e999"}}},"i0"],
             ["EmailSubmission/set",{"accountId":"acc1","created":{"sub":{"id":"s1"}}},"s0"]
           ]}"""

    /**
     * The second request every JMAP send now makes: it reads back what the server did with the
     */
    private val submissionGetResponse =
        """{"methodResponses":[
             ["EmailSubmission/get",{"accountId":"acc1","list":[{"id":"s1","deliveryStatus":{
                "bob@example.org":{"delivered":"unknown","smtpReply":"250 2.1.5 Queued"}}}],
              "notFound":[]},"g0"]
           ]}"""

    /** The arguments of the [name] method call in the request body the server received. */
    private fun argsOf(body: String, name: String): JsonObject =
        Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray
            .map { it.jsonArray }
            .first { it[0].jsonPrimitive.content == name }[1].jsonObject

    /** The object the EmailSubmission/set creates under the "sub" key. */
    private fun createdSub(body: String): JsonObject =
        argsOf(body, "EmailSubmission/set")["create"]!!.jsonObject["sub"]!!.jsonObject

    private fun sendWith(envelope: SubmissionEnvelope?): JsonObject {
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        runBlocking {
            client.sendEmail(
                session = session(),
                accountId = "acc1",
                auth = auth,
                identityId = "i-house",
                from = EmailAddress(name = "Théo", email = "theo@mydomain.com"),
                to = listOf(EmailAddress(email = "bob@example.org")),
                cc = listOf(EmailAddress(email = "carla@example.org")),
                bcc = listOf(EmailAddress(email = "dan@example.org")),
                subject = "Six o'clock",
                textBody = "see you there",
                draftMailboxId = "mbDrafts",
                sentMailboxId = "mbSent",
                envelope = envelope,
            )
        }
        return createdSub(server.takeRequest().body.readUtf8())
    }

    private fun importWith(envelope: SubmissionEnvelope?): JsonObject {
        val raw = "From: theo@mydomain.com\r\nTo: bob@example.org\r\nSubject: signed\r\n\r\nbody\r\n"
            .toByteArray(Charsets.UTF_8)
        server.enqueue(MockResponse().setBody("""{"blobId":"blob1","type":"message/rfc822","size":${raw.size}}"""))
        server.enqueue(MockResponse().setBody(importResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        runBlocking {
            client.importAndSendEmail(
                session = session(),
                accountId = "acc1",
                auth = auth,
                identityId = "i-house",
                rawMessage = raw,
                draftMailboxId = "mbDrafts",
                sentMailboxId = "mbSent",
                envelope = envelope,
            )
        }
        server.takeRequest() // the blob upload
        return createdSub(server.takeRequest().body.readUtf8())
    }

    /** The envelope this test poses, as a repository would build it: to + cc + bcc, in that order. */
    private fun envelope() = SubmissionEnvelope(
        mailFrom = "theo@mydomain.com",
        rcptTo = listOf("bob@example.org", "carla@example.org", "dan@example.org"),
    )

    private fun mailFromOf(sub: JsonObject): String =
        sub["envelope"]!!.jsonObject["mailFrom"]!!.jsonObject["email"]!!.jsonPrimitive.content

    private fun rcptToOf(sub: JsonObject): List<String> =
        sub["envelope"]!!.jsonObject["rcptTo"]!!.jsonArray
            .map { it.jsonObject["email"]!!.jsonPrimitive.content }

    @Test fun `an ordinary send puts the named envelope on the wire`() {
        val sub = sendWith(envelope())

        assertEquals("theo@mydomain.com", mailFromOf(sub))
        assertEquals(
            listOf("bob@example.org", "carla@example.org", "dan@example.org"),
            rcptToOf(sub),
        )
        assertEquals("i-house", sub["identityId"]!!.jsonPrimitive.content)
        assertEquals("#draft", sub["emailId"]!!.jsonPrimitive.content)
    }

    @Test fun `the raw PGP send puts the same envelope on the wire`() {
        val sub = importWith(envelope())

        assertEquals("theo@mydomain.com", mailFromOf(sub))
        assertEquals(
            listOf("bob@example.org", "carla@example.org", "dan@example.org"),
            rcptToOf(sub),
        )
        assertEquals("i-house", sub["identityId"]!!.jsonPrimitive.content)
    }

    /**
     * Every recipient, one entry each: RFC 8621 §7.5 makes `rcptTo` mandatory when an envelope
     */
    @Test fun `every recipient gets its own rcptTo entry`() {
        val sub = sendWith(
            SubmissionEnvelope(
                mailFrom = "theo@mydomain.com",
                rcptTo = listOf("bob@example.org", "carla@example.org", "dan@example.org"),
            ),
        )

        assertEquals(3, sub["envelope"]!!.jsonObject["rcptTo"]!!.jsonArray.size)
        assertTrue("a copy must not be dropped", "carla@example.org" in rcptToOf(sub))
        assertTrue("a blind copy must not be dropped", "dan@example.org" in rcptToOf(sub))
    }

    /** The default: nothing added, so the server derives the envelope exactly as it always has. */
    @Test fun `a send with no envelope writes no envelope key`() {
        val sub = sendWith(null)

        assertFalse("no envelope was asked for; the server derives it", "envelope" in sub.keys)
        assertEquals(setOf("emailId", "identityId"), sub.keys)
    }

    @Test fun `a raw PGP send with no envelope writes no envelope key`() {
        val sub = importWith(null)

        assertFalse("no envelope was asked for; the server derives it", "envelope" in sub.keys)
        assertEquals(setOf("emailId", "identityId"), sub.keys)
    }
}
