package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConversationExpansionTest {

    @Test fun `thread key is the account plus the threadId when present`() {
        assertEquals(ThreadKey("accA", "T1"), ConversationExpansion.threadKey("accA", threadId = "T1", id = "m9"))
    }

    @Test fun `thread key falls back to the message id when thread-less`() {
        assertEquals(ThreadKey("accA", "m9"), ConversationExpansion.threadKey("accA", threadId = null, id = "m9"))
    }

    @Test fun `two accounts sharing a thread id are two different keys`() {
        // The #92 family: servers number threads per account, so the unified inbox shows both.
        assertNotEquals(
            ConversationExpansion.threadKey("accA", "T1", "m1"),
            ConversationExpansion.threadKey("accB", "T1", "m2"),
        )
    }

    @Test fun `toggle expands a collapsed thread`() {
        val key = ThreadKey("accA", "T1")
        assertEquals(setOf(key), ConversationExpansion.toggle(emptySet(), key))
    }

    @Test fun `toggle collapses an expanded thread without touching the rest`() {
        val a = ThreadKey("accA", "T1")
        val b = ThreadKey("accA", "T2")
        assertEquals(setOf(b), ConversationExpansion.toggle(setOf(a, b), a))
    }

    @Test fun `unfolding one account's conversation leaves its homonym in the other account folded`() {
        // Same thread id, two accounts: expanding A's row must not draw B's row expanded.
        val a = ConversationExpansion.threadKey("accA", "T1", "m1")
        val b = ConversationExpansion.threadKey("accB", "T1", "m2")
        val expanded = ConversationExpansion.toggle(emptySet(), a)
        assertEquals(true, a in expanded)
        assertEquals(false, b in expanded)
    }

    @Test fun `the members of one account's conversation never answer for the other's`() {
        // The map that feeds both the unfolded rows and the reader's swipe context.
        val a = ThreadKey("accA", "T1")
        val b = ThreadKey("accB", "T1")
        val members = mapOf(a to listOf(Email(id = "a2", accountId = "accA")))
        assertEquals(emptyList<Email>(), members[b].orEmpty())
    }

    // --- the key travels through navigation without shedding its account ---

    @Test fun `a thread key survives a round trip through the route argument`() {
        val key = ThreadKey("acc-uuid", "T1")
        assertEquals(key, ThreadKey.decode(key.encode()))
    }

    @Test fun `a thread id containing the separator round-trips too`() {
        val key = ThreadKey("acc-uuid", "T|1|x")
        assertEquals(key, ThreadKey.decode(key.encode()))
    }

    @Test fun `a single-account key keeps its null account through the route`() {
        val key = ThreadKey(null, "T1")
        assertEquals(key, ThreadKey.decode(key.encode()))
    }

    @Test fun `a bare thread id left by an older route is refused, not guessed`() {
        // Rather than assume an account, the reader falls back to the single message it was given.
        assertEquals(null, ThreadKey.decode("T1"))
    }

    @Test fun `members below excludes the representative already shown on the row`() {
        val all = listOf(Email(id = "m3"), Email(id = "m2"), Email(id = "m1"))
        val below = ConversationExpansion.membersBelow(all, representativeId = "m3")
        assertEquals(listOf("m2", "m1"), below.map { it.id })
    }

    @Test fun `members below keeps the cache order (newest-first)`() {
        val all = listOf(Email(id = "m3"), Email(id = "m2"), Email(id = "m1"))
        val below = ConversationExpansion.membersBelow(all, representativeId = "m1")
        assertEquals(listOf("m3", "m2"), below.map { it.id })
    }

    @Test fun `members below is empty for a single-message thread`() {
        val all = listOf(Email(id = "only"))
        assertEquals(emptyList<String>(), ConversationExpansion.membersBelow(all, "only").map { it.id })
    }

    // The unfolded conversation is no longer built from the wire: the server completion only
    // fills the cache, and the rows on screen are a live reading of it (ThreadMemberStream). The

    // --- threadEntries: the reading view's swipe context (Codeberg #13) ---

    @Test fun `thread entries lead with the representative, then the members in list order`() {
        val members = listOf(Email(id = "m2"), Email(id = "m1"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(listOf("m3", "m2", "m1"), entries.map { it.first })
    }

    @Test fun `thread entries carry each message's own account`() {
        // A unified-view conversation belongs to one account; the members' accountId is what
        // the reader's per-page ViewModel needs to load and act on the right mailbox.
        val members = listOf(Email(id = "m2", accountId = "acc"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(listOf("acc", "acc"), entries.map { it.second })
    }

    @Test fun `a member with no account keeps a null account rather than borrowing one`() {
        val entries = ConversationExpansion.threadEntries("m3", "acc", listOf(Email(id = "m2")))
        assertEquals(listOf("acc" as String?, null), entries.map { it.second })
    }

    @Test fun `a single-message conversation is just the representative`() {
        val entries = ConversationExpansion.threadEntries("only", "acc", emptyList())
        assertEquals(listOf("only"), entries.map { it.first })
    }

    @Test fun `the representative is never listed twice`() {
        // Defensive: a merge racing a refresh could leave the rep among the members.
        val members = listOf(Email(id = "m3"), Email(id = "m2"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(listOf("m3", "m2"), entries.map { it.first })
    }

    @Test fun `the entries feed straight into the pager's opening page`() {
        // The tapped message is the second member — page 2 of a 3-message conversation.
        val members = listOf(Email(id = "m2"), Email(id = "m1"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(
            2,
            app.sterna.ui.message.MessagePaging.resolveInitialPage(
                entries.map { it.first },
                anchorId = "m1",
                fallbackIndex = 2,
            ),
        )
    }

    @Test fun `opening on the representative lands on the first page`() {
        val members = listOf(Email(id = "m2"), Email(id = "m1"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(
            0,
            app.sterna.ui.message.MessagePaging.resolveInitialPage(
                entries.map { it.first },
                anchorId = "m3",
                fallbackIndex = 0,
            ),
        )
    }
}
