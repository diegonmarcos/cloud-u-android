package app.sterna.core.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT — the last resort, and for the same reason as `LocalDraftUploadWiringTest`:
 */
class AttachmentCacheWiringTest {

    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    /** Every WHOLE line of [source] naming [needle] — the idiom used across `core/data`'s tests. */
    private fun codeLinesNaming(source: String, needle: String): List<String> =
        codeOf(source).filter { needle in it }

    /**
     * The body of `fun [functionName]` in `StorageRepository`, braces included.
     */
    private fun storageBody(functionName: String): String {
        val source = STORAGE.readText()
        val at = source.indexOf("fun $functionName(")
        assertTrue("StorageRepository has no $functionName( — renamed?", at >= 0)
        val open = source.indexOf('{', at)
        var depth = 0
        var i = open
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i)
    }

    @Test fun `clearing one account's cache takes the decrypted attachment files with it`() {
        // The whole body, in order: the file sweep is the step that was missing, and it is the last
        // one — the rows go first so a throw from the file system cannot keep them.
        //
        // `clearAttachments()` is passed NOTHING and must stay that way. The directory is flat
        // and shared, not namespaced per account (`enforceAttachmentCap` filters `isFile`,
        // `usage()` sums `length()`): an `accountId` argument here would mean someone has carved
        // sub-directories, which all three of those readers would stop seeing in silence.
        assertEquals(
            "⛔ StorageRepository.clearAccountCache must clear the attachment FILES, not only five " +
                "tables' rows: the decrypted bytes of an OpenPGP attachment live in that directory, " +
                "and enforceAttachmentCap (the only other sweeper) runs only on the next download:",
            listOf(
                "{",
                "emailDao.deleteForAccount(accountId)",
                "emailFtsDao.clearAccount(accountId)",
                "emailBodyDao.deleteForAccount(accountId)",
                "mailboxDao.deleteForAccount(accountId)",
                "mailboxUidValidityDao.deleteForAccount(accountId)",
                "clearAttachments()",
                "}",
            ),
            codeOf(storageBody("clearAccountCache")),
        )
    }

    @Test fun `the shared sweep is handed the two cache trees and nothing else`() {
        // The ARGUMENTS, not the name of the call. Two ways to break this that both compile:
        //  - `outgoingDir` dropped: staged copies of already-queued sends (#70) survive every
        assertEquals(
            "⛔ StorageRepository.clearAttachments must hand clearAttachmentTrees the two CACHE " +
                "directories — never localDraftFilesDir, whose bytes belong to drafts the server " +
                "has not got:",
            listOf(
                "{",
                "clearAttachmentTrees(attachmentsDir, outgoingDir)",
                "}",
            ),
            codeOf(storageBody("clearAttachments")),
        )
    }

    /**
     * THE TWO TREES THEMSELVES, whole line. The lint above pins the two IDENTIFIERS handed to the
     */
    @Test fun `the two swept trees resolve under cacheDir, by their own names`() {
        assertEquals(
            "⛔ the attachment cache and compose's staging tree are `cacheDir/attachments` and " +
                "`cacheDir/outgoing` — the names ComposeViewModel and OutboxViewModel write to. " +
                "Rename either and every 'clear cache' sweeps an empty directory, silently:",
            listOf(
                "private val attachmentsDir: File get() = File(context.cacheDir, \"attachments\")",
                "private val outgoingDir: File get() = File(context.cacheDir, \"outgoing\")",
            ),
            codeLinesNaming(STORAGE.readText(), "File(context.cacheDir"),
        )
    }

    /**
     * THE SWEEP ITSELF, whole line — the one function [ClearAttachmentTreesTest] executes, pinned
     */
    @Test fun `the sweep deletes every file in both trees, with no condition on any of them`() {
        assertEquals(
            "⛔ clearAttachmentTrees must delete UNCONDITIONALLY: a size, age or count filter here " +
                "would leave exactly the big decrypted attachment the user asked to be rid of, and " +
                "the small fixtures of an executed test cannot tell the difference:",
            listOf(
                "{",
                "attachmentsDir.listFiles()?.forEach { it.delete() }",
                "outgoingDir.listFiles()?.forEach { it.delete() }",
                "}",
            ),
            codeOf(storageBody("clearAttachmentTrees")),
        )
    }

    /**
     * THE GLOBAL BUTTON, whole body — the garde this branch must not defeat while fixing its
     */
    @Test fun `the global clear-cache still takes the attachment files with it`() {
        assertEquals(
            "⛔ StorageRepository.clearAllCache is the button PRIVACY.md names: it must clear the " +
                "attachment FILES, not five tables' rows. Dropping the last line puts the audited " +
                "defect back, one button over:",
            listOf(
                "{",
                "emailDao.deleteAll()",
                "emailFtsDao.clearAll()",
                "emailBodyDao.deleteAll()",
                "mailboxDao.deleteAll()",
                "mailboxUidValidityDao.deleteAll()",
                "clearAttachments()",
                "}",
            ),
            codeOf(storageBody("clearAllCache")),
        )
    }

    private companion object {
        val STORAGE: File by lazy {
            repoFile("core/data/src/main/kotlin/app/sterna/core/data/storage/StorageRepository.kt")
        }

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $path from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
    }
}
