package app.sterna.core.jmap

import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The styling of a SAVED DRAFT on the JMAP wire (#131) — the octets that leave, read back from a
 */
class DraftHtmlOnTheWireTest {

    private lateinit var server: MockWebServer
    private val client = JmapClient()
    private val auth = BasicAuth("alex@masto.top", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun session() = JmapSession(apiUrl = server.url("/jmap/api/").toString())

    private val draftResponse =
        """{"methodResponses":[["Email/set",{"accountId":"acc1","created":{"draft":{"id":"d123"}}},"e0"]]}"""

    /** The `create.draft` object of the `Email/set` this save put on the wire. */
    private fun savedDraft(htmlBody: String?): JsonObject {
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
                htmlBody = htmlBody,
                draftMailboxId = "mbDrafts",
            )
        }
        val request = server.takeRequest().body.readUtf8()
        return Json.parseToJsonElement(request).jsonObject["methodCalls"]!!.jsonArray
            .first().jsonArray[1].jsonObject["create"]!!.jsonObject["draft"]!!.jsonObject
    }

    private fun partIds(parts: JsonArray?): List<String> =
        parts.orEmpty().map { it.jsonObject["partId"]!!.jsonPrimitive.content }

    private fun types(parts: JsonArray?): List<String> =
        parts.orEmpty().map { it.jsonObject["type"]!!.jsonPrimitive.content }

    // --- 1. a styled draft carries both parts ---------------------------------------------------

    @Test fun `a styled draft puts its html beside the text, not instead of it`() {
        val draft = savedDraft("<b>see</b> you there")

        assertEquals(
            "the text alternative must survive: a client that reads text/plain still has to be " +
                "able to open the draft",
            listOf("body"),
            partIds(draft["textBody"]?.jsonArray),
        )
        val html = draft["htmlBody"]?.jsonArray
            ?: error("the create carried no htmlBody at all — the styling never left: $draft")
        assertEquals(listOf("html"), partIds(html))
        assertEquals(listOf("text/html"), types(html))
    }

    @Test fun `both body values go out, each under the partId its part names`() {
        val values = savedDraft("<b>see</b> you there")["bodyValues"]!!.jsonObject

        assertEquals(
            "see you there",
            values["body"]?.jsonObject?.get("value")?.jsonPrimitive?.content,
        )
        assertEquals(
            "⛔ the html part's own value: a part whose partId has no bodyValues entry behind it " +
                "is a create a strict server rejects, and the save fails with a banner",
            "<b>see</b> you there",
            values["html"]?.jsonObject?.get("value")?.jsonPrimitive?.content,
        )
    }

    @Test fun `the styling reaches the wire as written, escaped body and all`() {
        val values = savedDraft("<b>a &amp; b</b>")["bodyValues"]!!.jsonObject

        assertEquals(
            "<b>a &amp; b</b>",
            values["html"]?.jsonObject?.get("value")?.jsonPrimitive?.content,
        )
    }

    // --- 2. a plain draft is byte for byte what it was ------------------------------------------

    @Test fun `a draft with no styling carries no html part at all`() {
        val draft = savedDraft(null)

        assertNull(
            "⛔ every draft this app has ever saved is this shape. An empty htmlBody part list, " +
                "or one built from the text, would give them all a second MIME part and change " +
                "how every other client renders them.",
            draft["htmlBody"],
        )
    }

    @Test fun `a draft with no styling carries exactly one body value`() {
        val values = savedDraft(null)["bodyValues"]!!.jsonObject

        assertEquals(setOf("body"), values.keys)
        assertEquals("see you there", values["body"]?.jsonObject?.get("value")?.jsonPrimitive?.content)
    }

    @Test fun `a plain draft still says its one part is text-plain`() {
        val draft = savedDraft(null)

        assertEquals(listOf("text/plain"), types(draft["textBody"]?.jsonArray))
        assertTrue("the draft keywords are untouched", draft.containsKey("keywords"))
    }
}
