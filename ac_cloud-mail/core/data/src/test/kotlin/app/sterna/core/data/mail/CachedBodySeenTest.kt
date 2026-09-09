package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read state of a message body served from a cache (issue #148).
 */
class CachedBodySeenTest {

    private fun body(seen: Boolean, keywords: Map<String, Boolean> = emptyMap()): MessageBody =
        MessageBody(
            email = Email(
                id = "m1",
                accountId = "accA",
                subject = "Six o'clock",
                keywords = keywords + ("\$seen" to seen),
                textBody = listOf(EmailBodyPart(partId = "text")),
                bodyValues = mapOf("text" to EmailBodyValue(value = "see you there")),
            ),
            inlineImages = mapOf("cid1" to "data:image/png;base64,AAAA"),
        )

    // --- 1. executed: the four states the reconciliation can be handed -------------------------

    @Test fun `a body cached while unread is read again when the row says read`() {
        val reconciled = reconcileCachedSeen(cached = body(seen = false), rowSeen = true)

        assertTrue(
            "the row is the truth about what has been read: a body cached on the first open says " +
                "unread forever, and every reopen then looks like a fresh unread -> read transition",
            reconciled.email.isSeen,
        )
    }

    @Test fun `a body cached while unread stays unread when the row says unread`() {
        val reconciled = reconcileCachedSeen(cached = body(seen = false), rowSeen = false)

        assertFalse(
            "a message still unread must keep asking what it owes: reconciling must not mark " +
                "anything read on its own",
            reconciled.email.isSeen,
        )
    }

    @Test fun `a body cached while unread stays unread when there is no row at all`() {
        val cached = body(seen = false)

        val reconciled = reconcileCachedSeen(cached = cached, rowSeen = null)

        assertFalse(
            "no row means the read state is UNKNOWN, not read — inventing 'read' would silently " +
                "swallow a question that was owed",
            reconciled.email.isSeen,
        )
        assertSame("an unknown state must leave the cached body untouched", cached, reconciled)
    }

    @Test fun `a body cached while read goes back to unread when the row says unread`() {
        // Marking a message unread again is a real gesture (a swipe, the list menu), and it writes
        // the `emails` row while the cached body keeps the `$seen` of the day it was fetched.
        val reconciled = reconcileCachedSeen(cached = body(seen = true), rowSeen = false)

        assertFalse(
            "the row decides in BOTH directions — a one-way 'promote to read' would leave a " +
                "message marked unread again showing as read for as long as its body stays cached",
            reconciled.email.isSeen,
        )
    }

    @Test fun `a body already agreeing with the row is handed back as it is`() {
        val cached = body(seen = true)

        assertSame(cached, reconcileCachedSeen(cached = cached, rowSeen = true))
    }

    // --- executed: a read state that cannot be read is unknown, and the message still opens -----

    @Test fun `a seen read that throws is an unknown state, not a failed open`() = runTest {
        val cached = body(seen = false)

        val reconciled = reconcileCachedSeen(
            cached = cached,
            rowSeen = seenOrUnknown { throw IllegalStateException("database is locked") },
        )

        assertSame(
            "a message that can no longer be opened is worse than a question asked twice: the two " +
                "paths this sits on hold the body ALREADY, one of them entirely in memory, so a " +
                "database that refuses to answer must cost the reconciliation and nothing else",
            cached,
            reconciled,
        )
    }

    @Test fun `the guard hands an answered read straight through`() = runTest {
        // Read with the test above: a guard that swallowed everything would pass it and turn the
        // reconciliation into a no-op everywhere.
        assertEquals(true, seenOrUnknown { true })
        assertEquals(false, seenOrUnknown { false })
        assertNull(seenOrUnknown { null })
    }

    // --- the witness: nothing else about the body moves -----------------------------------------

    @Test fun `only the read state is reconciled, the rest of the body survives`() {
        // Read together with the four above: they would all pass on a function that returned a
        // bare `Email(id, keywords)`, which is not a message anyone can read.
        val cached = body(seen = false, keywords = mapOf("\$flagged" to true, "\$answered" to true))

        val reconciled = reconcileCachedSeen(cached = cached, rowSeen = true)

        assertEquals("m1", reconciled.email.id)
        assertEquals("accA", reconciled.email.accountId)
        assertEquals("Six o'clock", reconciled.email.subject)
        assertEquals("see you there", reconciled.email.textContent())
        assertEquals(mapOf("cid1" to "data:image/png;base64,AAAA"), reconciled.inlineImages)
        assertEquals(
            "the other keywords of a cached body are stale in the very same way, and correcting " +
                "them is separate work — this one must not touch them either way",
            mapOf("\$flagged" to true, "\$answered" to true, "\$seen" to true),
            reconciled.email.keywords,
        )
    }

    // --- 2. read as source text: both cache paths of openMessage go through it -------------------

    @Test fun `the Room body cache reconciles against the row of the very message it read`() {
        assertEquals(
            "cachedMessage is the Room cache path of openMessage: without this call it returns " +
                "the `\$seen` frozen into the row's JSON on the first open. The arguments are " +
                "pinned whole because passing the WRONG id (or another account's) reads a row " +
                "that is not this message's and answers with someone else's read state",
            listOf("cached = cached, rowSeen = seenOrUnknown { emailDao.seenOf(accountId, emailId) }"),
            callsOf(bodyOf("cachedMessage"), "reconcileCachedSeen("),
        )
    }

    @Test fun `what the Room cache path RETURNS is the reconciled body`() {
        // The mutation the test above cannot see: `reconcileCachedSeen(…)` on its own line,
        // followed by `return cached`. The call is still there, with the very arguments pinned
        // above, and the bug is back whole — this is the most travelled of the two cache paths.
        // So the returned EXPRESSION is pinned, whole, not merely the existence of a call.
        assertEquals(
            "cachedMessage must RETURN what it reconciled, not the body it read: a reconciliation " +
                "whose result is dropped is issue #148 restored, with every call site untouched",
            listOf(
                "null",
                "reconcileCachedSeen(cached = cached, " +
                    "rowSeen = seenOrUnknown { emailDao.seenOf(accountId, emailId) })",
            ),
            returnsOf(bodyOf("cachedMessage")),
        )
    }

    @Test fun `the in-memory decrypted cache reconciles too, and returns what it reconciled`() {
        // The path a partial fix leaves mute: an OpenPGP message decrypted earlier in this
        // process never touches Room again, so fixing `cachedMessage` alone leaves it re-asking.
        val branch = blockAfter(codeOf(bodyOf("openMessage")), "decryptedCache.get(")

        assertEquals(
            "the decrypted-body LRU is the OTHER cache openMessage returns from, and its bodies " +
                "are just as frozen as Room's",
            listOf("cached = entry.body, rowSeen = seenOrUnknown { emailDao.seenOf(credentials.id, emailId) }"),
            callsOf(branch, "reconcileCachedSeen("),
        )
        assertEquals(
            "and it must hand back the reconciled body — `return entry.body` here is the same " +
                "dropped result as in cachedMessage, on the path Room never sees again",
            listOf("body"),
            returnsOf(branch),
        )
        assertTrue(
            "the row must be read BEFORE the marking is launched, or this open reports the state " +
                "it is itself about to write",
            branch.indexOf("seenOrUnknown") < branch.indexOf("bgScope.launch"),
        )
    }

    @Test fun `the network path is left alone`() {
        // The third return of openMessage is the fetch: it carries the server's own answer and is
        // fresher than any local row. Reconciling it against a stale cached row would be a
        // downgrade, so the call count above is also a statement about what is NOT called.
        val code = codeOf(bodyOf("openMessage"))
        val fetch = code.indexOf("openEmail(credentials, emailId, markRead)")
        assertTrue("openMessage must still fetch over the network when both caches miss", fetch > 0)
        assertTrue(
            "nothing after the network fetch may be reconciled against the local row",
            "reconcileCachedSeen(" !in code.substring(fetch),
        )
    }

    // --- reading the source ---------------------------------------------------------------------

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /**
     * The argument text of every `[call]…)` in [body], whitespace-normalised, in source order.
     */
    private fun callsOf(body: String, call: String): List<String> {
        val code = codeOf(body)
        val calls = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = code.indexOf(call, from)
            if (at < 0) break
            val open = at + call.length - 1
            val close = matchingParen(code, open)
            calls += flatten(code.substring(open + 1, close))
            from = close
        }
        return calls
    }

    /**
     * The expression every `return` of [body] hands back, whole and in source order — labelled
     */
    private fun returnsOf(body: String): List<String> {
        val code = codeOf(body)
        return Regex("""\breturn\b(?!@)""").findAll(code).map { match ->
            val out = StringBuilder()
            var i = match.range.last + 1
            var depth = 0
            var inString = false
            loop@ while (i < code.length) {
                val c = code[i]
                when {
                    inString && c == '\\' -> { out.append(c).append(code.getOrElse(i + 1) { ' ' }); i += 2; continue@loop }
                    c == '"' -> { inString = !inString; out.append(c) }
                    inString -> out.append(c)
                    c == '(' || c == '{' || c == '[' -> { depth++; out.append(c) }
                    c == ')' || c == '}' || c == ']' -> { if (depth == 0) break@loop; depth--; out.append(c) }
                    c == '\n' && depth == 0 -> break@loop
                    else -> out.append(c)
                }
                i++
            }
            flatten(out.toString())
        }.toList()
    }

    /** The `{ … }` block that follows [marker] in [code], braces included — one branch of a
     *  function, so a rule about it cannot be satisfied by some other branch's code. */
    private fun blockAfter(code: String, marker: String): String {
        val at = code.indexOf(marker)
        check(at >= 0) { "no '$marker' in this function any more — did the path move?" }
        val open = code.indexOf('{', at)
        check(open >= 0) { "'$marker' is no longer followed by a block" }
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced block after '$marker'")
    }

    /** Source text on one line: runs of whitespace collapsed, and the padding a multi-line call or
     *  a trailing comma leaves behind removed, so an expected value reads as it is written. */
    private fun flatten(text: String): String =
        text.replace(Regex("""\s+"""), " ").trim().removeSuffix(",").trim()
            .replace("( ", "(").replace(", )", ")").replace(" )", ")")

    /** The index of the ')' closing the '(' at [open], quotes and nesting accounted for. */
    private fun matchingParen(code: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        while (i < code.length) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0 && c == ')') return i
                }
            }
            i++
        }
        error("unbalanced call in the source at offset $open")
    }

    /** [body] with every comment removed, string literals left alone — the comments in this
     *  repository name the very arguments the rules above pin. */
    private fun codeOf(body: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < body.length) {
            val c = body[i]
            when {
                inString && c == '\\' -> { out.append(c).append(body.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; out.append(c) }
                inString -> out.append(c)
                c == '/' && body.getOrNull(i + 1) == '/' -> {
                    while (i < body.length && body[i] != '\n') i++
                    continue
                }
                c == '/' && body.getOrNull(i + 1) == '*' -> {
                    val end = body.indexOf("*/", i + 2)
                    i = if (end < 0) body.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
