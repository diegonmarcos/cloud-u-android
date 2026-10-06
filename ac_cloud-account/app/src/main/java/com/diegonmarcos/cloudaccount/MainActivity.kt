package com.diegonmarcos.cloudaccount

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.core.os.bundleOf
import androidx.fragment.compose.AndroidFragment
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.profile.ProfileFragment
import com.diegonmarcos.superapp.updater.Updater

/**
 * #867 The Account page SuperApp shows under Config, as an app.
 *
 * libs:account's [ProfileFragment] holds the four tabs (Connect, Profiles, Runtime, Drift:
 * aa_cloud-superapp's build.json::ui.profile.tabs). #868: the four are this app's ui.sections and the
 * fleet's bottom island draws them; the fragment starts with [ProfileFragment.ARG_EXTERNAL_STRIP] (its
 * own strip hidden), the island drives it through selectTab, and the fragment says which tab it is
 * on through onTabShown, so the island never disagrees with the page.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AccountShell() }
            }
        }
        // Self-update, as every constellation app does.
        Updater.start(this)
    }

    @androidx.compose.runtime.Composable
    private fun AccountShell() {
        var selected by remember { mutableStateOf<String?>(NAV.default()?.id) }
        var selectedPage by remember { mutableStateOf<String?>(null) }
        var page by remember { mutableStateOf<ProfileFragment?>(null) }
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            BottomNavHost(
                entries = NAV.islandEntries { rememberVectorPainter(iconFor(it)) },
                selectedId = selected,
                onSelect = { selected = it.id; selectedPage = null; page?.selectTab(it.id) },
            ) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    // #874 the section's pages are the lib's strip; the fragment draws none (ARG_EXTERNAL_PAGES).
                    val pages = NAV.section(selected)?.pages.orEmpty()
                    if (pages.size > 1) {
                        PageTabs(
                            pages = pages,
                            selectedId = selectedPage ?: pages.first().id,
                            onSelect = { p -> selectedPage = p.id; page?.selectTab(p.id) },
                            underTopChrome = false,
                        )
                    }
                    AndroidFragment<ProfileFragment>(
                        Modifier.fillMaxSize(),
                        arguments = bundleOf(
                            ProfileFragment.ARG_EXTERNAL_STRIP to true,
                            ProfileFragment.ARG_EXTERNAL_PAGES to true,
                        ),
                        onUpdate = { f ->
                            page = f
                            f.onTabShown = { id -> if (id != selected) { selected = id; selectedPage = null } }
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** build.json::ui as baked into BuildConfig: the island's items and the section ids. */
        val NAV: NavDecl by lazy {
            NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
        }

        /** The declared icon name → the glyph the island draws. */
        private fun iconFor(name: String): ImageVector = when (name) {
            "connect" -> Icons.Filled.Link
            "runtime" -> Icons.Filled.Memory
            "drift" -> Icons.Filled.CompareArrows
            else -> Icons.Filled.AccountCircle
        }
    }
}
