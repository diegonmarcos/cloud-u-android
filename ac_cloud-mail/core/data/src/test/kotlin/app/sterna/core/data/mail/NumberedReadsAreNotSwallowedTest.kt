package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE DECISIONS NOBODY WAS WATCHING on the read-and-bin path of a move between accounts (#99, #189).
 *
 * This file guards nothing new. It closes four mutations that changed real behaviour and left the
 * whole suite green, found by `/go-no-go` and by two counter-expertises of this branch. Every rule
 * below is stated as the mutation it kills, because a lint whose reason is not written is a lint
 * the next person deletes.
 *
 * WHY THE EXISTING LINTS MISSED THEM. The ones next door filter a body by
 * `Regex("(?i)(numbering|validity|frozen|recorded)")` or by `"rawSource(" in it`. Two of these four
 * mutations sit on lines that name NONE of those words while deciding WHICH MESSAGE IS READ, and one
 * is an enclosure rather than a line at all. A closed list only sees what its needle matches.
 *
 * Source lint, the last resort as always: `MailRepository` needs Room, a `Context` and a socket,
 * so no JVM test in this module instantiates it.
 */
class NumberedReadsAreNotSwallowedTest {

    private fun codeLines(text: String): List<String> =
        text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    /**
     * MUTATION: the body of `rawSource` reduced to `openEmail(credentials, emailId, markRead =
     */
    @Test fun `the whole body of the public read is these lines, and nothing else decides`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "rawSource")
        assertEquals(
            "⛔ EVERY line of rawSource, in order. It resolves the Email per protocol and hands " +
                "the fetch what it was given: the IMAP branch prefers the CACHED row and only " +
                "falls back on the server; both openEmail calls are markRead = false. A read that " +
                "marks mail read is a write, and this function is called by a move, by a .eml " +
                "export and by the OpenPGP retry. Body was:\n$body",
            listOf(
                "{",
                "val email = if (credentials.protocol == MailProtocol.IMAP) {",
                "cachedEmail(credentials.id, emailId) ?: openEmail(credentials, emailId, markRead = false)",
                "} else {",
                "openEmail(credentials, emailId, markRead = false)",
                "}",
                "return rawSourceBytes(emailId, fetchRawSource(credentials, email, emailId, frozen))",
                "}",
            ),
            codeLines(body),
        )
    }

    /**
     * MUTATION: `fetchRawSource`'s default flipped from `FrozenNumbering.NothingFrozen` to
     */
    @Test fun `the fetch defaults to nothing frozen, in its signature`() {
        val signature = signatureOf("fetchRawSource")
        assertEquals(
            "fetchRawSource must default to NothingFrozen — 'no tick froze anything, fall back on " +
                "the folder's record', which is what every ordinary read needs. `Frozen(null)` " +
                "here means 'a tick froze nothing at all' and is REFUSED at the wire: the two " +
                "callers that pass no argument (rawHeaders, decryptMessage) would stop working, " +
                "and the cache would stop answering them. Signature was:\n$signature",
            listOf(
                "private suspend fun fetchRawSource(",
                "credentials: AccountCredentials,",
                "email: Email,",
                "emailId: String,",
                "frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,",
                "): String {",
            ),
            codeLines(signature),
        )
    }

    /**
     * MUTATION: a `runCatching { … }` — or a `try/catch` — around the IMAP block of
     */
    @Test fun `no IMAP command that carries a numbering sits inside a block that catches`() {
        val lines = DaoQuerySource.mailSource("MailRepository").lines()
        val command = Regex("""imap\.(move|fetchSource|deleteBatch)\(""")
        val numbering = Regex("(?i)(numbering|validity|frozen|recorded)")
        val sites = lines.indices.filter { i ->
            val line = lines[i].trim()
            if (line.startsWith("//") || line.startsWith("*")) false
            else command.containsMatchIn(line) && numbering.containsMatchIn(line)
        }
        assertEquals(
            "MailRepository no longer carries five IMAP commands opposing a numbering — the two " +
                "single-message movers, the bin, the batch destroy and the opposed FETCH. If a " +
                "path was added or removed, say so here:\n" +
                sites.joinToString("\n") { "${it + 1}: ${lines[it].trim()}" },
            5, sites.size,
        )
        sites.forEach { site ->
            val catching = openBlocksAt(lines, site).filter { "try" in it || "runCatching" in it }
            assertEquals(
                "the command on line ${site + 1} of MailRepository is inside a block that catches:\n" +
                    catching.joinToString("\n") +
                    "\nA swallowed refusal is worse than no guard here: on the move between " +
                    "accounts the bin would LOOK like it succeeded, crossAccountMove would report " +
                    "Moved, and the user would be told a duplicate on the other account was a " +
                    "completed move. There is no Undo on that path.",
                emptyList<String>(), catching,
            )
        }
    }

    /** The declaration lines of [function] — from its `fun` line to the line its body opens on.
     *  These signatures are written one parameter per line precisely so this can read them. */
    private fun signatureOf(function: String): String {
        val lines = DaoQuerySource.mailSource("MailRepository").lines()
        val start = lines.indexOfFirst { Regex("""\bfun $function\(""").containsMatchIn(it) }
        check(start >= 0) { "MailRepository declares no '$function' — did it get renamed?" }
        val end = (start until lines.size).first { lines[it].trimEnd().endsWith("{") }
        return lines.subList(start, end + 1).joinToString("\n")
    }
}
