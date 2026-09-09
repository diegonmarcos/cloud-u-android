package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, for the reason [DeleteWouldDestroyWiringTest]
 */
class MoveToAccountWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** Every CODE line of [text] — comments and blanks dropped, whitespace normalised — so prose
     *  can neither satisfy a rule nor break one, and the assertions compare whole lines. */
    private fun codeLines(text: String): List<String> =
        text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    private fun codeLinesNaming(text: String, needle: String): List<String> =
        codeLines(text).filter { needle in it }

    private fun codeLinesNaming(text: String, needle: Regex): List<String> =
        codeLines(text).filter { needle.containsMatchIn(it) }

    /** Any mention of a numbering, under any spelling — the needle [ImapMoveUnderNumberingTest]
     * invented for this family of mutations, borrowed verbatim. Case-INSENSITIVE on purpose:
     *  a needle of `"frozen"` misses `FrozenNumbering.…` and `NothingFrozen`, which is most of the
     *  vocabulary this rule now has to see. */
    private val anyNumbering = Regex("(?i)(numbering|validity|frozen|recorded)")

    @Test fun `the whole body is these lines, and nothing else decides`() {
        val body = bodyOf("moveToAccount")
        assertEquals(
            "⛔ EVERY line of moveToAccount, in order. The body builds the seven steps and hands " +
                "them to crossAccountMove — which CrossAccountMoveTest runs — and decides NOTHING " +
                "itself: no check skipped, no trash written here, no destroy. Body was:\n$body",
            listOf(
                "{",
                "val from = withCachedFallback(email, cachedEmail(source.id, email.id))",
                "val steps = object : CrossAccountMoveSteps {",
                "var messageId: String? = null",
                "override fun checkSourceConfigured() = checkAccountStillConfigured(source.id, accountStore.accounts().map { it.id })",
                "override fun checkTargetConfigured() = checkAccountStillConfigured(target.id, accountStore.accounts().map { it.id })",
                "override suspend fun readSource(): ByteArray = rawSource(source, email.id, frozen).also { messageId = messageIdOf(from, it) }",
                "override suspend fun writeTarget(bytes: ByteArray): String? {",
                "if (target.protocol == MailProtocol.IMAP) {",
                "val uid = imap.appendBytes(target, targetMailboxId, bytes, imapFlagsOf(from.keywords), internalDateOf(from.receivedAt))",
                "return uid?.let { ImapMailService.emailId(target.id, targetMailboxId, it) }",
                "}",
                "val ctx = connect(target)",
                """val blobId = client.uploadBlob(ctx.session, ctx.accountId, bytes, "message/rfc822", ctx.auth).blobId""",
                "return client.importEmail(ctx.session, ctx.accountId, ctx.auth, blobId, setOf(targetMailboxId), jmapKeywordsOf(from.keywords), from.receivedAt)",
                "}",
                "override suspend fun findTarget(): String? {",
                "if (target.protocol != MailProtocol.IMAP) return null",
                "val uid = imap.findByMessageId(target, targetMailboxId, messageId ?: return null)",
                "return uid?.let { ImapMailService.emailId(target.id, targetMailboxId, it) }",
                "}",
                "override fun markTargetLocalMove(targetId: String) = recentLocalMoves.mark(target.id, targetId)",
                "override suspend fun trashSource() { delete(source, email.id, frozen) }",
                "override suspend fun refreshTargetFolder() { refresh(target, targetMailboxId) }",
                "}",
                "return crossAccountMove(steps)",
                "}",
            ),
            codeLines(body),
        )
    }

    @Test fun `nothing in the body destroys`() {
        val body = bodyOf("moveToAccount")
        // The original leaves A through `delete` — the bin, with its own Undo — and through
        // nothing else. A destroy here would be the one irreversible step of a gesture whose whole
        // design is that every step can be undone or costs at most a duplicate.
        listOf("destroy", "destroyAll", "evictAll").forEach { forbidden ->
            assertEquals(
                "moveToAccount must not name $forbidden. Body was:\n$body",
                emptyList<String>(),
                codeLinesNaming(body, forbidden),
            )
        }
    }

    @Test fun `the append on B is never replayed inside its session`() {
        // Same rule, same reason as appendDraft: a replay of an APPEND whose failure reached us
        // after the server stored the bytes is TWO copies on B — and then A's original is trashed
        // on the strength of one of them. The failure is reported as Failed; nothing retries it.
        val body = DaoQuerySource.mailFunctionBody("ImapMailService", "appendBytes")
        assertEquals(
            "appendBytes must be exactly one APPEND under retryOnFailure = false. Body was:\n$body",
            listOf(
                "{",
                "return withSession(credentials, retryOnFailure = false) { it.append(mailbox, message, flags, internalDate) }",
                "}",
            ),
            codeLines(body),
        )
    }

    @Test fun `the copy is found back by ONE header search, and only when it is the only match`() {
        val body = DaoQuerySource.mailFunctionBody("ImapMailService", "findByMessageId")
        assertEquals(
            "findByMessageId must answer the uid only when the Message-ID names exactly one message " +
                "of the folder: two matches are two copies, and trashing A on that would keep the " +
                "wrong one as often as the right one. Body was:\n$body",
            listOf(
                "{",
                """return onMailbox(credentials, mailbox) { session, _ -> session.uidSearchHeader("Message-ID", "<${'$'}messageId>").singleOrNull() }""",
                "}",
            ),
            codeLines(body),
        )
    }

    /**
     * The signature, whole and in order — what the tick froze is the LAST parameter and it DEFAULTS
     */
    @Test fun `the stamp of the tick is the last parameter, and defaults to nothing frozen`() {
        val signature = signatureOf("moveToAccount")
        assertEquals(
            "moveToAccount must take the numbering the row was ticked under, LAST and optional. " +
                "Signature was:\n$signature",
            listOf(
                "suspend fun moveToAccount(",
                "source: AccountCredentials,",
                "email: Email,",
                "target: AccountCredentials,",
                "targetMailboxId: String,",
                "frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,",
                "): CrossAccountMove {",
            ),
            codeLines(signature),
        )
    }

    /**
     * AND IT REACHES EXACTLY TWO STEPS. A closed list, because both mistakes compile and both
     */
    @Test fun `the stamp reaches the read and the trash, and no other step`() {
        val body = bodyOf("moveToAccount")
        assertEquals(
            "the numbering the row was ticked under must be handed to EXACTLY the two steps that " +
                "touch the source account's mail — the read and the bin — and to nothing else. " +
                "Body was:\n$body",
            listOf(
                "override suspend fun readSource(): ByteArray = rawSource(source, email.id, frozen).also { messageId = messageIdOf(from, it) }",
                "override suspend fun trashSource() { delete(source, email.id, frozen) }",
            ),
            codeLinesNaming(body, anyNumbering),
        )
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
