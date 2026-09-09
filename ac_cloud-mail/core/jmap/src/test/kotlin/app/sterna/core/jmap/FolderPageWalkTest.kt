package app.sterna.core.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The windowed folder walk's decision, executed rather than read: how big the next request is,
 */
class FolderPageWalkTest {

    @Test fun aWindowThatFitsInOnePageIsAskedForInOneRequest() {
        assertEquals(200, nextWindowPageLimit(fetched = 0, target = 200, pageSize = 500, last = null))
    }

    @Test fun aWindowBiggerThanThePageIsCappedToThePageNotToTheWindow() {
        // "Messages to sync = All" (1000) on a server advertising maxObjectsInGet = 500. Asking
        // for 1000 is the production defect: Email/query answers, the back-referenced Email/get
        // is rejected whole, and the folder never syncs again.
        assertEquals(500, nextWindowPageLimit(fetched = 0, target = 1000, pageSize = 500, last = null))
    }

    @Test fun theSecondRequestAsksForTheRestOfTheWindow() {
        // ...and capping WITHOUT this second request would be a fresh defect: the window would
        // silently drop from 1000 to 500 (to 100 on a server that advertises nothing).
        assertEquals(
            200,
            nextWindowPageLimit(
                fetched = 500,
                target = 700,
                pageSize = 500,
                last = WalkedPage(requested = 500, queryCount = 500, added = 500),
            ),
        )
    }

    @Test fun theRemainderIsNeverRoundedUpToAWholePage() {
        assertEquals(
            100,
            nextWindowPageLimit(
                fetched = 900,
                target = 1000,
                pageSize = 500,
                last = WalkedPage(requested = 500, queryCount = 500, added = 400),
            ),
        )
    }

    @Test fun theWalkEndsWhenTheWindowIsFull() {
        assertNull(
            nextWindowPageLimit(
                fetched = 1000,
                target = 1000,
                pageSize = 500,
                last = WalkedPage(requested = 500, queryCount = 500, added = 500),
            ),
        )
    }

    @Test fun aQueryShorterThanItWasAskedForEndsTheWalk() {
        // A 300-message folder under a 1000-message window: the server had no more to give, and
        // asking again would cost a round trip per refresh for ever.
        assertNull(
            nextWindowPageLimit(
                fetched = 300,
                target = 1000,
                pageSize = 500,
                last = WalkedPage(requested = 500, queryCount = 300, added = 300),
            ),
        )
    }

    @Test fun aShortGetDoesNotEndTheWalkWhileTheQueryWasFull() {
        // Email/get returned fewer objects than Email/query listed ids (a message destroyed
        // between the two calls): 497 accumulated, but the folder plainly has more behind it.
        // Terminating here would abandon the rest of the window on every refresh.
        assertEquals(
            203,
            nextWindowPageLimit(
                fetched = 497,
                target = 700,
                pageSize = 500,
                last = WalkedPage(requested = 500, queryCount = 500, added = 497),
            ),
        )
    }

    @Test fun aPageThatAddedNothingEndsTheWalk() {
        // No progress: the anchor is not advancing. Asking again would loop for ever.
        assertNull(
            nextWindowPageLimit(
                fetched = 500,
                target = 1000,
                pageSize = 500,
                last = WalkedPage(requested = 500, queryCount = 500, added = 0),
            ),
        )
    }

    @Test fun anEmptyWindowOrAnEmptyPageAsksForNothing() {
        assertNull(nextWindowPageLimit(fetched = 0, target = 0, pageSize = 500, last = null))
        assertNull(nextWindowPageLimit(fetched = 0, target = 1000, pageSize = 0, last = null))
    }
}
