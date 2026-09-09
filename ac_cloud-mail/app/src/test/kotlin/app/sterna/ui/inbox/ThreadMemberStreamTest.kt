package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chip and its unfold must still agree A MINUTE AFTER the row was opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMemberStreamTest {

    /** The `emails` table, and the three readings the screen takes of it. */
    private class Cache {
        val rows = MutableStateFlow<List<Email>>(emptyList())

        /** Reads that were opened — a subscription per (thread, scope), as Room would give. */
        var opened = 0

        /** `EmailDao.cachedThreadEmails`: one account, these folders, this thread, newest first. */
        fun read(accountId: String, folders: List<String>, threadKey: String): Flow<List<Email>> {
            opened++
            return rows.map { all -> members(all, accountId, folders, threadKey) }
        }

        /**
         * The chip: the same messages, counted rather than listed — the shipped SQL's `c`
         * sub-query for one thread (`conversationSql`, exercised for real in core:data).
         */
        fun chip(scope: ListScope, key: ThreadKey): Int =
            members(rows.value, key.accountId.orEmpty(), scope.folders(key.accountId), key.threadId).size

        /**
         * The message the collapsed row draws: the shipped SQL's `g` sub-query — MAX(sortKey) over
         */
        fun representative(scope: ListScope, key: ThreadKey): Email? =
            members(rows.value, key.accountId.orEmpty(), scope.viewedMailboxIds, key.threadId).firstOrNull()

        private fun members(all: List<Email>, accountId: String, folders: List<String>, threadKey: String) =
            all.filter { it.accountId == accountId && it.mailboxId in folders && (it.threadId ?: it.id) == threadKey }
                .sortedByDescending { it.receivedAt }

        /** Keyed (account, id), like the table: two accounts of one server share message ids. */
        fun put(email: Email) {
            rows.value = rows.value.filterNot { it.id == email.id && it.accountId == email.accountId } + email
        }

        fun moveTo(id: String, mailboxId: String?) {
            rows.value = rows.value.mapNotNull { row ->
                when {
                    row.id != id -> row
                    mailboxId == null -> null
                    else -> row.copy(mailboxId = mailboxId)
                }
            }
        }
    }

    private fun mail(id: String, mailbox: String, receivedAt: String, account: String = "accA") =
        Email(id = id, accountId = account, mailboxId = mailbox, threadId = "T1", receivedAt = receivedAt)

    private val key = ThreadKey("accA", "T1")

    /** A thread the user has answered: two in the Inbox, the reply filed in Sent. */
    private fun Cache.anAnsweredThread() {
        put(mail("in1", "inbox", "2026-07-01T09:00:00Z"))
        put(mail("in2", "inbox", "2026-07-01T10:00:00Z")) // the representative the row draws
        put(mail("se1", "sentbox", "2026-07-01T11:00:00Z"))
    }

    /** The unfold, live: what [InboxViewModel] subscribes the expanded rows to. */
    private fun stream(
        cache: Cache,
        expanded: Flow<Set<ThreadKey>>,
        scope: Flow<ListScope>,
        snoozed: Flow<Set<EmailKey>> = MutableStateFlow(emptySet()),
    ) = ThreadMemberStream.members(
        expanded = expanded,
        scope = scope,
        snoozed = snoozed,
        fallbackAccountId = { "accA" },
        read = cache::read,
    )

    /**
     * The unfolded row as the reader sees it: the representative on the row, then the members
     */
    private fun Cache.screen(scope: ListScope, drawn: Map<ThreadKey, List<Email>>, k: ThreadKey = key): List<String> {
        val rep = representative(scope, k) ?: return emptyList()
        return listOf(rep.id) + ConversationExpansion.membersBelow(drawn[k].orEmpty(), rep.id).map { it.id }
    }

    /**
     * What the screen currently holds, kept up to date by a collection running beside the test —
     */
    private fun TestScope.draw(flow: () -> Flow<Map<ThreadKey, List<Email>>>): MutableStateFlow<Map<ThreadKey, List<Email>>> {
        val out = MutableStateFlow<Map<ThreadKey, List<Email>>>(emptyMap())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow().collect { out.value = it } }
        return out
    }

    // -- the case this fix exists for ------------------------------------------------------

    @Test fun `a Sent folder resolved after the row was opened is listed under it`() = runTest {
        // A fresh install: the folder cache has not synced yet, so the chip and the unfold both
        // start on the smaller, honest conversation — two messages. Then the first folder sync
        // lands and the resolution grows the chip to three.
        val cache = Cache().apply { anAnsweredThread() }
        val expanded = MutableStateFlow(setOf(key))
        val scope = MutableStateFlow(ListScope(viewedMailboxIds = listOf("inbox")))
        val drawn = draw { stream(cache, expanded, scope) }
        advanceUntilIdle()

        assertEquals(2, cache.chip(scope.value, key))
        assertEquals(listOf("in2", "in1"), cache.screen(scope.value, drawn.value))

        // The row is still open when the Sent folder finally resolves.
        val onceSynced = ListScope(listOf("inbox"), listOf("accA" to "sentbox"))
        // The witness: the reading the old code kept — taken when the row opened, and kept.
        val snapshotAtUnfold = drawn.value[key].orEmpty()

        scope.value = onceSynced
        advanceUntilIdle()

        assertEquals(3, cache.chip(onceSynced, key))
        assertEquals(listOf("in2", "se1", "in1"), cache.screen(onceSynced, drawn.value))

        // …and what that snapshot would still be showing: a chip of 3 over an unfold of 2, which is
        // the defect, with nothing on screen to explain the difference.
        assertEquals(2, snapshotAtUnfold.size)
        assertNotEquals(cache.chip(onceSynced, key), snapshotAtUnfold.size)
    }

    @Test fun `a message arriving in a conversation already unfolded joins it`() = runTest {
        // The frequent one: nothing is tapped, the reader is simply looking at the list when mail
        // lands in a thread they had left open.
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }
        advanceUntilIdle()
        assertEquals(3, cache.screen(scope.value, drawn.value).size)

        cache.put(mail("in3", "inbox", "2026-07-01T12:00:00Z"))
        advanceUntilIdle()

        assertEquals(4, cache.chip(scope.value, key))
        // in3 is newer than in2, so it becomes the message the ROW draws, and what goes beneath is
        // the rest of the conversation — in2 included, se1 first, nothing twice.
        assertEquals(listOf("in3", "se1", "in2", "in1"), cache.screen(scope.value, drawn.value))
    }

    /**
     * The defect the live members introduced, and the reason the subtraction had to move: the
     * members became a reading and the representative subtracted from them stayed a memory.
     */
    @Test fun `a message arriving in an unfolded conversation is not drawn twice over a vanished one`() = runTest {
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }
        advanceUntilIdle()
        // What the row was drawing when it was unfolded, and what the old code went on subtracting.
        val recordedAtUnfold = cache.representative(scope.value, key)!!.id
        assertEquals("in2", recordedAtUnfold)

        cache.put(mail("in3", "inbox", "2026-07-01T12:00:00Z"))
        advanceUntilIdle()

        val onScreen = cache.screen(scope.value, drawn.value)
        assertEquals("no message may be drawn twice: $onScreen", onScreen.distinct(), onScreen)
        assertTrue("the displaced representative must still be listed: $onScreen", "in2" in onScreen)
        assertEquals("one row plus its members must come to the chip", cache.chip(scope.value, key), onScreen.size)

        // The witness: the same reading, subtracted with the representative recorded at the tap —
        // in3 drawn on the row AND listed under it, in2 gone, and the count still 4, which is
        // exactly why this survived a chip that had just been made truthful.
        val stale = listOf("in3") +
            ConversationExpansion.membersBelow(drawn.value[key].orEmpty(), recordedAtUnfold).map { it.id }
        assertEquals(listOf("in3", "in3", "se1", "in1"), stale)
        assertEquals(cache.chip(scope.value, key), stale.size)
        assertFalse("the witness must actually lose the old representative", "in2" in stale)
    }

    @Test fun `a representative that leaves the folder hands its row to the next message`() = runTest {
        // Opened from the conversation and archived from the reader: the row must fall back on the
        // newest message left, and list the others once each — not keep subtracting a message the
        // folder no longer holds and show one member too many under a chip that has already
        // dropped it.
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }
        advanceUntilIdle()

        cache.moveTo("in2", "archive")
        advanceUntilIdle()

        assertEquals(2, cache.chip(scope.value, key))
        assertEquals(listOf("in1", "se1"), cache.screen(scope.value, drawn.value))
    }

    @Test fun `a member that leaves the viewed folders leaves the unfolded list`() = runTest {
        // Deleted from another client, or by this one: the conversation is folder-scoped, so the
        // member belongs to Trash's conversation now — and the chip has already stopped counting it.
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }
        advanceUntilIdle()

        cache.moveTo("in1", "trash")
        advanceUntilIdle()

        assertEquals(2, cache.chip(scope.value, key))
        assertEquals(listOf("in2", "se1"), cache.screen(scope.value, drawn.value))
    }

    @Test fun `folding and unfolding again reads the conversation, it does not replay it`() = runTest {
        // The old members map was only ever emptied on a folder or account change, so a row folded
        // and opened again showed whatever was loaded the first time — however old.
        val cache = Cache().apply { anAnsweredThread() }
        val expanded = MutableStateFlow(setOf(key))
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, expanded, scope) }
        advanceUntilIdle()
        assertEquals(3, cache.screen(scope.value, drawn.value).size)

        expanded.value = emptySet()
        advanceUntilIdle()
        assertEquals(emptyMap<ThreadKey, List<Email>>(), drawn.value)

        cache.put(mail("in3", "inbox", "2026-07-01T12:00:00Z"))
        expanded.value = setOf(key)
        advanceUntilIdle()

        assertEquals(4, cache.chip(scope.value, key))
        assertEquals(4, cache.screen(scope.value, drawn.value).size)
    }

    @Test fun `a conversation of another account keeps its own Sent folder in the unified list`() = runTest {
        // #92, on the live path: two accounts, one thread id, one Sent folder each. Each row must
        // be read with ITS account's folders, whichever account is current.
        val cache = Cache()
        cache.put(mail("a1", "inbox", "2026-07-01T09:00:00Z"))
        cache.put(mail("aSent", "sentA", "2026-07-01T10:00:00Z"))
        cache.put(mail("b1", "inbox", "2026-07-01T09:30:00Z", account = "accB"))
        cache.put(mail("bSent", "sentB", "2026-07-01T10:30:00Z", account = "accB"))
        val keyB = ThreadKey("accB", "T1")
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentA", "accB" to "sentB")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key, keyB)), scope) }
        advanceUntilIdle()

        assertEquals(listOf("a1", "aSent"), cache.screen(scope.value, drawn.value))
        assertEquals(listOf("b1", "bSent"), cache.screen(scope.value, drawn.value, keyB))

        // The witness: scoped to A's Sent folder alone, B's row would list nothing — so the two
        // rows are genuinely being read with different folders and not with one pooled set.
        val onlyA = ListScope(listOf("inbox"), listOf("accA" to "sentA"))
        assertEquals(1, cache.chip(onlyA, keyB))
    }

    @Test fun `nothing unfolded reads nothing`() = runTest {
        val cache = Cache().apply { anAnsweredThread() }
        val drawn = draw { stream(cache, MutableStateFlow(emptySet()), MutableStateFlow(ListScope(listOf("inbox")))) }
        advanceUntilIdle()

        assertEquals(emptyMap<ThreadKey, List<Email>>(), drawn.value)
        assertEquals(0, cache.opened)
    }

    @Test fun `the representative is never listed twice under its own row`() = runTest {
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }
        advanceUntilIdle()

        assertEquals(listOf("in2", "se1", "in1"), cache.screen(scope.value, drawn.value))
    }

    @Test fun `a live reading answers the same folders the chip counted, in the same order`() = runTest {
        // Stated as the invariant, over a sequence of writes: whatever happens to the table or the
        // scope, the number on the row and the messages under it are one answer.
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }

        val steps: List<() -> Unit> = listOf(
            { scope.value = ListScope(listOf("inbox"), listOf("accA" to "sentbox")) },
            { cache.put(mail("in3", "inbox", "2026-07-01T12:00:00Z")) },
            { cache.moveTo("in1", "trash") },
            { cache.moveTo("se1", null) },
            { scope.value = ListScope(listOf("inbox")) },
        )
        steps.forEach { step ->
            step()
            advanceUntilIdle()
            val onScreen = cache.screen(scope.value, drawn.value)
            assertEquals(cache.chip(scope.value, key), onScreen.size)
            assertEquals(onScreen.distinct(), onScreen)
        }

        // The witness: the sequence genuinely moved the number around, so the equality above is not
        // one constant compared with itself.
        assertEquals(2, cache.chip(scope.value, key))
    }

    // -- a snooze hides a member, and stops hiding it by itself ------------------------------

    @Test fun `a snoozed member leaves the unfolded row and comes back when the snooze lapses`() = runTest {
        // The chip's SQL excludes snoozed messages, so the list beneath it must too — and, because
        // the snooze is a row in another table, the unfold has to WATCH that table: the members are
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val snoozed = MutableStateFlow(emptySet<EmailKey>())
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope, snoozed) }
        advanceUntilIdle()
        assertEquals(listOf("in2", "se1", "in1"), cache.screen(scope.value, drawn.value))

        snoozed.value = setOf(EmailKey("accA", "in1"))
        advanceUntilIdle()
        assertEquals(listOf("in2", "se1"), cache.screen(scope.value, drawn.value))

        // The due date: the worker deletes the snooze row, and the member is listed again with no
        // write to the message table at all.
        snoozed.value = emptySet()
        advanceUntilIdle()
        assertEquals(listOf("in2", "se1", "in1"), cache.screen(scope.value, drawn.value))
    }

    @Test fun `a snooze hides only its own account's message`() = runTest {
        // Snoozes are keyed per account (issue #31): two accounts of one server share message ids
        // as they share thread ids, and hiding by bare id would blank the sibling's homonym.
        val cache = Cache()
        cache.put(mail("m1", "inbox", "2026-07-01T09:00:00Z"))
        cache.put(mail("m2", "inbox", "2026-07-01T10:00:00Z"))
        cache.put(mail("m1", "inbox", "2026-07-01T09:30:00Z", account = "accB"))
        cache.put(mail("m2", "inbox", "2026-07-01T10:30:00Z", account = "accB"))
        val keyB = ThreadKey("accB", "T1")
        val scope = MutableStateFlow(ListScope(listOf("inbox")))
        val snoozed = MutableStateFlow(setOf(EmailKey("accA", "m1")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key, keyB)), scope, snoozed) }
        advanceUntilIdle()

        assertEquals(listOf("m2"), cache.screen(scope.value, drawn.value))
        assertEquals(listOf("m2", "m1"), cache.screen(scope.value, drawn.value, keyB))
    }

    // -- the mask, and the fact that it comes down ------------------------------------------

    @Test fun `a masked member is out of the reading for the length of the call, and back after`() = runTest {
        val mask = ThreadMemberMask()
        val hidden = EmailKey("acc", "m1")
        val seenDuring = mask.hiding(setOf(hidden)) { mask.keys.toSet() }
        assertEquals(setOf(hidden), seenDuring)
        assertEquals(emptySet<EmailKey>(), mask.keys)
    }

    @Test fun `a mask comes down even when the call it covers throws`() = runTest {
        // The offline swipe, and the offline bulk: the action failed, the message never left, and
        // the reader must see it again.
        val mask = ThreadMemberMask()
        val hidden = EmailKey("acc", "m1")
        runCatching { mask.hiding(setOf(hidden)) { error("offline") } }
        assertEquals(emptySet<EmailKey>(), mask.keys)
    }

    @Test fun `a mask comes down after a call that removed nothing at all`() = runTest {
        // "Snooze" from the selection bar: it writes a date and leaves the message where it is, so
        // the mask was the only thing keeping it off the screen — and it kept it off for good,
        // past the due date the chip had already counted it back in. The mask now ends with the
        // call, and what hides the message afterwards is the snooze, which ends by itself.
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val snoozed = MutableStateFlow(emptySet<EmailKey>())
        val mask = ThreadMemberMask()
        val drawn = draw {
            stream(cache, MutableStateFlow(setOf(key)), scope, snoozed).map { live ->
                live.mapValues { (_, members) ->
                    ThreadMemberStream.reconcile(drawn = emptyList(), live = members, removed = mask.keys)
                }
            }
        }
        advanceUntilIdle()

        val target = EmailKey("accA", "in1")
        mask.hiding(setOf(target)) { snoozed.value = setOf(target) } // the op: a date, nothing else
        advanceUntilIdle()
        assertEquals("snoozed, so out of the row and out of the chip", listOf("in2", "se1"), cache.screen(scope.value, drawn.value))

        snoozed.value = emptySet() // the due date
        advanceUntilIdle()
        assertEquals(listOf("in2", "se1", "in1"), cache.screen(scope.value, drawn.value))
    }

    // -- what a live reading may and may not redraw -----------------------------------------

    @Test fun `a reading keeps the copies already on screen`() {
        // Codeberg #63: the row under the reader's eyes is not rewritten. A cache row carries no
        // recipients until the memo is warm, and adopting a richer copy a beat later flipped a
        // self-authored member's name line from the sender to "To: …".
        val drawn = listOf(Email(id = "m1", accountId = "acc", subject = "on screen"))
        val live = listOf(Email(id = "m1", accountId = "acc", subject = "from the cache"))
        val out = ThreadMemberStream.reconcile(drawn, live, removed = emptySet())
        assertEquals("on screen", out.single().subject)
        assertSame(drawn.single(), out.single())
    }

    @Test fun `a reading does not revert a star applied while the write was in flight`() {
        // The optimistic toggles reach the screen before the server has acknowledged anything, and
        // the cache only after.
        val drawn = listOf(Email(id = "m1", accountId = "acc", keywords = mapOf("\$flagged" to true)))
        val live = listOf(Email(id = "m1", accountId = "acc", keywords = emptyMap()))
        assertEquals(true, ThreadMemberStream.reconcile(drawn, live, removed = emptySet()).single().isFlagged)
    }

    @Test fun `a member the screen never had arrives as the cache has it`() {
        val live = listOf(Email(id = "m2", accountId = "acc", subject = "new"))
        assertEquals("new", ThreadMemberStream.reconcile(emptyList(), live, removed = emptySet()).single().subject)
    }

    @Test fun `a member a swipe just took away is not put back by the next reading`() {
        // Its cache row outlives the gesture until the server acknowledges the move.
        val swiped = Email(id = "m1", accountId = "acc")
        val out = ThreadMemberStream.reconcile(
            drawn = emptyList(),
            live = listOf(swiped, Email(id = "m2", accountId = "acc")),
            removed = setOf(EmailKey("acc", "m1")),
        )
        assertEquals(listOf("m2"), out.map { it.id })
    }

    @Test fun `a tombstone is account-qualified`() {
        // Two accounts of one server share message ids as they share thread ids (#92): swiping one
        // must not blank the other's homonym.
        val out = ThreadMemberStream.reconcile(
            drawn = emptyList(),
            live = listOf(Email(id = "m1", accountId = "accA"), Email(id = "m1", accountId = "accB")),
            removed = setOf(EmailKey("accA", "m1")),
        )
        assertEquals(listOf("accB"), out.map { it.accountId })
    }

    @Test fun `an unchanged conversation reconciles to an equal list, so nothing redraws`() {
        // A StateFlow does not re-emit an equal value: this is what keeps an unfolded row still
        // while mail is arriving elsewhere in the folder.
        val drawn = listOf(Email(id = "m1", accountId = "acc"), Email(id = "m2", accountId = "acc"))
        assertEquals(drawn, ThreadMemberStream.reconcile(drawn, drawn.map { it.copy() }, removed = emptySet()))
    }

    // -- the two repository queries the whole unfold rests on --------------------------------

    /**
     * Neither of the next two cases pins the shipped code — they are WITNESSES, and they are here
     */
    @Test fun `an emptied member query lists nothing under a chip that still counts three`() = runTest {
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw {
            ThreadMemberStream.members(
                expanded = MutableStateFlow(setOf(key)),
                scope = scope,
                snoozed = MutableStateFlow(emptySet()),
                fallbackAccountId = { "accA" },
                // MailRepository.observeThreadEmails, emptied out: same signature, no answer.
                read = { _, _, _ -> flowOf(emptyList()) },
            )
        }
        advanceUntilIdle()

        assertEquals(3, cache.chip(scope.value, key))
        // The row draws its representative — the paging query is a different reading — and under
        // it, nothing. A chip of 3 over an unfold of 1, with no test in this repository failing.
        assertEquals(listOf("in2"), cache.screen(scope.value, drawn.value))
        assertEquals(0, cache.opened)
    }

    @Test fun `an emptied snooze query leaves a snoozed member listed under a chip that excludes it`() = runTest {
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        // MailRepository.observeActiveSnoozed, emptied out: nothing is ever reported as snoozed.
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope, flowOf(emptySet())) }
        advanceUntilIdle()

        // in1 IS snoozed as far as the rest of the app is concerned — the chip's SQL leaves it out.
        val chipExcludingSnoozed = cache.chip(scope.value, key) - 1
        assertEquals(2, chipExcludingSnoozed)
        assertEquals(3, cache.screen(scope.value, drawn.value).size)
        assertTrue("the snoozed member is still under the row", "in1" in cache.screen(scope.value, drawn.value))
    }

    // -- the swipe context recorded at the tap ------------------------------------------------

    /**
     * [ThreadOrders] is the store the reading view's swipe reads: what the unfolded row was showing
     */
    @Test fun `the order recorded at the tap is the order the reading view pages over`() {
        val orders = ThreadOrders()
        val recorded = orders.record(key, mail("in2", "inbox", "2026-07-01T10:00:00Z"), listOf(mail("in1", "inbox", "2026-07-01T09:00:00Z")))

        assertEquals(listOf("in2", "in1"), recorded.map { it.first })
        assertEquals("what was recorded is what is read back", recorded, orders.entries(key))
    }

    @Test fun `a conversation nothing was opened from hands the reader no context at all`() {
        // Not an empty guess: the reader falls back to the single message it was given.
        assertEquals(emptyList<Pair<String, String?>>(), ThreadOrders().entries(key))
    }

    @Test fun `each account's conversation keeps its own order under the same thread id`() {
        // #92: two accounts of one server share thread ids, and the reader must never be paged
        // through the other account's messages.
        val orders = ThreadOrders()
        val keyB = ThreadKey("accB", "T1")
        orders.record(key, mail("a2", "inbox", "2026-07-01T10:00:00Z"), listOf(mail("a1", "inbox", "2026-07-01T09:00:00Z")))
        orders.record(keyB, mail("b2", "inbox", "2026-07-01T10:00:00Z", account = "accB"), emptyList())

        assertEquals(listOf("a2", "a1"), orders.entries(key).map { it.first })
        assertEquals(listOf("b2"), orders.entries(keyB).map { it.first })
    }

    @Test fun `opening a second message replaces the order, it does not accumulate one`() {
        // The row is live: mail arriving in the open conversation changes what a later tap records.
        val orders = ThreadOrders()
        orders.record(key, mail("in2", "inbox", "2026-07-01T10:00:00Z"), listOf(mail("in1", "inbox", "2026-07-01T09:00:00Z")))
        orders.record(key, mail("in3", "inbox", "2026-07-01T12:00:00Z"), listOf(mail("in2", "inbox", "2026-07-01T10:00:00Z")))

        assertEquals(listOf("in3", "in2"), orders.entries(key).map { it.first })
    }

    @Test fun `a conversation that leaves the list is forgotten, and the rest is not`() {
        val orders = ThreadOrders()
        val keyB = ThreadKey("accA", "T2")
        orders.record(key, mail("in2", "inbox", "2026-07-01T10:00:00Z"), emptyList())
        orders.record(keyB, mail("other", "inbox", "2026-07-01T10:00:00Z"), emptyList())

        orders.drop(key)
        assertEquals(emptyList<Pair<String, String?>>(), orders.entries(key))
        assertEquals(listOf("other"), orders.entries(keyB).map { it.first })

        orders.clear()
        assertEquals(emptyList<Pair<String, String?>>(), orders.entries(keyB))
    }

    // -- the scope decision the two sides share ---------------------------------------------

    @Test fun `the unfold's folders are the viewed ones plus that account's Sent`() = runTest {
        val scope = ListScope(listOf("inbox"), listOf("accA" to "sentbox", "accB" to "otherSent"))
        assertEquals(listOf("inbox", "sentbox"), scope.folders("accA"))
        assertEquals(listOf("inbox"), scope.folders("accC"))
        assertEquals(emptyList<String>(), ListScope().folders("accA"))

        // And a scope that resolves nothing still lists the folder on screen — the smaller, honest
        // conversation both sides then show.
        val cache = Cache().apply { anAnsweredThread() }
        assertEquals(listOf("in2", "in1"), cache.read("accA", ListScope(listOf("inbox")).folders("accA"), "T1").first().map { it.id })
    }
}
