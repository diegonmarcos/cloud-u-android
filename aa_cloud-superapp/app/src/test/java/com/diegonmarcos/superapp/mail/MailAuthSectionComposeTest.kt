package com.diegonmarcos.superapp.mail

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Account ▸ Authentication, composed under the kit palette: the account form offers None, Bearer
 * token and Authelia sign-in, each reveals its own controls, and the status chip says where the account stands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailAuthSectionComposeTest : KitPageHarness() {

    private var state by mutableStateOf(MailAuthSectionState())
    private val modes = mutableListOf<MailAuthMode>()
    private var tests = 0
    private var signIns = 0
    private var signOuts = 0

    private fun show(initial: MailAuthSectionState = MailAuthSectionState()) {
        state = initial
        showKit {
            MailAuthSection(
                state = state,
                onMode = { modes += it; state = state.copy(mode = it) },
                onBearer = { state = state.copy(bearer = it) },
                onTest = { tests++ }, onSignIn = { signIns++ }, onSignOut = { signOuts++ },
            )
        }
    }

    @Test fun `all three methods are offered`() {
        show()
        for (m in MailAuthMode.entries) compose.onNodeWithTag(MailAuthTags.mode(m)).assertExists()
        assertEquals(listOf("NONE", "BEARER", "AUTHELIA"), MailAuthMode.entries.map { it.name })
    }

    @Test fun `choosing bearer shows the token field, reveal toggle, paste and test`() {
        show()
        compose.onNodeWithTag(MailAuthTags.mode(MailAuthMode.BEARER)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(MailAuthMode.BEARER), modes)
        compose.onNodeWithTag(MailAuthTags.TOKEN).assertExists()
        compose.onNodeWithTag(MailAuthTags.PASTE).assertExists()
        compose.onNodeWithTag(MailAuthTags.TEST).assertIsNotEnabled()      // nothing to test yet
        compose.onNodeWithTag(MailAuthTags.SHOW).assert(hasText("Show"))
        compose.onNodeWithTag(MailAuthTags.SHOW).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MailAuthTags.SHOW).assert(hasText("Hide"))
    }

    @Test fun `test connection is live once a token is present and reports its result`() {
        show(MailAuthSectionState(mode = MailAuthMode.BEARER, bearer = "tok", status = MailAuthStatus.TOKEN,
            testMessage = "401 Unauthorized", testFailed = true))
        compose.onNodeWithTag(MailAuthTags.TEST).assertIsEnabled().performClick()
        assertEquals(1, tests)
        compose.onNodeWithTag(MailAuthTags.RESULT).assert(hasText("401 Unauthorized"))
        compose.onNodeWithTag(MailAuthTags.CHIP).assert(hasText("Token"))
    }

    @Test fun `authelia mode offers sign in, and sign out only while signed in`() {
        show(MailAuthSectionState(mode = MailAuthMode.AUTHELIA, status = MailAuthStatus.NEEDS_SIGN_IN))
        compose.onNodeWithTag(MailAuthTags.SIGN_IN).performClick()
        assertEquals(1, signIns)
        compose.onNodeWithTag(MailAuthTags.SIGN_OUT).assertDoesNotExist()
        compose.onNodeWithTag(MailAuthTags.CHIP).assert(hasText("Needs sign-in"))

        compose.runOnUiThread { state = state.copy(status = MailAuthStatus.SIGNED_IN, signedInAt = 1_700_000_000_000L) }
        compose.waitForIdle()
        compose.onNodeWithTag(MailAuthTags.CHIP).assert(hasText("Signed in"))
        compose.onNodeWithTag(MailAuthTags.SIGN_OUT).performClick()
        assertEquals(1, signOuts)
    }

    @Test fun `none shows no credential controls`() {
        show()
        compose.onNodeWithTag(MailAuthTags.TOKEN).assertDoesNotExist()
        compose.onNodeWithTag(MailAuthTags.SIGN_IN).assertDoesNotExist()
        compose.onNodeWithTag(MailAuthTags.CHIP).assert(hasText("Connected"))
    }

    @Test fun `the account form hosts the auth section and wires both methods`() {
        val src = File("../../ab_cloud-libs-shared/libs/mail/src/main/java/com/diegonmarcos/superapp/mail/MailFragment.kt").readText()
        assertTrue("the form shows MailAuthSection", src.contains("MailAuthSection("))
        assertTrue("the form opens the Authelia sign-in", src.contains("AutheliaSignInDialog("))
        assertTrue("the form stores credentials encrypted", src.contains("MailAuthStore"))
        assertTrue("requests go through the account's client", src.contains("jmapClient(ctx)"))
        val ui = File("../../ab_cloud-libs-shared/libs/mail/src/main/java/com/diegonmarcos/superapp/mail/MailAuthUi.kt").readText()
        assertTrue("segmented choice", ui.contains("SingleChoiceSegmentedButtonRow"))
        assertTrue("the section lists every mode", ui.contains("MailAuthMode.entries"))
    }
}
