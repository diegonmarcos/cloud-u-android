package app.sterna.ui.settings

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.sterna.core.data.account.AccountEndpoint
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointState
import com.diegonmarcos.superapp.fleetendpoints.MailEndpointsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1090 Account ▸ Endpoints: the mesh URL, the public URL and the bearer MASKED as Cloud Account synced them,
 * the account's endpoint policy, the route chip, and a Sync button; an account that is not a fleet account says
 * so and keeps its one address. No marker ever reaches the screen. Compose, no Views.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AccountEndpointsSectionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile } ?: error("checkout not found")
    }

    private val marker = "fleet-SECRET-tok-123"
    private val policies = mutableListOf<EndpointPolicy>()
    private var syncs = 0
    private val fleet = AccountEndpoint("me@example.org", "https://stalwart.app:2443", "https://jmap.example.org", bearer = marker)

    private fun show(e: AccountEndpoint?, state: EndpointState = EndpointState.MESH, syncing: Boolean = false, message: String = "") = compose.setContent {
        MaterialTheme { AccountEndpointsSection(e, state, syncing, message, { policies += it }, { syncs++ }) }
    }

    @Test fun `a fleet account shows both endpoints and the bearer masked`() {
        show(fleet)
        compose.onNodeWithTag(AccountEndpointTags.MESH).assert(hasText("https://stalwart.app:2443"))
        compose.onNodeWithTag(AccountEndpointTags.PUBLIC).assert(hasText("https://jmap.example.org"))
        compose.onNodeWithTag(AccountEndpointTags.BEARER).assert(hasText(MailEndpointsContract.MASK))
        compose.onNodeWithTag(AccountEndpointTags.CHIP).assert(hasText("Mesh"))
    }

    @Test fun `no part of the screen carries the marker`() {
        show(fleet)
        val tree = compose.onRoot().printToString()
        assertFalse(tree.contains(marker))
        compose.onNodeWithTag(AccountEndpointTags.BEARER).assert(hasText(MailEndpointsContract.MASK))
    }

    @Test fun `the mask does not reveal the length and an empty bearer shows a dash`() {
        assertEquals(bearerDisplay("a"), bearerDisplay(marker))
        show(fleet.copy(bearer = ""))
        compose.onNodeWithTag(AccountEndpointTags.BEARER).assert(hasText("—"))
    }

    @Test fun `the policy is offered and a tap reports it`() {
        show(fleet.copy(policy = EndpointPolicy.MESH_FIRST))
        for (p in EndpointPolicy.entries) compose.onNodeWithTag(AccountEndpointTags.policy(p)).assertExists()
        compose.onNodeWithTag(AccountEndpointTags.policy(EndpointPolicy.PUBLIC_ONLY)).performClick()
        compose.onNodeWithTag(AccountEndpointTags.policy(EndpointPolicy.MESH_ONLY)).performClick()
        assertEquals(listOf(EndpointPolicy.PUBLIC_ONLY, EndpointPolicy.MESH_ONLY), policies)
    }

    @Test fun `the chip says which route the mail is on`() {
        var shown by mutableStateOf(EndpointState.MESH)
        compose.setContent { MaterialTheme { EndpointChip(shown) } }
        for ((s, word) in listOf(EndpointState.MESH to "Mesh", EndpointState.PUBLIC to "Public", EndpointState.OFFLINE to "Offline", EndpointState.UNKNOWN to "Checking…")) {
            shown = s
            compose.waitForIdle()
            compose.onNodeWithTag(AccountEndpointTags.CHIP).assert(hasText(word))
        }
    }

    @Test fun `an account that is not a fleet account says so and still offers sync`() {
        show(null, EndpointState.UNKNOWN)
        compose.onNodeWithTag(AccountEndpointTags.MESH).assertDoesNotExist()
        compose.onNodeWithTag(AccountEndpointTags.policy(EndpointPolicy.MESH_FIRST)).assertDoesNotExist()
        compose.onNodeWithTag(AccountEndpointTags.SYNC).performClick()
        assertEquals(1, syncs)
    }

    @Test fun `sync is off while it runs and its answer is shown`() {
        show(fleet, syncing = true, message = "Synced from Cloud Account: 1 account endpoints.")
        compose.onNodeWithTag(AccountEndpointTags.SYNC).assertIsNotEnabled()
        compose.onNodeWithTag(AccountEndpointTags.MESSAGE).assert(hasText("Synced from Cloud Account: 1 account endpoints."))
    }

    @Test fun `the account screen hosts the section and the list shows the route chip`() {
        val screen = File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt").readText()
        assertTrue(screen.contains("AccountEndpointsSection("))
        assertTrue(screen.contains("endpoint = "))
        val keys = Regex("""R\.string\.(ep_\w+)""").findAll(File(root, "app/src/main/kotlin/app/sterna/ui/settings/AccountEndpointsSection.kt").readText())
            .map { it.groupValues[1] }.toSet() + setOf("ep_title", "ep_sync_done", "ep_sync_missing", "ep_sync_refused")
        for (loc in listOf("", "-de", "-es", "-fr", "-it", "-nl", "-pl", "-pt", "-ru")) {
            val xml = File(root, "app/src/main/res/values$loc/strings.xml").readText()
            for (k in keys) assertTrue("values$loc lacks $k", xml.contains("name=\"$k\""))
        }
    }
}
