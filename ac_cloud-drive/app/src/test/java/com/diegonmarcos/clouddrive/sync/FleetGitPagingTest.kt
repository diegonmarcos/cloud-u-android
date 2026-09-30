package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #684 Owner Amendment 2, rule 2: the Cloud (gitea) listing follows EVERY page — the declared
 * `limit` is a page size, not a cap. These are the pure rules the network loop is built on,
 * exercised on the JVM so the pagination cannot silently truncate a large account.
 */
class FleetGitPagingTest {

    @Test fun `the page size is read off the declared limit, absent means one whole page`() {
        assertEquals(50, FleetGit.pageLimit("https://git.diegonmarcos.com/api/v1/repos/search?limit=50"))
        assertEquals(10, FleetGit.pageLimit("https://h/x?a=1&limit=10&b=2"))
        assertEquals(Int.MAX_VALUE, FleetGit.pageLimit("https://h/x?a=1"))
    }

    @Test fun `pagedUrl sets page, and replacing it never doubles the parameter`() {
        assertEquals("https://h/x?limit=50&page=1", FleetGit.pagedUrl("https://h/x?limit=50", 1))
        assertEquals("https://h/x?limit=50&page=3", FleetGit.pagedUrl("https://h/x?limit=50", 3))
        // A base that already carries a page is re-paged, not appended twice.
        val once = FleetGit.pagedUrl("https://h/x?limit=50", 2)
        assertEquals(once, FleetGit.pagedUrl(once, 2))
        assertEquals(1, Regex("page=").findAll(FleetGit.pagedUrl(once, 5)).count())
        // No query at all → page becomes the first parameter.
        assertEquals("https://h/x?page=1", FleetGit.pagedUrl("https://h/x", 1))
    }

    @Test fun `a full page means keep going, a short page is the last`() {
        assertFalse(FleetGit.isLastPage(50, 50))
        assertTrue(FleetGit.isLastPage(23, 50))
        assertTrue(FleetGit.isLastPage(0, 50))
    }
}
