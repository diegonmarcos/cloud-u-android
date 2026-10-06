package com.diegonmarcos.cloudsearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.cloudsearch.ui.SearchShell
import com.diegonmarcos.cloudsearch.ui.SearchState

/** The one Activity: edge-to-edge through the lib's FleetChrome, the island clears its own inset (the cloud-calc shape). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        val state = SearchState(Services.get(applicationContext))
        setContent { SearchShell(state) }
    }
}
