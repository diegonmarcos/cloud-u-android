package com.diegonmarcos.cloudstore

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import com.diegonmarcos.superapp.appstore.StoreDensity
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.compose.AndroidFragment
import com.diegonmarcos.superapp.appstore.AppsMeshFragment
import com.diegonmarcos.superapp.appstore.FleetBearer
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StorePhoneFragment
import com.diegonmarcos.superapp.updater.Updater

/**
 * #865 The three store pages SuperApp shows under Config, as tabs:
 * Cloud (the constellation fleet), Phone (installed apps), Mesh and Settings (#866).
 *
 * The pages are libs:appstore's Fragments, hosted with AndroidFragment. All
 * three stay composed and the hidden ones are sized to zero, so switching
 * tabs keeps a running download list and its scroll position.
 */
class MainActivity : AppCompatActivity() {

    /** The tab an Intent asked for; read by the composition. */
    private var requested by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = tabFrom(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { StoreShell() }
            }
        }
        // Self-update, as every constellation app does.
        Updater.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFrom(intent)?.let { requested = it }
    }

    @androidx.compose.runtime.Composable
    private fun StoreShell() {
        var selected by rememberSaveable { mutableStateOf(requested ?: TAB_CLOUD) }
        // A tab named by a later Intent (SuperApp's Open button, a notification tap).
        androidx.compose.runtime.LaunchedEffect(requested) {
            requested?.let { selected = it; requested = null }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TabRow(selectedTabIndex = TABS.indexOfFirst { it.first == selected }.coerceAtLeast(0)) {
                TABS.forEach { (id, label) ->
                    Tab(selected = id == selected, onClick = { selected = id },
                        modifier = Modifier.height(StoreDensity.dpValue(StoreDensity.TAP).dp),
                        text = { Text(label, fontSize = StoreDensity.T_META.sp) })
                }
            }
            Box(Modifier.fillMaxSize()) {
                val shown = Modifier.fillMaxSize()
                val hidden = Modifier.size(0.dp)
                AndroidFragment<StoreCloudFragment>(if (selected == TAB_CLOUD) shown else hidden)
                AndroidFragment<StorePhoneFragment>(if (selected == TAB_PHONE) shown else hidden)
                AndroidFragment<AppsMeshFragment>(if (selected == TAB_MESH) shown else hidden)
                if (selected == TAB_SETTINGS) SettingsPage()
            }
        }
    }

    /** #866 The one setting: the fleet token for the feeds, used only when SuperApp is not supplying it. */
    @androidx.compose.runtime.Composable
    private fun SettingsPage() {
        val ctx = this
        val own = androidx.compose.runtime.remember { FleetBearer.Own(ctx) }
        var token by androidx.compose.runtime.remember { mutableStateOf(own.token) }
        var source by androidx.compose.runtime.remember { mutableStateOf(FleetBearer.source(ctx)) }
        Column(Modifier.fillMaxSize().padding(StoreDensity.dpValue(StoreDensity.S12).dp)) {
            Text("Fleet token", fontSize = StoreDensity.T_TITLE.sp)
            Text(when (source) {
                FleetBearer.Source.SUPERAPP -> "Supplied by Cloud SuperApp; the entry below is not used."
                FleetBearer.Source.OWN -> "Using the token entered below."
                FleetBearer.Source.NONE -> "No token: the Commits and CI/CD feeds read their public sources."
            }, fontSize = StoreDensity.T_CAPTION.sp)
            androidx.compose.material3.OutlinedTextField(
                value = token,
                onValueChange = { token = it; own.token = it; source = FleetBearer.source(ctx) },
                label = { Text("Bearer token", fontSize = StoreDensity.T_CAPTION.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = StoreDensity.T_BODY.sp),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = StoreDensity.dpValue(StoreDensity.S8).dp),
            )
        }
    }

    private fun tabFrom(i: Intent?): String? =
        i?.getStringExtra(EXTRA_TAB)?.takeIf { t -> TABS.any { it.first == t } }

    companion object {
        /** Intent extra naming the tab to open: [TAB_CLOUD], [TAB_PHONE] or [TAB_MESH]. */
        const val EXTRA_TAB = "tab"
        const val TAB_CLOUD = "cloud"
        const val TAB_PHONE = "phone"
        const val TAB_MESH  = "mesh"
        const val TAB_SETTINGS = "settings"
        /** Tab id → label, in display order. */
        val TABS = listOf(TAB_CLOUD to "Cloud", TAB_PHONE to "Phone", TAB_MESH to "Mesh", TAB_SETTINGS to "Settings")
    }
}
