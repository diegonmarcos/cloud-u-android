package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
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
 * WHERE A STAR ACTUALLY GOES — the owner's question, asked of the wire instead of the UI.
 *
 * Every assertion here reads the REQUEST THIS CLIENT SENDS, recovered from a MockWebServer. That is
 * deliberate and it is the point of the file: asserting that the local Room row gained a `flagged`
 * column proves local storage, which is the very thing in doubt. Only the outgoing `Email/set` can
 * answer "does the server hear about it".
 *
 * The keyword is compared against a LITERAL WRITTEN OUT HERE, never against the constant the
 * production code uses — read `$flagged` out of `MailRepository` and compare it to itself and the
 * test passes just as happily when that constant says `$Flagged`. It would not be a loud failure
 * either: RFC 8621 §4.1.1 lets a client invent its own keywords, so a server ACCEPTS `$Flagged`,
 * stores it, and returns it — to nobody, because no other client looks there. Webmail would simply
 * show no star, and the app would look like it worked. Hence the byte-for-byte pin below.
 */
class StarOnTheWireTest {

    private lateinit var server: MockWebServer
    private val client = JmapClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /**
     * THE ANSWER. Starring a message sends `Email/set` with the RFC 8621 §4.1.1 patch
     * `keywords/$flagged` — the registered keyword, lowercase, `$`-prefixed — set to `true`.
     */
    @Test fun `starring patches the registered flagged keyword, byte for byte`() {
        val patch = patchSentByStarring(star = true)

        assertEquals(
            "the star must be the IANA-registered `\$flagged` keyword and nothing else. A private " +
                "keyword (a capitalised \$Flagged, a bare `flagged`, `starred`) is accepted, stored, and " +
                "read by no other client — it looks identical to working until webmail is opened",
            setOf("keywords/\$flagged"),
            patch.keys,
        )
        assertEquals("true", patch.getValue("keywords/\$flagged").jsonPrimitive.content)
    }

    /**
     * Un-starring REMOVES the keyword (JSON `null`, RFC 8620 §5.3 patch semantics) rather than
     * setting it false. A `$flagged: false` is a keyword that is still THERE, holding a value the
     * spec gives no meaning to, and servers differ on whether that counts as flagged.
     */
    @Test fun `un-starring removes the keyword rather than setting it false`() {
        val patch = patchSentByStarring(star = false)

        assertEquals(setOf("keywords/\$flagged"), patch.keys)
        assertEquals(JsonNull, patch.getValue("keywords/\$flagged"))
    }

    /**
     * THE DATA-LOSS GUARD, and the reason this assertion is on the key SET and not on "contains".
     *
     * A star is a keyword: an attribute OF the message. It must never travel with a `mailboxIds`
     * edit, because that is what MOVES mail — the #67 shape, where a write meant to change one
     * thing took the message out of the folders it lived in. One key goes out, and it is the flag.
     */
    @Test fun `a star moves no message between folders`() {
        for (star in listOf(true, false)) {
            val patch = patchSentByStarring(star)
            assertNull(
                "starring must not touch mailboxIds — that is the property a MOVE writes, and a " +
                    "star that rewrote it would take the message out of its folders (#67)",
                patch["mailboxIds"],
            )
            assertTrue(
                "no mailbox membership may ride along with the flag under any spelling",
                patch.keys.none { it == "mailboxIds" || it.startsWith("mailboxIds/") },
            )
        }
    }

    /**
     * Star one message, change one message. Not a `create`, not a `destroy`, and not a second id
     * swept in beside it.
     */
    @Test fun `a star is an update of exactly the message that was tapped`() {
        server.enqueue(MockResponse().setBody(SET_OK_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        runBlocking { client.setKeyword(session, "acc1", "e1", "\$flagged", true, BasicAuth("u", "p")) }

        val args = setArgs(server.takeRequest().body.readUtf8())
        assertEquals(
            "an Email/set that stars carries an account and an update, nothing else",
            setOf("accountId", "update"),
            args.keys,
        )
        assertEquals("acc1", args.getValue("accountId").jsonPrimitive.content)
        assertEquals(listOf("e1"), args.getValue("update").jsonObject.keys.toList())
    }

    // ---- "mark all as read": the same keyword machinery, in bulk -----------------------------

    /**
     * `$seen` is a SERVER keyword exactly like `$flagged`, and "mark all as read" must reach the
     * server or the folder is read on the phone and unread again in webmail.
     *
     * Pinned as a LITERAL for the same reason the star is: `$Seen`, `$read` or a bare `seen` would
     * each be accepted and stored by the server as a private keyword that no other client reads.
     */
    @Test fun `mark-all-read patches the registered seen keyword, byte for byte`() {
        val updates = seenUpdatesSentFor(ids(3), advertisedSetLimit = 500).single()

        assertEquals("three unread messages must produce three updates", 3, updates.size)
        for ((id, patch) in updates) {
            assertEquals(
                "marking read must be the IANA-registered `\$seen` keyword and nothing else",
                setOf("keywords/\$seen"),
                patch.jsonObject.keys,
            )
            assertEquals("true", patch.jsonObject.getValue("keywords/\$seen").jsonPrimitive.content)
            assertTrue("an update must be keyed by the id it marks", id.startsWith("e"))
        }
    }

    /**
     * THE THOUSAND-UNREAD CASE, and the reason no page size is written in the UI layer.
     *
     * A folder can hold thousands of unread. One request per message would hammer the server; one
     * unbounded request would build a payload it may reject. The split is at the server's OWN
     * advertised `maxObjectsInSet` (RFC 8620 §2) — here 500, Stalwart's measured value — so the
     * limit is read from the server rather than guessed by the client.
     *
     * Asserts the COUNT of requests and the size of each: a client that sent one giant request, or
     * one per id, fails here rather than in production against a folder nobody tested on.
     */
    @Test fun `a folder of thousands splits at the server's own advertised set limit`() {
        val batches = seenUpdatesSentFor(ids(1250), advertisedSetLimit = 500)

        assertEquals("1250 ids at 500 per request is three requests", 3, batches.size)
        assertEquals(listOf(500, 500, 250), batches.map { it.size })
        assertEquals(
            "every id must be marked exactly once — no gaps, no duplicates",
            ids(1250).toSet(),
            batches.flatMap { it.keys }.toSet(),
        )
        assertEquals(1250, batches.sumOf { it.size })
    }

    /** A server that advertises a SMALLER limit is obeyed, not overridden by our fallback. */
    @Test fun `a smaller advertised limit is a limit, not a suggestion`() {
        assertEquals(listOf(3, 3, 1), seenUpdatesSentFor(ids(7), advertisedSetLimit = 3).map { it.size })
    }

    /** Marking read moves nothing between folders either — same #67 guard as the star's. */
    @Test fun `marking read moves no message between folders`() {
        for ((_, patch) in seenUpdatesSentFor(ids(2), advertisedSetLimit = 500).single()) {
            assertTrue(
                "no mailbox membership may ride along with the \$seen keyword",
                patch.jsonObject.keys.none { it == "mailboxIds" || it.startsWith("mailboxIds/") },
            )
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /**
     * The patch object one star (or un-star) of `e1` puts on the wire.
     *
     * FAILS CLOSED at every step: each `getValue`/`!!` throws if the request is not shaped as
     * claimed, so a client that sent nothing at all — or sent something unrecognisable — ERRORS
     * here. It can never return an empty object and let an assertion pass over it vacuously.
     */
    private fun patchSentByStarring(star: Boolean): JsonObject {
        server.enqueue(MockResponse().setBody(SET_OK_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        runBlocking { client.setKeyword(session, "acc1", "e1", "\$flagged", star, BasicAuth("u", "p")) }
        val args = setArgs(server.takeRequest().body.readUtf8())
        return args.getValue("update").jsonObject.getValue("e1").jsonObject
    }

    private fun ids(n: Int): List<String> = (1..n).map { "e$it" }

    /**
     * The per-request `update` objects that marking [emailIds] read puts on the wire, in order —
     * one entry per HTTP request actually made.
     *
     * FAILS CLOSED: [setArgs] throws if a request is not an `Email/set`, and `getValue` throws if it
     * carries no `update`. A client that sent nothing errors here; it cannot return an empty list
     * and let a `.map { it.size }` assertion pass over zero batches.
     */
    private fun seenUpdatesSentFor(
        emailIds: List<String>,
        advertisedSetLimit: Int,
    ): List<Map<String, JsonObject>> {
        val expected = (emailIds.size + advertisedSetLimit - 1) / advertisedSetLimit
        repeat(expected) { server.enqueue(MockResponse().setBody(SET_OK_JSON)) }
        val session = JmapSession(
            apiUrl = server.url("/jmap/api/").toString(),
            capabilities = buildMap {
                put(Jmap.MAIL_CAPABILITY, buildJsonObject { })
                put(
                    Jmap.CORE_CAPABILITY,
                    Json.parseToJsonElement("""{"maxObjectsInSet":$advertisedSetLimit}""").jsonObject,
                )
            },
        )
        runBlocking { client.setSeenAll(session, "acc1", emailIds, true, BasicAuth("u", "p")) }
        assertEquals(
            "the number of requests must follow the advertised limit, not the client's mood",
            expected,
            server.requestCount,
        )
        return (1..expected).map {
            setArgs(server.takeRequest().body.readUtf8())
                .getValue("update").jsonObject.mapValues { (_, v) -> v.jsonObject }
        }
    }

    /** The arguments of the one `Email/set` call in [body]. Throws if it is not there. */
    private fun setArgs(body: String): JsonObject {
        val call = Json.parseToJsonElement(body).jsonObject.getValue("methodCalls").jsonArray[0].jsonArray
        assertEquals("the star must go out as Email/set", "Email/set", call[0].jsonPrimitive.content)
        return call[1].jsonObject
    }

    private companion object {
        const val SET_OK_JSON = """
            {"methodResponses":[["Email/set",
              {"accountId":"acc1","oldState":"s1","newState":"s2","updated":{"e1":null}},
              "s0"]]}
        """
    }
}
