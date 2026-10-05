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

/**
 * #865 The Store is its own app now: Cloud Store (ac_cloud-store), built from
 * the same libs:appstore this app hosts.
 *
 * Once Cloud Store is installed, SuperApp hands it two things:
 *  - the unattended fleet pass (periodic check, Wi-Fi trigger, auto-update
 *    chain), through AppStoreHost.runsFleetPass, so the two apps never race
 *    the same downloads and install sessions;
 *  - the Store pages, which become a button that opens Cloud Store on the
 *    matching tab.
 *
 * Until it is installed, SuperApp keeps both, so the first install of Cloud
 * Store comes from SuperApp's own Store (it is a row in the fleet list).
 */
object CloudStoreHandoff {
    const val PKG = "com.diegonmarcos.cloudstore"
    private const val ACTION_OPEN = "com.diegonmarcos.cloudstore.OPEN"
    private const val EXTRA_TAB = "tab"

    /** Store page id (SectionPages) → Cloud Store tab. */
    val TAB_OF_PAGE = mapOf("store-cloud" to "cloud", "store-phone" to "phone", "apps-mesh" to "mesh")

    fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(PKG, 0).enabled
    }.getOrDefault(false)   // NameNotFoundException = not installed

    /** Opens Cloud Store on [tab]. False when it is not installed or will not start. */
    fun open(ctx: Context, tab: String): Boolean = runCatching {
        ctx.startActivity(Intent(ACTION_OPEN).setPackage(PKG).putExtra(EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /**
     * The fragment SectionPages puts where a Store page was. It decides when
     * it is shown, not when the page list is built (no Context there): Cloud
     * Store installed -> a button that opens it on the same tab; otherwise the
     * Store page itself, embedded, exactly as before.
     */
    fun page(pageId: String): Fragment = Page().apply {
        arguments = Bundle().apply { putString(ARG_PAGE, pageId) }
    }

    private const val ARG_PAGE = "page"

    class Page : Fragment() {
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            // Cloud Store installed since this page was saved: the embedded
            // child restored above would look for a container the button
            // layout does not have, so it goes before any view exists.
            if (installed(requireContext()) && childFragmentManager.fragments.isNotEmpty())
                childFragmentManager.beginTransaction()
                    .apply { childFragmentManager.fragments.forEach(::remove) }.commitNow()
        }

        override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
            val pageId = requireArguments().getString(ARG_PAGE) ?: "store-cloud"
            val tab = TAB_OF_PAGE[pageId] ?: "cloud"
            val installed = installed(requireContext())
            return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
                setContent {
                    if (installed) androidx.compose.material3.MaterialTheme(androidx.compose.material3.darkColorScheme()) {
                        androidx.compose.material3.Surface { OpenCloudStore(tab) }
                    }
                    else when (pageId) {
                        // Not installed: the Store page itself, exactly as before.
                        // Each page named, so the store testers can follow page -> fragment.
                        "store-cloud" -> androidx.fragment.compose.AndroidFragment<com.diegonmarcos.superapp.appstore.StoreCloudFragment>(Modifier.fillMaxSize())
                        "store-phone" -> androidx.fragment.compose.AndroidFragment<com.diegonmarcos.superapp.appstore.StorePhoneFragment>(Modifier.fillMaxSize())
                        "apps-mesh"   -> androidx.fragment.compose.AndroidFragment<com.diegonmarcos.superapp.appstore.AppsMeshFragment>(Modifier.fillMaxSize())
                        else          -> Text("Unknown Store page: $pageId")
                    }
                }
            }
        }
    }

    @Composable
    private fun OpenCloudStore(tab: String) {
        val ctx = LocalContext.current
        var failed by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "The Store is its own app now: Cloud Store.\nDownloads, updates and auto-update run there, SuperApp included.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { failed = !open(ctx, tab) }) { Text("Open Cloud Store") }
            if (failed) Text("Cloud Store did not open - is it still installed?", Modifier.padding(top = 12.dp))
        }
    }
}
