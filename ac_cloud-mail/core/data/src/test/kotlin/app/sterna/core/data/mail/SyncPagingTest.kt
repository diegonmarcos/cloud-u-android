package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sizing decision of a folder refresh, executed. Every expectation is a literal: a test that
 */
class SyncPagingTest {

    @Test fun theRequestIsCappedToWhatTheServerAcceptsInOneGo() {
        // "Messages to sync = All" (1000) on Stalwart (maxObjectsInGet = 500). Asking for 1000
        // gets the whole back-referenced Email/get rejected, and the folder never syncs again.
        assertEquals(500, folderSyncSizing(windowLimit = 1000, serverCapacity = 500).pageSize)
    }

    @Test fun theRetentionFloorIsNotCappedWithIt() {
        // The trap this function exists for. The floor is "keep at least the newest N whatever
        // their age" (#110). Capping it to the server's request limit would make the prune delete,
        // right after the refresh, mail the user asked to keep — 500 kept out of the 1000 wanted.
        assertEquals(1000, folderSyncSizing(windowLimit = 1000, serverCapacity = 500).retentionFloor)
    }

    @Test fun theWindowItselfIsNotClampedToTheServerPage() {
        // The window survives whole as the walk's target; it is fetched in several requests, not
        // shrunk to one. Clamping here would drop everyone's window to the server's page size.
        assertEquals(1000, folderSyncSizing(windowLimit = 1000, serverCapacity = 500).windowTarget)
    }

    @Test fun aWindowThatAlreadyFitsIsLeftAlone() {
        assertEquals(
            FolderSyncSizing(windowTarget = 50, pageSize = 50, retentionFloor = 50),
            folderSyncSizing(windowLimit = 50, serverCapacity = 500),
        )
    }

    @Test fun aServerSmallerThanTheDefaultWindowCapsTheDefaultWindowToo() {
        // The defect is not the "All" setting: a server advertising 20 breaks the 50-message
        // default for every account on it, and its retention floor must still be 50.
        val sizing = folderSyncSizing(windowLimit = 50, serverCapacity = 20)
        assertEquals(20, sizing.pageSize)
        assertEquals(50, sizing.retentionFloor)
        assertEquals(50, sizing.windowTarget)
    }

    @Test fun aRequestIsCappedToWhatTheServerAdmits() {
        // The header crawl that feeds local search asked for a hardcoded 500. On a server
        // admitting 200 every page was refused whole and the crawl gave up after three refusals —
        // silently: it has no surface of its own, so local search just stopped being covered.
        assertEquals(200, requestPageSize(wanted = 500, serverCapacity = 200))
    }

    @Test fun aRequestSmallerThanTheServerLimitIsLeftAlone() {
        assertEquals(500, requestPageSize(wanted = 500, serverCapacity = 5000))
        assertEquals(1, requestPageSize(wanted = 500, serverCapacity = 0))
    }

    @Test fun aServerAdvertisingNothingUsableStillLeavesAWalkThatAdvances() {
        assertEquals(1, folderSyncSizing(windowLimit = 200, serverCapacity = 0).pageSize)
        assertEquals(200, folderSyncSizing(windowLimit = 200, serverCapacity = 0).retentionFloor)
    }
}
