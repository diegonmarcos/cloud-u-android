package com.diegonmarcos.superapp.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #883 search_scope: THE FALLBACK TEST. No suggestion (no engine, no consent, a closed gate, unsure, a scope not on
 * the page) marks no chip: exactly today's scope chips. The suggestion is only ever a mark; nothing here selects.
 */
class SearchJevTest {

    private val ids = listOf("apps", "mail", "web")

    @Test fun noOpinionMarksNoChip() {
        for (pick in listOf(null, "", "drive", "APPS")) assertNull("pick=$pick", SearchJev.valid(pick, ids))
    }

    @Test fun anOfferedScopeIsMarked() {
        assertEquals("mail", SearchJev.valid("mail", ids))
    }

    @Test fun aShortQueryIsNeverAsked() {
        assertEquals(3, SearchJev.MIN_QUERY)
    }
}
