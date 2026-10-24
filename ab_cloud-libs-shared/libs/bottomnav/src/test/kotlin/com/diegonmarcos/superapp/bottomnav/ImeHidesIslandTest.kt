package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/** #1160 the soft keyboard HIDES the island and the search-variant bar (it used to lift them above itself). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImeHidesIslandTest {
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

    @Test fun `the island and the search bar vanish while the keyboard is up and return after`() {
        var ime by mutableStateOf(false)
        compose.setContent {
            val entries = listOf(
                BottomNavEntry("home", "Home", rememberVectorPainter(Icons.Filled.Home)),
                BottomNavEntry("mail", "Mail", rememberVectorPainter(Icons.Filled.Mail), tinted = false),
            )
            CompositionLocalProvider(LocalNavImeVisible provides ime) {
                androidx.compose.foundation.layout.Column {
                    @Suppress("DEPRECATION")
                    BottomNavIsland(entries, "home", {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_ISLAND).assertExists()
        ime = true
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_ISLAND).assertDoesNotExist()
        ime = false
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_ISLAND).assertExists()
    }

    @Test fun `the search-html variant hides too`() {
        var ime by mutableStateOf(true)
        compose.setContent {
            val entries = listOf(BottomNavEntry("home", "Home", rememberVectorPainter(Icons.Filled.Home)))
            CompositionLocalProvider(LocalNavImeVisible provides ime) { SearchHtmlIsland(entries, "home", {}, dark = true) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_ISLAND).assertDoesNotExist()
        ime = false
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_ISLAND).assertExists()
    }
}
