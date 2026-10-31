package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UI spec 4.3 / 4.4: a tab opens on its default dashboard, a quote is pushed, back unwinds quote -> default -> home tab -> exit. */
class FinNavStackTest {
    @Test fun aTabOpensOnItsDefaultDashboard() {
        assertEquals(listOf("list", "dash:market_monitor"), FinNavStack.initial("market_monitor"))
        assertEquals(listOf("list"), FinNavStack.initial(null))
    }

    @Test fun backUnwindsInTheDeclaredOrder() {
        val s0 = FinNavStack.initial("fx")
        val s1 = FinNavStack.open(s0, "crypto")
        val s2 = FinNavStack.quote(s1, "BTC")
        assertEquals(listOf("list", "dash:crypto", "quote:BTC"), s2)
        assertEquals(s2, FinNavStack.quote(s2, "BTC"))
        assertEquals("BTC", FinNavStack.symbolOf(s2.last()))
        assertEquals("crypto", FinNavStack.dashboardOf(s2))
        assertEquals(s1, FinNavStack.back(s2, "fx", isHomeTab = false))
        assertEquals(s0, FinNavStack.back(s1, "fx", isHomeTab = false))
        assertEquals(s0, FinNavStack.back(FinNavStack.LIST_ONLY, "fx", isHomeTab = false))
        assertEquals(FinNavStack.HOME, FinNavStack.back(s0, "fx", isHomeTab = false))
        assertNull(FinNavStack.back(FinNavStack.initial("market_monitor"), "market_monitor", isHomeTab = true))
    }
}
