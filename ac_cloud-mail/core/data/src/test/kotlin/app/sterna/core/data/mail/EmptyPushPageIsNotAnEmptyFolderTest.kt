package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.imap.ImapMailboxStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push pass's twin of [EmptyImapWalkIsNotAnEmptyFolderTest]: a page that came back with
 */
class EmptyPushPageIsNotAnEmptyFolderTest {

    private val account = "acc-1"
    private val inbox = "INBOX"

    /**
     * A watched folder that is NOT the inbox: one of the Sieve-filed folders multi-folder push
     * exists for (#16). No `role` — the server names no role for it — and a path of its own.
     */
    private val sieveFolder = "INBOX.Lists.sterna"
    private val sieveFolderName = "sterna"

    private fun message(uid: Long, folder: String = inbox) = EmailEntity(
        id = "imap:$account:$folder:$uid",
        accountId = account,
        mailboxId = folder,
        threadId = null,
        subject = "Subject $uid",
        preview = null,
        receivedAt = "2026-08-13T09:00:00Z",
        fromName = "Alex",
        fromEmail = "alex@example.org",
        seen = false,
        flagged = false,
        hasAttachment = false,
        sortKey = 1_700_000_000_000L + uid,
    )

    /**
     * The folder is a PARAMETER, and the three fields that identify it move together. A fixture
     */
    private fun load(
        messages: List<EmailEntity>,
        folderStatedEmpty: Boolean,
        mailboxId: String = inbox,
        name: String = "Inbox",
        role: String? = "inbox",
    ) = ImapWatchedLoad(
        mailboxId = mailboxId,
        name = name,
        role = role,
        messages = messages,
        previewSources = emptyMap(),
        folderStatedEmpty = folderStatedEmpty,
        // Nothing here is about the numbering: these cases are all about what an empty page may
        // say about a folder. Stated explicitly because the field has no default, on purpose.
        uidValidity = null,
    )

    // -- the decision itself ---------------------------------------------------------------------------

    /** A1 — the first door: the SELECT never stated a count, so nothing here knows anything. */
    @Test fun `an empty page whose SELECT never stated a count is a REFUSAL, not an empty folder`() {
        assertNull(
            "the baseline of a folder nothing was learned about was replaced with an empty one",
            watchedBaselineIds(load(messages = emptyList(), folderStatedEmpty = false)),
        )
    }

    /**
     * A3 — THE SECOND DOOR, and the case that tells the two designs apart. The server stated its
     */
    @Test fun `an empty page from a folder the server said HOLDS messages is a REFUSAL too`() {
        val status = ImapMailboxStatus(exists = 12, uidValidity = 1L, uidNext = 99L, existsObserved = true)
        assertTrue("this case only bites when the count IS stated", status.existsObserved)

        assertNull(
            "the server said this folder holds 12 messages and the page brought none back; that " +
                "page cannot say the folder is empty, and the baseline must survive it",
            watchedBaselineIds(load(messages = emptyList(), folderStatedEmpty = pageVouchesForEmpty(status))),
        )
    }

    /** A4 — the witness: a folder the server SAID holds nothing does clear its baseline. */
    @Test fun `a folder the server SAID holds nothing clears its baseline — the witness`() {
        val status = ImapMailboxStatus(exists = 0, uidValidity = 1L, uidNext = 99L, existsObserved = true)

        assertEquals(
            "an emptied folder must be able to forget what it remembered, or the baseline is " +
                "immortal and mail refiled here later can never be announced",
            emptyList<String>(),
            watchedBaselineIds(load(messages = emptyList(), folderStatedEmpty = pageVouchesForEmpty(status))),
        )
    }

    /** A7 — the ordinary path: the page brought messages back, so it saw the folder. */
    @Test fun `a page that DID bring messages back is remembered as those messages, stated-empty or not`() {
        listOf(false, true).forEach { stated ->
            assertEquals(
                "a non-empty page is the folder's baseline whatever the SELECT line looked like " +
                    "(folderStatedEmpty = $stated)",
                listOf("imap:$account:$inbox:9", "imap:$account:$inbox:8"),
                watchedBaselineIds(load(listOf(message(9L), message(8L)), folderStatedEmpty = stated)),
            )
        }
    }

    // -- the same decision, on a WATCHED folder that is not the inbox ----------------------------------

    /**
     * A1 on a Sieve folder. The decision knows nothing about WHICH folder it is deciding for, and
     */
    @Test fun `a watched folder's empty page whose SELECT stated no count is refused just the same`() {
        assertNull(
            "the refusal became the inbox's privilege; a Sieve folder's baseline was wiped by a " +
                "page that learned nothing about it",
            watchedBaselineIds(
                load(
                    messages = emptyList(),
                    folderStatedEmpty = false,
                    mailboxId = sieveFolder,
                    name = sieveFolderName,
                    role = null,
                ),
            ),
        )
    }

    /** A3 on a Sieve folder: the second door, in the folder the second door is most likely to open. */
    @Test fun `a watched folder the server said HOLDS messages is refused just the same`() {
        val status = ImapMailboxStatus(exists = 12, uidValidity = 1L, uidNext = 99L, existsObserved = true)

        assertNull(
            "the server said this watched folder holds 12 messages and the page brought none " +
                "back; its baseline must survive that page, inbox or not",
            watchedBaselineIds(
                load(
                    messages = emptyList(),
                    folderStatedEmpty = pageVouchesForEmpty(status),
                    mailboxId = sieveFolder,
                    name = sieveFolderName,
                    role = null,
                ),
            ),
        )
    }

    /**
     * A4 on a Sieve folder — the witness travels too, so the hole cannot be closed the other way
     * round (refusing everything that is not the inbox): an emptied watched folder still clears.
     */
    @Test fun `a watched folder the server SAID holds nothing clears its baseline too`() {
        val status = ImapMailboxStatus(exists = 0, uidValidity = 1L, uidNext = 99L, existsObserved = true)

        assertEquals(
            "an emptied watched folder must forget too, or its baseline is immortal and mail " +
                "filed there later is never announced",
            emptyList<String>(),
            watchedBaselineIds(
                load(
                    messages = emptyList(),
                    folderStatedEmpty = pageVouchesForEmpty(status),
                    mailboxId = sieveFolder,
                    name = sieveFolderName,
                    role = null,
                ),
            ),
        )
    }

    /** A7 on a Sieve folder: the ordinary path is the page's ids, under that folder's own keys. */
    @Test fun `a watched folder's non-empty page is remembered as its own ids`() {
        assertEquals(
            "a watched folder's page must be remembered under that folder's keys, whole",
            listOf("imap:$account:$sieveFolder:4", "imap:$account:$sieveFolder:3"),
            watchedBaselineIds(
                load(
                    messages = listOf(message(4L, sieveFolder), message(3L, sieveFolder)),
                    folderStatedEmpty = false,
                    mailboxId = sieveFolder,
                    name = sieveFolderName,
                    role = null,
                ),
            ),
        )
    }

    // -- the provenance, the four cases apart ----------------------------------------------------------

    @Test fun `a stated zero is the only page that vouches for an empty folder`() {
        assertTrue(
            "* 0 EXISTS, parsed: the server stated it, and stated zero",
            pageVouchesForEmpty(ImapMailboxStatus(exists = 0, uidValidity = 1L, uidNext = 1L, existsObserved = true)),
        )
        assertFalse(
            "a stated count ABOVE zero vouches for nothing: the page came back empty for some " +
                "other reason (\\Deleted rows, unreadable FETCHes)",
            pageVouchesForEmpty(ImapMailboxStatus(exists = 12, uidValidity = 1L, uidNext = 1L, existsObserved = true)),
        )
        assertFalse(
            "zero that was never stated is the initial value of the field, not an answer",
            pageVouchesForEmpty(ImapMailboxStatus(exists = 0, uidValidity = 1L, uidNext = 1L, existsObserved = false)),
        )
        assertFalse(
            "and a count that was never stated says nothing whatever its value",
            pageVouchesForEmpty(ImapMailboxStatus(exists = 12, uidValidity = 1L, uidNext = 1L, existsObserved = false)),
        )
    }

    @Test fun `a status built without naming the flag states nothing — the default is UNKNOWN`() {
        // The dangerous default, executed. `exists` reads 0 here because that is what the field is
        // initialised to, and the whole defect is the two zeroes being indistinguishable.
        val neverStated = ImapMailboxStatus(exists = 0, uidValidity = 1L, uidNext = 1L)

        assertFalse("the default must be UNKNOWN, never 'stated'", neverStated.existsObserved)
        assertFalse("so a status nobody filled in cannot vouch for an empty folder", pageVouchesForEmpty(neverStated))
    }

    // -- the wiring, read from the shipped source ------------------------------------------------------

    /**
     * SOURCE LINT — `ImapMailService.loadWatchedFolders` cannot be run on the JVM (a token
     */
    @Test fun `the load fills the provenance from the shared decision, never forges one on the spot`() {
        val body = DaoQuerySource.mailFunctionBody("ImapMailService", "loadWatchedFolders")

        assertEquals(
            "the watched load must take its provenance from pageVouchesForEmpty(status) — the pure " +
                "function the tests above execute. Written out here, the shipped rule and the " +
                "tested one drift by any amount and nothing sees it.",
            listOf("folderStatedEmpty = pageVouchesForEmpty(status),"),
            codeLinesNaming(body, "folderStatedEmpty"),
        )
        assertEquals(
            "and it must be the status of THIS page's own SELECT, taken whole: no second SELECT, " +
                "no STATUS call, nothing new asked of the server in a background push pass",
            listOf("val status = session.select(folder.path)"),
            codeLinesNaming(body, "session.select("),
        )
    }

    /**
     * SOURCE LINT — the same for `MailRepository.refreshAccountFolders`: the IMAP branch must hand
     * the notifier the DECISION's answer, not the page it happens to hold.
     */
    @Test fun `the IMAP refresh hands out the decided baseline, not the page it read`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "refreshAccountFolders")

        assertEquals(
            "the IMAP branch must pass watchedBaselineIds(load); `page.map { it.id }` is the " +
                "defect — an empty page then becomes an empty baseline and the next pass " +
                "re-announces the day",
            listOf("load.mailboxId, load.name, load.role, page, watchedBaselineIds(load),"),
            codeLinesNaming(body, "load.mailboxId"),
        )
    }

    /** The code lines of [body] naming [needle], comments dropped — whole lines, never a search. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }
}
