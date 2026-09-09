package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 */
class PrefetchStaysInTheInboxWiringTest {

    @Test fun `a single-account refresh warms bodies only when the folder is the inbox`() {
        assertEquals(
            "the guard and the launch it guards must sit together, in this order, whole. ⚠ An " +
                "empty `but was []` means the line `if (target.role == \"inbox\") {` is no longer " +
                "in `refresh` verbatim — widened to `!= null`, renamed, moved, or simply " +
                "lengthened by a trailing comment — so the pair cannot be looked up at all. " +
                "Widened, a refresh of Junk, Trash or Archive prefetches their bodies, and every " +
                "prefetched body hands the sender's announced key to OpenKeychain: a spoofed " +
                "message the user never saw, and never opened, plants a key under the address of " +
                "the contact it imitates, and nothing in this app can take it back. And should " +
                "the second line hand the prefetch anything other than `target.id`, the guard " +
                "stays in place while another folder is warmed anyway.",
            listOf(REFRESH_GUARD, "bgScope.launch { runCatching { prefetchInboxBodies(credentials, target.id) } }"),
            adjacentBlock(codeLines(bodyOf("refresh")), REFRESH_GUARD, 2),
        )
        assertEquals(
            "`target` must be BOUND exactly once in `refresh`, and to this. The pair above pins " +
                "two adjacent lines; a second binding of the same name anywhere above them — " +
                "`val target = mailboxes.first { it.role == \"junk\" }` — leaves both of those " +
                "lines untouched, satisfies every text comparison in this file, and hands the " +
                "prefetch a different folder at run time. A pin on a NAME is worth nothing " +
                "without a pin on what that name is bound to, once.",
            listOf("val target = mailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }"),
            bindingsOf(bodyOf("refresh"), "target"),
        )
    }

    @Test fun `the unified refresh warms the folder it resolved as the inbox, and no other`() {
        assertEquals(
            "`refreshAllInboxes` is the SECOND producer of a prefetch, and the one the branch " +
                "brief forgot. The folder it warms must be the one it resolved as the account's " +
                "inbox — `inbox.id`, the same `inbox` it just synced. Any other id (a mailbox " +
                "picked from `resolved.mailboxes`, the account's trash, a folder carried over " +
                "from the previous loop turn) prefetches bodies outside the inbox on every " +
                "unified refresh, and each of those bodies imports its sender's public key into " +
                "OpenKeychain unopened. Exactly one such line, so a second prefetch cannot be " +
                "added beside it — ⛔ including one hidden behind a `/**/` on its own line, which " +
                "is what this file's [codeLines] exists to stop.",
            listOf("bgScope.launch { runCatching { prefetchInboxBodies(credentials, inbox.id) } }"),
            codeLinesNaming(bodyOf("refreshAllInboxes"), "prefetchInboxBodies("),
        )
    }

    @Test fun `the unified refresh resolves that folder by the server's inbox role, once`() {
        assertEquals(
            "`inbox` must be BOUND exactly once in `refreshAllInboxes`, and to this. ⛔ This is " +
                "the assertion that makes the next one mean anything: eleven lines sit between " +
                "the resolution and the launch, and `val inbox = resolved.mailboxes.firstOrNull " +
                "{ it.role == \"junk\" } ?: inbox` slipped in among them leaves the resolution " +
                "line in place, unique and verbatim, while `inbox.id` at the launch names the " +
                "junk folder. Every message in Spam then has its body fetched on each unified " +
                "refresh, and every spoofed sender in it plants a key in OpenKeychain under the " +
                "address it imitates, with nothing opened and nothing undoable.",
            listOf(INBOX_RESOLUTION),
            bindingsOf(bodyOf("refreshAllInboxes"), "inbox"),
        )
        assertEquals(
            "and the three lines of that single binding must stay exactly these, in this order. " +
                "⚠ `?: resolved.mailboxes.firstOrNull()` is deliberate and already the widest " +
                "this may be: an account whose server declares no `inbox` role warms its first " +
                "folder, whatever that is. Widen it further (drop the role filter, take a folder " +
                "by name, iterate the list) and the keys of junk senders arrive with no gesture.",
            listOf(INBOX_RESOLUTION, "?: resolved.mailboxes.firstOrNull()", "?: continue"),
            adjacentBlock(codeLines(bodyOf("refreshAllInboxes")), INBOX_RESOLUTION, 3),
        )
    }

    @Test fun `an IMAP account leaves the unified refresh before any of this`() {
        assertEquals(
            "⛔ This is one of the TWO lines that actually keep an IMAP account away from the " +
                "prefetch, and the one nothing watched: `refreshAllInboxes` must serve an IMAP " +
                "account entirely inside this branch and `continue` out of the loop turn. Drop " +
                "the `continue` — or let a statement appear after the branch and before it — and " +
                "the pass falls through into the JMAP half for an IMAP account: `resolve`, " +
                "`syncMailbox`, and then the prefetch, which then really does import the keys of " +
                "the newest inbox senders on an IMAP account without anything being opened. " +
                "ENCRYPTION.md and SECURITY.md promise the opposite in those words. The other " +
                "line, `refresh`'s `return refreshImap(...)`, is pinned whole by " +
                "[SyncWindowReachesBothProtocolsTest] and is deliberately not restated here.",
            listOf(
                "if (credentials.protocol == MailProtocol.IMAP) {",
                "val load = imapWriteThrough(credentials, mailboxId = null, limit = limit)",
                "mailboxDao.replaceAll(credentials.id, load.mailboxes)",
                "results += AccountInboxMeta(",
                "credentials.id, load.accountName, load.targetMailboxId, load.targetName, load.unread,",
                ")",
                "continue",
                "}",
            ),
            adjacentBlock(
                codeLines(bodyOf("refreshAllInboxes")),
                "if (credentials.protocol == MailProtocol.IMAP) {",
                8,
            ),
        )
    }

    @Test fun `the prefetch keeps its own IMAP refusal, unreachable though it is today`() {
        assertEquals(
            "`prefetchInboxBodies` must still open on `if (credentials.protocol == " +
                "MailProtocol.IMAP) return`, as its FIRST statement. ⚠ Be honest about what this " +
                "is worth: BOTH of its callers already leave for IMAP before reaching it " +
                "(`refresh` returns into `refreshImap`, `refreshAllInboxes` `continue`s), so " +
                "removing this line alone changes nothing observable today and no user could tell. " +
                "It is defence in depth — the last net, not the first — and it is pinned so that " +
                "the day a third caller appears, the net is still there rather than having been " +
                "tidied away as dead code. What the two documents actually rest on is pinned by " +
                "the test above and by [SyncWindowReachesBothProtocolsTest]. And should this line " +
                "move BELOW the fetch, it stops being a refusal at all: the bodies are already " +
                "cached by then.",
            listOf("{", "if (credentials.protocol == MailProtocol.IMAP) return"),
            codeLines(bodyOf("prefetchInboxBodies")).take(2),
        )
    }

    @Test fun `only these two call sites can start a prefetch at all`() {
        assertEquals(
            "the whole of MailRepository.kt may name `prefetchInboxBodies(` on exactly three " +
                "lines: the two refresh call sites and the declaration. A fourth line is a third " +
                "producer — a push pass, a delta, a folder the user opened — warming bodies " +
                "somewhere nobody audited, and every warmed body is an unopened key import. " +
                "Fewer than three means a call site was deleted and the inbox is no longer " +
                "warmed at all.",
            listOf(
                "bgScope.launch { runCatching { prefetchInboxBodies(credentials, inbox.id) } }",
                "bgScope.launch { runCatching { prefetchInboxBodies(credentials, target.id) } }",
                "private suspend fun prefetchInboxBodies(credentials: AccountCredentials, mailboxId: String) {",
            ),
            codeLines(DaoQuerySource.mailSource("MailRepository")).filter { "prefetchInboxBodies(" in it },
        )
    }

    // --- reading the source ----------------------------------------------------------------------

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /**
     * [text]'s lines as CODE: every COMPLETE block comment removed from wherever it sits on the
     */
    private fun codeLines(text: String): List<String> = text.lines()
        .map { WHITESPACE.replace(BLOCK_COMMENT.replace(it, " "), " ").trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The code lines of [body] naming [needle], whole — the assertions compare them, never search
     *  inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLines(body).filter { needle in it }

    /**
     * Every code line of [body] that BINDS the name [name] — a `val`/`var` declaration or a bare
     */
    private fun bindingsOf(body: String, name: String): List<String> =
        codeLines(body).filter { Regex("""^(val\s+|var\s+)?$name\s*=(?!=)""").containsMatchIn(it) }

    /**
     * [count] lines starting at the single line equal to [needle]. Empty when [needle] is not there
     */
    private fun adjacentBlock(lines: List<String>, needle: String, count: Int): List<String> {
        val at = lines.indices.filter { lines[it] == needle }.singleOrNull() ?: return emptyList()
        return if (at + count > lines.size) emptyList() else lines.subList(at, at + count)
    }

    private companion object {
        const val REFRESH_GUARD = "if (target.role == \"inbox\") {"

        const val INBOX_RESOLUTION = "val inbox = resolved.mailboxes.firstOrNull { it.role == \"inbox\" }"

        /** A block comment that OPENS AND CLOSES on the same line — the only kind that can sit
         *  beside code, and therefore the only kind [codeLines] may remove rather than obey. */
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""")

        val WHITESPACE = Regex("""\s+""")
    }
}
