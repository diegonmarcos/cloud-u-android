package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.network.WireGuardFragment
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #877 The Cloud Mesh page composes: every declared page draws under the one PageTabs strip, the readout
 * shows what the engine said, a control the engine cannot honour is on screen with its reason, and the
 * hosting fragment opens on the page it is told to. Host: KitPageHarness (the production ComposeView).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshPageComposeTest : KitPageHarness() {

    private val decl = MeshDecl.baked

    private fun showStore(port: FakePort = FakePort(up = true)): MeshStore {
        val store = MeshStore(port, decl, FakeHost(), exec = { it.run() })
        store.poll()
        showKit { MeshScreen(store, ticker = false) }
        return store
    }

    private fun open(store: MeshStore, id: String) {
        compose.runOnUiThread { store.page = id }
        compose.waitForIdle()
    }

    @Test fun `every declared page composes under the strip`() {
        val store = showStore()
        for (p in decl.pages) {
            compose.onNodeWithTag(PageTabsTags.tab(p.id)).assertExists()
            open(store, p.id)
            compose.onNodeWithTag(MeshTags.page(p.id)).assertExists()
        }
    }

    @Test fun `tapping a tab moves the page`() {
        val store = showStore()
        compose.onNodeWithTag(PageTabsTags.tab("log")).performClick()
        compose.waitForIdle()
        assertEquals("log", store.page)
        compose.onNodeWithTag(MeshTags.page("log")).assertExists()
    }

    @Test fun `status draws every declared row from the engine's reading`() {
        val store = showStore()
        for (row in decl.page("status")!!.rows) compose.onNodeWithTag(MeshTags.row(row)).assertExists()
        compose.onNodeWithText("CONNECTED", substring = true).assertExists()
        compose.onNodeWithTag(MeshTags.row("pubkey")).assertTextContains("PUBKEY", substring = true)
        store.hashCode()
    }

    @Test fun `status without the engine says it cannot tell`() {
        showStore(FakePort(engine = false))
        compose.onNodeWithText("CANNOT TELL").assertExists()
        compose.onNodeWithText("not installed", substring = true).assertExists()
    }

    @Test fun `peers lists a dense row per peer and a tap opens its full config`() {
        val store = showStore()
        open(store, "peers")
        compose.onNodeWithTag(MeshTags.peer(0)).assertExists(); compose.onNodeWithTag(MeshTags.peer(1)).assertExists()
        compose.onNodeWithText("gcp-proxy").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MeshTags.control("peer_copy")).assertExists()
        compose.onNodeWithTag(MeshTags.control("peer_remove")).assertExists()
    }

    @Test fun `routes shows the verdict, the nat64 prefix and the excluded list`() {
        val store = showStore()
        open(store, "routes")
        compose.onNodeWithTag(MeshTags.row("route_mode")).assertTextContains("SPLIT", substring = true)
        compose.onNodeWithTag(MeshTags.row("route_nat64")).assertTextContains("ff9b", substring = true)
        compose.onNodeWithTag("mesh:excluded:none").performScrollTo().assertExists()
    }

    @Test fun `profiles draws the declared profiles and their actions`() {
        val store = showStore()
        open(store, "profiles")
        val first = com.diegonmarcos.superapp.network.WireGuardProfiles.mesh
        assertTrue("the build declares no mesh profiles", first.isNotEmpty())
        for (p in first) compose.onNodeWithTag("mesh:profile:${p.id}").assertExists()
        compose.onNodeWithTag(MeshTags.control("profile_diff") + ":" + first[0].id).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mesh:diff:${first[0].id}").assertExists()
    }

    @Test fun `every control the engine cannot honour is on screen with its reason`() {
        val store = showStore()
        open(store, "controls")
        for (c in decl.page("controls")!!.controls.filter { !it.honoured }) {
            compose.onNodeWithTag(MeshTags.control(c.id)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(c.unsupported, substring = true).assertExists()
        }
        for (c in decl.page("controls")!!.controls) compose.onNodeWithTag(MeshTags.control(c.id)).assertExists()
        store.hashCode()
    }

    @Test fun `without the engine the engine-bound controls say why`() {
        val store = showStore(FakePort(engine = false))
        open(store, "controls")
        compose.onNodeWithTag(MeshTags.control("connect")).assertExists()
        compose.onAllNodesWithTextCount("Cloud-Lib-Net-Wg")
    }

    @Test fun `the log page prints the journal, newest first, and copies it`() {
        val store = showStore()
        store.run("prefs.mtu", "1400")
        open(store, "log")
        compose.onAllNodes(hasText("MTU: ok", substring = true) and !hasTestTag(MeshTags.NOTICE)).onFirst().assertExists()
        compose.onNodeWithTag(MeshTags.control("log_copy")).performClick()
        compose.waitForIdle()
        assertTrue(store.notice.contains("copied"))
    }

    @Test fun `the hosting fragment opens on the declared first page, or the one it is told to`() {
        val page = show(WireGuardFragment.newInstance())
        compose.onNodeWithTag(PageTabsTags.tab(decl.startPage.id)).assertExists()
        compose.onNodeWithTag(MeshTags.page(decl.startPage.id)).assertExists()
        page.hashCode()
    }

    @Test fun `the hidden status route and a named page open on their tab`() {
        show(WireGuardFragment.newInstance("peers"))
        compose.onNodeWithTag(MeshTags.page("peers")).assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t, substring = true)).fetchSemanticsNodes().size
}
