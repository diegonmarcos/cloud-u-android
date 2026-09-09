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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The read receipt (RFC 8098's `Disposition-Notification-To:`) on the JMAP wire — the OCTETS that
 */
class ReadReceiptOnTheWireTest {

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
        // Built by hand: OkHttp percent-encodes the braces of a template put through HttpUrl, and
        // the client's `{accountId}` substitution would then find nothing to replace.
        uploadUrl = server.url("/jmap/upload/").toString() + "{accountId}",
    )

    private val sendResponse =
        """{"methodResponses":[
             ["Email/set",{"accountId":"acc1","created":{"draft":{"id":"e123"}}},"e0"],
             ["EmailSubmission/set",{"accountId":"acc1","created":{"sub":{"id":"s1"}}},"s0"]
           ]}"""

    private val draftResponse =
        """{"methodResponses":[["Email/set",{"accountId":"acc1","created":{"draft":{"id":"d123"}}},"e0"]]}"""

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

    /** The object an `Email/set` creates under the "draft" key. */
    private fun createdDraft(body: String): JsonObject =
        argsOf(body, "Email/set")["create"]!!.jsonObject["draft"]!!.jsonObject

    private fun sendWith(requestReceipt: Boolean): String {
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        runBlocking {
            client.sendEmail(
                session = session(),
                accountId = "acc1",
                auth = auth,
                identityId = "i1",
                from = EmailAddress(name = "Alex Doe", email = "alex@masto.top"),
                to = listOf(EmailAddress(email = "bob@example.org")),
                subject = "Six o'clock",
                textBody = "see you there",
                draftMailboxId = "mbDrafts",
                sentMailboxId = "mbSent",
                requestReceipt = requestReceipt,
            )
        }
        return server.takeRequest().body.readUtf8()
    }

    private fun saveDraftWith(requestReceipt: Boolean): String {
        server.enqueue(MockResponse().setBody(draftResponse))
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
                requestReceipt = requestReceipt,
            )
        }
        return server.takeRequest().body.readUtf8()
    }

    // --- 1. the ordinary send -----------------------------------------------------------------

    @Test fun `a send that asks for a receipt names the From in the header property`() {
        val draft = createdDraft(sendWith(requestReceipt = true))

        val addresses = draft[RECEIPT_PROPERTY]
            ?: error("Email/set carried no $RECEIPT_PROPERTY — the send asks for nothing: $draft")
        // The form matters as much as the key: `asAddresses` is an array of {name, email} objects.
        // A bare string would be silently rejected (or stored as a literal) by a strict server.
        assertEquals(
            listOf("alex@masto.top"),
            addresses.jsonArray.map { it.jsonObject["email"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("Alex Doe"),
            addresses.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
        // One header, and none of the pre-RFC spellings.
        assertEquals(1, draft.keys.count { it.startsWith("header:") })
        assertFalse("no Return-Receipt-To", draft.keys.any { "Return-Receipt" in it })
        assertFalse("no X-Confirm-Reading-To", draft.keys.any { "Confirm-Reading" in it })
    }

    @Test fun `a send that asks for nothing carries no header property at all`() {
        // The negative witness: this key on a message nobody asked it for reports back to the
        // sender every time the mail is opened.
        val draft = createdDraft(sendWith(requestReceipt = false))
        assertEquals(emptyList<String>(), draft.keys.filter { it.startsWith("header:") })
        assertFalse("nothing named Disposition-Notification anywhere", draft.toString().contains("Disposition"))
    }

    @Test fun `the submission is not where the header goes`() {
        val body = sendWith(requestReceipt = true)
        assertFalse(
            "the header belongs to the message; EmailSubmission/set must not carry it",
            argsOf(body, "EmailSubmission/set").toString().contains("Disposition"),
        )
    }

    @Test fun `the receipt follows the From of a delegated send, not the login`() {
        // "On behalf" (issue #31): the submission runs on the login's account while From shows the
        // delegated address. The receipt has to come back to the address the correspondent sees.
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        runBlocking {
            client.sendEmail(
                session = session(),
                accountId = "loginAccount",
                auth = auth,
                identityId = "loginIdentity",
                from = EmailAddress(name = "Support", email = "support@example.org"),
                to = listOf(EmailAddress(email = "bob@example.org")),
                subject = "s",
                textBody = "b",
                draftMailboxId = "mbDrafts",
                sentMailboxId = "mbSent",
                requestReceipt = true,
            )
        }

        val draft = createdDraft(server.takeRequest().body.readUtf8())
        assertEquals(
            listOf("support@example.org"),
            draft[RECEIPT_PROPERTY]!!.jsonArray.map { it.jsonObject["email"]!!.jsonPrimitive.content },
        )
        // The same object the `from` array carries — one address, not two versions of it.
        assertEquals(draft["from"]!!.jsonArray.toString(), draft[RECEIPT_PROPERTY]!!.jsonArray.toString())
    }

    // --- 2. the saved draft -------------------------------------------------------------------

    @Test fun `a draft saved while asking for a receipt carries the header too`() {
        val draft = createdDraft(saveDraftWith(requestReceipt = true))
        assertEquals(
            listOf("alex@masto.top"),
            (draft[RECEIPT_PROPERTY] ?: error("saveDraft carried no $RECEIPT_PROPERTY: $draft"))
                .jsonArray.map { it.jsonObject["email"]!!.jsonPrimitive.content },
        )
    }

    @Test fun `a draft saved without asking carries no header property`() {
        val draft = createdDraft(saveDraftWith(requestReceipt = false))
        assertEquals(emptyList<String>(), draft.keys.filter { it.startsWith("header:") })
    }

    // --- 3. the PGP/MIME route, which never sees Email-set ------------------------------------

    @Test fun `the imported raw message reaches the server byte for byte, header included`() {
        // A signed or encrypted send goes this way and NOT through Email/set: the receipt is in the
        // bytes, so this method must not rebuild or re-encode them. Anything else and the header
        // (and the signature with it) would be lost with nothing to show for it.
        val raw = (
            "From: alex@masto.top\r\n" +
                "To: bob@example.org\r\n" +
                "Subject: signed\r\n" +
                "Disposition-Notification-To: alex@masto.top\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/signed; protocol=\"application/pgp-signature\"\r\n\r\nbody\r\n"
            ).toByteArray(Charsets.UTF_8)
        server.enqueue(MockResponse().setBody("""{"blobId":"blob1","type":"message/rfc822","size":${raw.size}}"""))
        server.enqueue(MockResponse().setBody(importResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))

        val id = runBlocking {
            client.importAndSendEmail(
                session = session(),
                accountId = "acc1",
                auth = auth,
                identityId = "i1",
                rawMessage = raw,
                draftMailboxId = "mbDrafts",
                sentMailboxId = "mbSent",
            )
        }

        assertEquals("e999", id)
        // What the client actually PUT on the wire, read once and kept. It has to be THIS and never
        // `String(raw)`: `raw` is the test's own literal, so an assertion against it is true by
        // construction and crosses no line of production code.
        val uploaded = server.takeRequest().body.readUtf8()
        // The named guard first, so a lost header says so in its own words instead of arriving as a
        // multi-line byte diff; the verbatim equality below then catches everything else.
        assertTrue(
            "the uploaded blob is what will be sent; it must still carry the receipt header",
            "Disposition-Notification-To: alex@masto.top" in uploaded,
        )
        assertEquals("the raw message must be uploaded verbatim", String(raw), uploaded)
        // The import references that blob and adds no structured header of its own.
        val import = server.takeRequest().body.readUtf8()
        assertTrue("blob1" in import)
        assertFalse(
            "the header travels in the bytes, so Email/import must not restate it",
            argsOf(import, "Email/import").toString().contains("Disposition"),
        )
    }

    private companion object {
        /** RFC 8621 §4.1.2 header property, in the `asAddresses` form (a list of {name, email}). */
        const val RECEIPT_PROPERTY = "header:Disposition-Notification-To:asAddresses"
    }
}
