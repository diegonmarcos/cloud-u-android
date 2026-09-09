package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT — THE LAST RESORT, and stated as one.
 */
class LocalDraftLeaseWiringTest {

    @Test fun `the lease is taken through the decision, over the row's own two DAO calls`() {
        assertEquals(
            "MailRepository.takeLocalDraftForEdit, whole. `load` and `setState` are both scoped by " +
                "(accountId, id) — ids collide between two accounts of one server (#31), and an " +
                "unscoped write leases ANOTHER account's row — and the answer must be the pure " +
                "decision's, whole: it is what tells GONE from BUSY, and the composer says a " +
                "different sentence for each. A `setState = { }` here compiles and ships a lease " +
                "nobody holds: the row stays PENDING, the worker takes it while the composer has " +
                "the text, and discardLocalDraft deletes the row and its files under the user. " +
                "Lines were:",
            listOf(
                "suspend fun takeLocalDraftForEdit(accountId: String, id: String): LocalDraftEdit =",
                "takeLocalDraftEdit(",
                "id,",
                "load = { localDraftDao.byId(accountId, it) },",
                "setState = { localDraftDao.setState(accountId, id, it) },",
                ")",
            ),
            declarationAndExpressionBody("suspend fun takeLocalDraftForEdit("),
        )
    }

    @Test fun `the release goes back through the decision, clock and re-arm included`() {
        assertEquals(
            "MailRepository.releaseLocalDraftEdit, whole. Three load-bearing arguments: the clock, " +
                "because the delay handed to `schedule` is what is LEFT of the row's own backoff " +
                "and a draft a server is REFUSING must not retry the instant a composer is opened " +
                "and closed; the account-scoped load, which is what makes the EDITING guard read " +
                "the right row; and `schedule` itself — the work item booked for this draft may " +
                "well have fired while the composer held it, found the row not pending and gone " +
                "away, so without a fresh one the draft waits for the next START of the app. ⛔ And " +
                "it is now the ONLY way out of EDITING: the startup sweep leaves that state alone, " +
                "so a give-back silenced here strands the draft for good. Lines were:",
            listOf(
                "suspend fun releaseLocalDraftEdit(accountId: String, id: String) =",
                "giveLocalDraftEditBack(",
                "id,",
                "nowMillis = System.currentTimeMillis(),",
                "load = { localDraftDao.byId(accountId, it) },",
                "setState = { localDraftDao.setState(accountId, id, it) },",
                "schedule = { delay -> localDraftScheduler?.schedule(accountId, id, delay) },",
                ")",
            ),
            declarationAndExpressionBody("suspend fun releaseLocalDraftEdit("),
        )
    }

    @Test fun `the startup re-arm runs the two rescues, and no third one`() {
        assertEquals(
            "MailRepository.revertUnfinishedLocalDrafts, whole — and WHOLE is the point: the body " +
                "is read to its closing brace, so a third statement bolted in beside these two is " +
                "visible here. ⛔ The one that must never come back is an EDITING sweep, under any " +
                "name: this runs from appScope while Android is still restoring the back stack, so " +
                "it would flip the row the restored composer is about to take, and a successful " +
                "upload then deletes the row and its attachment directory under the user. ⛔ The " +
                "staged rescue is the other statement and stays separate: it is the one that flags " +
                "bodyIsLossy, and merged into the first it would flag rows that are whole. Lines " +
                "were:",
            listOf(
                "suspend fun revertUnfinishedLocalDrafts(accountId: String) {",
                "localDraftDao.revertUploadingToPending(accountId)",
                "localDraftDao.revertStagedToPendingLossy(accountId)",
                "}",
            ),
            declarationAndBlockBody("suspend fun revertUnfinishedLocalDrafts("),
        )
    }

    @Test fun `no in-memory lease survives anywhere in the repository`() {
        // The field, its mutex and the three pure functions that fed them are GONE, and this is
        // what keeps them from creeping back: a lease is a promise about a process, and the process
        val code = codeOf(MAIL_REPOSITORY.readText()).lines().map { it.trim() }
        assertEquals(
            "⛔ no localDraftLease field, no localDraftLeaseMutex, no localDraftLeaseHeldFor call:",
            emptyList<String>(),
            code.filter { "localDraftLease" in it },
        )
    }

    @Test fun `consuming the row really destroys it, files included`() {
        assertEquals(
            "MailRepository.consumeLocalDraft, whole — one line, and `= Unit` is the whole defect. " +
                "It is the same destruction the upload path uses (discardLocalDraft: the row AND " +
                "its attachment directory), under the name the composer calls it by once the " +
                "message exists somewhere else. Silenced, the row survives a send: it sits at the " +
                "top of Drafts with its \"not on the server yet\" pill, the startup re-arm puts it " +
                "back to PENDING, and the worker files a copy of an already-sent message in the " +
                "server's Drafts, permanently. Lines were:",
            listOf("suspend fun consumeLocalDraft(accountId: String, id: String) = discardLocalDraft(accountId, id)"),
            declarationAndExpressionBody("suspend fun consumeLocalDraft("),
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /**
     * The line containing [opener] in `MailRepository.kt` and its BRACED body — down to the line
     */
    private fun declarationAndBlockBody(opener: String): List<String> {
        val lines = codeOf(MAIL_REPOSITORY.readText()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = lines.indexOfFirst { opener in it }
        check(start >= 0) { "MailRepository.kt has no line containing '$opener' — renamed, or gone?" }
        val out = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth > 0) opened = true
            if (opened && depth <= 0) return out
        }
        error("unbalanced braces under '$opener' in MailRepository.kt")
    }

    /**
     * The line containing [opener] in `MailRepository.kt` and its EXPRESSION body — down to the
     */
    private fun declarationAndExpressionBody(opener: String): List<String> {
        val lines = codeOf(MAIL_REPOSITORY.readText()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = lines.indexOfFirst { opener in it }
        check(start >= 0) { "MailRepository.kt has no line containing '$opener' — renamed, or gone?" }
        val out = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            out += line
            depth += line.count { it == '(' } - line.count { it == ')' }
            if (depth <= 0 && !line.endsWith("=")) return out
        }
        error("unbalanced parentheses under '$opener' in MailRepository.kt")
    }

    /** [text] with every comment taken out — block comments tracked, `//` honoured outside strings. */
    private fun codeOf(text: String): String {
        val out = StringBuilder()
        var inBlockComment = false
        for (raw in text.lines()) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            out.append(code.toString().trimEnd()).append('\n')
        }
        return out.toString()
    }

    private companion object {
        val MAIL_REPOSITORY: File by lazy {
            repoFile("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        }

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}")
    }
}
