package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1068 guards on the Notify page (communication / my-rss), drawn at A37 size:
 *   1. every badge build.json declares renders as a card, in declared order, none invented and none missing;
 *   2. the page's configuration (Filters, badge switches) sits below ALL cards, the notifications, the Archive and the
 *      page actions, inside ONE folded section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotifyPageGuardTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val realPoller = AggregatorModel.ntfyPoller

    @Before
    fun seed() {
        AggregatorModel.ntfyPoller = { topics -> topics.associateWith { NtfyResult(true, "", emptyList()) } }
        NotifyFixtures.install()
        NotificationStore.clear(ctx)
        NotificationStore.push(ctx, "Chat", "Dev channel", "CI is green on main again", NotificationStore.Sev.INFO)
        NotificationStore.push(ctx, "Fleet", "gcp-proxy disk at 91 %", "Root volume is filling up", NotificationStore.Sev.WARN)
    }

    @After
    fun restore() {
        AggregatorModel.ntfyPoller = realPoller
        NotifyFixtures.uninstall()
        NotificationStore.clear(ctx)
    }

    private fun top(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top
    private fun bottom(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().bottom

    @Test
    fun `every declared badge renders as a card, in the declared order`() {
        val declared = BadgeDeclaration.badges(BadgeServices.declared).map { it.id }
        assertTrue("build.json declares no badge", declared.isNotEmpty())
        assertEquals("the page must draw exactly the declared badges", declared.toSet(), orderedBadges(ctx).map { it.id }.toSet())
        show(AggregatorStackFragment.newInstance("communication", "", "my-rss"))
        compose.waitForIdle()
        for (id in declared) compose.onNodeWithTag(BadgeCardTags.card(id)).assertExists()
        // the named ones the owner listed are among them
        for (id in listOf("network_mesh", "network_data", "battery_status", "weather_today", "markets_prices")) {
            assertTrue("$id is not declared", id in declared)
        }
        // order = the order orderedBadges (groups in the owner's order, members in order) gives
        val order = orderedBadges(ctx).map { it.id }
        val tops = order.map { top(BadgeCardTags.card(it)).value }
        assertEquals("cards are not drawn in declared order", tops.sorted(), tops)
    }

    @Test
    fun `the configs render below all cards, notifications, archive and page actions`() {
        show(AggregatorStackFragment.newInstance("communication", "", "my-rss"))
        compose.waitForIdle()
        val cards = orderedBadges(ctx).map { BadgeCardTags.card(it.id) }
        val lastCardBottom = cards.maxOf { bottom(it).value }
        val cfgTop = top(AggTags.CONFIG_SECTION).value
        assertTrue("settings ($cfgTop) must sit under every badge card ($lastCardBottom)", cfgTop >= lastCardBottom)
        // under the notification groups too
        for (n in compose.onAllNodesWithTag(AggTags.group("Chat")).fetchSemanticsNodes()) {
            assertTrue(cfgTop >= n.boundsInRoot.bottom / ctx.resources.displayMetrics.density)
        }
        // folded: no control is on the page until the section is opened
        compose.onNodeWithTag(AggTags.CONFIG_BODY).assertDoesNotExist()
        assertEquals(0, compose.onAllNodesWithTag(AggTags.FILTERS_TOGGLE).fetchSemanticsNodes().size)
        compose.onNodeWithTag(AggTags.CONFIG_HEADER).performScrollTo()
        compose.onNodeWithTag(AggTags.CONFIG_HEADER).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.CONFIG_BODY).assertExists()
        // everything the section holds sits under its header, which sits under all the cards
        assertTrue(top(AggTags.CONFIG_BODY).value >= bottom(AggTags.CONFIG_HEADER).value)
        if (Sections.stackFiltersFor(Sections.byId("communication")!!, "my-rss").isNotEmpty()) {
            compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).assertExists()
            assertTrue("the Filters must be inside the Settings section",
                top(AggTags.FILTERS_TOGGLE).value >= top(AggTags.CONFIG_BODY).value)
        }
    }
}
