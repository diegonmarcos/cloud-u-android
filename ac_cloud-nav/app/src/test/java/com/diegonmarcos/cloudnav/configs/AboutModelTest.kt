package com.diegonmarcos.cloudnav.configs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The About page is built as data: the sink keeps order, and a permission button words its state. */
class AboutModelTest {
    @Test fun sinkKeepsInsertionOrder() {
        val s = AboutSink()
        s.add(AboutItem.Title("t")); s.add(AboutItem.Header("h")); s.add(AboutItem.Row("k", "v")); s.add(AboutItem.Small("x"))
        assertEquals(listOf("Title", "Header", "Row", "Small"), s.items.map { it::class.simpleName })
        assertEquals("v", (s.items[2] as AboutItem.Row).value)
    }

    @Test fun grantedButtonIsTickedAndOthersAreNot() {
        assertEquals("✓ Set Usage Access", PermBtn("Set Usage Access", true) {}.text)
        assertEquals("Set Usage Access", PermBtn("Set Usage Access", false) {}.text)
        assertEquals("Request All Perms", PermBtn("Request All Perms", null) {}.text)
    }

    @Test fun monoReadsItsTextLazily() {
        var v = "a"
        val m = AboutItem.Mono({ v }, 28)
        v = "b"
        assertEquals("b", m.text()); assertEquals(28, m.maxLines)
        assertTrue(AboutItem.Mono({ "" }).maxLines == Int.MAX_VALUE)
    }
}
