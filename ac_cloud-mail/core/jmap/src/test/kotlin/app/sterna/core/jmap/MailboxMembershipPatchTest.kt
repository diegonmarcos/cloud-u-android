package app.sterna.core.jmap

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE data-loss check for a message's folder membership, on the patch that actually goes out.
 *
 * JMAP `mailboxIds` (RFC 8621 §4.1.1) is a message's COMPLETE set of mailboxes, and in this app's
 * labels model every category is a mailbox. Writing the whole property with `{target: true}` is
 * therefore not a move but a deletion of every other label and folder the message was in — on the
 * server, permanently. It shipped once (00127a847), and the Kotlin rewrite brought it straight
 * back, because that fix lived at the call sites rather than in one function.
 *
 * So these assertions are about the emitted JSON, not about a screen: RFC 8620 §5.3 patch paths
 * touch one membership bit each and say nothing about the others.
 */
class MailboxMembershipPatchTest {

    private fun patch(add: String?, remove: String?) =
        buildJsonObject { putMembershipPatch(add, remove) }

    @Test fun addingOneMailboxSaysNothingAboutAnyOther() {
        val p = patch(add = "mbWork", remove = null)
        assertEquals("an add is exactly one patch path", 1, p.size)
        assertEquals(setOf("mailboxIds/mbWork"), p.keys)
        assertEquals("true", p["mailboxIds/mbWork"].toString())
    }

    @Test fun removingOneMailboxSaysNothingAboutAnyOther() {
        val p = patch(add = null, remove = "mbWork")
        assertEquals("a remove is exactly one patch path", 1, p.size)
        assertEquals(JsonNull, p["mailboxIds/mbWork"])
    }

    /**
     * The case the whole file exists for. A message in {Inbox, Work, Receipts} moved to Archive
     * must come out of Inbox, go into Archive, and STILL be in Work and Receipts — and the patch
     * proves it by never naming them.
     */
    @Test fun aMoveEditsTwoBitsAndLeavesEveryOtherMailboxUnnamed() {
        val p = patch(add = "mbArchive", remove = "mbInbox")
        assertEquals(setOf("mailboxIds/mbArchive", "mailboxIds/mbInbox"), p.keys)
        assertEquals("true", p["mailboxIds/mbArchive"].toString())
        assertEquals(JsonNull, p["mailboxIds/mbInbox"])
        for (untouched in listOf("mbWork", "mbReceipts")) {
            assertTrue(
                "the patch names $untouched, so the server is being told something about a " +
                    "mailbox this operation was never about",
                p.keys.none { it.endsWith("/$untouched") },
            )
        }
    }

    /**
     * The exact shape that caused the loss, stated as a prohibition rather than left implied: a
     * bare "mailboxIds" key is a whole-property write, and there is no argument to this function
     * that may produce one.
     */
    @Test fun noArgumentEverProducesAWholePropertyWrite() {
        val combinations = listOf(
            null to null,
            "mbArchive" to null,
            null to "mbInbox",
            "mbArchive" to "mbInbox",
            "mbArchive" to "mbArchive",
        )
        for ((add, remove) in combinations) {
            val p = patch(add, remove)
            assertTrue(
                "putMembershipPatch($add, $remove) wrote the whole mailboxIds property",
                "mailboxIds" !in p.keys,
            )
            assertTrue(
                "putMembershipPatch($add, $remove) emitted a key that is not a patch path",
                p.keys.all { it.startsWith("mailboxIds/") },
            )
        }
    }

    /** Same mailbox added and removed: one patch cannot both hold and drop a membership, and which
     *  half a server honours is not a thing to find out in production. The remove is dropped. */
    @Test fun addingAndRemovingTheSameMailboxDoesNotContradictItself() {
        val p = patch(add = "mbArchive", remove = "mbArchive")
        assertEquals(setOf("mailboxIds/mbArchive"), p.keys)
        assertEquals("true", p["mailboxIds/mbArchive"].toString())
    }

    /** Neither side given: nothing is written at all, rather than an empty object that a server
     *  could read as "set the membership to nothing". */
    @Test fun sayingNothingWritesNothing() {
        assertTrue(patch(null, null).isEmpty())
    }
}
