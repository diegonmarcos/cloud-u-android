package app.sterna.core.data.mail


import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHO DECIDES to read opening lines for the message list, and what the decision is made of (#187).
 */
class ListPreviewFillTest {

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * The cache lambda is built in the REPOSITORY, from the two things `ImapMailService` has not
     */
    @Test fun `the repository decides, from the settings and from the cache`() {
        val lines = DaoQuerySource.mailFunctionBody("MailRepository", "listPreviewCache")
            .lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

        assertEquals(
            "the whole of the decision, line for line: no settings means no read, an unreadable " +
                "preference means no read either, the rule is CALLED and not rewritten, and the " +
                "answer is what the cache already holds for the page, ids AND values — read with " +
                "the SAME query both write paths take their values from (EmailDao.cachedPreviews), " +
                "and bounded by the NUMBERING the page was just read under: the caller hands both " +
                "in, this lambda invents neither",
            listOf(
                "{",
                "val prefs = settings ?: return null",
                "val wanted = runCatching {",
                "previewWantedInList(prefs.previewLines.first(), prefs.notificationContent.first())",
                "}.getOrElseUnlessCancelled { false }",
                "if (!wanted) return null",
                "return { ids, numbering -> emailDao.cachedPreviews(accountId, ids, numbering).associate { it.id to it.preview } }",
                "}",
            ),
            lines,
        )
    }

    /**
     * The three call sites, and what each of them passes.
     */
    @Test fun `the two caching reads ask for the cache, and the folder listing asks for none`() {
        val source = DaoQuerySource.mailSource("MailRepository")

        assertEquals(
            "the scroll page and the folder walk both carry the cache lambda; the folder-list " +
                "refresh must carry a literal null — it caches no message at all",
            listOf(
                "val (entities, exists) = imap.fetchOlderPage(credentials, mailboxId, offset, PAGE_SIZE, listPreviewCache(credentials.id))",
                "walk = { onPage -> imap.loadFolder(credentials, mailboxId, window, onlySubscribed = accountStore.showOnlySubscribedFolders(credentials.id), onPage = onPage, cachedPreviewsFor = listPreviewCache(credentials.id)) },",
                "val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1, onlySubscribed = onlySubscribed, onPage = {}, cachedPreviewsFor = null)",
            ),
            codeLinesNaming(source, "imap.fetchOlderPage(") + codeLinesNaming(source, "imap.loadFolder("),
        )
    }

    /**
     * The "already filled" question is bound to a COUPLE, and both halves are load-bearing.
     */
    @Test fun `the already-filled question is asked of this account's rows alone`() {
        assertEquals(
            "SELECT id, preview FROM emails WHERE accountId = :accountId AND id IN (:ids) " +
                "AND uidValidity IS :uidValidity AND preview IS NOT NULL",
            DaoQuerySource.emailDaoQuery("cachedPreviews"),
        )
    }

    // -- what the walk WRITES for a row it did not re-read: pageOpenings, executed --------------
    //
    // The defect these three stand on (#187): the folder walk asks the server only for the rows
    // the cache lacks — that is what makes an opening line cost one read ever — and then wrote the

    /**
     * The rows the page did NOT re-read come out carrying the value the cache already holds —
     */
    @Test fun `a row the page did not re-read comes out with the opening line the cache holds`() {
        val opened = pageOpenings(
            cached = mapOf(
                "imap:acct-1:INBOX:7" to "the cached opening",
                "imap:acct-1:INBOX:8" to "another cached opening",
            ),
            read = mapOf("imap:acct-1:INBOX:9" to "freshly read"),
        )

        assertEquals(
            "the page writes an opening line for every row it can name — the two it skipped keep " +
                "the cache's value, the one it fetched carries the new one. A result holding only " +
                "the fetched row is issue #187: the upsert then blanks the other two.",
            mapOf(
                "imap:acct-1:INBOX:7" to "the cached opening",
                "imap:acct-1:INBOX:8" to "another cached opening",
                "imap:acct-1:INBOX:9" to "freshly read",
            ),
            opened,
        )
    }

    /**
     * A page that read NOTHING — every row already filled, which is what a second pull-to-refresh
     */
    @Test fun `a page that fetched nothing still carries every opening line the cache holds`() {
        assertEquals(
            "an empty fetch over a filled cache must not answer an empty map: the upsert would " +
                "wipe the column for the whole page",
            mapOf(
                "imap:acct-1:INBOX:7" to "the cached opening",
                "imap:acct-1:INBOX:8" to "another cached opening",
            ),
            pageOpenings(
                cached = mapOf(
                    "imap:acct-1:INBOX:7" to "the cached opening",
                    "imap:acct-1:INBOX:8" to "another cached opening",
                ),
                read = emptyMap(),
            ),
        )
    }

    /**
     * Which ARGUMENT wins on a collision, pinned. It cannot happen on the shipped path — the
     */
    @Test fun `a freshly read opening beats the cached one for the same row`() {
        assertEquals(
            "the read is the second operand and must overwrite: the reverse order silently makes " +
                "a stale cached line permanent",
            mapOf("imap:acct-1:INBOX:7" to "freshly read"),
            pageOpenings(
                cached = mapOf("imap:acct-1:INBOX:7" to "the stale cached opening"),
                read = mapOf("imap:acct-1:INBOX:7" to "freshly read"),
            ),
        )
    }
}
