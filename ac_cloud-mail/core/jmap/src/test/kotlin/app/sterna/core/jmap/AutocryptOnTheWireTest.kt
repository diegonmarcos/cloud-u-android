package app.sterna.core.jmap

import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.JmapSession
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The `Autocrypt:` header on the JMAP wire — the OCTETS that leave, read back from a mock server.
 */
class AutocryptOnTheWireTest {

    private lateinit var server: MockWebServer
    private val client = JmapClient()
    private val auth = BasicAuth("alex@masto.top", "secret")
    private val header = "addr=alex@masto.top; keydata=mDMEZmFrZQABCgB0aGlzIGlzIG5vdCBhIHJlYWwga2V5"

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun session() = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        uploadUrl = server.url("/jmap/upload/").toString() + "{accountId}",
    )

    private val sendResponse =
        """{"methodResponses":[
             ["Email/set",{"accountId":"acc1","created":{"draft":{"id":"e123"}}},"e0"],
             ["EmailSubmission/set",{"accountId":"acc1","created":{"sub":{"id":"s1"}}},"s0"]
           ]}"""

    private val submissionGetResponse =
        """{"methodResponses":[
             ["EmailSubmission/get",{"accountId":"acc1","list":[{"id":"s1","deliveryStatus":{
                "bob@example.org":{"delivered":"unknown","smtpReply":"250 2.1.5 Queued"}}}],
              "notFound":[]},"g0"]
           ]}"""

    /** What a server answers when it does not know the property we asked it to set (RFC 8620 §5.3). */
    private val refusesPropertyResponse =
        """{"methodResponses":[
             ["Email/set",{"accountId":"acc1","notCreated":{"draft":{"type":"invalidProperties",
                "properties":["header:Autocrypt:asText"]}}},"e0"],
             ["EmailSubmission/set",{"accountId":"acc1","notCreated":{"sub":{"type":"invalidProperties"}}},"s0"]
           ]}"""

    /**
     * The submission STANDS and the server then says one recipient was refused (#183's read), for
     */
    private val refusedDeliveryResponse =
        """{"methodResponses":[
             ["EmailSubmission/get",{"accountId":"acc1","list":[{"id":"s1","deliveryStatus":{
                "autocrypt@x.test":{"delivered":"no","smtpReply":"550 no such user"}}}],
              "notFound":[]},"g0"]
           ]}"""

    /** A refusal that has nothing to do with our property. */
    private val refusesSomethingElseResponse =
        """{"methodResponses":[
             ["Email/set",{"accountId":"acc1","notCreated":{"draft":{"type":"invalidProperties",
                "properties":["mailboxIds"]}}},"e0"],
             ["EmailSubmission/set",{"accountId":"acc1","notCreated":{"sub":{"type":"invalidProperties"}}},"s0"]
           ]}"""

    private fun argsOf(body: String, name: String): JsonObject =
        Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray
            .map { it.jsonArray }
            .first { it[0].jsonPrimitive.content == name }[1].jsonObject

    private fun createdDraft(body: String): JsonObject =
        argsOf(body, "Email/set")["create"]!!.jsonObject["draft"]!!.jsonObject

    private fun send(session: JmapSession = session(), autocrypt: String? = header): String? =
        runBlocking {
            client.sendEmail(
                session = session,
                accountId = "acc1",
                auth = auth,
                identityId = "i1",
                from = EmailAddress(name = "Alex Doe", email = "alex@masto.top"),
                to = listOf(EmailAddress(email = "bob@example.org")),
                subject = "Six o'clock",
                textBody = "see you there",
                draftMailboxId = "mbDrafts",
                sentMailboxId = "mbSent",
                autocryptHeader = autocrypt,
            )
        }

    // --- the ordinary send --------------------------------------------------------------------------

    @Test fun `a send with a key to announce carries the header property, flat`() {
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        send()
        val draft = createdDraft(server.takeRequest().body.readUtf8())
        assertEquals(header, draft["header:Autocrypt:asText"]!!.jsonPrimitive.content)
        assertFalse("the server folds it, not us", header.contains("\r"))
    }

    @Test fun `a send with nothing to announce carries no header property at all`() {
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        send(autocrypt = null)
        val draft = createdDraft(server.takeRequest().body.readUtf8())
        assertNull(draft["header:Autocrypt:asText"])
        assertFalse(server.takeRequest().body.readUtf8().contains("Autocrypt"))
    }

    @Test fun `the submission is not where the header goes`() {
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        send()
        val body = server.takeRequest().body.readUtf8()
        assertFalse(argsOf(body, "EmailSubmission/set").toString().contains("Autocrypt"))
    }

    @Test fun `a saved draft announces nothing — a draft is not a send`() {
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["Email/set",{"accountId":"acc1","created":{"draft":{"id":"d1"}}},"e0"]]}""",
            ),
        )
        runBlocking {
            client.saveDraft(
                session = session(),
                accountId = "acc1",
                auth = auth,
                from = EmailAddress(name = "Alex Doe", email = "alex@masto.top"),
                to = listOf(EmailAddress(email = "bob@example.org")),
                subject = "Six o'clock",
                textBody = "see you there",
                draftMailboxId = "mbDrafts",
            )
        }
        assertFalse(server.takeRequest().body.readUtf8().contains("Autocrypt"))
    }

    // --- the net ------------------------------------------------------------------------------------

    @Test fun `a server that names the property gets the send replayed without it`() {
        val session = session()
        server.enqueue(MockResponse().setBody(refusesPropertyResponse))
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        assertEquals("e123", send(session))

        val first = server.takeRequest().body.readUtf8()
        assertTrue("the first attempt does ask for it", first.contains("header:Autocrypt:asText"))
        val second = server.takeRequest().body.readUtf8()
        assertFalse("the replay drops it", second.contains("Autocrypt"))
        assertTrue("and the server is remembered", client.refusesAutocryptHeader(session))
    }

    @Test fun `the refusal is remembered, so the next send costs one request`() {
        val session = session()
        server.enqueue(MockResponse().setBody(refusesPropertyResponse))
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        send(session)
        server.takeRequest(); server.takeRequest(); server.takeRequest()

        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        send(session)
        assertFalse(server.takeRequest().body.readUtf8().contains("Autocrypt"))
    }

    @Test fun `a failure that does not name the property is NOT replayed`() {
        // The safety property of this guard: this request CREATES a message and submits it, so a
        // blind replay of a failure that happened after the create would send the message twice.
        val session = session()
        server.enqueue(MockResponse().setBody(refusesSomethingElseResponse))
        val thrown = runCatching { send(session) }.exceptionOrNull()
        assertTrue("the sender must be told", thrown is JmapException)
        assertEquals("exactly one request may be made", 1, server.requestCount)
        assertFalse("and nothing is blamed on the property", client.refusesAutocryptHeader(session))
    }

    @Test fun `a refusal that only NAMES an autocrypt address never re-sends the message`() {
        // THE one that cannot be taken back. The delivery refusal is read AFTER the message was
        // created and submitted, and its text is the recipient's address plus the server's own SMTP
        // reply — neither of which this app writes. A replay here puts a second copy in front of
        // every recipient that DID accept it, and a second copy in Sent.
        val session = session()
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(refusedDeliveryResponse))
        // Enqueued only so a second send would ANSWER instead of hanging: nothing may consume them.
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(refusedDeliveryResponse))

        val thrown = runCatching { send(session) }.exceptionOrNull()
        assertEquals(
            "the message left ONCE (send + delivery read); more means it was sent again",
            2,
            server.requestCount,
        )
        assertTrue("the sender must be told", thrown is JmapException)
        assertTrue(
            "and told what the server said",
            thrown?.message.orEmpty().contains("Not delivered to autocrypt@x.test"),
        )
        assertFalse("nothing may be blamed on the property", client.refusesAutocryptHeader(session))
    }

    @Test fun `a send with nothing to announce is never replayed`() {
        val session = session()
        server.enqueue(MockResponse().setBody(refusesPropertyResponse))
        runCatching { send(session, autocrypt = null) }
        assertEquals(1, server.requestCount)
        assertFalse(client.refusesAutocryptHeader(session))
    }

    // --- the decision itself, executed ----------------------------------------------------------------

    @Test fun `nothing that happens after the create may buy a replay`() {
        // The decision itself, run. The MockWebServer test above proves the point is TRIPPED where
        // it must be; this proves what tripping it does, on the exact texts a server produces.
        val propertyRefusal =
            """Could not create the message: {"type":"invalidProperties","properties":["header:Autocrypt:asText"]}"""
        assertTrue(
            "refused before anything existed: replaying costs nothing",
            JmapClient.mayReplayWithoutAutocryptHeader(
                asked = true,
                pastPointOfNoReturn = false,
                failure = propertyRefusal,
            ),
        )
        assertFalse(
            "the SAME words, once a message exists: never",
            JmapClient.mayReplayWithoutAutocryptHeader(
                asked = true,
                pastPointOfNoReturn = true,
                failure = propertyRefusal,
            ),
        )
        assertFalse(
            "the delivery refusal that started all this — the address carries the word",
            JmapClient.mayReplayWithoutAutocryptHeader(
                asked = true,
                pastPointOfNoReturn = true,
                failure = "Not delivered to autocrypt@lists.mayfirst.org: 550 no such user",
            ),
        )
        assertFalse(
            "and nothing is replayed when the header was never asked for",
            JmapClient.mayReplayWithoutAutocryptHeader(
                asked = false,
                pastPointOfNoReturn = false,
                failure = propertyRefusal,
            ),
        )
    }

    @Test fun `the refusal rule reads what a server actually answers`() {
        assertTrue(
            JmapClient.namesAutocryptProperty(
                """Could not create the message: {"type":"invalidProperties","properties":["header:Autocrypt:asText"]}""",
            ),
        )
        // Header field names are case-insensitive (RFC 5322 §1.2.2); a server may echo its own spelling.
        assertTrue(JmapClient.namesAutocryptProperty("""{"description":"unknown header autocrypt"}"""))
        assertFalse(
            JmapClient.namesAutocryptProperty(
                """Could not create the message: {"type":"invalidProperties","properties":["mailboxIds"]}""",
            ),
        )
        assertFalse(JmapClient.namesAutocryptProperty("Send failed: HTTP 500 Internal Server Error"))
        assertFalse(JmapClient.namesAutocryptProperty(null))
    }
}
