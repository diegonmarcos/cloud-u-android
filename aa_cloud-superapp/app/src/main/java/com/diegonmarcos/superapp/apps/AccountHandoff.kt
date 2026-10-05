package com.diegonmarcos.superapp.apps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.ProfileFragment

/**
 * #867 Account is its own app now: Cloud Account (ac_cloud-account), built from the same
 * libs:account this app hosts.
 *
 * The Configs Account entry launches it (build.json: action extapp:cloud-account, with
 * ui.external_apps[id=cloud-account] offering the APK when it is absent). This hand-off covers
 * the page: route that stays (SectionPages, config/profile): with Cloud Account installed it is
 * a button that opens it, otherwise it is the Account page itself, embedded, exactly as before,
 * so Account works from the first install of SuperApp.
 *
 * The data follows the same rule: [AccountData.migrate] copies Cloud Account's configs, profile
 * and S/R/L files into this app once, and the imported-configs blob reads through to it while
 * this app's own is empty (AccountHost.readThrough, set in App.kt).
 */
object AccountHandoff {
    const val PKG = AccountData.PKG
    private const val ACTION_OPEN = "$PKG.OPEN"

    fun installed(ctx: Context): Boolean = AccountData.accountInstalled(ctx)

    /** Opens Cloud Account. False when it is not installed or will not start. */
    fun open(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(ACTION_OPEN).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)   // not installed / refused

    /** The fragment SectionPages puts where the Account page was. */
    fun page(): Fragment = Page()

    /** Which fragment the page shows for the current install state. Read by the JVM test. */
    val EMBEDDED: Class<out Fragment> = ProfileFragment::class.java

    class Page : Fragment() {
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            // Cloud Account installed since this page was saved: the embedded child restored above
            // would look for a container the button layout does not have, so it goes before any view.
            if (installed(requireContext()) && childFragmentManager.fragments.isNotEmpty())
                childFragmentManager.beginTransaction()
                    .apply { childFragmentManager.fragments.forEach(::remove) }.commitNow()
        }

        override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
            val installed = installed(requireContext())
            return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
                setContent {
                    if (installed) {
                        androidx.compose.material3.MaterialTheme(androidx.compose.material3.darkColorScheme()) {
                            androidx.compose.material3.Surface { OpenCloudAccount() }
                        }
                    } else {
                        // Not installed: the Account page itself, exactly as before.
                        @Suppress("UNCHECKED_CAST")
                        val cls = EMBEDDED as Class<Fragment>
                        androidx.fragment.compose.AndroidFragment(clazz = cls, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    @Composable
    private fun OpenCloudAccount() {
        val ctx = LocalContext.current
        var failed by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Account is its own app now: Cloud Account.\nSign-in, profile, the fleet cockpit and drift live there.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { failed = !open(ctx) }) { Text("Open Cloud Account") }
            if (failed) Text("Cloud Account did not open - is it still installed?", Modifier.padding(top = 12.dp))
        }
    }
}
