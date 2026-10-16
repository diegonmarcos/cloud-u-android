package app.sterna.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.sterna.core.data.account.GatewayMode
import app.sterna.core.data.account.GatewayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Account ▸ Authentication: the account form offers None, Bearer token and Authelia sign-in, the
 * chip says where the account stands, and Authelia exposes sign-in / sign-out. Compose, no Views.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AccountAuthSectionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    // The app's manifest has no entry for the activity the compose rule launches.
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

    private val modes = mutableListOf<GatewayMode>()
    private var signIns = 0
    private var signOuts = 0

    private fun show(mode: GatewayMode, status: GatewayStatus, at: Long = 0L) = compose.setContent {
        MaterialTheme {
            AccountAuthSection(mode, status, at, { modes += it }, { signIns++ }, { signOuts++ })
        }
    }

    @Test fun `both methods are offered next to none`() {
        show(GatewayMode.NONE, GatewayStatus.CONNECTED)
        for (m in GatewayMode.entries) compose.onNodeWithTag(AccountAuthTags.mode(m)).assertExists()
        compose.onNodeWithTag(AccountAuthTags.mode(GatewayMode.BEARER)).performClick()
        compose.onNodeWithTag(AccountAuthTags.mode(GatewayMode.AUTHELIA)).performClick()
        assertEquals(listOf(GatewayMode.BEARER, GatewayMode.AUTHELIA), modes)
        compose.onNodeWithTag(AccountAuthTags.CHIP).assert(hasText("Connected"))
    }

    @Test fun `authelia needing sign-in offers sign in and no sign out`() {
        show(GatewayMode.AUTHELIA, GatewayStatus.NEEDS_SIGN_IN)
        compose.onNodeWithTag(AccountAuthTags.SIGN_OUT).assertDoesNotExist()
        compose.onNodeWithTag(AccountAuthTags.SIGN_IN).performClick()
        assertEquals(1, signIns)
        compose.onNodeWithTag(AccountAuthTags.CHIP).assert(hasText("Needs sign-in"))
    }

    @Test fun `a signed-in authelia account can sign out`() {
        show(GatewayMode.AUTHELIA, GatewayStatus.SIGNED_IN, at = 1_700_000_000_000L)
        compose.onNodeWithTag(AccountAuthTags.SIGN_OUT).performClick()
        assertEquals(1, signOuts)
        compose.onNodeWithTag(AccountAuthTags.CHIP).assert(hasText("Signed in"))
    }

    @Test fun `bearer shows the token chip`() {
        show(GatewayMode.BEARER, GatewayStatus.TOKEN)
        compose.onNodeWithTag(AccountAuthTags.CHIP).assert(hasText("Token"))
    }

    @Test fun `the account screen hosts the section and the list shows the chip`() {
        val screen = File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt").readText()
        assertTrue(screen.contains("AccountAuthSection("))
        assertTrue(screen.contains("AutheliaSignInDialog("))
        assertTrue(screen.contains("gateway = "))
        val keys = Regex("""R\.string\.gw_\w+""").findAll(File(root, "app/src/main/kotlin/app/sterna/ui/settings/AccountAuthSection.kt").readText())
            .map { it.value.removePrefix("R.string.") }.toSet()
        for (loc in listOf("", "-de", "-es", "-fr", "-it", "-nl", "-pl", "-pt", "-ru")) {
            val xml = File(root, "app/src/main/res/values$loc/strings.xml").readText()
            for (k in keys) assertTrue("values$loc lacks $k", xml.contains("name=\"$k\""))
        }
    }
}
