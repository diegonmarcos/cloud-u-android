package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the preview fetch is PLUGGED IN — read from the shipped source, because `ImapMailService`
 */
class PreviewFetchWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("ImapMailService", function)

    /** Every CODE line of [function]'s body, in order, trimmed — comments and blank lines dropped.
     *  Unlike [codeLinesNaming] this keeps the lines nobody thought to name. */
    private fun codeLinesOf(function: String): List<String> =
        bodyOf(function).lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /**
     * The CODE lines of the `{ … }` block that opens at or after [anchor] in `ImapMailService.kt`,
     */
    private fun blockLinesAfter(anchor: String): List<String> {
        val source = DaoQuerySource.mailSource("ImapMailService")
        val at = source.indexOf(anchor)
        require(at >= 0) { "ImapMailService no longer contains '$anchor' — did it get renamed?" }
        val open = source.indexOf('{', at)
        require(open >= 0) { "no block opens after '$anchor'" }
        var depth = 0
        var i = open
        var close = -1
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { close = i; break }
            }
            i++
        }
        require(close >= 0) { "unbalanced braces after '$anchor'" }
        return source.substring(open, close + 1).lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    }

    /** The code lines of [body] naming [needle] — comments dropped. Whole lines: the assertions
     *  compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * THE WHOLE BODY, LINE FOR LINE, IN ORDER — the only assertion here that can see a line
     */
    @Test fun `the preview path is exactly these lines, and no others`() {
        val lines = codeLinesOf("fetchPreview")

        // The lookup itself fails loudly (mailFunctionBody errors when the function is gone), and
        // a body that is not a block would leave nothing to compare — say so rather than pass.
        assertEquals("the body must be a block, or this test is comparing nothing", "{", lines.firstOrNull())
        assertEquals(
            "no line may be added to the preview path without being written down here — an extra " +
                "command in this block runs against the user's mailbox on every notification",
            listOf(
                "{",
                "if (part == null) return null",
                "return try {",
                "onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->",
                "BodyPreview.fromPart(session.fetchSectionPartial(uid, part.section, IMAP_PREVIEW_FETCH_BYTES), part)",
                "}",
                "} catch (cancelled: CancellationException) {",
                "throw cancelled",
                "} catch (failed: Throwable) {",
                "android.util.Log.w(\"ImapPreview\", \"no preview for \$mailboxId:\$uid: \${failed.message}\")",
                "null",
                "}",
                "}",
            ),
            lines,
        )
    }

    /**
     * The read is BOUNDED, and the bound is a parameter with no default.
     */
    @Test fun `the preview read carries a deadline, and cannot be called without one`() {
        assertEquals(
            "the budget must reach onMailbox — a call without it blocks for ever on a silent server",
            listOf("onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->"),
            codeLinesNaming(bodyOf("fetchPreview"), "onMailbox("),
        )
        // No default value: a caller cannot forget the deadline, because there is nothing to forget.
        val signature = DaoQuerySource.mailSource("ImapMailService")
            .substringAfter("suspend fun fetchPreview(")
            .substringBefore("): String?")
        assertEquals(
            "budgetMs must have no default: a defaulted deadline is a deadline a caller drops by " +
                "writing nothing at all",
            listOf("budgetMs: Int,"),
            signature.lines().map { it.trim() }.filter { it.startsWith("budgetMs") },
        )
    }

    /**
     * Through `onMailbox`, which is the chokepoint that SELECTs the folder and refuses a UID
     */
    @Test fun `the preview is read through the numbering chokepoint, never around it`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "the fetch must go through onMailbox: it is what SELECTs the folder and refuses a " +
                "renumbered one before a single UID goes out",
            listOf("onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->"),
            codeLinesNaming(body, "onMailbox("),
        )
        assertEquals(
            "and never around it — withSession fetches without checking the numbering",
            emptyList<String>(),
            codeLinesNaming(body, "withSession("),
        )
    }

    /**
     * The bounded, peeked read, with the section the SERVER named — and no other fetch. Reaching
     */
    @Test fun `the preview reads one bounded section, and nothing else`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "the section comes from what the envelope fetch learned, and the read is bounded by " +
                "IMAP_PREVIEW_FETCH_BYTES",
            listOf(
                "BodyPreview.fromPart(session.fetchSectionPartial(uid, part.section, IMAP_PREVIEW_FETCH_BYTES), part)",
            ),
            codeLinesNaming(body, "fetchSectionPartial("),
        )
        assertEquals(
            "no whole-message read on the notification path",
            emptyList<String>(),
            codeLinesNaming(body, "fetchSource(") + codeLinesNaming(body, "fetchSection(uid"),
        )
    }

    /**
     * No text part means no round trip AT ALL. A message that is one PDF has no opening line;
     */
    @Test fun `a message with no text part is answered without touching the server`() {
        assertEquals(
            "the null part must short-circuit before the session is taken",
            listOf("if (part == null) return null"),
            codeLinesNaming(bodyOf("fetchPreview"), "part == null"),
        )
    }

    /**
     * A preview is decoration on a notification whose job is to say that mail arrived: every
     */
    @Test fun `a failed preview costs the preview, and a cancellation still propagates`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "a cancellation must be re-thrown, never turned into 'no preview'",
            listOf("} catch (cancelled: CancellationException) {", "throw cancelled"),
            codeLinesNaming(body, "cancelled"),
        )
        assertEquals(
            "and every other failure yields no preview rather than taking the notification down",
            listOf("} catch (failed: Throwable) {"),
            codeLinesNaming(body, "catch (failed"),
        )
    }

    /**
     * THE ONE THING THAT MUST NOT HAPPEN, and it has not changed: A HALF-TRUE LIST. Writing the
     */
    @Test fun `the cached preview covers the whole page, never the notified messages alone`() {
        val source = DaoQuerySource.mailSource("ImapMailService")

        assertEquals(
            "EVERY line of this layer that assigns a preview, and no other. The two lookups take " +
                "the opening line read for THE ROW BEING BUILT, under that row's own cache id — a " +
                "lookup under any other id shows one correspondent's words under another " +
                "correspondent's name. The row then copies what it was handed: a literal there is " +
                "a rule written twice, and `preview = null,` is issue #187 itself",
            listOf(
                "val preview = previews[emailId(credentials.id, target.path, it.uid)]",
                "val preview = previews[emailId(credentials.id, mailboxId, it.uid)]",
                "preview = preview,",
            ),
            codeLinesNaming(source, "preview ="),
        )
        assertEquals(
            "and what fills it is the WHOLE page of the read, handed over untouched at both sites: " +
                "a subset here is the half-true list under another name. ⛔ accountId and mailboxId " +
                "BY NAME at both sites: they are two adjacent Strings, and swapping the two names " +
                "in the declaration mints 'imap:INBOX:acct-1:7' for a row cached as " +
                "'imap:acct-1:INBOX:7' — no lookup hits, no row shows an opening line, the page is " +
                "re-downloaded every walk, and not one assertion of this suite can see it, because " +
                "these lints read function BODIES and a declaration is not one",
            listOf(
                "val previews = pagePreviews(session, accountId = credentials.id, mailboxId = target.path, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)",
                "val previews = pagePreviews(session, accountId = credentials.id, mailboxId = mailboxId, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)",
            ),
            codeLinesNaming(source, "pagePreviews(session"),
        )
        assertEquals(
            "the notification load keeps READING no preview: it reads six messages of a page under " +
                "its own budget, and caching those six is the half-true list. ⛔ What it must not " +
                "do is DESTROY one, and this line cannot help it — the upsert replaces the whole " +
                "row — so the repair is at the write and is asserted just below",
            listOf("page.map { it.toEntity(credentials.id, folder.path, status.uidValidity, null) },"),
            codeLinesNaming(DaoQuerySource.mailFunctionBody("ImapMailService", "loadWatchedFolders"), "toEntity("),
        )
    }

    /**
     * THE WHOLE BODY OF THE LIST-PREVIEW READ, LINE FOR LINE, IN ORDER — the same last resort
     */
    @Test fun `the list preview path is exactly these lines, and no others`() {
        val lines = codeLinesOf("pagePreviews")

        assertEquals("the body must be a block, or this test is comparing nothing", "{", lines.firstOrNull())
        assertEquals(
            "no line may be added to the list-preview path without being written down here — an " +
                "extra command in this block runs against a whole page of the user's mailbox on " +
                "every folder refresh",
            listOf(
                "{",
                "if (cachedPreviewsFor == null || page.isEmpty()) return emptyMap()",
                "val ids = page.map { emailId(accountId, mailboxId, it.uid) }",
                "var cached = emptyMap<String, String>()",
                "return try {",
                "cached = cachedPreviewsFor(ids, uidValidity)",
                "pageOpenings(",
                "cached,",
                "previewsForPage(accountId, mailboxId, page, ids.toSet() - cached.keys) { section, uids ->",
                "session.fetchSectionPartials(uids, section, IMAP_PREVIEW_FETCH_BYTES)",
                "},",
                ")",
                "} catch (cancelled: CancellationException) {",
                "throw cancelled",
                "} catch (failed: Throwable) {",
                "android.util.Log.w(\"ImapPreview\", \"no list previews for \$mailboxId: \${failed.message}\")",
                "cached",
                "}",
                "}",
            ),
            lines,
        )
    }

    /**
     * THE WALK'S PAGE BLOCK, LINE FOR LINE, IN ORDER — the third and last of the additive
     */
    @Test fun `the folder walk's page block is exactly these lines, and no others`() {
        val lines = blockLinesAfter("session.walkFolder(status, limit, IMAP_FOLDER_PAGE) {")

        assertEquals(
            "no line may be added to the walk's page block without being written down here — this " +
                "block decides what every row of a refreshed folder carries in its preview column. " +
                "⛔ The numbering comes from the SELECT that enumerated this very page and is " +
                "normalised by the one shipped rule (UidValidity.stated): a cache read not bounded " +
                "by it answers with rows of the PREVIOUS numbering, whose UIDs collide with these " +
                "after a renumbering — the right sender, the right subject, somebody else's first " +
                "line, for good (#99 inside #187)",
            listOf(
                "{ page ->",
                "val previews = pagePreviews(session, accountId = credentials.id, mailboxId = target.path, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)",
                "onPage(",
                "page.map {",
                "val preview = previews[emailId(credentials.id, target.path, it.uid)]",
                "it.toEntity(credentials.id, target.path, status.uidValidity, preview)",
                "},",
                ")",
                "}",
            ),
            lines,
        )
    }

    /**
     * THE PAGER'S READ, LINE FOR LINE, IN ORDER — the same guard, at the other read site.
     */
    @Test fun `the older-page read is exactly these lines, and no others`() {
        val lines = blockLinesAfter("suspend fun fetchOlderPage(")

        assertEquals(
            "no line may be added to the pager's read without being written down here, and its " +
                "cache read is bounded by the numbering of the SELECT that enumerated the page",
            listOf(
                "{ session, status ->",
                "val page = session.fetchPage(status.exists, offset, limit)",
                "val previews = pagePreviews(session, accountId = credentials.id, mailboxId = mailboxId, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)",
                "val messages = page.map {",
                "val preview = previews[emailId(credentials.id, mailboxId, it.uid)]",
                "it.toEntity(credentials.id, mailboxId, status.uidValidity, preview)",
                "}",
                "messages to status.exists",
                "}",
            ),
            lines,
        )
    }

    /**
     * NOTHING ESCAPES THIS READ. It runs inside the folder walk, and the walk is what fills the
     */
    @Test fun `a failed body read costs the unread previews, and a cancellation still propagates`() {
        val body = bodyOf("pagePreviews")

        assertEquals(
            "a cancellation must be re-thrown, never turned into 'no previews'",
            listOf("} catch (cancelled: CancellationException) {", "throw cancelled"),
            codeLinesNaming(body, "cancelled"),
        )
        assertEquals(
            "and every other failure yields the openings the cache already had, rather than taking " +
                "the folder walk down — and rather than nothing, which the upsert would write as a " +
                "blanked column over the whole page",
            listOf("} catch (failed: Throwable) {", "cached"),
            codeLinesNaming(body, "catch (failed") + codeLinesNaming(body, "cached").takeLast(1),
        )
    }

    /**
     * It reads on the session the WALK already has selected, and takes none of its own. A
     */
    @Test fun `the list preview is read on the walk's own session, never on one of its own`() {
        val body = bodyOf("pagePreviews")

        assertEquals(
            "the read must use the session handed in by the walk",
            listOf("session.fetchSectionPartials(uids, section, IMAP_PREVIEW_FETCH_BYTES)"),
            codeLinesNaming(body, "session."),
        )
        assertEquals(
            "and never open one of its own — a second session selects the folder again, under a " +
                "numbering these UIDs may no longer belong to",
            emptyList<String>(),
            codeLinesNaming(body, "withSession(") + codeLinesNaming(body, "onMailbox("),
        )
    }
}
