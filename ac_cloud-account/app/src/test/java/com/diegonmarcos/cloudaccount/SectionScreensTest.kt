package com.diegonmarcos.cloudaccount

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudaccount.autofill.AutofillProfilesPage
import com.diegonmarcos.cloudaccount.hub.AppProfilesPage
import com.diegonmarcos.cloudaccount.hub.ConnectionsPage
import com.diegonmarcos.cloudaccount.hub.LocalDeviceAuth
import com.diegonmarcos.cloudaccount.hub.SecretsProvidersPage
import com.diegonmarcos.cloudaccount.hub.TokensPage
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountModel
import com.diegonmarcos.superapp.profile.AccountPermsPage
import com.diegonmarcos.superapp.profile.AccountProfilePage
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.ActivityFeed
import com.diegonmarcos.superapp.profile.ActivityPage
import com.diegonmarcos.superapp.profile.BackupsPage
import com.diegonmarcos.superapp.profile.C3ConfigsPage
import com.diegonmarcos.superapp.profile.DesktopConfigsPage
import com.diegonmarcos.superapp.profile.DevicesMeshPage
import com.diegonmarcos.superapp.profile.EndpointCheck
import com.diegonmarcos.superapp.profile.FleetEndpointsPage
import com.diegonmarcos.superapp.profile.RunbookPage
import com.diegonmarcos.superapp.profile.WalletPage
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1081 evidence: the real chrome of Cloud Account (the island from the baked NavDecl, the section's top strip, the
 * container's second strip) drawn over one page of each section at Galaxy A37 size, written to
 * app/build/section-screens/<section>-<page>.png (the account-screens workflow uploads the folder). The pages this
 * change added, and the strips, must render; a page that predates it is shot best-effort (a failure is written as
 * <name>.err, not a red test: its own suite owns it). Every value in the seeded vault is an obvious fake.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SectionScreensTest(private val section: String, private val page: String) {

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

    @Before fun seed() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ConfigsPrefs.factory = { it.getSharedPreferences("screens_test", Context.MODE_PRIVATE) }
        AccountHost.mesh = null
        // The phone's model keeps its files in an EncryptedFile (Android Keystore), which Robolectric does not have: the
        // seeding used to fail there inside a runCatching and every page drew its empty state. A plain-file model is installed
        // instead, and a seed that does not land fails the test rather than shooting empty screens.
        val model = AccountModel(ctx, AccountStore(File(ctx.filesDir, "account-screens"), AccountStore.PlainIo))
        AccountModel.install(model)
        model.store.write(AccountStore.Slot.S, JSONObject("""{
          "electronics": {
            "computers": {"fedora-box": {"label": "Fedora box", "type": "notebook", "os": "fedora 43", "home_manager": "diego@fedora-box",
                "wg_peer": {"name": "fedora-box", "wg_ip": "10.0.0.2"}}},
            "phones": {"galaxy-s21": {"label": "Galaxy S21", "type": "phone", "os": "android 15",
                "wg_peer": {"name": "galaxy", "wg_ip": "10.0.0.3"}}}
          },
          "containers": {"caddy": {"image": "caddy:2", "host": "vm-1"}, "authelia": {"image": "authelia:4", "host": "vm-1"}}
        }"""), "screens-test", "2026-10-21T00:00:00Z")
        model.invalidate()
        val seeded = model.doc(AccountStore.Slot.S)?.body
        assertTrue("the vault seed did not land in slot S: the screenshots would show empty states",
            seeded != null && seeded.optJSONObject("electronics")?.optJSONObject("computers")?.has("fedora-box") == true &&
                seeded.optJSONObject("containers")?.has("caddy") == true)
        ActivityFeed.clear(ctx)
        ActivityFeed.record(ctx, "backup", "auto-backup of galaxy-s21: ok", now = 1_760_000_000_000)
        ActivityFeed.record(ctx, "secret", "revealed github.token", now = 1_760_000_100_000)
        ActivityFeed.record(ctx, "import", "imported 3 token(s)", now = 1_760_000_200_000)
    }

    @Composable private fun PageFor(go: (String, String) -> Unit) {
        when ("$section/$page") {
            "personal/me" -> AccountProfilePage(go)
            "personal/wallet" -> WalletPage()
            "personal/personal" -> AutofillProfilesPage()
            "configs/runbook" -> RunbookPage(go)
            "configs/perms" -> AccountPermsPage(go)
            "configs/desktop" -> DesktopConfigsPage()
            "configs/c3" -> C3ConfigsPage()
            "data/providers" -> SecretsProvidersPage(go)
            "data/tokens" -> TokensPage()
            "data/databases" -> BackupsPage()
            "data/mesh" -> DevicesMeshPage()
            "connect/connections" -> ConnectionsPage(go)
            "connect/apps" -> AppProfilesPage()
            "connect/endpoints" -> FleetEndpointsPage(check = { sv -> sv.associate { it.id to EndpointCheck(meshUp = it.hasPublic, publicStatus = if (it.hasPublic) 200 else null) } })
            "activity/activity" -> ActivityPage()
            else -> error("no page for $section/$page")
        }
    }

    @Composable private fun Chrome() {
        val nav = MainActivity.NAV
        val sec = nav.section(section)!!
        val path = sec.path(page)
        val go: (String, String) -> Unit = { _, _ -> }
        CloudKitTheme(AccountHost.palette(compose.activity)) {
            CompositionLocalProvider(LocalDeviceAuth provides com.diegonmarcos.cloudaccount.hub.DeviceAuth { _, done -> done(false) }) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        NavBarBottomHost(
                            entries = nav.islandEntries { rememberVectorPainter(Icons.Filled.Star) },
                            selectedId = section, onSelect = {},
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                if (sec.pages.size > 1) NavBarTopSecondRow(sec.pages, path.firstOrNull()?.id, onSelect = {}, underTopChrome = false,
                                    surface = nav.stripSurface(sec.id, darkTheme = true))
                                val container = path.firstOrNull()?.takeIf { it.pages.size > 1 }
                                if (container != null) NavBarTopSecondRow(container.pages, page, onSelect = {}, underTopChrome = false,
                                    surface = nav.stripSurface(sec.id, darkTheme = true, pageId = container.id))
                                PageFor(go)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shot(name: String) {
        val bmp = compose.runOnUiThread {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).also { c -> c.drawColor(android.graphics.Color.BLACK); root.draw(c) }
            }
        }
        File(OUT, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `the section renders under its island and strips`() {
        OUT.mkdirs()
        val name = "$section-$page"
        val must = "$section/$page" in MUST
        try {
            compose.setContent { Chrome() }
            compose.waitForIdle()
            val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes(false)
            assertTrue("$name drew nothing", nodes.isNotEmpty())
            shot(name)
        } catch (t: Throwable) {
            File(OUT, "$name.err").writeText("${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.take(300)}\n")
            assertTrue("$name did not render: ${t.javaClass.simpleName}: ${t.message?.take(300)}", !must)
        }
    }

    companion object {
        private val OUT = File("build/section-screens")

        /** The pages #1081 added: they must render. */
        private val MUST = setOf("personal/wallet", "configs/desktop", "configs/c3", "data/databases", "data/mesh", "activity/activity", "connect/endpoints")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}/{1}")
        fun pages(): List<Array<Any>> = listOf(
            "personal/me", "personal/wallet", "personal/personal",
            "configs/runbook", "configs/perms", "configs/desktop", "configs/c3",
            "data/providers", "data/tokens", "data/databases", "data/mesh",
            "connect/connections", "connect/apps", "connect/endpoints",
            "activity/activity",
        ).map { it.split('/').toTypedArray<Any>() }
    }
}
