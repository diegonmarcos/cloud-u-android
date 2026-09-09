package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, for the reason [DestroyChecksTheFolderWiringTest]
 */
class DeleteWouldDestroyWiringTest {

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

    @Test fun `the whole body is these lines, and nothing else decides`() {
        val body = bodyOf("deleteWouldDestroy")
        assertEquals(
            "⛔ EVERY line of deleteWouldDestroy, in order. Three things at once: the bin is " +
                "resolved the way delete() finds it (role on IMAP, role-then-by-name on JMAP), " +
                "the verdict is [deleteDestroysMessage] — which DeleteDestroysMessageTest runs — " +
                "handed the account's trash first and THIS message's folder second, and NOTHING " +
                "else stands between them. An extra `if (trash == null) return …` here is the " +
                "defect back in full, on a suite that would stay green. Body was:\n$body",
            listOf(
                "{",
                """val trash = if (credentials.protocol == MailProtocol.IMAP) imapRoleFolder(credentials, "trash")""",
                "else trashMailboxId(connect(credentials))",
                "return deleteDestroysMessage(trash, email.mailboxId)",
                "}",
            ),
            codeLines(body),
        )
    }

    @Test fun `answering the question never writes a folder to the user's server`() {
        val body = bodyOf("deleteWouldDestroy")
        // delete() goes on to CREATE the bin where this stops; a probe that created one would
        // write to the user's server to answer a question — and would do it on the swipe path,
        // per message, and from deleteThread, per member of the thread.
        listOf("createTrashFolder(", "createMailbox(", "createFolder(").forEach { creation ->
            assertEquals(
                "deleteWouldDestroy must not call $creation — it reports \"does not destroy\" and " +
                    "leaves the creation to the delete itself. Body was:\n$body",
                emptyList<String>(),
                codeLinesNaming(body, creation),
            )
        }
    }

    @Test fun `an absent Trash no longer answers destroy`() {
        val body = bodyOf("deleteWouldDestroy")
        assertEquals(
            "⛔ `?: return true`, or any other `true` written into this body, is the defect " +
                "itself: on an account whose bin carries no role every swipe would be routed to " +
                "the held-back destroy and the message erased, while delete() would have " +
                "resolved or created a Trash for it. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "return true"),
        )
    }

    @Test fun `accountHasTrash keeps asking the role itself, never through the message rule`() {
        // The two are NOT the same question, and their answers may now differ: a JMAP bin known
        // by name only is a Trash for the message rule and not for this one. That asymmetry is
        // arbitrated — the account probe's conservatism can only leave a row untreated and
        // announced, never destroy one — so it stays exactly as it is.
        val source = DaoQuerySource.mailSource("MailRepository")
        assertEquals(
            "the account-level probe must stay its own role lookup, whole, arguments included — " +
                "and be the ONLY line in the file written that way",
            listOf("""roleMailboxId(credentials, "trash") != null"""),
            codeLinesNaming(source, """roleMailboxId(credentials, "trash")"""),
        )
        assertEquals(
            "and the message rule must be called from ONE place in the whole file: the probe " +
                "above has no message, and giving it a made-up folder is how a null folder starts " +
                "deciding a destroy again",
            listOf("return deleteDestroysMessage(trash, email.mailboxId)"),
            codeLinesNaming(source, "deleteDestroysMessage("),
        )
    }

    @Test fun `the notification banner's cache probe is untouched`() {
        // Out of this fix's way entirely: it answers about the local folder table, for a receiver
        // that cannot afford a socket. Pinned so a tidy-up of the three neighbours cannot fold it
        // into one of them.
        val source = DaoQuerySource.mailSource("MailRepository")
        assertEquals(
            "hasCachedTrash reads the FOLDER CACHE, scoped to the account, and nothing else",
            listOf("""mailboxDao.idForRole(accountId, "trash") != null"""),
            codeLinesNaming(source, "idForRole(accountId,"),
        )
    }
}
