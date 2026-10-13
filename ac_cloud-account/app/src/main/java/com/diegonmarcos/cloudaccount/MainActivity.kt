package com.diegonmarcos.cloudaccount

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudaccount.autofill.AutofillImportPage
import com.diegonmarcos.cloudaccount.autofill.AutofillProfilesPage
import com.diegonmarcos.cloudaccount.autofill.AutofillRulesPage
import com.diegonmarcos.cloudaccount.autofill.AutofillSnippetsPage
import com.diegonmarcos.cloudaccount.hub.AppProfilesPage
import com.diegonmarcos.cloudaccount.hub.HomePage
import com.diegonmarcos.cloudaccount.hub.HubNav
import com.diegonmarcos.cloudaccount.hub.LocalDeviceAuth
import com.diegonmarcos.cloudaccount.hub.SecretsProvidersPage
import com.diegonmarcos.cloudaccount.hub.SystemDeviceAuth
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
import com.diegonmarcos.superapp.profile.FleetSetupTab
import com.diegonmarcos.superapp.profile.ProfilesDevicesPage
import com.diegonmarcos.superapp.profile.ProfilesDiffPage
import com.diegonmarcos.superapp.profile.ProfilesWorkingPage
import com.diegonmarcos.superapp.profile.GrantsTab
import com.diegonmarcos.superapp.profile.SecretsTab
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.updater.Updater

/**
 * Cloud Account, reorganised: four islands of build.json::ui.bottom_nav, Profiles, Secrets, Home, Configs,
 * drawn by libs:bottomnav (island + PageTabs) from the baked NavDecl, every (section, page) mapped to ONE
 * composable in [Page]. Setup is no island: each app profile (Profiles ▸ Apps) and each provider
 * (Secrets ▸ Providers) has its own Setup, and Home has Setup all. Links from libs:account pages that
 * still name the old islands ("setup", "account", "settings") go through [HubNav.resolve].
 *
 * Where each old page went: Account ▸ profile/connect/import -> Home ▸ account/connect/import;
 * Account ▸ autofill/sites/snippets -> Profiles ▸ personal/sites/snippets; Profiles ▸ devices/working/diff
 * stay; Setup ▸ runbook/apps/configs/perms/adb-shell -> Configs ▸ the same ids; Secrets ▸
 * connections/secrets/grants stay (beside the new Providers page); Settings -> Configs ▸ settings.
 */
class MainActivity : AppCompatActivity() {
    // Secrets ▸ connections: real file pickers behind ConnectionsTab's pickBundle / export callbacks.
    private var pendingPick: ((String) -> Unit)? = null
    private var pendingExport: Pair<String, String>? = null
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val onText = pendingPick; pendingPick = null
        if (uri != null && onText != null) onText(contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty())
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = pendingExport; pendingExport = null
        if (uri != null && pending != null) contentResolver.openOutputStream(uri)?.use { it.write(pending.second.toByteArray()) }
    }

    // Reveal / copy of a secret: the keyguard confirm screen on phones older than BiometricPrompt-with-PIN (API 29).
    private var pendingConfirm: ((Boolean) -> Unit)? = null
    private val confirmLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val done = pendingConfirm; pendingConfirm = null
        done?.invoke(r.resultCode == RESULT_OK)
    }
    private val deviceAuth by lazy { SystemDeviceAuth(this) { intent, done -> pendingConfirm = done; confirmLauncher.launch(intent) } }

    private fun pickText(onText: (String) -> Unit) { pendingPick = onText; pickLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        setContent {
            CloudKitTheme(AccountHost.palette(this)) {
                CompositionLocalProvider(LocalDeviceAuth provides deviceAuth) {
                    Surface(Modifier.fillMaxSize()) { AccountShell() }
                }
            }
        }
        Updater.start(this)
    }

    @Composable
    private fun AccountShell() {
        var selected by remember { mutableStateOf<String?>(NAV.default()?.id) }
        var selectedPage by remember { mutableStateOf<String?>(null) }
        // libs:account pages still link with the old island ids; HubNav maps them to where the page lives now.
        val go: (String, String) -> Unit = { s, p -> HubNav.resolve(s, p).let { (ns, np) -> selected = ns; selectedPage = np } }
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
            "profiles" -> when (page) {
                // The non-secret autofill Source of Truth (a0_docs/eng-specs/autofill-3-tier.md): personal data.
                "personal" -> AutofillProfilesPage()
                "sites" -> AutofillRulesPage()
                "snippets" -> AutofillSnippetsPage()
                "devices" -> ProfilesDevicesPage()
                "working" -> ProfilesWorkingPage()
                "diff" -> ProfilesDiffPage(model)
                else -> AppProfilesPage()
            }
            "secrets" -> when (page) {
                "connections" -> ConnectionsTab(
                    pickBundle = { onText -> pendingPick = onText; pickLauncher.launch(arrayOf("application/json", "text/plain")) },
                    export = { name, text -> pendingExport = name to text; exportLauncher.launch(name) },
                )
                "secrets" -> SecretsTab(openVault = { AccountHost.route(this, "extapp:cloud-vault") })
                "grants" -> GrantsTab()
                else -> SecretsProvidersPage(go)
            }
            "home" -> when (page) {
                "account" -> AccountProfilePage(go)
                "connect" -> AccountConnectPage()
                "import" -> AutofillImportPage(::pickText)
                else -> HomePage(go, ::pickText)
            }
            "configs" -> when (page) {
                "runbook" -> RunbookPage(go)
                "apps" -> AppsPage()
                "configs" -> FleetSetupPage(model)
                "perms" -> AccountPermsPage(go)
                "adb-shell" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { com.diegonmarcos.superapp.adbdebug.AdbShellPage() }
                else -> AccountSettingsPage("${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHORT_SHA} · built ${BuildConfig.BUILD_TIMESTAMP}")
            }
            else -> AccountPlaceholderPage(section, page, "no task: undeclared section")
        }
    }

    /** Configs ▸ configs (was Setup ▸ configs, spec 4.8): FleetSetupTab as it is, in a scrolling page. */
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
            "secrets" -> Icons.Filled.Key
            "home" -> Icons.Filled.Home
            "configs" -> Icons.Filled.Settings
            else -> Icons.Filled.AccountCircle
        }
    }
}
