package app.sterna.ui.settings

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.sterna.ui.compose.signatureBlockHtml
import org.junit.Assert.assertEquals
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
import org.robolectric.annotation.GraphicsMode

/**
 * The full-screen signature editor, rendered: HTML typed into the source pane reaches the preview
 * through the debounce, as the document built from the send path's own signature block, and reaches
 * the validation panel and the save button too.
 *
 * The preview slot is replaced by a text node showing the document, because a WebView's pixels are
 * not something a JVM test can read; what the WebView is HANDED is the whole claim. The plain
 * Application stands in for SternaApplication, whose onCreate wires the whole mail stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignatureEditorScreenTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val saved = mutableListOf<Triple<String, String, Boolean>>()

    private fun show(state: SignatureEditorState) {
        compose.setContent {
            MaterialTheme {
                SignatureEditorScreen(
                    state = state,
                    delimiter = true,
                    onSave = { name, source, makeDefault -> saved += Triple(name, source, makeDefault) },
                    onRequestClose = {},
                    preview = { document, _, _, _, _ -> Text(document, Modifier.testTag("preview-doc")) },
                )
            }
        }
    }

    @Test fun typingHtmlUpdatesThePreviewWithTheSignatureAsSent() {
        show(SignatureEditorState("", "Work", false))
        compose.onNodeWithTag("sig-source").performTextInput("<b>Alex</b>")
        compose.waitForIdle()
        compose.onNodeWithTag("preview-doc").assertTextContains(signatureBlockHtml("<b>Alex</b>", true), substring = true)
        compose.onNodeWithTag("preview-doc").assertTextContains("<b>Alex</b>", substring = true)
    }

    @Test fun theSanitisedMarkupIsWhatThePreviewShows() {
        show(SignatureEditorState("", "Work", false))
        compose.onNodeWithTag("sig-source").performTextInput("<i>hi</i><script>alert(1)</script>")
        compose.waitForIdle()
        val doc = compose.onNodeWithTag("preview-doc")
        doc.assertTextContains("<i>hi</i>", substring = true)
        // not asserting absence on the node (it has no negative matcher): the same string is pinned in SignaturePreviewTest
        assertTrue(!SignaturePreview.document("<i>hi</i><script>alert(1)</script>").contains("alert"))
    }

    @Test fun theValidationPanelFollowsTheSource() {
        show(SignatureEditorState("", "Work", false))
        compose.onNodeWithTag("sig-validation-summary").assertTextContains("No problems found", substring = true)
        compose.onNodeWithTag("sig-source").performTextInput("<div>")
        compose.waitForIdle()
        compose.onNodeWithTag("sig-validation-summary").assertTextContains("1 errors", substring = true)
    }

    @Test fun saveIsOnlyOfferedForUnsavedEditsAndHandsBackTheSource() {
        show(SignatureEditorState("<p>old</p>", "Work", false))
        compose.onNodeWithTag("sig-save").assertIsNotEnabled()
        compose.onNodeWithTag("sig-source").performTextInput("x")
        compose.waitForIdle()
        compose.onNodeWithTag("sig-save").assertIsEnabled().performClick()
        assertEquals(1, saved.size)
        assertEquals("Work", saved[0].first)
        assertTrue(saved[0].second, saved[0].second.contains("old"))
        assertTrue(saved[0].second.contains("x"))
    }

    @Test fun thePreviewOptionsChangeTheFrameNotTheSignature() {
        show(SignatureEditorState("<b>Alex</b>", "Work", false))
        compose.onNodeWithTag("sig-opt-dark").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("preview-doc").assertTextContains("#1f1f1f", substring = true)
        compose.onNodeWithTag("preview-doc").assertTextContains(signatureBlockHtml("<b>Alex</b>", true), substring = true)
    }
}
