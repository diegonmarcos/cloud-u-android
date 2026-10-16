package com.diegonmarcos.superapp.recovery

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.updater.Advisory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the Recovery surfaces, Compose since they were programmatic Views.
 *
 *   B1 a STUCK/WARN advisory shows its title, its detail and "Fix it — install directly"; Dismiss is always there
 *   B2 a posted (feed, INFO) message has a button only when it carries a link ("Open link")
 *   B3 the feed source line shows for a feed advisory and not for a local one
 *   B4 the action and Dismiss buttons call back, and only their own callback
 *   B5 the severity picks the kit's state colour: STUCK bad, WARN warn, INFO accent
 *   R1 the recovery list puts the preselected app first, this app second, the rest in order
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryComposeTest : KitPageHarness() {

    private fun item(sev: Advisory.Severity, source: String = "local", link: String? = null) =
        Advisory.Item("id-$sev-$source", "mail", "Mail is stuck", "  Update path is broken.  ", sev, source, link)

    private fun draw(i: Advisory.Item, act: () -> Unit = {}, dismiss: () -> Unit = {}) {
        compose.setContent { CloudKitTheme(RecoveryPalette.kit) { RecoveryBanner.Card(i, act, dismiss) } }
        compose.waitForIdle()
    }

    @Test
    fun `B1 a recovery advisory offers the fix and dismissal`() {
        draw(item(Advisory.Severity.STUCK))
        compose.onNodeWithTag(RecoveryBanner.TAG_CARD).assert(hasText("Mail is stuck"))
        compose.onNodeWithTag(RecoveryBanner.TAG_CARD).assert(hasText("Update path is broken."))
        compose.onNodeWithTag(RecoveryBanner.TAG_ACTION).assert(hasText("Fix it — install directly"))
        compose.onNodeWithTag(RecoveryBanner.TAG_DISMISS).assert(hasText("Dismiss"))
    }

    @Test
    fun `B2 a posted message has a button only when it has somewhere to go`() {
        assertNull(RecoveryBanner.actionLabel(item(Advisory.Severity.INFO, "feed")))
        assertEquals("Open link", RecoveryBanner.actionLabel(item(Advisory.Severity.INFO, "feed", "https://x.test/y")))
        assertEquals("Fix it — install directly", RecoveryBanner.actionLabel(item(Advisory.Severity.WARN, "feed")))
        draw(item(Advisory.Severity.INFO, "feed"))
        compose.onAllNodesWithTag(RecoveryBanner.TAG_ACTION).assertCountEquals(0)
        compose.onNodeWithTag(RecoveryBanner.TAG_DISMISS).assert(hasText("Dismiss"))
    }

    @Test
    fun `B3 the fleet-channel line is for feed advisories only`() {
        draw(item(Advisory.Severity.WARN, "feed"))
        compose.onNodeWithTag(RecoveryBanner.TAG_SOURCE).assert(hasText("advisory from the fleet channel"))
    }

    @Test
    fun `B3b a local advisory has no source line`() {
        draw(item(Advisory.Severity.WARN, "local"))
        compose.onAllNodesWithTag(RecoveryBanner.TAG_SOURCE).assertCountEquals(0)
    }

    @Test
    fun `B4 each button calls only its own callback`() {
        var acted = 0; var dismissed = 0
        draw(item(Advisory.Severity.STUCK), { acted++ }, { dismissed++ })
        compose.onNodeWithTag(RecoveryBanner.TAG_ACTION).performClick()
        assertEquals(1 to 0, acted to dismissed)
        compose.onNodeWithTag(RecoveryBanner.TAG_DISMISS).performClick()
        assertEquals(1 to 1, acted to dismissed)
    }

    @Test
    fun `B5 severity maps to the state colour`() {
        val p = RecoveryPalette.kit
        assertEquals(p.bad, RecoveryBanner.accentOf(Advisory.Severity.STUCK, p))
        assertEquals(p.warn, RecoveryBanner.accentOf(Advisory.Severity.WARN, p))
        assertEquals(p.accent, RecoveryBanner.accentOf(Advisory.Severity.INFO, p))
    }

    @Test
    fun `R1 preselected first then this app then the rest`() {
        val apps = listOf("a" to "p.a", "self" to "p.self", "b" to "p.b", "c" to "p.c")
        fun order(pre: String?) = RecoveryActivity.ordered(apps, { it.first }, { it.second }, pre, "p.self").map { it.first }
        assertEquals(listOf("c", "self", "a", "b"), order("c"))
        assertEquals(listOf("self", "a", "b", "c"), order(null))
        assertEquals(listOf("self", "a", "b", "c"), order("self"))
        assertEquals(listOf("self", "a", "b", "c"), order("nope"))
    }
}
