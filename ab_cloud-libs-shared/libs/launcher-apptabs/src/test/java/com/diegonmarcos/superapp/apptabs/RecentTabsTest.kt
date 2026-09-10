package com.diegonmarcos.superapp.apptabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure filter behind the Recent Tabs star's arc — no Android framework,
 *  runs on the JVM. Sibling of onehand's CentaurusStarFilterTest. */
class RecentTabsTest {

    /** Newest-first, exactly as AppTabPrefs.all() hands them over. */
    private fun page(section: String, id: String, ts: Long) =
        AppTabPrefs.Entry.PageEntry(section, id, label = id, iconName = "ic_$id", ts = ts)

    private val everythingAlive: (AppTabPrefs.Entry) -> Boolean = { true }

    @Test fun mostRecentFirstAndNoDuplicates() {
        // Mail ▸ Inbox visited twice: the newer visit is at the top, the older
        // copy is gone — not a second entry further down.
        val entries = listOf(
            page("mail", "inbox", 300),
            page("cloud", "drive", 200),
            page("mail", "inbox", 100),
        )
        assertEquals(
            listOf("page:mail/inbox", "page:cloud/drive"),
            pickRecentTabs(entries, everythingAlive, limit = 9).map { it.key },
        )
    }

    @Test fun androidAppsAreNotPages() {
        // The same LRU records apps launched from the Phone tab. Those belong to
        // Centauri's ring and to Active Apps; this star is in-app pages only.
        val entries = listOf(
            AppTabPrefs.Entry.ExternalAppEntry("com.whatsapp", "WhatsApp", 300),
            page("mail", "inbox", 200),
            AppTabPrefs.Entry.SectionEntry("cloud", "Cloud", "ic_cloud", 100),
        )
        assertEquals(
            listOf("page:mail/inbox", "section:cloud"),
            pickRecentTabs(entries, everythingAlive, limit = 9).map { it.key },
        )
    }

    @Test fun aPageThatNoLongerExistsIsSkippedNotOffered() {
        // "myfin" was deleted from build.json; an entry stored before that
        // still names it. It must vanish from the arc, and the surviving
        // entries must still be offered — not the whole list lost.
        val entries = listOf(
            page("myfin", "summary", 300),
            page("mail", "inbox", 200),
        )
        val alive: (AppTabPrefs.Entry) -> Boolean = { e ->
            (e as? AppTabPrefs.Entry.PageEntry)?.sectionId != "myfin"
        }
        assertEquals(
            listOf("page:mail/inbox"),
            pickRecentTabs(entries, alive, limit = 9).map { it.key },
        )
    }

    @Test fun everyEntryDeadYieldsEmptyRatherThanCrashing() {
        val entries = listOf(page("myfin", "summary", 300), page("health", "today", 200))
        assertEquals(emptyList<String>(), pickRecentTabs(entries, { false }, limit = 9).map { it.key })
    }

    @Test fun capHoldsAtNine() {
        // Twelve distinct pages in a store of ten; the star draws nine.
        val entries = (12 downTo 1).map { page("cloud", "p$it", it.toLong()) }
        val picked = pickRecentTabs(entries, everythingAlive, limit = 9)
        assertEquals(9, picked.size)
        // …and they are the NINE MOST RECENT, not the first nine of some other order.
        assertEquals(
            (12 downTo 4).map { "page:cloud/p$it" },
            picked.map { it.key },
        )
    }

    @Test fun theCapCountsSurvivorsNotStoredEntries() {
        // Ten stored, one of them an Android app: nine pages survive and all
        // nine are drawn. A cap applied before the filters would show eight.
        val entries = listOf(AppTabPrefs.Entry.ExternalAppEntry("com.whatsapp", "WhatsApp", 100)) +
            (9 downTo 1).map { page("cloud", "p$it", it.toLong()) }
        assertEquals(9, pickRecentTabs(entries, everythingAlive, limit = 9).size)
    }

    @Test fun freshInstallIsEmpty() {
        assertTrue(pickRecentTabs(emptyList(), everythingAlive, limit = 9).isEmpty())
    }

    @Test fun labelAndIconTravelWithTheEntry() {
        // The arc draws the page's real name and its declared icon; it does not
        // re-derive either, so they have to survive the filter intact.
        val picked = pickRecentTabs(listOf(page("mail", "inbox", 300)), everythingAlive, limit = 9)
        val entry = picked.single() as AppTabPrefs.Entry.PageEntry
        assertEquals("inbox", entry.label)
        assertEquals("ic_inbox", entry.iconName)
    }
}
