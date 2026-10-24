package com.diegonmarcos.superapp.uiicons

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1160: the catalog holds the semantic names, tints everything but its brand entries, and never answers null. */
class UiIconsTest {
    @Test fun `the semantic names are all in the catalog`() {
        val names = listOf("inbox", "profile", "configs", "ai", "assistant", "search", "settings", "home")
        assertEquals(emptyList<String>(), names.filterNot(UiIcons::has))
        assertEquals(UiIcons.Names.PROFILE, "profile")
    }

    @Test fun `an ordinary entry is tinted and a name is one glyph everywhere`() {
        val inbox = UiIcons.icon("inbox")
        assertTrue(inbox.tinted)
        assertSame(inbox.vector, UiIcons.vector("inbox"))
        assertTrue(UiIcons.isTinted("inbox"))
    }

    @Test fun `the assistant is a brand icon drawn in its gradient, not tinted`() {
        val assistant = UiIcons.icon("assistant")
        assertFalse(assistant.tinted)
        assertFalse(UiIcons.isTinted("assistant"))
        val fills = assistant.vector.root.filterIsInstance<VectorPath>().map { it.fill }
        assertTrue("the assistant mark has a path", fills.isNotEmpty())
        assertTrue("every path of the assistant mark is filled with the gradient, not a flat colour", fills.all { it != null && it !is SolidColor })
    }

    @Test fun `an unknown or blank name draws the tinted fallback, never nothing`() {
        for (n in listOf("no_such_icon", "")) {
            assertFalse(UiIcons.has(n))
            assertNotNull(UiIcons.vector(n))
            assertEquals(UiIcons.FALLBACK.name, UiIcons.vector(n).name)
            assertTrue(UiIcons.isTinted(n))
        }
    }

    @Test fun `every name resolves to a glyph`() {
        assertTrue(UiIcons.names().size > 150)
        for (n in UiIcons.names()) assertTrue(n, UiIcons.has(n))
    }
}
