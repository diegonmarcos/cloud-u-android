package com.diegonmarcos.cloudme

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Parcel
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
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

/** A page Fragment standing in for Health / Fin. */
class LeakProbeFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = TextView(requireContext()).apply { text = "probe" }
}

/**
 * #1150: switching between Fragment-hosted pages must not accumulate Fragments. A new page key used to
 * reach the OLD container (the AndroidView factory runs once), so every recomposition added another
 * fragment; ~400 of them made the saved state 680 KB and the activity stop threw TransactionTooLargeException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostedFragmentLeakTest {

    private val compose = createAndroidComposeRule<FragmentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private var page by mutableStateOf("health/0")

    @Test
    fun switchingPagesKeepsOneFragmentAndSmallSavedState() {
        compose.setContent { HostedFragment(page, Modifier) { LeakProbeFragment() } }
        compose.waitForIdle()
        repeat(60) { n ->
            page = "health/${n + 1}"
            compose.waitForIdle()
        }
        val fm = compose.activity.supportFragmentManager
        compose.runOnUiThread { fm.executePendingTransactions() }
        assertTrue("fragments after 60 switches: ${fm.fragments.size}", fm.fragments.size <= 1)

        val saved = Bundle()
        compose.runOnUiThread { compose.activity.savedStateRegistry.performSave(saved) }
        val parcel = Parcel.obtain()
        parcel.writeBundle(saved)
        val size = parcel.dataSize()
        parcel.recycle()
        assertTrue("saved state is $size bytes", size < 100 * 1024)
    }
}
