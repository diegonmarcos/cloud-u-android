package com.diegonmarcos.superapp.navbartop1

import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The breadcrumb and the dropdown's pages, derived from a nav tree (pure, no drawing). */
class BreadcrumbTest {
    private val mail = NavSection(
        "mail", "Mail", "mail",
        listOf(
            NavPage("inbox", "Inbox", "i"),
            NavPage("sent", "Sent", "s"),
            NavPage("labels", "Labels", "l", pages = listOf(NavPage("work", "Work", "w"), NavPage("home", "Home", "h"))),
        ),
    )
    private val news = NavSection("rss", "News", "n", listOf(NavPage("all", "All", "a")))
    private val sections = listOf(mail, news)

    private fun nav(page: String?, leaf: String? = null, secs: List<NavSection> = sections) =
        titleNav("Cloud Mail", "Mail", titleHierarchy(mail, page), secs, leaf)

    @Test fun `the path is App, Section, Page and ends with the open message`() {
        assertEquals(listOf("Cloud Mail", "Mail", "Inbox"), nav("inbox").labels)
        assertEquals(listOf("Cloud Mail", "Mail", "Inbox", "Hello"), nav("inbox", "Hello").labels)
        assertEquals(listOf("Cloud Mail", "Mail", "Labels", "Work"), nav("work").labels)
        assertEquals(listOf("Cloud Mail", "Mail"), nav(null).labels)
    }

    @Test fun `every segment but the open message is a destination`() {
        val n = nav("work", "Hello")
        assertEquals(listOf("mail", "mail", "labels", "work"), n.crumbs.dropLast(1).map { it.target?.id })
        assertEquals("the app segment goes to the first section", "mail", n.crumbs[0].target?.sectionId)
        assertEquals("labels", n.crumbs[2].target?.id)
        assertNull("the message is not a link", n.crumbs.last().target)
    }

    @Test fun `a page lists its siblings then its children`() {
        assertEquals(listOf("inbox", "sent", "labels"), nav("inbox").related.map { it.id })
        assertEquals(listOf("inbox", "sent", "labels", "work", "home"), nav("labels").related.map { it.id })
        assertEquals(listOf("work", "home"), nav("work").related.map { it.id })
        assertTrue(nav("inbox").related.first { it.id == "inbox" }.selected)
    }

    @Test fun `a section lists the sections beside it and its own pages as children`() {
        assertEquals(listOf("mail", "rss", "inbox", "sent", "labels"), nav(null).related.map { it.id })
        // no sections handed in: the section itself stands alone, then its pages
        assertEquals(listOf("mail", "inbox", "sent", "labels"), nav(null, secs = emptyList()).related.map { it.id })
    }

    @Test fun `an app with no pages is App and its title`() {
        val n = titleNav("Cloud Calc", "Sheet", emptyList())
        assertEquals(listOf("Cloud Calc", "Sheet"), n.labels)
        assertTrue(n.related.isEmpty())
        assertEquals(listOf("Cloud Calc"), titleNav("Cloud Calc", "Cloud Calc", emptyList()).labels)
    }

    @Test fun `a long path is cut from the left with an ellipsis and keeps its end`() {
        val labels = listOf("Cloud Mail", "Mail", "Inbox", "Quarterly numbers")
        assertEquals("Cloud Mail / Mail / Inbox / Quarterly numbers", fitCrumbs(labels) { true })
        assertEquals("… / Inbox / Quarterly numbers", fitCrumbs(labels) { it.length <= 30 })
        assertEquals("… / Quarterly numbers", fitCrumbs(labels) { it.length <= 22 })
        assertEquals("Quarterly numbers", fitCrumbs(labels) { false })
    }
}
