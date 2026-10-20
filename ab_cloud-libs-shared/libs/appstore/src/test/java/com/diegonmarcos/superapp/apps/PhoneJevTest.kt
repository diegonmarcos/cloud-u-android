package com.diegonmarcos.superapp.apps

import com.diegonmarcos.superapp.appstore.AppStoreHost
import com.diegonmarcos.superapp.appstore.StoreJev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #882 phone_folder and store_shelf: THE FALLBACK TESTS. With no suggestion (no engine, no consent, a closed
 * gate, unsure) an app stays on the sink and a Store row stays unshelved: exactly today's classification.
 */
class PhoneJevTest {

    private fun f(id: String) = PhoneFolders.Folder(id, "0", "Label $id", emptyList())
    private val folders = listOf(f("games"), f("tools"), f("others"))

    @Test fun noSuggestionKeepsTheSinkForEveryPackage() {
        for (pkg in listOf("a.b.c", "x.y", "")) assertEquals("others", PhoneJev.refine("others", "others", pkg))
    }

    @Test fun aRuleClaimedAppIsNeverTouched() {
        assertEquals("games", PhoneJev.refine("games", "others", "any.pkg"))
    }

    @Test fun onlyAnOfferedNonSinkFolderIsAccepted() {
        assertEquals("tools", PhoneJev.valid("tools", folders, "others"))
        assertNull(PhoneJev.valid("others", folders, "others"))
        assertNull(PhoneJev.valid("nope", folders, "others"))
        assertNull(PhoneJev.valid(null, folders, "others"))
    }

    @Test fun theQuestionOffersEveryFolderButTheSink() {
        val crit = PhoneJev.questions(folders, "others").getJSONObject("folder").getJSONObject("criteria")
        assertEquals(setOf("games", "tools"), crit.keys().asSequence().toSet())
        assertEquals("Label games", crit.getString("games"))
    }

    @Test fun aStoreRowNobodyFiledStaysUnshelvedWithoutASuggestion() {
        val shelves = listOf(AppStoreHost.Shelf("Games", "001"), AppStoreHost.Shelf("Tools", "002"))
        assertNull(StoreJev.shelfFor("some.app", shelves))
        assertNull(StoreJev.pick("Tools", emptyList()))
        assertNull(StoreJev.pick("", shelves))
        assertNull(StoreJev.pick(null, shelves))
        assertEquals("Tools", StoreJev.pick("Tools", shelves)?.heading)
        val crit = StoreJev.questions(shelves + shelves).getJSONObject("shelf").getJSONObject("criteria")
        assertEquals(setOf("Games", "Tools"), crit.keys().asSequence().toSet())
    }
}
