package com.diegonmarcos.cloudaccount

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.profile.AccountConnectPage
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountModel
import com.diegonmarcos.superapp.profile.AccountPermsPage
import com.diegonmarcos.superapp.profile.AccountPlaceholderPage
import com.diegonmarcos.superapp.profile.AccountProfilePage
import com.diegonmarcos.superapp.profile.AccountSettingsPage
import com.diegonmarcos.superapp.profile.AppsPage
import com.diegonmarcos.superapp.profile.RunbookPage
import com.diegonmarcos.superapp.profile.ConnectionsTab
import com.diegonmarcos.superapp.profile.DriftTab
import com.diegonmarcos.superapp.profile.FleetSetupTab
import com.diegonmarcos.superapp.profile.ProfilesTab
import com.diegonmarcos.superapp.profile.SecretsTab
import com.diegonmarcos.superapp.profile.VaultCockpit
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.updater.Updater

/**
 * Cloud Account redesign task 3 (a0_docs/eng-specs/cloud-account-redesign.md sections 3, 4.1,
 * 4.2, 4.11): the five islands of build.json::ui.bottom_nav (Account, Profiles, Setup, Secrets,
 * Settings) drawn by libs:bottomnav exactly as Cloud Store's shell is, the section's pages as
 * the PageTabs strip, and every (section, page) mapped to ONE composable in [Page]. The old
 * ProfileFragment host is gone.
 *
 * Pages a later task owns draw a placeholder that names the task; where an existing
 * libs:account composable already does that page's job it is shown under the placeholder
 * until its task lands (Profiles ▸ working/diff, Setup ▸ configs, Secrets ▸ connections/secrets).
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        setContent {
            CloudKitTheme(AccountHost.palette(this)) {
                Surface(Modifier.fillMaxSize()) { AccountShell() }
            }
        }
        Updater.start(this)
    }

    @Composable
    private fun AccountShell() {
        var selected by remember { mutableStateOf<String?>(NAV.default()?.id) }
        var selectedPage by remember { mutableStateOf<String?>(null) }
        val go: (String, String) -> Unit = { s, p -> selected = s; selectedPage = p }
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            BottomNavHost(
                entries = NAV.islandEntries { rememberVectorPainter(iconFor(it)) },
                selectedId = selected,
                onSelect = { selected = it.id; selectedPage = null },
            ) {
                Column(Modifier.fillMaxSize()) {
                    val section = NAV.section(selected)
                    val pages = section?.pages.orEmpty()
                    val page = section?.page(selectedPage)?.id.orEmpty()
                    if (pages.size > 1) {
                        PageTabs(
                            pages = pages,
                            selectedId = page,
                            onSelect = { p -> selectedPage = p.id },
                            underTopChrome = false,
                        )
                    }
                    Page(selected.orEmpty(), page, go)
                }
            }
        }
    }

    /** (section, page) → its composable. Ids are build.json::ui.sections'; nothing here names a label. */
    @Composable
    private fun Page(section: String, page: String, go: (String, String) -> Unit) {
        val ctx = LocalContext.current
        val model = remember { AccountModel.get(ctx) }
        when (section) {
            "account" -> when (page) {
                "connect" -> AccountConnectPage()
                else -> AccountProfilePage(go)
            }
            "profiles" -> when (page) {
                "working" -> AccountPlaceholderPage(section, page, "task 4") { ProfilesTab(model, "Connect", { _, _ -> }, { r -> AccountHost.route(this, r) }) }
                "diff" -> AccountPlaceholderPage(section, page, "task 4") { DriftTab(model, VaultCockpit.selectedDevice(ctx)) { _, _ -> } }
                else -> AccountPlaceholderPage(section, page, "task 4")
            }
            "setup" -> when (page) {
                "configs" -> FleetSetupPage(model)
                "apps" -> AppsPage()
                "perms" -> AccountPermsPage(go)
                "runbook" -> RunbookPage(go)
                else -> AccountPlaceholderPage(section, page, "no task: undeclared page")
            }
            "secrets" -> when (page) {
                "connections" -> AccountPlaceholderPage(section, page, "task 7") { ConnectionsTab({ }, { _, _ -> }) }
                "secrets" -> AccountPlaceholderPage(section, page, "task 7") { SecretsTab() }
                else -> AccountPlaceholderPage(section, page, "task 7")
            }
            "settings" -> AccountSettingsPage("${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHORT_SHA})")
            else -> AccountPlaceholderPage(section, page, "no task: undeclared section")
        }
    }

    /** Setup ▸ configs (spec 4.8): FleetSetupTab as it is, in a scrolling page. */
    @Composable
    private fun FleetSetupPage(model: AccountModel) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { FleetSetupTab(model) }
    }

    companion object {
        /** build.json::ui as baked into BuildConfig: the island's items and the section ids. */
        val NAV: NavDecl by lazy {
            NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
        }

        /** The declared icon name → the glyph the island draws. */
        private fun iconFor(name: String): ImageVector = when (name) {
            "profiles" -> Icons.Filled.PhoneAndroid
            "setup" -> Icons.Filled.Build
            "secrets" -> Icons.Filled.Key
            "settings" -> Icons.Filled.Settings
            else -> Icons.Filled.AccountCircle
        }
    }
}
