package com.diegonmarcos.superapp.navbartop1

import org.junit.Assert.assertEquals
import org.junit.Test

/** The title path follows the page the strips show, even when the app keeps no sub-page state (Nav explored/maps, Drive manage/apps). */
class HierarchyFollowsStripsTest {
    private fun e(id: String, depth: Int, sel: Boolean = false) = HierarchyEntry(id, id.uppercase(), depth, "s", sel)
    private val tree = listOf(e("s", 0, true), e("a", 1), e("b", 1), e("b1", 2), e("b2", 2), e("c", 1))

    @Test fun `nothing shown leaves the hierarchy alone`() = assertEquals(tree, hierarchyWithShown(tree, emptyList()))

    @Test fun `a shown first-level page becomes the path`() {
        val h = hierarchyWithShown(tree, listOf("c"))
        assertEquals(listOf("c"), h.filter { it.depth > 0 && it.selected }.map { it.id })
        assertEquals(false, h.first { it.depth == 0 }.selected)
        assertEquals(listOf("S", "C"), titleNav("App", "S", h).labels.drop(1))
    }

    @Test fun `the deepest shown page wins and brings its container`() {
        val h = hierarchyWithShown(tree, listOf("b", "b2"))
        assertEquals(listOf("b", "b2"), h.filter { it.selected && it.depth > 0 }.map { it.id })
    }

    @Test fun `an id the section does not hold changes nothing`() = assertEquals(tree, hierarchyWithShown(tree, listOf("zzz")))

    @Test fun `a deeper page the app already marked is kept`() {
        val app = tree.map { if (it.id == "b" || it.id == "b1") it.copy(selected = true) else it }
        assertEquals(app, hierarchyWithShown(app, listOf("a")))
    }
}
