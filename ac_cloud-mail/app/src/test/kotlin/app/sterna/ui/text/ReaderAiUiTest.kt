package app.sterna.ui.text

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.sterna.ui.message.ReaderSummary
import app.sterna.ui.message.ReaderSummaryUi
import app.sterna.ui.message.ResumeBox
import app.sterna.ui.settings.LANGUAGE_FIELD_TAG
import app.sterna.ui.settings.LanguageDropdown
import app.sterna.ui.settings.languageRowTag
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
 * The language dropdown and the summary box RENDERED, and clicked. English strings: Robolectric runs
 * in the default locale, and the labels asserted are the ones in values/strings.xml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderAiUiTest {

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

    // ---- the dropdown -------------------------------------------------------------------------

    @Test fun `the dropdown shows English by default and offers the languages`() {
        val picked = mutableListOf<String>()
        compose.setContent { MaterialTheme { LanguageDropdown("Translate to", "", { picked += it }) } }
        compose.onNodeWithText("English").assertExists()
        compose.onNodeWithTag(LANGUAGE_FIELD_TAG).performClick()
        compose.onNodeWithTag(languageRowTag("es")).assertExists()
        compose.onNodeWithTag(languageRowTag("de")).assertExists()
        assertTrue("opening picks nothing", picked.isEmpty())
    }

    @Test fun `choosing a language reports its tag and shows its name`() {
        val picked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                var selected by remember { mutableStateOf("") }
                LanguageDropdown("Translate to", selected, { selected = it; picked += it })
            }
        }
        compose.onNodeWithTag(LANGUAGE_FIELD_TAG).performClick()
        compose.onNodeWithTag(languageRowTag("es")).performClick()
        assertEquals(listOf("es"), picked)
        compose.onNodeWithText("Spanish").assertExists()
        compose.onNodeWithTag(languageRowTag("es")).assertDoesNotExist() // the menu closed
    }

    // ---- the summary box ----------------------------------------------------------------------

    @Test fun `no summary, no box`() {
        compose.setContent { MaterialTheme { ResumeBox(ReaderSummaryUi.None, "m1") } }
        compose.onNodeWithText("AI Resume").assertDoesNotExist()
    }

    @Test fun `an existing summary is shown expanded and collapses and expands again`() {
        compose.setContent {
            MaterialTheme { ResumeBox(ReaderSummaryUi(ReaderSummary(text = "The offer ends Friday.")) {}, "m1") }
        }
        compose.onNodeWithText("AI Resume").assertExists()
        compose.onNodeWithText("The offer ends Friday.").assertExists()       // expanded by default
        compose.onNodeWithText("AI Resume").performClick()
        compose.onNodeWithText("The offer ends Friday.").assertDoesNotExist() // collapsed, header stays
        compose.onNodeWithText("AI Resume").assertExists()
        compose.onNodeWithText("AI Resume").performClick()
        compose.onNodeWithText("The offer ends Friday.").assertExists()
    }

    @Test fun `a running summary says so and an error is shown with its reason`() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                var failed by remember { mutableStateOf(false) }
                if (!failed) ResumeBox(ReaderSummaryUi(ReaderSummary(running = true)) {}, "m1")
                else ResumeBox(ReaderSummaryUi(ReaderSummary(error = "no API key for OpenRouter")) { dismissed = true }, "m2")
                androidx.compose.material3.TextButton(onClick = { failed = true }) { androidx.compose.material3.Text("flip") }
            }
        }
        compose.onNodeWithText("Working…").assertExists()
        compose.onNodeWithText("flip").performClick()
        compose.onNodeWithText("no API key for OpenRouter").assertExists()
        compose.onNodeWithText("Close").performClick()
        assertTrue(dismissed)
    }
}
