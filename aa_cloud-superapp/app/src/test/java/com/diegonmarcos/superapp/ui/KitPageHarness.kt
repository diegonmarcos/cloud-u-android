package com.diegonmarcos.superapp.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.uikit.kitComposeView
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #773 — the host every migrated-page test stands on: a FragmentActivity, a page added the way
 * the shell adds pages, and the compose rule that reads the semantics tree it draws. Pages are
 * KitComposeFragments, so this exercises the production ComposeView, lifecycle and palette read.
 */
abstract class KitPageHarness {

    protected val compose = createAndroidComposeRule<FragmentActivity>()

    // The app manifest declares no bare FragmentActivity; register it with Robolectric's package
    // manager rather than shipping a test activity in the APK (ShellIslandHarness does the same).
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    protected fun <F : Fragment> show(page: F): F {
        compose.runOnUiThread {
            compose.activity.supportFragmentManager.beginTransaction()
                .add(android.R.id.content, page).commitNow()
        }
        compose.waitForIdle()
        return page
    }

    /** Kit composables alone, through the View-interop shim a half-migrated page uses. */
    protected fun showKit(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val a = compose.activity
            a.setContentView(a.kitComposeView(LauncherPalette.kit(a), content))
        }
        compose.waitForIdle()
    }
}
