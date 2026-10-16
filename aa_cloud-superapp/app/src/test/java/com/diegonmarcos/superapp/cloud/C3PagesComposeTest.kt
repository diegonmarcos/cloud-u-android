package com.diegonmarcos.superapp.cloud

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — C3 Health and C3 Mesh, Compose since their rows were inflated Views
 * (fragment_c3_health / fragment_c3_mesh / item_c3_*), proven under the app's real declarations.
 *
 *   H1 the standalone page draws the status line and both section headers
 *   H2 a single-scope card (embedded in the Infos stack) draws NO status line and NO header
 *   H3 every declared service is drawn, once per declaration, in the scope that owns it
 *   M1 one block per declared transport, and the status line counts nodes/peerings/transports
 *   M2 every declared node of the mesh is drawn at least once (a node may sit in two transports)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class C3PagesComposeTest : KitPageHarness() {

    @Test
    fun `H1 the standalone page draws the status line and both headers`() {
        assertTrue("services_public.json is empty - nothing to draw", Sections.publicServices().isNotEmpty())
        show(C3HealthFragment.newInstance())
        compose.onNodeWithTag(C3HealthFragment.TAG_STATUS).assertExists()
        compose.onNodeWithTag(C3HealthFragment.TAG_PUBLIC).assertExists()
        compose.onNodeWithTag(C3HealthFragment.TAG_PRIVATE).assertExists()
    }

    @Test
    fun `H2 a single-scope card has no status line and no header`() {
        show(C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PUBLIC))
        compose.onNodeWithTag(C3HealthFragment.TAG_STATUS).assertDoesNotExist()
        compose.onNodeWithTag(C3HealthFragment.TAG_PUBLIC).assertDoesNotExist()
        compose.onNodeWithTag(C3HealthFragment.TAG_PRIVATE).assertDoesNotExist()
    }

    @Test
    fun `H3 every declared service is drawn once per declaration in its scope`() {
        val pub = Sections.publicServices()
        val priv = Sections.privateServices()
        show(C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PUBLIC))
        for (n in pub.map { it.name }.toSet()) {
            compose.onAllNodesWithTag(C3HealthFragment.rowTag(n)).assertCountEquals(pub.count { it.name == n })
        }
        for (n in priv.map { it.name }.toSet() - pub.map { it.name }.toSet()) {
            compose.onAllNodesWithTag(C3HealthFragment.rowTag(n)).assertCountEquals(0)
        }
    }

    @Test
    fun `M1 one block per transport and a counting status line`() {
        val mesh = Sections.mesh()
        assertTrue("mesh.json declares no transport", mesh.transports.isNotEmpty())
        show(C3MeshFragment.newInstance())
        compose.onNodeWithTag(MeshView.TAG_STATUS).assertExists()
        for (t in mesh.transports) compose.onNodeWithTag(MeshView.blockTag(t.name)).assertExists()
    }

    @Test
    fun `M2 every declared node is drawn`() {
        val mesh = Sections.mesh()
        show(C3MeshFragment.newInstance())
        for (n in mesh.nodes.map { it.name }.toSet()) {
            assertTrue("node $n is not drawn in any transport",
                compose.onAllNodesWithTag(MeshView.nodeTag(n)).fetchSemanticsNodes().isNotEmpty())
        }
    }
}
