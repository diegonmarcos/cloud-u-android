package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxIdRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An account whose folder list has never been synced, and the one question that decides whether
 */
class SearchNeverSyncedAccountTest {

    private fun folders(vararg pairs: Pair<String, String?>) = pairs.map { MailboxIdRole(it.first, it.second) }

    // ---- the two decisions, chained as the repository chains them ----

    /**
     * The plain never-synced case: nothing in the folder cache. The walk falls back to the inbox,
     * so whatever it finds is what one folder holds — never what the account holds.
     */
    @Test fun `an account with no cached folder cannot claim a total, however the walk went`() {
        val known = searchableFolderIds(emptyList())

        assertEquals(emptyList<String>(), known)
        assertFalse(imapSearchComplete(known, walkComplete = true, found = 3, limit = 50))
        // Especially when it found nothing: THIS is the answer that must not read "No results".
        assertFalse(imapSearchComplete(known, walkComplete = true, found = 0, limit = 50))
    }

    /**
     * The half-synced case, which looks nothing like "no folders" in the database and behaves
     */
    @Test fun `an account whose only cached folders are Trash and Junk is in the same position`() {
        val known = searchableFolderIds(folders("t" to "trash", "j" to "junk", "s" to "Spam"))

        assertEquals(emptyList<String>(), known)
        assertFalse(imapSearchComplete(known, walkComplete = true, found = 2, limit = 50))
    }

    /** The witness: an account whose folders ARE cached, walked to the end, under the cap. */
    @Test fun `an account with its folders cached answers with a total`() {
        val known = searchableFolderIds(folders("i" to "inbox", "a" to "archive", "t" to "trash"))

        assertEquals(listOf("i", "a"), known)
        assertTrue(imapSearchComplete(known, walkComplete = true, found = 3, limit = 50))
    }

    // ---- the wiring, read out of the shipped repository ----

    /**
     * The one that cannot be called: the walk runs on the FALLBACK list, and the verdict is
     */
    @Test fun `the completeness verdict is computed from the cached folder list, not from the walked one`() {
        val source = imapSearchBranch()

        val cached = Regex("""val (\w+) = searchableFolderIds\(""").find(source)?.groupValues?.get(1)
            ?: error("MailRepository.search no longer reads the cached folder list through searchableFolderIds()")
        val fallback = Regex("""val (\w+) = $cached\.ifEmpty""").find(source)?.groupValues?.get(1)
            ?: error("MailRepository.search no longer falls back to the inbox when '$cached' is empty")
        val verdict = Regex("""imapSearchComplete\(\s*(\w+)""").find(source)?.groupValues?.get(1)
            ?: error("MailRepository.search no longer decides completeness through imapSearchComplete()")

        assertNotEquals("the fallback list and the cached list must stay two different things", cached, fallback)
        assertTrue(
            "the walk must run on the fallback list ('$fallback')",
            source.contains("imap.search(credentials, $fallback,"),
        )
        assertEquals(
            "completeness must be judged on the CACHED folder list ('$cached'), not on what was walked",
            cached,
            verdict,
        )
    }

    /**
     * The far end of the same fallback: not even an inbox id, so the search looked nowhere. The
     */
    @Test fun `an account with nowhere to search answers incomplete rather than empty`() {
        assertTrue(
            "the 'nothing to search at all' return must not claim a whole answer",
            imapSearchBranch().contains("return MailSearchResult(emptyList(), complete = false)"),
        )
    }

    /**
     * The body of `search(credentials, query, limit)` — up to the unified `search(accounts, …)`
     */
    private fun imapSearchBranch(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun search(credentials: AccountCredentials")
        check(start >= 0) { "MailRepository no longer declares search(credentials, query, limit)" }
        val end = source.indexOf("suspend fun search(accounts:", start)
        check(end > start) { "MailRepository no longer declares the unified search(accounts, …) after it" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up — as [DaoQuerySource] does. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
