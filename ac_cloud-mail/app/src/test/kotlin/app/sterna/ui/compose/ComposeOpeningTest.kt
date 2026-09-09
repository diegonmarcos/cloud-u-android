package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place the compose `mode` navigation argument is read. Before it existed,
 */
class ComposeOpeningTest {

    // --- Forward as attachment -------------------------------------------------------------------

    @Test fun forwardAttachmentDoesNotThread() {
        assertFalse(
            "a forward-as-attachment must NOT set In-Reply-To / References: the recipient's " +
                "client would file the forwarded message into a thread they were never part of.",
            composeOpening("forwardAttachment").threads,
        )
    }

    @Test fun forwardAttachmentPrefixesFwd() {
        assertEquals(
            "a forward-as-attachment opens with a 'Fwd:' subject, not 'Re:' — with 'Re:' the " +
                "composer looks like a reply to the sender of the original.",
            "Fwd:", composeOpening("forwardAttachment").subjectPrefix,
        )
    }

    @Test fun forwardAttachmentDoesNotQuote() {
        assertFalse(
            "a forward-as-attachment opens with an EMPTY body: quoting the original above the " +
                "attached .eml sends the same message twice, once inline and once as a file.",
            composeOpening("forwardAttachment").quotes,
        )
    }

    @Test fun forwardAttachmentDoesNotCarryTheOriginalInline() {
        assertFalse(
            "a forward-as-attachment must not ALSO run buildForwarded: the original would travel " +
                "twice — inline under the note AND as the .eml — with its attachments re-staged " +
                "a second time.",
            composeOpening("forwardAttachment").carriesOriginal,
        )
    }

    @Test fun forwardAttachmentAttachesTheSource() {
        assertTrue(
            "a forward-as-attachment must stage the original's raw source as a message/rfc822 " +
                "part: without it the composer opens as an empty 'Fwd:' with NO attachment, and " +
                "the user sends nothing.",
            composeOpening("forwardAttachment").attachesSource,
        )
    }

    // --- Forward (inline) ------------------------------------------------------------------------

    @Test fun forwardCarriesTheOriginalInlineAndNothingElse() {
        val o = composeOpening("forward")
        assertFalse("a forward must not set In-Reply-To / References", o.threads)
        assertEquals("a forward's subject is prefixed 'Fwd:'", "Fwd:", o.subjectPrefix)
        assertFalse("a forward's editable body starts empty, the original is carried at send time", o.quotes)
        assertTrue(
            "a forward MUST run buildForwarded: without it the recipient gets the user's note " +
                "and none of the original.",
            o.carriesOriginal,
        )
        assertFalse(
            "a plain forward must not stage the .eml too — the original would go out twice.",
            o.attachesSource,
        )
    }

    // --- Replies: reply-all, reply, and everything the route did not name ---------------------------

    @Test fun replyAllIsAThreadedQuotedReply() {
        assertReply(composeOpening("replyAll"), "replyAll")
    }

    @Test fun replyIsAThreadedQuotedReply() {
        assertReply(composeOpening("reply"), "reply")
    }

    @Test fun aMissingModeOpensAReply() {
        // The route's `else` today: no mode argument means reply.
        assertReply(composeOpening(null), "null")
    }

    @Test fun anUnknownModeOpensAReply() {
        // Same as before this enum existed: an unknown string fell into the reply branch. What
        // changes is that it now falls there in ALL three places at once, instead of one.
        assertReply(composeOpening("fowrard"), "an unknown string")
    }

    @Test fun replyAllAndReplyAreDistinctOpenings() {
        // Reply-all differs from reply by its recipients, which the prefill decides on the enum
        // value — so the two must not collapse into one.
        assertEquals(ComposeOpening.REPLY_ALL, composeOpening("replyAll"))
        assertEquals(ComposeOpening.REPLY, composeOpening("reply"))
    }

    private fun assertReply(o: ComposeOpening, what: String) {
        assertTrue("$what must set In-Reply-To / References: the reply would fall out of its thread", o.threads)
        assertEquals("$what is prefixed 'Re:'", "Re:", o.subjectPrefix)
        assertTrue("$what opens with the quoted original in the body", o.quotes)
        assertFalse("$what must not run buildForwarded: a reply would carry the original twice", o.carriesOriginal)
        assertFalse("$what must not stage the original as a .eml: a reply with a surprise attachment", o.attachesSource)
    }

    // --- Across every opening: the original travels at most one way -----------------------------

    @Test fun theOriginalTravelsExactlyOneWayInAForwardAndNoWayInAReply() {
        for (o in ComposeOpening.entries) {
            val ways = listOf(o.carriesOriginal, o.attachesSource).count { it }
            if (o.subjectPrefix == "Fwd:") {
                assertEquals(
                    "$o is a forward and must carry the original in EXACTLY one way — inline " +
                        "(buildForwarded) or as the .eml. Zero sends nothing; two sends it twice.",
                    1, ways,
                )
            } else {
                assertEquals(
                    "$o is a reply and must carry the original in NO way beyond the quote.",
                    0, ways,
                )
            }
        }
    }

    @Test fun onlyTheForwardsSkipThreadingAndQuoting() {
        for (o in ComposeOpening.entries) {
            val isForward = o.subjectPrefix == "Fwd:"
            assertEquals(
                "$o: 'Fwd:' and In-Reply-To go together only in a broken client — a forward " +
                    "threads nothing, a reply always threads.",
                !isForward, o.threads,
            )
            assertEquals(
                "$o: a forward's body starts empty, a reply's starts with the quote.",
                !isForward, o.quotes,
            )
        }
    }
}
