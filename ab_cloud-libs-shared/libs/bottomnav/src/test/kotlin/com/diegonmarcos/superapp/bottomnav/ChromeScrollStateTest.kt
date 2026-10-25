package com.diegonmarcos.superapp.bottomnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chrome's three-state scroll machine: DEFAULT, HIDDEN while a downward drag is on screen, SHRUNK on release; up restores DEFAULT. */
class ChromeScrollStateTest {
    private val d = ChromeScrollState.Default
    private val h = ChromeScrollState.Hidden
    private val s = ChromeScrollState.Shrunk

    @Test fun `a downward drag hides everything`() {
        assertEquals(h, chromeScrollOnDelta(d, 10f, dragging = true))
        assertEquals(h, chromeScrollOnDelta(h, 10f, dragging = true))
        assertEquals(h, chromeScrollOnDelta(s, 10f, dragging = true))
    }

    @Test fun `release after a downward drag shrinks, other states are unchanged`() {
        assertEquals(s, chromeScrollOnRelease(h))
        assertEquals(s, chromeScrollOnRelease(s))
        assertEquals(d, chromeScrollOnRelease(d))
    }

    @Test fun `the fling settle after release stays shrunk`() {
        assertEquals(s, chromeScrollOnDelta(s, 40f, dragging = false))
        assertEquals(s, chromeScrollOnDelta(d, 40f, dragging = false))
        assertEquals("a settle never re-hides a hidden chrome before the release", h, chromeScrollOnDelta(h, 40f, dragging = false))
    }

    @Test fun `any upward scroll restores DEFAULT`() {
        for (from in listOf(d, h, s)) for (drag in listOf(true, false)) assertEquals(d, chromeScrollOnDelta(from, -5f, drag))
    }

    @Test fun `no movement changes nothing`() {
        for (from in listOf(d, h, s)) assertEquals(from, chromeScrollOnDelta(from, 0f, true))
    }

    @Test fun `the connection exposes the state and the derived flags`() {
        val c = BottomNavCollapse()
        assertEquals(d, c.state); assertFalse(c.collapsed); assertFalse(c.hidden); assertTrue(c.firstRowVisible)
        c.onDragDelta(12f)
        assertEquals(h, c.state); assertTrue(c.collapsed); assertTrue(c.hidden); assertFalse(c.firstRowVisible)
        c.onRelease()
        assertEquals(s, c.state); assertTrue(c.collapsed); assertFalse(c.hidden); assertFalse(c.firstRowVisible)
        c.onScrollDelta(-3f)
        assertEquals(d, c.state)
        c.onScrollDelta(3f)
        assertEquals("a host without touch state settles straight to SHRUNK", s, c.state)
        c.restore(); c.onFinger(true)
        c.onScrollDelta(3f)
        assertEquals("a WebView-style read with the finger down is a drag", h, c.state)
        c.onFinger(false); c.onRelease()
        assertEquals(s, c.state)
    }

    @Test fun `with a touch watcher a user-input scroll without a finger shrinks instead of hiding`() {
        val c = BottomNavCollapse()
        c.onFinger(false)
        c.onPreScroll(androidx.compose.ui.geometry.Offset(0f, -20f), androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput)
        assertEquals("an injected scroll (accessibility, a test) settles to SHRUNK", s, c.state)
        c.restore()
        c.onFinger(true)
        c.onPreScroll(androidx.compose.ui.geometry.Offset(0f, -20f), androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput)
        assertEquals("a scroll with the finger down is a drag", h, c.state)
        c.onFinger(false); c.onRelease()
        assertEquals(s, c.state)
    }

    @Test fun `without a touch watcher a user-input scroll is a drag`() {
        val c = BottomNavCollapse()
        c.onPreScroll(androidx.compose.ui.geometry.Offset(0f, -20f), androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput)
        assertEquals(h, c.state)
    }
}
