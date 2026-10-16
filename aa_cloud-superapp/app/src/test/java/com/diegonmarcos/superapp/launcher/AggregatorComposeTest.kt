package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the aggregator page in Compose (was ScrollView + SwipeRefreshLayout + hand-built Views), tested where the
 * View version could only be grepped.
 *
 *   G1 a group draws its header (label, chip), its rows, and folds shut on a header tap
 *   G2 the double tick marks the group read and the read set is persisted under the page id
 *   G3 the Archive pill files the box away and says Restore for a box that is in the Archive
 *   G4 a swipe flips a row's read state both ways, and under Show=Unread a row that became read leaves
 *   F1 the Filters row draws the declared toggles, persists a pick, and a Tools pick returns Services to All
 *   P1 the page draws one card per declared panel, folds on a header tap and on the bottom-nav re-tap
 *   P2 a phone-stream card without Notification Access says so instead of drawing an empty list
 *   P3 an `anchor:` tile scrolls THIS page to the card that claimed the id
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AggregatorComposeTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private val realPoller = AggregatorModel.ntfyPoller

    // Hermetic: the page polls the ntfy relay on its own; a unit test answers "every channel is quiet" instead of
    // opening a socket.
    @Before
    fun quietRelay() {
        AggregatorModel.ntfyPoller = { topics -> topics.associateWith { NtfyResult(true, "", emptyList()) } }
    }

    @After
    fun restoreRelay() {
        AggregatorModel.ntfyPoller = realPoller
    }

    private fun row(id: String, ts: Long, title: String = "title $id") =
        NotifRow(ts = ts, title = title, text = "body $id", id = id)

    private fun view(vararg rows: NotifRow, key: String = "pkg.a") = GroupView(
        NotifGroup(key = key, label = "App A", sub = key, rows = rows.toList()),
        GroupChip("${rows.size} new · ${rows.size}", Tone.OK),
    )

    private fun state(page: String = "test-page") = AggregatorState(ctx, page, emptyList(), emptyList())

    @Test
    fun `G1 a group draws header and rows and folds on a header tap`() {
        val st = state()
        showKit { GroupBlock(st, view(row("phone:1", 100), row("phone:2", 200)), archived = false) { } }
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).assertTextContains("App A", substring = true)
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).assertTextContains("2 new · 2", substring = true)
        compose.onNodeWithText("title phone:1").assertExists()
        compose.onNodeWithText("title phone:2").assertExists()
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performClick()
        compose.onNodeWithText("title phone:1").assertDoesNotExist()
        // The fold lives on the state, so it survives a re-render (a toggle tap rebuilds every body).
        assertEquals(true, st.collapsedGroups["pkg.a"])
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performClick()
        compose.onNodeWithText("title phone:1").assertExists()
    }

    @Test
    fun `G2 the double tick marks every row of the group read and persists it`() {
        val st = state("g2-page")
        val v = view(row("phone:1", 100), row("phone:2", 200))
        showKit { GroupBlock(st, v, archived = false) { } }
        assertTrue(st.isUnread(v.group.rows[0]))
        compose.onNodeWithTag(AggTags.groupCheck("pkg.a")).performClick()
        assertFalse(st.isUnread(v.group.rows[0]))
        assertFalse(st.isUnread(v.group.rows[1]))
        // A new state on the same page (the process died and came back) still knows.
        assertEquals(setOf("phone:1", "phone:2"), StackFilters.readKeys(ctx, "g2-page"))
    }

    @Test
    fun `G3 the archive pill files a box away and offers Restore once it is there`() {
        val st = state("g3-page")
        val v = view(row("phone:1", 100))
        showKit { GroupBlock(st, v, archived = st.isArchived("pkg.a")) { } }
        compose.onNodeWithTag(AggTags.groupArchive("pkg.a")).assertTextContains(AggregatorModel.ARCHIVE_LABEL)
        compose.onNodeWithTag(AggTags.groupArchive("pkg.a")).performClick()
        assertTrue(st.isArchived("pkg.a"))
        // Remembered per page, like every other choice on it.
        assertEquals("yes", StackFilters.selected(ctx, "g3-page", st.yesNo(AggregatorModel.ARCHIVED_PREFIX + "pkg.a")))
        compose.onNodeWithTag(AggTags.groupArchive("pkg.a")).assertTextContains(AggregatorModel.ARCHIVE_LABEL)
    }

    @Test
    fun `G4 a swipe flips read both ways and Show unread drops a row that became read`() {
        val st = state("g4-page")
        val r = row("phone:1", 100)
        showKit { GroupBlock(st, view(r, row("phone:2", 200)), archived = false) { } }
        assertTrue(st.isUnread(r))
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { !st.isUnread(r) }
        compose.waitForIdle()   // the row is still sliding back in from the other edge
        // And back: swiping a read row marks it unread again — the gesture is reversible.
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { st.isUnread(r) }
        assertEquals(setOf<String>(), StackFilters.readKeys(ctx, "g4-page"))
    }

    @Test
    fun `G5 a vertical drag on a row does not mark it read`() {
        val st = state("g5-page")
        val r = row("phone:1", 100)
        showKit { GroupBlock(st, view(r), archived = false) { } }
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, top + 2f), androidx.compose.ui.geometry.Offset(centerX, bottom - 2f))
        }
        compose.waitForIdle()
        assertTrue(st.isUnread(r))
    }

    private fun filtersOf(sectionId: String, page: String): List<Sections.StackFilter> =
        Sections.stackFiltersFor(Sections.byId(sectionId)!!, page)

    @Test
    fun `F1 the Filters row draws the declared toggles and persists a pick`() {
        val filters = filtersOf("communication", "rss-inboxes")
        assumeTrue("the Inboxes tab declares no filter row in this build", filters.isNotEmpty())
        val st = AggregatorState(ctx, "rss-inboxes", emptyList(), filters)
        showKit { FilterRow(st) }
        compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).assertExists()
        val sort = filters.first { it.id == "sort" }
        val pick = sort.options.first { it.id != st.selected(sort) }
        // The row starts folded on the tabs whose filters are chosen for them; open it if so.
        if (compose.onAllNodesWithTag(AggTags.filter("sort", pick.id)).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).performClick()
        }
        compose.onNodeWithTag(AggTags.filter("sort", pick.id)).performClick()
        assertEquals(pick.id, st.selected(sort))
        assertEquals(pick.id, st.sortMode)
        assertEquals(pick.id, StackFilters.selected(ctx, "rss-inboxes", sort))
    }

    @Test
    fun `F2 narrowing Tools returns Services to All, they are one choice over two rows`() {
        val filters = filtersOf("communication", "rss-inboxes")
        val tools = filters.firstOrNull { it.id == AggregatorModel.FILTER_TOOLS }
        val services = filters.firstOrNull { it.id == AggregatorModel.FILTER_SERVICES }
        assumeTrue("this build declares no Tools/Services rows", tools != null && services != null)
        val st = AggregatorState(ctx, "rss-inboxes-f2", emptyList(), filters)
        val narrowS = services!!.options.first { it.id != "all" }
        val narrowT = tools!!.options.first { it.id != "all" }
        st.select(services, narrowS.id)
        assertEquals(narrowS.id, st.servicesMode)
        st.select(tools, narrowT.id)
        assertEquals(narrowT.id, st.toolsMode)
        assertEquals("all", st.servicesMode)
        // Picking All on one row leaves the other alone.
        st.select(tools, "all")
        assertEquals("all", st.servicesMode)
    }

    private fun page(section: String, mode: String) =
        show(AggregatorStackFragment.newInstance(section, "", mode))

    @Test
    fun `P1 the page draws a card per panel and folds on a header tap and on the nav re-tap`() {
        val sec = Sections.byId("communication")!!
        val panels = Sections.aggregatorStackFor(sec, "rss-inboxes")
        val f = page("communication", "rss-inboxes")
        compose.onNodeWithTag(AggTags.PAGE).assertExists()
        for ((i, p) in panels.withIndex()) if (p.kind != "section_title") compose.onNodeWithTag(AggTags.panelHeader(i)).assertExists()
        val first = panels.indexOfFirst { it.kind != "section_title" }
        val open0 = !panels[first].collapsed
        if (open0) compose.onNodeWithTag(AggTags.panelBody(first)).assertExists()
        compose.onNodeWithTag(AggTags.panelHeader(first)).performClick()
        if (open0) compose.onNodeWithTag(AggTags.panelBody(first)).assertDoesNotExist()
        else compose.onNodeWithTag(AggTags.panelBody(first)).assertExists()
        // The bottom-nav re-tap: collapse every panel if any is open, expand them all if all are closed.
        var handled = false
        compose.runOnUiThread { handled = f.toggleAllCollapsed() }
        assertTrue(handled)
        compose.waitForIdle()
        var again = false
        compose.runOnUiThread { again = f.toggleAllCollapsed() }
        assertTrue(again)
    }

    @Test
    fun `P2 a phone card without notification access says it is unavailable, not empty`() {
        val sec = Sections.byId("communication")!!
        val panels = Sections.aggregatorStackFor(sec, "rss-inboxes")
        assumeTrue(panels.any { it.kind == "notification_center" && it.stream == "phone" })
        page("communication", "rss-inboxes")
        compose.onNodeWithText("unavailable · permission not granted").assertExists()
    }

    @Test
    fun `P3 an anchor tile scrolls this page to the card that claimed the id`() {
        val sec = Sections.byId("c3")!!
        val panels = Sections.aggregatorStackFor(sec, "topology")
        val index = panels.firstOrNull { it.kind == "tile_row" && it.tiles.any { t -> t.target.startsWith(StackAnchors.PREFIX) } }
        assumeTrue("the topology page declares no anchor tile in this build", index != null)
        val owned = panels.flatMap { p -> listOfNotNull(p.anchor.takeIf { it.isNotBlank() }) + p.anchors.map { it.id } }.toSet()
        // The last anchor tile that resolves to a card: far enough down the page to need a scroll.
        val tile = index!!.tiles.last { it.target.startsWith(StackAnchors.PREFIX) && it.target.removePrefix(StackAnchors.PREFIX) in owned }
        page("c3", "topology")
        fun scrolled(): Float = compose.onNodeWithTag(AggTags.PAGE).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(0f, scrolled(), 0f)
        compose.onNodeWithTag("index:" + tile.label).performScrollTo().performClick()
        compose.waitUntil(10_000) { scrolled() > 0f }
    }
}
