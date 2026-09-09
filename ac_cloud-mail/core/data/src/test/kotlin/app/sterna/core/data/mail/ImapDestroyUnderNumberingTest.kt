package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IMAP twin of [DestroyableIdsTest] (Codeberg #99): given the numbering a destroy was
 */
class ImapDestroyUnderNumberingTest {

    private fun id(mailbox: String, uid: Long) = ImapMailService.emailId(ACCOUNT, mailbox, uid)

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped. Whole lines: the assertions
     *  compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    // ---- the decision, executed --------------------------------------------------------------

    @Test fun `a destroy with no numbering to oppose destroys nothing`() {
        // The held-back destroy of a selection, enqueued by a version predating the key, or for a
        // folder whose numbering was never observed. Nothing can be shown to still mean anything.
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = null),
        )
    }

    @Test fun `a numbering the server never announced destroys nothing`() {
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = 0L),
        )
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = -1L),
        )
    }

    /** The inverse witness, without which "always empty" would satisfy every case above — and
     *  refusing every destroy is a total regression, not a guard. */
    @Test fun `a destroy carrying its numbering keeps its whole list, in its own order`() {
        assertEquals(
            listOf("a", "b"),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = 42L),
        )
        assertEquals(
            listOf("b", "a"),
            UidValidity.destroyableUnderNumbering(listOf("b", "a"), expected = 42L),
        )
    }

    // ---- the routing, executed ---------------------------------------------------------------

    @Test fun `with nothing to oppose, every id is refused and no folder is touched`() {
        val ids = listOf(id("INBOX", 1L), id("Trash", 2L))

        val plan = UidValidity.imapDestroyPlan(ids, expected = null)

        // Empty byFolder is what makes it safe: the destroy loop has nothing to iterate, so no
        // UID EXPUNGE is ever issued and nothing can land in `succeeded`.
        assertEquals(emptyMap<String, List<String>>(), plan.byFolder)
        assertEquals(ids, plan.refused)
    }

    @Test fun `an unverifiable numbering refuses the wave just as a missing one does`() {
        val ids = listOf(id("INBOX", 1L))

        assertEquals(emptyMap<String, List<String>>(), UidValidity.imapDestroyPlan(ids, 0L).byFolder)
        assertEquals(ids, UidValidity.imapDestroyPlan(ids, 0L).refused)
    }

    @Test fun `under its own numbering the wave is grouped by the folder its ids name`() {
        val ids = listOf(id("INBOX", 1L), id("Trash", 2L), id("INBOX", 3L))

        val plan = UidValidity.imapDestroyPlan(ids, expected = 42L)

        assertEquals(
            mapOf("INBOX" to listOf(ids[0], ids[2]), "Trash" to listOf(ids[1])),
            plan.byFolder,
        )
        assertEquals(emptyList<String>(), plan.refused)
    }

    @Test fun `an id naming no folder is refused, never destroyed`() {
        // A JMAP-shaped id in an IMAP account's wave: unparsable, so it names no UID either.
        val ids = listOf(id("INBOX", 1L), "Mabcdef")

        val plan = UidValidity.imapDestroyPlan(ids, expected = 42L)

        assertEquals(mapOf("INBOX" to listOf(ids[0])), plan.byFolder)
        assertEquals(listOf("Mabcdef"), plan.refused)
    }

    @Test fun `a folder path containing colons survives the grouping`() {
        // IMAP paths can hold ':' — the id parser keeps them, and so must the plan.
        val ids = listOf(id("a:b", 7L))

        assertEquals(mapOf("a:b" to ids), UidValidity.imapDestroyPlan(ids, expected = 42L).byFolder)
    }

    @Test fun `the plan is a partition - nothing destroyed twice, nothing invented, nothing dropped`() {
        val ids = listOf(id("INBOX", 1L), "Mabcdef", id("Trash", 2L))

        listOf(null, 0L, -1L, 1L, 42L, Long.MAX_VALUE).forEach { expected ->
            val plan = UidValidity.imapDestroyPlan(ids, expected)
            val destroyed = plan.byFolder.values.flatten()
            assertEquals("$expected: every id is accounted for, once", ids.sorted(), (destroyed + plan.refused).sorted())
            assertTrue(
                "$expected: an id may not be both destroyed and refused",
                destroyed.none { it in plan.refused },
            )
            assertTrue("$expected: the plan may not invent ids", destroyed.all { it in ids })
        }
    }

    // ---- where the decision is plugged in (source text: MailRepository needs Room) ------------

    @Test fun `the IMAP branch of destroyAll routes through the decision and fails what it refuses`() {
        val body = bodyOf("destroyAll")
        assertEquals(
            "the IMAP destroy must ask the decision what its numbering licenses — a predicate " +
                "written inline is a decision no test can execute",
            listOf("val plan = UidValidity.imapDestroyPlan(emailIds, expectedUidValidity)"),
            codeLinesNaming(body, "imapDestroyPlan("),
        )
        assertEquals(
            "what the decision refused must land in `failed` — never silently counted a success, " +
                "and `failed` is what makes the worker re-query so the survivors come back",
            listOf("failed += plan.refused"),
            codeLinesNaming(body, "plan.refused"),
        )
        assertEquals(
            "and only what it licensed may reach the expunge",
            listOf("plan.byFolder.forEach { (source, ids) ->"),
            codeLinesNaming(body, "plan.byFolder"),
        )
        assertEquals(
            "the frozen numbering must travel on to the SELECT, which is what actually refuses",
            listOf("imapDestroyGroup(credentials, source, ids, succeeded, failed, expectedUidValidity)"),
            codeLinesNaming(body, "imapDestroyGroup("),
        )
    }

    /**
     * One link further than the test above, and the link that actually destroys: the argument has
     */
    @Test fun `the frozen numbering reaches the wire, not merely the branch that routes`() {
        assertEquals(
            "the expunge must SELECT under the numbering the caller froze — the argument is what " +
                "makes it refusable, and its absence is silent, legal Kotlin",
            listOf("imap.deleteBatch(credentials, source, uidToId.keys.toList(), expectedUidValidity)"),
            codeLinesNaming(bodyOf("imapDestroyGroup"), "imap.deleteBatch("),
        )
    }

    /**
     * The OTHER destroy path, and the one `destroyAll` never routed: discarding a draft, or
     */
    @Test fun `discarding a draft opposes the numbering frozen when its id was read`() {
        val body = bodyOf("destroyDraft")
        assertEquals(
            "the decision the held-back destroy runs must be handed the FROZEN parameter, never a " +
                "value this path went and looked up: nothing to oppose licenses nothing, and the " +
                "draft simply stays",
            listOf("val destroyable = UidValidity.destroyableUnderNumbering(listOf(emailId), frozenUidValidity)"),
            codeLinesNaming(body, "destroyableUnderNumbering("),
        )
        assertEquals(
            "only what the decision licensed may reach the expunge, and the same frozen numbering " +
                "must travel with it: the argument is what `deleteBatch` opposes to the number the " +
                "server states. Left out, the destroy arrives carrying nothing and the guard below " +
                "has only the fallback to compare with itself — which is the defect entire",
            listOf(
                "imapDestroyGroup(credentials, folder, destroyable, mutableSetOf(), mutableSetOf(), frozenUidValidity)",
            ),
            codeLinesNaming(body, "imapDestroyGroup("),
        )
    }

    /**
     * The freeze must name the folder the destroy will SELECT. The accessor the composer calls
     */
    @Test fun `the numbering is frozen for the very folder the destroy will select`() {
        val resolution = listOf(
            "val folder = emailDao.mailboxOf(credentials.id, emailId)",
            "?: mailboxDao.idForRole(credentials.id, \"drafts\")",
        )
        listOf("destroyDraft", "recordedUidValidityForDraft").forEach { function ->
            val body = bodyOf(function)
            assertEquals(
                "$function must resolve the draft's folder account-scoped (#31) and identically on " +
                    "both sides of the freeze",
                resolution,
                codeLinesNaming(body, "mailboxOf(") + codeLinesNaming(body, "idForRole("),
            )
        }
        assertEquals(
            "and the accessor must answer with the numbering recorded for THAT folder — the " +
                "repository's own protocol-guarded read, which is null on JMAP",
            listOf("return recordedUidValidity(credentials, folder)"),
            codeLinesNaming(bodyOf("recordedUidValidityForDraft"), "recordedUidValidity("),
        )
    }

    /**
     * And it must refuse WITHOUT A WORD ON SCREEN.
     */
    @Test fun `a draft the server will not confirm the numbering of is refused in silence`() {
        val body = bodyOf("destroyDraft")
        assertEquals(
            "the draft path must swallow the two numbering refusals AND NOTHING ELSE, each caught " +
                "BY TYPE. `ImapNumberingUnconfirmed` is the server stating no UIDVALIDITY at all; " +
                "`ImapUidValidityChanged` is the one the frozen number now makes reachable — " +
                "`onMailbox` hands `expectedUidValidity ?: recorded` to the SELECT, and a folder " +
                "renumbered since the composer read the id refuses THERE. Uncaught, it travels the " +
                "#69 path up to `ComposeViewModel.submit`, which puts `t.message` verbatim in the " +
                "\"could not save\" banner: English protocol jargon in all nine locales, at the " +
                "exact moment the guard saved the user's mail. A `runCatching` or a " +
                "`catch (t: Throwable)` here would instead hide a transport failure and close the " +
                "composer over a draft that comes back on the next sync. Body was:\n$body",
            listOf(
                "} catch (unconfirmed: ImapNumberingUnconfirmed) {",
                "} catch (renumbered: ImapUidValidityChanged) {",
            ),
            codeLinesNaming(body, "catch"),
        )
        assertEquals(
            "and the ONLY runCatching allowed on this path is the best-effort list refresh of the " +
                "renumbering catch. Wrapping the destroy itself in one — the shape that reads as " +
                "harmless — hides a transport failure just as `catch (t: Throwable)` would, and " +
                "closes the composer over a draft that comes back on the next sync. Body was:\n$body",
            listOf("runCatching { refresh(credentials, folder) }"),
            codeLinesNaming(body, "runCatching"),
        )
        assertEquals(
            "and every OTHER mention in the repository is a RAISE, never a second catch: on the " +
                "trash purge and on a selection destroy the same refusal must keep travelling up " +
                "to `MessageDestroyWorker`, which bounds its own retries (swallowed there, a purge " +
                "the user confirmed would report success having destroyed nothing), and the two " +
                "raises of the move between accounts (#189) must reach `crossAccountMove`, which " +
                "turns them into a `Failed` — caught here, the copy would go to account B and the " +
                "original to A's bin under a numbering nothing confirmed.",
            listOf(
                "NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mailboxId, null)",
                "NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mb, null)",
                "} catch (unconfirmed: ImapNumberingUnconfirmed) {",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "ImapNumberingUnconfirmed"),
        )
    }

    /**
     * The numbering ARRIVES on this path; it is never read on it. A local read — including one
     */
    @Test fun `the destroy path never looks the numbering up for itself`() {
        listOf("destroyAll", "imapDestroyGroup", "destroyDraft", "discardDraft", "finishDraftSave")
            .forEach { function ->
                assertEquals(
                    "$function must take the frozen numbering as a parameter and read nothing: what " +
                        "it could read is the numbering of the renumbering it must refuse (#99)",
                    emptyList<String>(),
                    codeLinesNaming(bodyOf(function), "recordedUidValidity"),
                )
            }
    }

    /**
     * And every one of them must be written as a BLOCK, because a single-expression body is
     */
    @Test fun `the draft destroy path is written in blocks, or no rule can see inside it`() {
        listOf("destroyDraft", "discardDraft", "finishDraftSave").forEach { function ->
            assertTrue(
                "$function must have a BLOCK body: an expression body silently drops it out of " +
                    "every source rule in this file, starting with the one forbidding it to read a " +
                    "numbering for itself",
                runCatching { bodyOf(function) }.isSuccess,
            )
        }
    }

    /**
     * The freezing accessor, everywhere it is CALLED in the repository — pinned as a list rather
     */
    @Test fun `the freezing accessor is declared for the composer and called at no execution point`() {
        assertEquals(
            "`recordedUidValidityForDraft` is the READ-TIME accessor and the repository must not " +
                "call it AT ALL: every path that destroys a draft is handed the frozen number as a " +
                "parameter — the composer's, or the outbox row's. A call restored here is a number " +
                "read at execution, i.e. the defect, whichever function it is written in",
            listOf(
                // The declaration itself, pinned whole: the signature is part of the contract.
                "suspend fun recordedUidValidityForDraft(credentials: AccountCredentials, emailId: String): Long? {",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "recordedUidValidityForDraft("),
        )
    }

    /**
     * The two hand-overs of `uploadDraft` (`saveDraft`'s upload half since #95) — the IMAP branch
     */
    @Test fun `re-saving hands on the numbering the composer froze, on both protocols`() {
        val body = bodyOf("uploadDraft")
        assertEquals(
            "both branches of uploadDraft must hand `finishDraftSave` the parameter the composer " +
                "froze — twice, identically: the IMAP branch destroys the replaced original by " +
                "UID EXPUNGE, and the JMAP one carries null all the same so neither branch can " +
                "start reading a numbering for itself. Body was:\n$body",
            listOf("frozenUidValidity = replacesUidValidity,", "frozenUidValidity = replacesUidValidity,"),
            codeLinesNaming(body, "frozenUidValidity"),
        )
    }

    /**
     * The inverse witness for the new accessor, twin of "the accessor really asks IMAP" below.
     */
    @Test fun `the freezing accessor answers a real number for an IMAP account`() {
        val body = bodyOf("recordedUidValidityForDraft")
        assertEquals(
            "the accessor must refuse JMAP (`!=`) and answer the folder's recorded numbering for " +
                "everything else. `==` there reads as a typo and turns the guard into a blanket " +
                "refusal of every IMAP draft destroy, silently. Body was:\n$body",
            listOf(
                "if (credentials.protocol != MailProtocol.IMAP) return null",
                "?: return null",
                "return recordedUidValidity(credentials, folder)",
            ),
            codeLinesNaming(body, "return"),
        )
    }

    /**
     * WYSIWYG on the refusal. `onMailbox` invalidates the folder before raising
     */
    @Test fun `a refused discard leaves the drafts list telling the truth`() {
        val body = bodyOf("destroyDraft")
        assertEquals(
            "the renumbering catch must re-read the folder, best-effort: the refusal is silent, so " +
                "the list is the only thing that can tell the user anything, and it must not keep " +
                "showing a row whose id belongs to a numbering the server has thrown away. " +
                "`runCatching` because a refusal that then fails to refresh is still a refusal — " +
                "letting the reload throw would turn a silent, harmless refusal into the " +
                "\"could not save\" banner it exists to avoid. Body was:\n$body",
            listOf("runCatching { refresh(credentials, folder) }"),
            codeLinesNaming(body, "refresh("),
        )
    }

    /**
     * The inverse witness at system level, and the one the executable tests cannot give: they all
     */
    @Test fun `the accessor really asks IMAP for the folder's numbering`() {
        assertEquals(
            "the numbering must come from the IMAP layer's record for THAT account and THAT folder — " +
                "a body that answers null refuses every IMAP destroy for ever. The two folder reads " +
                "carry a protocol guard; the notification one deliberately does not (it is reached " +
                "only with IMAP preview sources in hand, and JMAP conveys none). ⛔ The list is CLOSED " +
                "and it no longer holds a per-selection destroy: since #99's row-stamping branch, a " +
                "held-back destroy freezes what the ROWS were read under " +
                "(`numberingRowsWereReadUnder`, off `emails.uidValidity`) and never the folder's " +
                "current record, which a background pass realigns the moment it meets a renumbering. " +
                "A new `imap.recordedUidValidity(` on that path is the defect coming back",
            listOf(
                // The notification pre-pass's own read of the same value: it is how a preview pass
                // notices a folder renumbered under it, since `fetchPreview` swallows the refusal
                // (see `MailRepository.recordedNumbering`, and `PreviewConveyanceTest`).
                "imap.recordedUidValidity(accountId, mailboxId)",
                "if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, mailboxId) else null",
                "if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, trashMailboxId)",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "imap.recordedUidValidity("),
        )
    }

    private companion object {
        const val ACCOUNT = "acc1"
    }
}
