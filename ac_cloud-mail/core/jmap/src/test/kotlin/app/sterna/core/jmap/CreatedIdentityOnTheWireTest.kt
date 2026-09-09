package app.sterna.core.jmap

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
 * The OCTETS of `Identity/set` (RFC 8621 §6.4), read back from a mock server — never the source
 */
class CreatedIdentityOnTheWireTest {

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
        uploadUrl = server.url("/jmap/upload/").toString() + "{accountId}",
    )

    private val createdResponse =
        """{"methodResponses":[
             ["Identity/set",{"accountId":"acc1","created":{"new":{"id":"i-new"}}},"i0"]
           ]}"""

    /** Measured against Stalwart on an address the account may not send as. */
    private val refusedResponse =
        """{"methodResponses":[
             ["Identity/set",{"accountId":"acc1","notCreated":{"new":{
                "type":"invalidProperties",
                "description":"E-mail address not configured for this account.",
                "properties":["email"]}}},"i0"]
           ]}"""

    private fun body(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun call(body: JsonObject, name: String) =
        body["methodCalls"]!!.jsonArray.map { it.jsonArray }.first { it[0].jsonPrimitive.content == name }

    private fun createIdentity(name: String = "Iris Work", email: String = "iris.work@x.test"): String =
        runBlocking {
            client.createIdentity(
                session = session(),
                accountId = "acc1",
                name = name,
                email = email,
                auth = auth,
            )
        }

    @Test fun `the create names Identity-set and returns the new id`() {
        server.enqueue(MockResponse().setBody(createdResponse))

        val id = createIdentity()

        val sent = body()
        assertEquals("i-new", id)
        // Straight out of methodCalls[0][0], NOT through `call`: that helper selects the entry by
        // this very name, so asserting the name on its result is an assertion that cannot fail.
        val posted = sent["methodCalls"]!!.jsonArray[0].jsonArray
        assertEquals("Identity/set", posted[0].jsonPrimitive.content)
        assertEquals("acc1", posted[1].jsonObject["accountId"]!!.jsonPrimitive.content)
    }

    @Test fun `the create asks for the submission capability`() {
        server.enqueue(MockResponse().setBody(createdResponse))

        createIdentity()

        val using = body()["using"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("using was $using", "urn:ietf:params:jmap:submission" in using)
        assertTrue("using was $using", "urn:ietf:params:jmap:core" in using)
    }

    @Test fun `the created object carries the email AND the name`() {
        server.enqueue(MockResponse().setBody(createdResponse))

        createIdentity(name = "Iris Work", email = "iris.work@x.test")

        val new = call(body(), "Identity/set")[1].jsonObject["create"]!!.jsonObject["new"]!!.jsonObject
        assertEquals("iris.work@x.test", new["email"]!!.jsonPrimitive.content)
        assertEquals("Iris Work", new["name"]!!.jsonPrimitive.content)
    }

    /** The signature stays local (manual identities win in `resolvedIdentities`), and nothing
     *  in this volet asks the server for a reply-to. Anything extra here is a write nobody asked
     *  for, on a record the server keeps. */
    @Test fun `the created object carries neither signature nor replyTo`() {
        server.enqueue(MockResponse().setBody(createdResponse))

        createIdentity()

        val new = call(body(), "Identity/set")[1].jsonObject["create"]!!.jsonObject["new"]!!.jsonObject
        assertNull(new["textSignature"])
        assertNull(new["htmlSignature"])
        assertNull(new["signature"])
        assertNull(new["replyTo"])
        assertEquals(setOf("email", "name"), new.keys)
    }

    @Test fun `a refusal surfaces the server type and description`() {
        server.enqueue(MockResponse().setBody(refusedResponse))

        val e = runCatching { createIdentity(email = "refused.alias@x.test") }.exceptionOrNull()

        assertTrue("was $e", e is JmapException)
        assertEquals("invalidProperties", (e as JmapException).errorType)
        assertTrue(
            "message was ${e.message}",
            e.message!!.contains("E-mail address not configured for this account."),
        )
        // And NOT the address. The screen names the refused address itself, in the user's
        // language (`settings_identity_not_created`), then shows this message beside it: carrying
        // it here too wrote it twice, the second time inside an English fragment shown in eight
        // translations. Keep this a negative assertion — the duplication came back once already.
        assertFalse("message was ${e.message}", e.message!!.contains("refused.alias@x.test"))
    }
}
