package com.diegonmarcos.superapp.fleetconfigs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Configs > General and Configs > About for an app drawn with Views or a WebView: the same [FleetConfigsScreen] the Compose scaffold
 * shows, in an activity of its own. Such an app links libs:ui-nav-bars and starts [intent] from its settings menu; the declaration is
 * the app's build.json::configs_pages, baked like everywhere else. "App" in General closes this and returns to the app's own settings.
 */
public class FleetConfigsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = FleetConfigsPage.entries.firstOrNull { it.id == intent.getStringExtra(EXTRA_PAGE) } ?: FleetConfigsPage.General
        val dark = intent.getBooleanExtra(EXTRA_DARK, true)
        setContent { FleetConfigsScreen(page, dark, onDismiss = { finish() }, onAppConfigs = { finish() }) }
    }

    public companion object {
        public const val EXTRA_PAGE: String = "fleet_configs_page"
        public const val EXTRA_DARK: String = "fleet_configs_dark"

        public fun intent(context: Context, page: FleetConfigsPage, dark: Boolean = true): Intent =
            Intent(context, FleetConfigsActivity::class.java).putExtra(EXTRA_PAGE, page.id).putExtra(EXTRA_DARK, dark)
    }
}
