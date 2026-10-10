package com.diegonmarcos.cloudsearch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.fleetconfig.CloudSearchQuery
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.cloudsearch.ui.SearchShell
import com.diegonmarcos.cloudsearch.ui.SearchState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** The one Activity: edge-to-edge through the lib's FleetChrome, the island clears its own inset (the cloud-calc shape). */
class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private lateinit var state: SearchState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        state = SearchState(Services.get(applicationContext))
        setContent { SearchShell(state) }
        // #937 a query handed over (a search intent, a share, CloudSearchQuery.EXTRA_QUERY) runs once:
        // a recreation (rotation) keeps the intent but must not ask again.
        if (savedInstanceState == null) ask(intent)
    }

    /** singleTask: a query sent while the app is up arrives here, not in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ask(intent)
    }

    private fun ask(intent: Intent?) {
        CloudSearchQuery.from(intent)?.let { state.ask(it, scope) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
