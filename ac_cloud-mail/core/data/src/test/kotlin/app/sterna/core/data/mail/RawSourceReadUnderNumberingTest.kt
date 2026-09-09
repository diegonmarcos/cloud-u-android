package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHERE THE FROZEN NUMBERING IS PLUGGED INTO THE READ — the source-text half of
 */
class RawSourceReadUnderNumberingTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [text] — comments dropped, whitespace normalised. Whole lines: the
     *  assertions compare them, never search inside them. */
    private fun codeLinesOf(text: String): List<String> =
        text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    private fun codeLinesNaming(text: String, needle: String): List<String> =
        codeLinesOf(text).filter { needle in it }

    private fun codeLinesNaming(text: String, needle: Regex): List<String> =
        codeLinesOf(text).filter { needle.containsMatchIn(it) }

    /**
     * Any mention of a numbering, under any spelling — the needle [ImapMoveUnderNumberingTest]
     */
    private val anyNumbering = Regex("(?i)(numbering|validity|frozen|recorded)")

    /**
     * `rawSource` is a pass-through: it resolves the [Email] per protocol and hands the fetch
     */
    @Test fun `the public read hands the numbering it was given straight down`() {
        val body = bodyOf("rawSource")
        assertEquals(
            "rawSource must mention a numbering on exactly ONE line, the one that hands its " +
                "parameter to fetchRawSource — and under ANY name. A `val expectedUidValidity: " +
                "Long? = null` here shadows the parameter, changes not one other line of this " +
                "body, and quietly turns the move between accounts back into a read that opposes " +
                "the folder's own realigned record. Body was:\n$body",
            listOf("return rawSourceBytes(emailId, fetchRawSource(credentials, email, emailId, frozen))"),
            codeLinesNaming(body, anyNumbering),
        )
    }

    /**
     * THE THREE USES, AND THEY ARE ALL THE POINT. `fetchRawSource` must spend what it was
     */
    @Test fun `the fetch skips the cache AND goes out under the numbering it was handed`() {
        val body = bodyOf("fetchRawSource")
        assertEquals(
            "fetchRawSource must spend the frozen numbering TWICE and mention one nowhere else: " +
                "on the cache decision, and on the FETCH itself. Dropping it from the " +
                "`imap.fetchSource(` call still compiles — the parameter defaults to null — and " +
                "`onMailbox` then selects under `null ?: recorded`, comparing the folder's own " +
                "record with itself; the octets of whatever holds that uid now are what gets " +
                "appended on the other account. Body was:\n$body",
            listOf(
                "rawSourceFromCache(credentials.protocol, frozen) { rawSourceCache.get(cryptoKey(credentials.id, emailId)) }?.let { return it }",
                "val numbering = when (val verdict = numberingToOppose(frozen)) {",
                "NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mailboxId, null)",
                "is NumberingToOppose.Select -> verdict.stamp",
                "imap.fetchSource(credentials, mailboxId, uid, numbering)",
            ),
            codeLinesNaming(body, anyNumbering),
        )
    }

    /**
     * The cache is READ through the decision and nowhere else — a closed list over the whole file,
     */
    @Test fun `the raw-source cache is read through the decision, and nowhere else`() {
        assertEquals(
            "the raw-source cache may be READ only through rawSourceFromCache, which is what " +
                "refuses to answer a caller carrying a frozen numbering. A direct " +
                "`rawSourceCache.get(…)` anywhere is that refusal short-circuited: the move " +
                "between accounts would then never reach the SELECT that opposes the number, and " +
                "the copy on B would be written before anything could refuse.",
            listOf(
                "private val rawSourceCache = android.util.LruCache<String, String>(4)",
                "rawSourceFromCache(credentials.protocol, frozen) { rawSourceCache.get(cryptoKey(credentials.id, emailId)) }?.let { return it }",
                "rawSourceCache.put(cryptoKey(credentials.id, emailId), raw)",
                "rawSourceCache.remove(cryptoKey(credentials.id, emailId))",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "rawSourceCache"),
        )
        // AND THE ANCHOR IS THE TYPE, NOT THE NAME — the hole the rule above still had. Every
        // line of it names the FIELD, so a SECOND `android.util.LruCache` under any other name,
        assertEquals(
            "the in-memory caches of MailRepository, whole and in order. A third one is a reader " +
                "nobody has decided the numbering of — and if it holds message source, it answers " +
                "in front of the refusal that this volet exists to reach.",
            listOf(
                "private val decryptedCache = android.util.LruCache<String, DecryptedEntry>(8)",
                "private val rawSourceCache = android.util.LruCache<String, String>(4)",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "android.util.LruCache"),
        )
    }

    /**
     * And the three whole-message FETCHes of the file, pinned together: ONE carries a frozen
     */
    @Test fun `exactly one whole-message fetch carries a frozen numbering`() {
        assertEquals(
            "the file's whole-message FETCHes, whole and in order. The one inside fetchRawSource " +
                "carries the numbering its caller froze; the two others carry none because their " +
                "callers froze none. A new one on this list is a read nobody has decided the " +
                "numbering of.",
            listOf(
                "val raw = imap.fetchSource(credentials, mailboxId, uid)",
                "imap.fetchSource(credentials, mailboxId, uid, numbering)",
                "val raw = imap.fetchSource(credentials, cached.mailboxId, uid)",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "imap.fetchSource("),
        )
    }
}
