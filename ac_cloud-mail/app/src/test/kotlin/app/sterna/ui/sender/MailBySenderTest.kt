package app.sterna.ui.sender

import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.SenderVolume
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the "Mail by sender" screen makes, taken out of the ViewModel so a JVM test can
 */
class MailBySenderTest {

    private fun volume(email: String, total: Int, unread: Int = 0) =
        SenderVolume(email = email, name = null, total = total, unread = unread, latest = 0)

    /** The account whose screen this is — one identity and one alias. */
    private val own = listOf("alex.rivera@masto.top", "alex@alias.example")

    /**
     * [senderMenuEntries] for an ordinary row — somebody else's address, on this account. The
     * tests that are ABOUT the address call the decision directly, with theirs.
     */
    private fun menu(
        canDelete: Boolean,
        canBlock: Boolean,
        blocked: Boolean,
        working: Boolean,
    ) = senderMenuEntries(canDelete, canBlock, blocked, working, "news@example.com", own)

    @Test fun `the header number is the sum of the lines`() {
        val rows = listOf(volume("a@x", 3), volume("b@x", 2), volume("c@x", 1))
        assertEquals(6, cachedTotal(rows))
    }

    @Test fun `no lines is zero, not an empty-looking number from somewhere else`() {
        assertEquals(0, cachedTotal(emptyList()))
    }

    @Test fun `the header number counts messages, not senders`() {
        // The distinction the screen lives on: three senders, twelve messages.
        assertEquals(12, cachedTotal(listOf(volume("a@x", 7), volume("b@x", 4), volume("c@x", 1))))
    }

    // -- naming the Trash --------------------------------------------------------------------

    @Test fun `a JMAP trash at the root is named by its name`() {
        val trash = Mailbox(id = "m5", name = "Trash", role = "trash")
        val folders = listOf(Mailbox(id = "m1", name = "Inbox", role = "inbox"), trash)
        assertEquals("Trash", trashFilePath(folders))
    }

    @Test fun `a nested trash is named by its whole path`() {
        // Sieve names a subfolder by its path; the leaf alone reaches the wrong folder, or none.
        val root = Mailbox(id = "m1", name = "INBOX", role = "inbox")
        val trash = Mailbox(id = "m9", name = "Trash", role = "trash", parentId = "m1")
        assertEquals("INBOX/Trash", trashFilePath(listOf(root, trash)))
    }

    @Test fun `an account with no trash names nothing`() {
        val folders = listOf(
            Mailbox(id = "m1", name = "Inbox", role = "inbox"),
            Mailbox(id = "m2", name = "Archive", role = "archive"),
        )
        assertNull(trashFilePath(folders))
        assertNull(trashFilePath(emptyList()))
    }

    @Test fun `a trash whose parent is missing names nothing`() {
        // The folder cannot be named with certainty, so nothing is offered — rather than filing
        // future mail somewhere nobody chose.
        val trash = Mailbox(id = "m9", name = "Trash", role = "trash", parentId = "gone")
        assertNull(trashFilePath(listOf(trash)))
    }

    // -- when the delete may be offered at all --------------------------------------------------

    @Test fun `an account with no Trash keeps the delete and still loses the rule`() {
        // The delete used to be withheld here (`canDeleteFrom`, now gone): with no bin, deleteAll
        // failed the whole batch. It resolves or creates one now, exactly as the single-message
        val noTrash = listOf(Mailbox(id = "m1", name = "Inbox", role = "inbox"))
        // A bin the cache cannot name — its parent is missing — which is a perfectly good
        // destination for a move and no path a Sieve script can file into.
        val orphan = listOf(Mailbox(id = "m9", name = "Trash", role = "trash", parentId = "gone"))
        listOf(noTrash, orphan, emptyList()).forEach { folders ->
            assertNull("nothing to name, so no server-side rule", trashFilePath(folders))
            assertFalse(
                "and the block entry stays away, script or no script",
                canBlockSender(FilterRulesState.Loaded(emptyList()), trashFilePath(folders)),
            )
        }
        assertEquals(
            "…while the delete stays on the menu: the batch now makes itself a bin",
            listOf(SenderAction.SEARCH, SenderAction.DELETE),
            menu(canDelete = true, canBlock = false, blocked = false, working = false)
                .map { it.action },
        )
    }

    // -- when the rule may be offered at all ---------------------------------------------------

    @Test fun `the rule is offered on a server that takes it, with a trash to name`() {
        assertTrue(
            "a Sieve-capable account with a nameable Trash is the case the gesture exists for",
            canBlockSender(FilterRulesState.Loaded(emptyList()), trashPath = "Trash"),
        )
    }

    @Test fun `no server support, no entry`() {
        // IMAP, or a JMAP server without the Sieve capability: exactly where the Filters screen
        // shows its "not supported" note. Nothing local is invented to stand in for it.
        assertFalse(
            "the server would refuse the rule; offering it promises something that cannot happen",
            canBlockSender(FilterRulesState.Unsupported, trashPath = "Trash"),
        )
    }

    @Test fun `no trash to name, no entry`() {
        val noTrash = "with no Trash to name there is no target to write into the rule, and " +
            "nothing is invented to stand in for one"
        assertFalse(noTrash, canBlockSender(FilterRulesState.Loaded(emptyList()), trashPath = null))
        assertFalse(noTrash, canBlockSender(FilterRulesState.Unsupported, trashPath = null))
        assertFalse(noTrash, canBlockSender(null, trashPath = null))
    }

    @Test fun `an account running its own Sieve script does not get the entry`() {
        // Saving activates Sterna's script and switches hers off. The filter editor warns about
        // that in red before its Save button and stays the place to do it knowingly; a list row
        // has nowhere to put the warning, so it does not carry the gesture.
        assertFalse(
            "one tap would switch off the account's own Sieve script; the entry must be absent, " +
                "and the Filters screen — which warns in red before saving — stays the way in",
            canBlockSender(
                FilterRulesState.Loaded(emptyList(), foreignActiveScript = true),
                trashPath = "Trash",
            ),
        )
    }

    @Test fun `a read that failed keeps the entry`() {
        // Offline is not "unsupported". Hiding the entry here makes an unreachable server
        // indistinguishable from an IMAP account — no word, no retry, and the difference is
        // never explained. Tapping it reads again and reports the failure honestly.
        assertTrue(
            "a read that failed is not an unsupported account: hiding the entry makes an " +
                "unreachable server look like IMAP, with no word and no retry",
            canBlockSender(null, trashPath = "Trash"),
        )
    }

    // -- what an Undo has to move back ------------------------------------------------------------

    @Test fun `only the ids the server confirmed are moved back`() {
        val targets = restoreTargets(
            succeeded = setOf("a", "b"),
            sources = mapOf("a" to "inbox", "b" to "archive", "c" to "inbox"),
            dest = "trash",
        )
        assertEquals(listOf("a", "b"), targets.map { it.emailId })
        assertEquals(listOf("inbox", "archive"), targets.map { it.sourceMailboxId })
        assertEquals(listOf("trash", "trash"), targets.map { it.destMailboxId })
    }

    @Test fun `a message that was already in the destination is not moved back`() {
        // Its "restore" would be a move to where it already sits: the server accepts it, the undo
        // reports success, and the mail stays in the Trash. Nothing to undo, so nothing offered.
        val targets = restoreTargets(
            succeeded = setOf("a", "b"),
            sources = mapOf("a" to "trash", "b" to "inbox"),
            dest = "trash",
        )
        assertEquals(listOf("b"), targets.map { it.emailId })
    }

    @Test fun `a message whose source folder is unknown is not moved back`() {
        val targets = restoreTargets(
            succeeded = setOf("a"),
            sources = mapOf("a" to null),
            dest = "trash",
        )
        assertEquals(emptyList<Any>(), targets)
    }

    // -- which body the screen draws ----------------------------------------------------------------

    @Test fun `while it counts, the screen says it is counting`() {
        // The screen exists for a big cache, so the count is the slow part. Drawing the rows body
        // over no rows leaves a blank page under a title for the whole scan — no name, no
        // sentence, no spinner — and drawing the empty body claims the phone holds nothing from
        // anyone, which is a count nobody has made yet.
        assertEquals(SenderScreenBody.LOADING, screenBody(MailBySenderUiState(loading = true)))
    }

    @Test fun `a count that could not be read is not the same as a count of nothing`() {
        // The read can throw — a corrupt database, a cancelled disk read. Falling through to the
        // empty body would print "no mail stored on this phone", which is a COUNT, and no count
        // was made. The error body says what happened and offers the read again.
        assertEquals(
            SenderScreenBody.FAILED,
            screenBody(MailBySenderUiState(loading = false, loadError = "disk I/O error")),
        )
    }

    @Test fun `a count that landed on nothing says so`() {
        assertEquals(
            SenderScreenBody.EMPTY,
            screenBody(MailBySenderUiState(loading = false, rows = emptyList())),
        )
    }

    @Test fun `rows are drawn once they are counted`() {
        assertEquals(
            SenderScreenBody.ROWS,
            screenBody(MailBySenderUiState(loading = false, rows = listOf(volume("a@x", 2)))),
        )
    }

    @Test fun `no account beats every other body`() {
        // Including while loading: there is nothing to count and nothing to wait for.
        assertEquals(
            SenderScreenBody.NO_ACCOUNT,
            screenBody(MailBySenderUiState(loading = true, noAccount = true)),
        )
    }

    // -- what a row's menu holds --------------------------------------------------------------------

    @Test fun `a batch in flight greys the two writing entries out, it does not remove them`() {
        // The defect the greying replaces: with both entries taken away, every tap on the row's
        // ⋮ during a batch opens an EMPTY 280 dp menu, with no word anywhere on the screen about
        // why. A greyed entry says "not now" and keeps the gesture where the finger left it.
        val entries = menu(canDelete = true, canBlock = true, blocked = false, working = true)
        assertEquals(
            "all three entries must still be there while a batch is on its way",
            listOf(SenderAction.SEARCH, SenderAction.BLOCK, SenderAction.DELETE),
            entries.map { it.action },
        )
        assertEquals(
            "…the two that WRITE may not be tappable — the second gesture returns at the " +
                "ViewModel's guard and would do nothing at all, silently — while looking at the " +
                "messages a batch is sweeping stays available, because it changes nothing",
            listOf(true, false, false),
            entries.map { it.enabled },
        )
    }

    @Test fun `what the account cannot do at all is absent, not greyed`() {
        // The other half of the distinction, and the reason both cases cannot share a rendering:
        // canDelete/canBlock false mean "this account will never be able to do this" (no Trash,
        // IMAP, no Sieve, another script running). There is nothing to wait for, so there is
        // nothing to grey out — and an entry that greys out forever reads as a bug.
        assertEquals(
            "the search needs nothing of the account and never goes away",
            listOf(SenderAction.SEARCH),
            menu(canDelete = false, canBlock = false, blocked = false, working = false)
                .map { it.action },
        )
        assertEquals(
            listOf(SenderAction.SEARCH, SenderAction.BLOCK),
            menu(canDelete = false, canBlock = true, blocked = false, working = false)
                .map { it.action },
        )
        assertEquals(
            listOf(SenderAction.SEARCH, SenderAction.DELETE),
            menu(canDelete = true, canBlock = false, blocked = false, working = false)
                .map { it.action },
        )
    }

    // -- R6: the two addresses this screen may not rule on, whatever the account can do ----------

    @Test fun `the row that IS the account does not offer to file the account's own mail away`() {
        // Believed unreachable from this screen and it is not (banc-1.4.8.md § 5.3): the counting
        // query drops Sent, Drafts, Trash and Junk, so a message from oneself filed in an
        assertEquals(
            "the account's own row keeps the two gestures that are about mail already here, and " +
                "loses the one that is about mail to come",
            listOf(SenderAction.SEARCH, SenderAction.DELETE),
            senderMenuEntries(
                canDelete = true,
                canBlock = true,
                blocked = false,
                working = false,
                address = "alex.rivera@masto.top",
                ownAddresses = own,
            ).map { it.action },
        )
        assertEquals(
            "an alias of the account is just as much oneself",
            listOf(SenderAction.SEARCH, SenderAction.DELETE),
            senderMenuEntries(true, true, false, false, "ALEX@alias.example", own).map { it.action },
        )
    }

    @Test fun `a row with no address at all cannot be ruled on either`() {
        // The counting query already drops an empty fromEmail, so this is the belt to that
        // brace: `FROM :is ""` is a rule about nobody that would sit in the script for ever.
        assertEquals(
            listOf(SenderAction.SEARCH, SenderAction.DELETE),
            senderMenuEntries(true, true, false, false, "", own).map { it.action },
        )
    }

    @Test fun `somebody else's row still gets the rule, on the same account`() {
        // The witness that the refusal above is about the ADDRESS and not about the account: same
        // arguments, same identities, another sender.
        assertEquals(
            listOf(SenderAction.SEARCH, SenderAction.BLOCK, SenderAction.DELETE),
            senderMenuEntries(true, true, false, false, "news@example.com", own).map { it.action },
        )
    }

    @Test fun `an account that can do both, with nothing in flight, gets three tappable entries`() {
        val entries = menu(canDelete = true, canBlock = true, blocked = false, working = false)
        assertEquals(
            listOf(
                app.sterna.R.string.sender_volume_search,
                app.sterna.R.string.sender_volume_block,
                app.sterna.R.string.sender_volume_delete,
            ),
            entries.map { it.labelRes },
        )
        assertEquals(listOf(true, true, true), entries.map { it.enabled })
    }

    // -- R1: the order of the row menu, which is a defect and not a layout -----------------------

    @Test fun `the rule comes before the delete, and the delete is last`() {
        // The aggregate excludes the Trash. Delete first and the sender's total falls to zero,
        // its ROW DISAPPEARS, and the "never again" gesture — which lives on that row — becomes
        val entries = menu(canDelete = true, canBlock = true, blocked = false, working = false)
        assertEquals(
            "the rule must be offered BEFORE the delete that can take its row away, and the " +
                "destructive entry must sit last",
            listOf(SenderAction.SEARCH, SenderAction.BLOCK, SenderAction.DELETE),
            entries.map { it.action },
        )
        assertTrue(
            "…and that holds however the row is: a rule already there, a batch in flight",
            listOf(true to true, true to false, false to true, false to false).all { (blocked, working) ->
                menu(canDelete = true, canBlock = true, blocked = blocked, working = working)
                    .map { it.action } ==
                    listOf(SenderAction.SEARCH, SenderAction.BLOCK, SenderAction.DELETE)
            },
        )
    }

    // -- R2: something to look at, before anything is destroyed ---------------------------------

    @Test fun `every row offers to look, first`() {
        // The most visible gap in the walk-through: one confirms a NUMBER and never a content.
        // The entry needs nothing of the account — no Trash, no Sieve, no round trip — so it is
        listOf(true, false).forEach { canDelete ->
            listOf(true, false).forEach { canBlock ->
                val entries = menu(canDelete, canBlock, blocked = false, working = false)
                assertEquals(
                    "canDelete=$canDelete canBlock=$canBlock must still open on the search",
                    SenderAction.SEARCH,
                    entries.first().action,
                )
                assertEquals(
                    app.sterna.R.string.sender_volume_search,
                    entries.first().labelRes,
                )
                assertTrue("and it is always tappable", entries.first().enabled)
            }
        }
    }

    // -- R3: the number the deletion announces ----------------------------------------------------

    @Test fun `the deletion announces what the server confirmed it moved`() {
        // Not the batch that was sent (a partial failure is reported on its own, and counting
        // the whole batch would announce messages that never moved), and not the Undo's targets
        // either: a message already in the Trash is not moved back, so it is not a target — and
        // it WAS deleted. Two ids succeed here and only one is restorable.
        val result = MailRepository.BulkResult(succeeded = setOf("a", "b"), failed = setOf("c"))
        assertEquals(2, deletedCount(result))
        val targets = restoreTargets(
            succeeded = result.succeeded,
            sources = mapOf("a" to "trash", "b" to "inbox"),
            dest = "trash",
        )
        assertEquals(
            "the snackbar's number is the delete's, not the undo's — they differ exactly here",
            1,
            targets.size,
        )
    }

    @Test fun `nothing confirmed announces nothing`() {
        assertEquals(0, deletedCount(MailRepository.BulkResult(emptySet(), setOf("a", "b"))))
    }

    // -- what a delete that half worked is allowed to say -------------------------------------------

    /**
     * The three cases as `confirmDelete` builds them: the batch it handed over is `ids`, and the
     */
    private fun said(ids: List<String>, result: MailRepository.BulkResult) =
        deleteMessageRes(ids.size, result.failed.size)

    @Test fun `a delete that went through says nothing about failing`() {
        val ids = listOf("m1", "m2", "m3")
        assertNull(
            "the snackbar already counts what went; there is no second thing to say",
            said(ids, MailRepository.BulkResult(ids.toSet(), emptySet())),
        )
    }

    @Test fun `a delete that half worked says SOME, not the total failure`() {
        // The defect, in one assertion: the screen said "Couldn't complete the action" for one
        // rejected id out of three and then, in the next breath, "2 messages deleted". Two
        // sentences about one gesture, contradicting each other.
        val ids = listOf("m1", "m2", "m3")
        assertEquals(
            app.sterna.R.string.status_action_partly_failed,
            said(ids, MailRepository.BulkResult(setOf("m1", "m2"), setOf("m3"))),
        )
        assertEquals(
            "…and the other way round too: one that went, two that did not, is still partial",
            app.sterna.R.string.status_action_partly_failed,
            said(ids, MailRepository.BulkResult(setOf("m1"), setOf("m2", "m3"))),
        )
    }

    @Test fun `a delete where nothing moved is the failure it always was`() {
        val ids = listOf("m1", "m2", "m3")
        assertEquals(
            app.sterna.R.string.status_action_failed,
            said(ids, MailRepository.BulkResult(emptySet(), ids.toSet())),
        )
        assertEquals(
            "the result confirmDelete fabricates when deleteAll THROWS is exactly that one",
            app.sterna.R.string.status_action_failed,
            said(listOf("m1"), MailRepository.BulkResult(emptySet(), setOf("m1"))),
        )
    }

    @Test fun `an empty batch has nothing to report`() {
        assertNull(said(emptyList(), MailRepository.BulkResult(emptySet(), emptySet())))
    }

    // -- R4: saying why the rule gesture is missing -------------------------------------------------

    @Test fun `only the foreign script gets a note, and it names one`() {
        // The one "no" that is invisible AND explicable. The other two are the Filters screen's
        // own silences: an IMAP account gets a "not supported" note over there and nothing here,
        // and an account with no nameable Trash is offered nothing rather than told about a
        // folder it does not have.
        assertEquals(
            app.sterna.R.string.sender_volume_foreign_script,
            blockNoteRes(BlockAvailability.FOREIGN_SCRIPT),
        )
        assertNull("an account that CAN do it has nothing to explain", blockNoteRes(BlockAvailability.OFFERED))
        assertNull(blockNoteRes(BlockAvailability.UNSUPPORTED))
        assertNull(blockNoteRes(BlockAvailability.NO_TRASH))
    }

    @Test fun `each no is told apart from the others`() {
        // The screen used to flatten all three into canBlock = false, which is why none of them
        // could be explained. Turning the holiday responder on activates a `vacation` script and
        // switches Sterna's off — measured on Stalwart — so FOREIGN_SCRIPT is the common case,
        // not the exotic one.
        assertEquals(
            BlockAvailability.FOREIGN_SCRIPT,
            blockAvailability(FilterRulesState.Loaded(emptyList(), foreignActiveScript = true), "Trash"),
        )
        assertEquals(
            BlockAvailability.UNSUPPORTED,
            blockAvailability(FilterRulesState.Unsupported, "Trash"),
        )
        assertEquals(
            "no Trash to name answers first: there is nothing to write into a rule whatever the " +
                "script says",
            BlockAvailability.NO_TRASH,
            blockAvailability(FilterRulesState.Loaded(emptyList(), foreignActiveScript = true), null),
        )
        assertEquals(
            BlockAvailability.OFFERED,
            blockAvailability(FilterRulesState.Loaded(emptyList()), "Trash"),
        )
        assertEquals(
            "a read that FAILED is not a foreign script and not an unsupported server — the " +
                "entry stays, and no note claims a cause nobody established",
            BlockAvailability.OFFERED,
            blockAvailability(null, "Trash"),
        )
        assertNull(blockNoteRes(blockAvailability(null, "Trash")))
    }

    @Test fun `a sender the script already handles keeps its entry, greyed, and says why`() {
        val entries = menu(canDelete = true, canBlock = true, blocked = true, working = false)
        val block = entries.single { it.action == SenderAction.BLOCK }
        assertEquals(app.sterna.R.string.sender_volume_block_done, block.labelRes)
        assertFalse("adding a second identical rule would file the mail twice", block.enabled)
        assertTrue(
            "the delete is untouched by the rule already existing",
            entries.single { it.action == SenderAction.DELETE }.enabled,
        )
    }

    // -- the state a confirmed delete starts from -----------------------------------------------------

    @Test fun `confirming the delete takes the dialog away as it starts the batch`() {
        // Both halves in one assertion because the missing half is invisible in review: a confirm
        // that raises `working` but leaves `pending` set keeps the dialog up over a batch already
        // on its way, and its button then returns at the `working` guard — a second tap that does
        // nothing and says nothing, which is the defect this screen was audited for.
        val started = deleteStarted(
            MailBySenderUiState(pending = PendingDelete(volume("a@x", 3), listOf("m1", "m2"))),
        )
        assertNull("the dialog must be gone the moment the batch leaves", started.pending)
        assertTrue("and the batch must be marked in flight", started.working)
    }

    // -- the number the confirmation announces -----------------------------------------------------

    @Test fun `the dialog counts the list it will delete, not the row it came from`() {
        // The row's total was read when the screen loaded; the ids are read when the dialog
        // opens. A message arriving in between makes them differ, and the dialog is the one that
        // is right — so PendingDelete carries the ids and NOT the row's total, and a dialog that
        // cannot reach the stale number cannot print it.
        val row = volume("a@x", total = 3)
        val pending = PendingDelete(row, listOf("m1", "m2", "m3", "m4"))
        assertEquals(4, pending.ids.size)
        assertEquals("a@x", pending.sender.email)
    }

    // -- what the two refusals SAY ------------------------------------------------------------

    /**
     * The two rules above pin WHICH string each refusal shows. Neither of them has ever looked
     */
    @Test fun `both refusals say only what is true in all three causes of the fold`() {
        val strings = englishStrings()
        assertEquals(
            "the by-sender screen's footnote is shown for FOREIGN_SCRIPT, which is " +
                "(foreignScript != null) || unreadable — a third-party script, our own " +
                "unreadable one, " +
                "or her own vacation script. It keeps its pointer to Settings → Filters, where " +
                "the state is actually read and repaired, and claims nothing about a cause",
            "Cloud Mail isn't managing this account's filter script at the moment, so no filter " +
                "rule can be added from here. %1\$s → %2\$s is where this account's rules are edited.",
            strings["sender_volume_foreign_script"],
        )
        assertEquals(
            "the greyed entry in the reader's menu — a menu line, so short, and the same claim: " +
                "not managing, therefore not writable. No cause named",
            "Cloud Mail isn't managing this filter script",
            strings["sender_volume_block_foreign"],
        )
    }

    /**
     * `values/strings.xml`, name → text as the app renders it (`\'` is XML escaping, not part of
     */
    private fun englishStrings(): Map<String, String> {
        val res = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "app/src/main/res/values/strings.xml") }
            .firstOrNull { it.isFile }
            ?: error(
                "cannot locate app/src/main/res/values/strings.xml from " +
                    "${java.io.File("").absolutePath} — this rule reads the resources as text and " +
                    "needs a working directory inside the checkout",
            )
        val strings = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(res.readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }
        listOf("sender_volume_foreign_script", "sender_volume_block_foreign").forEach {
            check(it in strings) { "no <string name=\"$it\"> in ${res.path} — this rule would assert over nothing" }
        }
        return strings
    }
}
