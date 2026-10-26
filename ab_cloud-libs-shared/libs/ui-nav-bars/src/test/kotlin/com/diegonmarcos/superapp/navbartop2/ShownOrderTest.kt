package com.diegonmarcos.superapp.navbartop2

import org.junit.Assert.assertEquals
import org.junit.Test

/** Two pill rows on one page: the title follows the strip the reader used last, and a dropdown request names it. */
class ShownOrderTest {
    @Test fun `the strip that changed last is last`() {
        val r = SubPageRequests(); val fleet = Any(); val personal = Any()
        r.reportShown(fleet, "constellation"); r.reportShown(personal, "machines")
        assertEquals(listOf("constellation", "machines"), r.shownIds())
        r.reportShown(fleet, "other"); r.reportShown(fleet, "constellation")
        assertEquals(listOf("machines", "constellation"), r.shownIds())
    }

    @Test fun `a requested page that is already shown goes last`() {
        val r = SubPageRequests(); val fleet = Any(); val personal = Any()
        r.reportShown(fleet, "constellation"); r.reportShown(personal, "machines")
        r.request("constellation")
        assertEquals(listOf("machines", "constellation"), r.shownIds())
    }

    @Test fun `a later pick of another page drops the request`() {
        val r = SubPageRequests(); val a = Any(); val b = Any()
        r.reportShown(a, "x"); r.reportShown(b, "y")
        r.request("x"); r.reportShown(b, "z")
        assertEquals(listOf("x", "z"), r.shownIds())
    }

    @Test fun `a cleared strip leaves the order`() {
        val r = SubPageRequests(); val a = Any(); val b = Any()
        r.reportShown(a, "x"); r.reportShown(b, "y"); r.clearShown(b)
        assertEquals(listOf("x"), r.shownIds())
    }
}
